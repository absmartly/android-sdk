package com.absmartly.android.sdk;

import android.content.Context;

import androidx.annotation.NonNull;

import com.absmartly.android.sdk.cache.SqliteAndroidLocalCache;
import com.absmartly.sdk.ABSmartly;
import com.absmartly.sdk.ABSmartlyConfig;
import com.absmartly.sdk.Client;
import com.absmartly.sdk.ClientConfig;
import com.absmartly.sdk.ContextConfig;
import com.absmartly.sdk.json.ContextData;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class ABSmartlyAndroid implements Closeable {

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
        return new ABSmartlyAndroid(endpoint, apiKey, application, environment, androidContext);
    }

    public static Builder builder() {
        return new Builder();
    }

    private final ABSmartly sdk;
    private final SqliteAndroidLocalCache cache;
    private final ScheduledExecutorService scheduler;

    private ABSmartlyAndroid(
            @NonNull String endpoint,
            @NonNull String apiKey,
            @NonNull String application,
            @NonNull String environment,
            @NonNull Context androidContext) {
        requireNonEmpty(endpoint, "endpoint is required");
        requireNonEmpty(apiKey, "apiKey is required");
        requireNonEmpty(application, "application is required");
        requireNonEmpty(environment, "environment is required");
        Objects.requireNonNull(androidContext, "Android Context is required");

        this.cache = new SqliteAndroidLocalCache(androidContext.getApplicationContext());

        final ClientConfig clientConfig = ClientConfig.create()
                .setEndpoint(endpoint)
                .setAPIKey(apiKey)
                .setApplication(application)
                .setEnvironment(environment);

        final Client client = Client.create(clientConfig);

        // core-api 1.6.3's ABSmartly.close() awaits the scheduler for 5s but never shuts it
        // down. Owning the scheduler here lets us shut it down on close() so normal closure
        // does not block (and risk an ANR when called on the UI thread).
        this.scheduler = new ScheduledThreadPoolExecutor(1);

        final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
                .setClient(client)
                .setScheduler(scheduler);

        this.sdk = ABSmartly.create(sdkConfig);
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
        // Shut the scheduler down first so core-api's close() (which only awaits termination,
        // never shuts the scheduler down itself) returns as soon as pending tasks drain,
        // instead of blocking for the full 5s timeout.
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
            return new ABSmartlyAndroid(endpoint, apiKey, application, environment, androidContext);
        }
    }
}
