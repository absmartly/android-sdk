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

    private ABSmartlyAndroid(
            @NonNull String endpoint,
            @NonNull String apiKey,
            @NonNull String application,
            @NonNull String environment,
            @NonNull Context androidContext) {
        this.cache = new SqliteAndroidLocalCache(androidContext.getApplicationContext());

        final ClientConfig clientConfig = ClientConfig.create()
                .setEndpoint(endpoint)
                .setAPIKey(apiKey)
                .setApplication(application)
                .setEnvironment(environment);

        final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
                .setClient(Client.create(clientConfig));

        this.sdk = ABSmartly.create(sdkConfig);
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
            if (endpoint == null) throw new IllegalStateException("endpoint is required");
            if (apiKey == null) throw new IllegalStateException("apiKey is required");
            if (application == null) throw new IllegalStateException("application is required");
            if (environment == null) throw new IllegalStateException("environment is required");
            if (androidContext == null) throw new IllegalStateException("Android Context is required");
            return new ABSmartlyAndroid(endpoint, apiKey, application, environment, androidContext);
        }
    }
}
