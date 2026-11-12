package com.absmartly.android.sdk;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.absmartly.android.sdk.cache.SqliteAndroidLocalCache;
import com.absmartly.sdk.ABSmartly;
import com.absmartly.sdk.ABSmartlyConfig;
import com.absmartly.sdk.Client;
import com.absmartly.sdk.ClientConfig;
import com.absmartly.sdk.ContextConfig;
import com.absmartly.sdk.DefaultContextEventHandler;
import com.absmartly.sdk.DefaultHTTPClient;
import com.absmartly.sdk.DefaultHTTPClientConfig;
import com.absmartly.sdk.HTTPClient;
import com.absmartly.sdk.json.ContextData;

import java.io.Closeable;
import java.io.IOException;
import java.security.Provider;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class ABSmartlyAndroid implements Closeable {

    /** The SDK name, sent as sdk_name and in the X-Agent header. */
    public static final String SDK_NAME = "absmartly-android-sdk";

    /** The SDK version, sent as sdk_version and in the X-Agent header. */
    public static final String SDK_VERSION = BuildConfig.SDK_VERSION;

    /** The X-Agent header value: "absmartly-android-sdk/&lt;version&gt;". */
    public static final String AGENT = SDK_NAME + "/" + SDK_VERSION;

    public static ABSmartlyAndroid create(
            @NonNull String endpoint,
            @NonNull String apiKey,
            @NonNull String application,
            @NonNull String environment,
            @NonNull Context androidContext) {
        Objects.requireNonNull(endpoint, "endpoint is required");
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(application, "application is required");
        Objects.requireNonNull(environment, "environment is required");
        Objects.requireNonNull(androidContext, "Android Context is required");
        return new ABSmartlyAndroid(endpoint, apiKey, application, environment, null, androidContext,
                defaultTransport(null));
    }

    public static Builder builder() {
        return new Builder();
    }

    private final ABSmartly sdk;
    private final SqliteAndroidLocalCache cache;
    private final ScheduledThreadPoolExecutor scheduler;

    /** Creates the HTTP transport. The wrapper owns it and closes it in close(). */
    interface TransportFactory {
        @NonNull
        HTTPClient create();
    }

    @NonNull
    static TransportFactory defaultTransport(@Nullable final Provider securityProvider) {
        return new TransportFactory() {
            @NonNull
            @Override
            public HTTPClient create() {
                final DefaultHTTPClientConfig config = DefaultHTTPClientConfig.create();
                if (securityProvider != null) {
                    config.setSecurityProvider(securityProvider);
                }
                return DefaultHTTPClient.create(config);
            }
        };
    }

    // Package-private so tests can supply the HTTP transport.
    ABSmartlyAndroid(
            @NonNull String endpoint,
            @NonNull String apiKey,
            @NonNull String application,
            @NonNull String environment,
            @Nullable String applicationVersion,
            @NonNull Context androidContext,
            @NonNull TransportFactory transportFactory) {
        requireNonEmpty(endpoint, "endpoint is required");
        requireNonEmpty(apiKey, "apiKey is required");
        requireNonEmpty(application, "application is required");
        requireNonEmpty(environment, "environment is required");
        Objects.requireNonNull(androidContext, "Android Context is required");

        final ClientConfig clientConfig = ClientConfig.create()
                .setEndpoint(endpoint)
                .setAPIKey(apiKey)
                .setApplication(application)
                .setEnvironment(environment)
                .setApplicationVersion(applicationVersion);

        // Everything acquired below is released if a later step throws, because no wrapper is
        // returned for the caller to close.
        HTTPClient transport = null;
        ScheduledThreadPoolExecutor scheduler = null;
        SqliteAndroidLocalCache cache = null;
        try {
            transport = transportFactory.create();
            final Client client = Client.create(clientConfig, new AgentHTTPClient(transport, AGENT));

            // core-api leaves a caller-supplied scheduler running on ABSmartly.close(), so the wrapper
            // owns it and shuts it down in close() without blocking (which would risk an ANR on the UI
            // thread). Delayed publish timers still queued at that point are dropped rather than run
            // against the closed client: Context.close() publishes pending events itself.
            scheduler = new ScheduledThreadPoolExecutor(1);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

            final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
                    .setClient(client)
                    .setContextEventHandler(new AndroidContextEventHandler(
                            new DefaultContextEventHandler(client), SDK_NAME, SDK_VERSION))
                    .setScheduler(scheduler);

            cache = new SqliteAndroidLocalCache(androidContext.getApplicationContext());
            this.sdk = ABSmartly.create(sdkConfig);
        } catch (RuntimeException | Error e) {
            releaseAfterFailedConstruction(e, transport, scheduler, cache);
            throw e;
        }
        this.scheduler = scheduler;
        this.cache = cache;
    }

    private static void releaseAfterFailedConstruction(@NonNull Throwable failure,
            @Nullable HTTPClient transport, @Nullable ScheduledThreadPoolExecutor scheduler,
            @Nullable SqliteAndroidLocalCache cache) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (cache != null) {
            try {
                cache.close();
            } catch (RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
        if (transport != null) {
            try {
                transport.close();
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
    }

    private static void requireNonEmpty(@NonNull String value, @NonNull String message) {
        Objects.requireNonNull(value, message);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    public com.absmartly.sdk.Context createContext(@NonNull ContextConfig config) {
        return sdk.createContext(config);
    }

    public com.absmartly.sdk.Context createContextWith(@NonNull ContextConfig config, ContextData data) {
        return sdk.createContextWith(config, data);
    }

    public ABSmartly getSdk() {
        return sdk;
    }

    public SqliteAndroidLocalCache getCache() {
        return cache;
    }

    @Override
    public void close() throws IOException {
        IOException sdkException = null;
        // Stop the publish and refresh timers before the client closes.
        scheduler.shutdown();
        try {
            sdk.close();
        } catch (IOException e) {
            sdkException = e;
        }
        try {
            cache.close();
        } catch (Exception e) {
            if (sdkException != null) {
                sdkException.addSuppressed(e);
                throw sdkException;
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(e);
        }
        if (sdkException != null) {
            throw sdkException;
        }
    }

    public static final class Builder {
        private String endpoint;
        private String apiKey;
        private String application;
        private String environment;
        private String applicationVersion;
        private Provider securityProvider;
        private Context androidContext;

        private Builder() {}

        public Builder endpoint(@NonNull String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder apiKey(@NonNull String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder application(@NonNull String application) {
            this.application = application;
            return this;
        }

        public Builder environment(@NonNull String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * The application version, for example "2.4.1". It is the app_version system attribute that
         * audience filters and assignment rules can compare with the semver_* operators, and is sent
         * as the X-Application-Version header when it is a whole number.
         */
        public Builder applicationVersion(@Nullable String applicationVersion) {
            this.applicationVersion = applicationVersion;
            return this;
        }

        /**
         * The security provider for HTTPS connections. On Android 6.0 (API 23) and earlier the
         * default provider does not work; pass {@code Conscrypt.newProvider()} there.
         */
        public Builder securityProvider(@Nullable Provider securityProvider) {
            this.securityProvider = securityProvider;
            return this;
        }

        public Builder context(@NonNull Context androidContext) {
            this.androidContext = androidContext;
            return this;
        }

        public ABSmartlyAndroid build() {
            if (endpoint == null || endpoint.isEmpty()) throw new IllegalStateException("endpoint is required");
            if (apiKey == null || apiKey.isEmpty()) throw new IllegalStateException("apiKey is required");
            if (application == null || application.isEmpty()) throw new IllegalStateException("application is required");
            if (environment == null || environment.isEmpty()) throw new IllegalStateException("environment is required");
            if (androidContext == null) throw new IllegalStateException("Android Context is required");
            return new ABSmartlyAndroid(endpoint, apiKey, application, environment, applicationVersion,
                    androidContext, defaultTransport(securityProvider));
        }
    }
}
