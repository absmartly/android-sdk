package com.absmartly.android.sdk;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.absmartly.sdk.HTTPClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java8.util.concurrent.CompletableFuture;

/** Records requests and answers them in-process, so tests exercise the real Client code path. */
final class RecordingHTTPClient implements HTTPClient {
    static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = headers != null ? new HashMap<>(headers) : Collections.<String, String>emptyMap();
            this.body = body;
        }

        String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
    private volatile byte[] contextData = "{\"experiments\":[]}".getBytes(StandardCharsets.UTF_8);

    void setContextData(@NonNull String json) {
        contextData = json.getBytes(StandardCharsets.UTF_8);
    }

    List<Request> requests(@NonNull String method) {
        final List<Request> result = new ArrayList<>();
        synchronized (requests) {
            for (final Request request : requests) {
                if (request.method.equals(method)) {
                    result.add(request);
                }
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<Response> get(@NonNull String url, @Nullable Map<String, String> query,
            @Nullable Map<String, String> headers) {
        requests.add(new Request("GET", url, headers, null));
        return CompletableFuture.completedFuture(response(contextData));
    }

    @Override
    public CompletableFuture<Response> put(@NonNull String url, @Nullable Map<String, String> query,
            @Nullable Map<String, String> headers, @NonNull byte[] body) {
        requests.add(new Request("PUT", url, headers, body));
        return CompletableFuture.completedFuture(response(new byte[0]));
    }

    @Override
    public CompletableFuture<Response> post(@NonNull String url, @Nullable Map<String, String> query,
            @Nullable Map<String, String> headers, @NonNull byte[] body) {
        requests.add(new Request("POST", url, headers, body));
        return CompletableFuture.completedFuture(response(new byte[0]));
    }

    private volatile boolean closed;

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static Response response(final byte[] content) {
        return new Response() {
            @Override
            public int getStatusCode() {
                return 200;
            }

            @Override
            public String getStatusMessage() {
                return "OK";
            }

            @Override
            public String getContentType() {
                return "application/json; charset=utf8";
            }

            @Override
            public byte[] getContent() {
                return content;
            }
        };
    }
}
