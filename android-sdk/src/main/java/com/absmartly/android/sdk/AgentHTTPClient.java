package com.absmartly.android.sdk;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.absmartly.sdk.HTTPClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import java8.util.concurrent.CompletableFuture;

/**
 * Sends the Android SDK identity as the X-Agent header. core-api sets X-Agent to its own
 * "absmartly-java-sdk/&lt;version&gt;", which would report Android traffic as the Java SDK.
 */
final class AgentHTTPClient implements HTTPClient {
    static final String AGENT_HEADER = "X-Agent";

    private final HTTPClient delegate;
    private final String agent;

    AgentHTTPClient(@NonNull HTTPClient delegate, @NonNull String agent) {
        this.delegate = delegate;
        this.agent = agent;
    }

    @Override
    public CompletableFuture<Response> get(@NonNull String url, @Nullable Map<String, String> query,
            @Nullable Map<String, String> headers) {
        return delegate.get(url, query, withAgent(headers));
    }

    @Override
    public CompletableFuture<Response> put(@NonNull String url, @Nullable Map<String, String> query,
            @Nullable Map<String, String> headers, @NonNull byte[] body) {
        return delegate.put(url, query, withAgent(headers), body);
    }

    @Override
    public CompletableFuture<Response> post(@NonNull String url, @Nullable Map<String, String> query,
            @Nullable Map<String, String> headers, @NonNull byte[] body) {
        return delegate.post(url, query, withAgent(headers), body);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @NonNull
    private Map<String, String> withAgent(@Nullable Map<String, String> headers) {
        final Map<String, String> result = headers != null
                ? new HashMap<>(headers)
                : new HashMap<String, String>(1);
        result.put(AGENT_HEADER, agent);
        return result;
    }
}
