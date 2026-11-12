package com.absmartly.android.sdk;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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
    public void builder_allFieldsSet_returnsNonNull() {
        ABSmartlyAndroid.Builder builder = ABSmartlyAndroid.builder()
                .endpoint("https://test.absmartly.io/v1")
                .apiKey("test-api-key")
                .application("test-app")
                .environment("test-env")
                .context(androidContext);

        assertNotNull(builder);
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
