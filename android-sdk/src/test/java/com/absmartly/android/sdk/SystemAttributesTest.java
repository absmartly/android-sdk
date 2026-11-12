package com.absmartly.android.sdk;

import android.content.Context;

import com.absmartly.sdk.ContextConfig;
import com.absmartly.sdk.DefaultContextDataDeserializer;
import com.absmartly.sdk.json.ContextData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * System attributes, SDK identity (X-Agent) and application version through ABSmartlyAndroid,
 * checked on the requests the real core-api Client sends.
 */
@RunWith(RobolectricTestRunner.class)
public class SystemAttributesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXP = "exp_rule";

    private Context androidContext;
    private RecordingHTTPClient http;
    private ABSmartlyAndroid sdk;

    @Before
    public void setUp() {
        androidContext = RuntimeEnvironment.getApplication();
        http = new RecordingHTTPClient();
    }

    @After
    public void tearDown() throws IOException {
        if (sdk != null) {
            sdk.close();
        }
    }

    private ABSmartlyAndroid create(String applicationVersion) {
        sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "android-app", "production",
                applicationVersion, androidContext, () -> http);
        return sdk;
    }

    // One two-variant experiment whose only rule assigns variant 1 when the condition matches. Its
    // normal split sends every unit to variant 0.
    private static ContextData ruleData(String condition) throws IOException {
        final String rules = "{\"rules\":[{\"name\":\"r\",\"type\":\"assign\",\"variant\":1,\"conditions\":"
                + condition + "}]}";
        final String json = "{\"experiments\":[{\"id\":1,\"name\":\"" + EXP + "\",\"iteration\":1,"
                + "\"unitType\":\"session_id\",\"seedHi\":0,\"seedLo\":0,\"split\":[1,0],"
                + "\"trafficSeedHi\":0,\"trafficSeedLo\":0,\"trafficSplit\":[0,1],\"fullOnVariant\":0,"
                + "\"applications\":[{\"name\":\"android-app\"}],"
                + "\"variants\":[{\"name\":\"A\",\"config\":null},{\"name\":\"B\",\"config\":null}],"
                + "\"audience\":null,\"audienceStrict\":false,"
                + "\"assignmentRules\":" + MAPPER.writeValueAsString(rules) + "}]}";
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length);
    }

    private static ContextConfig config(boolean includeSystemAttributes) {
        return ContextConfig.create()
                .setUnit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec")
                .setPublishDelay(-1)
                .setIncludeSystemAttributes(includeSystemAttributes);
    }

    private JsonNode lastPublish() throws IOException {
        final List<RecordingHTTPClient.Request> puts = http.requests("PUT");
        assertFalse("no publish request was sent", puts.isEmpty());
        return MAPPER.readTree(puts.get(puts.size() - 1).bodyString());
    }

    private static List<String> attributeNames(JsonNode event) {
        final List<String> names = new ArrayList<>();
        for (final JsonNode attribute : event.path("attributes")) {
            names.add(attribute.get("name").asText());
        }
        return names;
    }

    private static JsonNode attribute(JsonNode event, String name) {
        for (final JsonNode attribute : event.path("attributes")) {
            if (name.equals(attribute.get("name").asText())) {
                return attribute.get("value");
            }
        }
        return null;
    }

    @Test
    public void sdkIdentityConstants() {
        assertEquals("absmartly-android-sdk", ABSmartlyAndroid.SDK_NAME);
        assertEquals(BuildConfig.SDK_VERSION, ABSmartlyAndroid.SDK_VERSION);
        assertFalse(ABSmartlyAndroid.SDK_VERSION.isEmpty());
        assertEquals("absmartly-android-sdk/" + ABSmartlyAndroid.SDK_VERSION, ABSmartlyAndroid.AGENT);
    }

    @Test
    public void publishSendsAndroidAgentHeader() throws IOException {
        final com.absmartly.sdk.Context context = create(null).createContextWith(config(false), ruleData("null"));
        context.track("goal", null);
        context.publish();

        final RecordingHTTPClient.Request put = http.requests("PUT").get(0);
        assertEquals("absmartly-android-sdk/" + ABSmartlyAndroid.SDK_VERSION, put.headers.get("X-Agent"));
        assertEquals("android-app", put.headers.get("X-Application"));
        assertEquals("production", put.headers.get("X-Environment"));
        context.close();
    }

    @Test
    public void contextFetchSendsAndroidAgentHeader() {
        http.setContextData("{\"experiments\":[]}");
        final com.absmartly.sdk.Context context = create(null).createContext(config(false));
        context.waitUntilReady();

        final RecordingHTTPClient.Request get = http.requests("GET").get(0);
        assertEquals(ABSmartlyAndroid.AGENT, get.headers.get("X-Agent"));
        context.close();
    }

    @Test
    public void systemAttributesAreLeftOutByDefault() throws IOException {
        final com.absmartly.sdk.Context context = create("2.4.1").createContextWith(config(false), ruleData("null"));
        context.setAttribute("age", 21);
        context.track("goal", null);
        context.publish();

        final JsonNode event = lastPublish();
        final List<String> expected = new ArrayList<>();
        expected.add("age");
        assertEquals(expected, attributeNames(event));
        context.close();
    }

    @Test
    public void systemAttributesArePrependedWithAndroidIdentity() throws IOException {
        final com.absmartly.sdk.Context context = create("2.4.1").createContextWith(config(true), ruleData("null"));
        context.setAttribute("age", 21);
        context.track("goal", null);
        context.publish();

        final JsonNode event = lastPublish();
        final List<String> expected = new ArrayList<>();
        expected.add("sdk_name");
        expected.add("sdk_version");
        expected.add("application");
        expected.add("environment");
        expected.add("app_version");
        expected.add("age");
        assertEquals(expected, attributeNames(event));
        assertEquals("absmartly-android-sdk", attribute(event, "sdk_name").asText());
        assertEquals(ABSmartlyAndroid.SDK_VERSION, attribute(event, "sdk_version").asText());
        assertEquals("android-app", attribute(event, "application").asText());
        assertEquals("production", attribute(event, "environment").asText());
        assertEquals("2.4.1", attribute(event, "app_version").asText());

        // System attributes apply to every event in the publish, so they share the earliest setAt.
        final long setAt = event.get("attributes").get(0).get("setAt").asLong();
        for (int i = 1; i < 5; ++i) {
            assertEquals(setAt, event.get("attributes").get(i).get("setAt").asLong());
        }
        context.close();
    }

    @Test
    public void appVersionIsLeftOutWhenNotSet() throws IOException {
        final com.absmartly.sdk.Context context = create(null).createContextWith(config(true), ruleData("null"));
        context.track("goal", null);
        context.publish();

        final JsonNode event = lastPublish();
        assertFalse(attributeNames(event).contains("app_version"));
        assertTrue(attributeNames(event).contains("sdk_name"));
        context.close();
    }

    @Test
    public void userAttributeWithSystemNameIsKept() throws IOException {
        final com.absmartly.sdk.Context context = create("2.4.1").createContextWith(config(true), ruleData("null"));
        context.setAttribute("sdk_name", "custom");
        context.setAttribute("sdk_version", "9");
        context.track("goal", null);
        context.publish();

        final JsonNode event = lastPublish();
        int sdkNames = 0;
        for (final String name : attributeNames(event)) {
            if ("sdk_name".equals(name)) {
                ++sdkNames;
            }
        }
        assertEquals(1, sdkNames);
        assertEquals("custom", attribute(event, "sdk_name").asText());
        assertEquals("9", attribute(event, "sdk_version").asText());
        context.close();
    }

    @Test
    public void wholeNumberApplicationVersionIsSentAsHeader() throws IOException {
        final com.absmartly.sdk.Context context = create("42").createContextWith(config(false), ruleData("null"));
        context.track("goal", null);
        context.publish();
        assertEquals("42", http.requests("PUT").get(0).headers.get("X-Application-Version"));
        context.close();
    }

    @Test
    public void semverApplicationVersionSendsZeroHeader() throws IOException {
        final com.absmartly.sdk.Context context = create("2.4.1").createContextWith(config(false), ruleData("null"));
        context.track("goal", null);
        context.publish();
        assertEquals("0", http.requests("PUT").get(0).headers.get("X-Application-Version"));
        context.close();
    }

    @Test
    public void systemAttributesAreAvailableToRuleConditions() throws IOException {
        final String[] conditions = {
                "{\"eq\":[{\"var\":\"application\"},{\"value\":\"android-app\"}]}",
                "{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}",
                "{\"semver_gte\":[{\"var\":\"app_version\"},{\"value\":\"2.4.0\"}]}",
        };
        create("2.4.1");
        for (final String condition : conditions) {
            assertEquals(condition, 1, sdk.createContextWith(config(true), ruleData(condition)).getTreatment(EXP));
            assertEquals(condition, 0, sdk.createContextWith(config(false), ruleData(condition)).getTreatment(EXP));
        }
    }

    @Test
    public void ruleWinIsPublishedWithRuleOverride() throws IOException {
        final com.absmartly.sdk.Context context = create(null).createContextWith(config(false),
                ruleData("{\"eq\":[{\"var\":\"country\"},{\"value\":\"US\"}]}"));
        context.setAttribute("country", "US");
        assertEquals(1, context.getTreatment(EXP));
        context.publish();

        final JsonNode exposure = lastPublish().get("exposures").get(0);
        assertEquals(1, exposure.get("variant").asInt());
        assertTrue(exposure.get("ruleOverride").asBoolean());
        assertFalse(exposure.get("assigned").asBoolean());
        context.close();
    }
}
