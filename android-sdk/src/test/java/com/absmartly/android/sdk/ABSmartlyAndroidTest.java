package com.absmartly.android.sdk;

import android.content.Context;

import com.absmartly.sdk.ContextConfig;
import com.absmartly.sdk.json.ContextData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ABSmartlyAndroidTest {

    private Context androidContext;

    @Before
    public void setUp() {
        androidContext = RuntimeEnvironment.getApplication();
    }

    @Test
    public void builder_missingEndpoint_throwsIllegalStateException() {
        try {
            ABSmartlyAndroid.builder()
                    .apiKey("key")
                    .application("app")
                    .environment("env")
                    .context(androidContext)
                    .build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("endpoint"));
        }
    }

    @Test
    public void builder_missingApiKey_throwsIllegalStateException() {
        try {
            ABSmartlyAndroid.builder()
                    .endpoint("https://test.absmartly.io/v1")
                    .application("app")
                    .environment("env")
                    .context(androidContext)
                    .build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("apiKey"));
        }
    }

    @Test
    public void builder_missingApplication_throwsIllegalStateException() {
        try {
            ABSmartlyAndroid.builder()
                    .endpoint("https://test.absmartly.io/v1")
                    .apiKey("key")
                    .environment("env")
                    .context(androidContext)
                    .build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("application"));
        }
    }

    @Test
    public void builder_missingEnvironment_throwsIllegalStateException() {
        try {
            ABSmartlyAndroid.builder()
                    .endpoint("https://test.absmartly.io/v1")
                    .apiKey("key")
                    .application("app")
                    .context(androidContext)
                    .build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("environment"));
        }
    }

    @Test
    public void builder_missingAndroidContext_throwsIllegalStateException() {
        try {
            ABSmartlyAndroid.builder()
                    .endpoint("https://test.absmartly.io/v1")
                    .apiKey("key")
                    .application("app")
                    .environment("env")
                    .build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Context"));
        }
    }

    @Test
    public void builder_allFieldsSet_buildsWrapper() throws IOException {
        try (ABSmartlyAndroid sdk = ABSmartlyAndroid.builder()
                .endpoint("https://test.absmartly.io/v1")
                .apiKey("test-api-key")
                .application("test-app")
                .environment("test-env")
                .context(androidContext)
                .build()) {
            assertNotNull(sdk.getSdk());
            assertNotNull(sdk.getCache());
        }
    }

    @Test
    public void builder_withApplicationVersion_buildsWrapper() throws IOException {
        try (ABSmartlyAndroid sdk = ABSmartlyAndroid.builder()
                .endpoint("https://test.absmartly.io/v1")
                .apiKey("test-api-key")
                .application("test-app")
                .environment("test-env")
                .applicationVersion("2.4.1")
                .context(androidContext)
                .build()) {
            assertNotNull(sdk.getSdk());
        }
    }

    @Test
    public void builder_withSecurityProvider_usesItForTls() {
        final String marker = "test provider asked for an SSLContext";
        // A provider whose TLS SSLContext fails with a marker: if build() reports the marker, the
        // HTTP client asked this provider, not the platform default, for its SSL context.
        final Provider provider = new Provider("ABSmartlyTestTLS", 1.0, "test TLS provider") {
            {
                putService(new Service(this, "SSLContext", "TLS", "unused", null, null) {
                    @Override
                    public Object newInstance(Object constructorParameter) throws NoSuchAlgorithmException {
                        throw new NoSuchAlgorithmException(marker);
                    }
                });
            }
        };

        try {
            ABSmartlyAndroid.builder()
                    .endpoint("https://test.absmartly.io/v1")
                    .apiKey("test-api-key")
                    .application("test-app")
                    .environment("test-env")
                    .securityProvider(provider)
                    .context(androidContext)
                    .build();
            fail("expected the test provider to be used");
        } catch (SecurityException e) {
            boolean found = false;
            for (Throwable t = e; t != null; t = t.getCause()) {
                found |= t.getMessage() != null && t.getMessage().contains(marker);
            }
            assertTrue("the configured provider was not used: " + e, found);
        }
    }

    @Test
    public void constructionFailure_closesTheTransport() {
        final RecordingHTTPClient http = new RecordingHTTPClient();
        try {
            // Non-empty, so it passes the wrapper's checks, but core-api's Client rejects the scheme.
            new ABSmartlyAndroid("ftp://test.absmartly.io/v1", "key", "app", "env", null, androidContext,
                    () -> http);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("protocol"));
        }
        assertTrue("the transport must be closed when construction fails", http.isClosed());
    }

    @Test
    public void close_closesTheTransport() throws IOException {
        final RecordingHTTPClient http = new RecordingHTTPClient();
        new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "app", "env", null, androidContext,
                () -> http).close();
        assertTrue(http.isClosed());
    }

    @Test
    public void close_dropsQueuedDelayedPublishAfterClose() throws Exception {
        final RecordingHTTPClient http = new RecordingHTTPClient();
        final ABSmartlyAndroid sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "app", "env",
                null, androidContext, () -> http);
        // A long publish delay queues a timer on the wrapper's scheduler without publishing.
        final com.absmartly.sdk.Context context = sdk.createContextWith(
                ContextConfig.create().setUnit("session_id", "abc").setPublishDelay(60_000),
                new ContextData());
        context.track("goal", null);
        assertEquals(1, context.getPendingCount());

        final long start = System.nanoTime();
        sdk.close();
        assertTrue("close() must not wait for the delayed publish",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000);
        // The delayed timer is cancelled, so nothing is published against the closed client.
        assertTrue(http.requests("PUT").isEmpty());
    }

    @Test
    public void close_afterContextClose_publishesPendingEventsFirst() throws Exception {
        final RecordingHTTPClient http = new RecordingHTTPClient();
        final ABSmartlyAndroid sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "app", "env",
                null, androidContext, () -> http);
        final com.absmartly.sdk.Context context = sdk.createContextWith(
                ContextConfig.create().setUnit("session_id", "abc").setPublishDelay(60_000),
                new ContextData());
        context.track("goal", null);

        context.close();
        sdk.close();

        assertEquals(1, http.requests("PUT").size());
    }

    @Test(expected = NullPointerException.class)
    public void create_nullEndpoint_throwsNullPointerException() {
        ABSmartlyAndroid.create(null, "key", "app", "env", androidContext);
    }

    @Test(expected = NullPointerException.class)
    public void create_nullApiKey_throwsNullPointerException() {
        ABSmartlyAndroid.create("https://test.absmartly.io/v1", null, "app", "env", androidContext);
    }

    @Test(expected = NullPointerException.class)
    public void create_nullApplication_throwsNullPointerException() {
        ABSmartlyAndroid.create("https://test.absmartly.io/v1", "key", null, "env", androidContext);
    }

    @Test(expected = NullPointerException.class)
    public void create_nullEnvironment_throwsNullPointerException() {
        ABSmartlyAndroid.create("https://test.absmartly.io/v1", "key", "app", null, androidContext);
    }

    @Test(expected = NullPointerException.class)
    public void create_nullContext_throwsNullPointerException() {
        ABSmartlyAndroid.create("https://test.absmartly.io/v1", "key", "app", "env", null);
    }

    @Test
    public void builder_emptyEndpoint_throwsIllegalStateException() {
        try {
            ABSmartlyAndroid.builder()
                    .endpoint("")
                    .apiKey("key")
                    .application("app")
                    .environment("env")
                    .context(androidContext)
                    .build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("endpoint"));
        }
    }

    @Test
    public void builder_emptyApiKey_throwsIllegalStateException() {
        try {
            ABSmartlyAndroid.builder()
                    .endpoint("https://test.absmartly.io/v1")
                    .apiKey("")
                    .application("app")
                    .environment("env")
                    .context(androidContext)
                    .build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("apiKey"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_emptyEndpoint_throwsIllegalArgumentException() {
        ABSmartlyAndroid.create("", "key", "app", "env", androidContext);
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_emptyApiKey_throwsIllegalArgumentException() {
        ABSmartlyAndroid.create("https://test.absmartly.io/v1", "", "app", "env", androidContext);
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_emptyApplication_throwsIllegalArgumentException() {
        ABSmartlyAndroid.create("https://test.absmartly.io/v1", "key", "", "env", androidContext);
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_emptyEnvironment_throwsIllegalArgumentException() {
        ABSmartlyAndroid.create("https://test.absmartly.io/v1", "key", "app", "", androidContext);
    }
}
