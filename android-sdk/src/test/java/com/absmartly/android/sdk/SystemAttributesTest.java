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
        return ruleData(condition, null);
    }

    private static ContextData ruleData(String condition, String environments) throws IOException {
        final byte[] bytes = ruleJson(condition, environments, "r").getBytes(StandardCharsets.UTF_8);
        return new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length);
    }

    private static String ruleJson(String condition, String environments, String ruleName) throws IOException {
        final String rules = "{\"rules\":[{\"name\":\"" + ruleName + "\",\"type\":\"assign\",\"variant\":1,"
                + "\"conditions\":" + condition + (environments != null ? ",\"environments\":" + environments : "")
                + "}]}";
        return "{\"experiments\":[{\"id\":1,\"name\":\"" + EXP + "\",\"iteration\":1,"
                + "\"unitType\":\"session_id\",\"seedHi\":0,\"seedLo\":0,\"split\":[1,0],"
                + "\"trafficSeedHi\":0,\"trafficSeedLo\":0,\"trafficSplit\":[0,1],\"fullOnVariant\":0,"
                + "\"applications\":[{\"name\":\"android-app\"}],"
                + "\"variants\":[{\"name\":\"A\",\"config\":null},{\"name\":\"B\",\"config\":null}],"
                + "\"audience\":null,\"audienceStrict\":false,"
                + "\"assignmentRules\":" + MAPPER.writeValueAsString(rules) + "}]}";
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
    public void onlyUserAttributesArePublishedByDefault() throws IOException {
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
    public void includeSystemAttributesPublishesOnlyAndroidIdentity() throws IOException {
        final com.absmartly.sdk.Context context = create("2.4.1").createContextWith(config(true), ruleData("null"));
        context.setAttribute("age", 21);
        context.track("goal", null);
        context.publish();

        // application and environment are added by the collector, and the SDK provides no app_version.
        final JsonNode event = lastPublish();
        final List<String> expected = new ArrayList<>();
        expected.add("sdk_name");
        expected.add("sdk_version");
        expected.add("age");
        assertEquals(expected, attributeNames(event));
        assertEquals("absmartly-android-sdk", attribute(event, "sdk_name").asText());
        assertEquals(ABSmartlyAndroid.SDK_VERSION, attribute(event, "sdk_version").asText());

        // System attributes apply to every event in the publish, so they share the earliest setAt.
        assertEquals(event.get("attributes").get(0).get("setAt").asLong(),
                event.get("attributes").get(1).get("setAt").asLong());
        context.close();
    }

    @Test
    public void userAppVersionIsPublishedWithEitherSetting() throws IOException {
        for (final boolean includeSystemAttributes : new boolean[]{false, true}) {
            final com.absmartly.sdk.Context context = create("2.4.1")
                    .createContextWith(config(includeSystemAttributes), ruleData("null"));
            context.setAttribute("app_version", "9.9.9");
            context.track("goal", null);
            context.publish();

            final JsonNode event = lastPublish();
            assertEquals(String.valueOf(includeSystemAttributes), "9.9.9", attribute(event, "app_version").asText());
            context.close();
            sdk.close();
            sdk = null;
        }
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
    public void configuredNamesAreNotUserAttributes() throws IOException {
        final com.absmartly.sdk.Context context = create("2.4.1").createContextWith(config(true), ruleData("null"));
        assertTrue(context.getAttributes().isEmpty());
        assertEquals(null, context.getAttribute("application"));
        assertEquals(null, context.getAttribute("environment"));
        context.close();
    }

    @Test
    public void applicationVersionHeaderIsAWholeNumberOnGetAndPut() throws IOException {
        final String[][] cases = {
                {null, "0"},
                {"", "0"},
                {"   ", "0"},
                {"1004", "1004"},
                {" 1004 ", "1004"},
                {"3.2.1", "0"},
                {"-1", "0"},
                {"\u0661\u0662\u0663", "0"},
                {"123456789012345678", "123456789012345678"},
                {"1234567890123456789", "0"},
        };
        for (final String[] c : cases) {
            http = new RecordingHTTPClient();
            http.setContextData("{\"experiments\":[]}");
            final com.absmartly.sdk.Context context = create(c[0]).createContext(config(false));
            context.waitUntilReady();
            context.track("goal", null);
            context.publish();

            assertEquals("GET " + c[0], c[1], http.requests("GET").get(0).headers.get("X-Application-Version"));
            assertEquals("PUT " + c[0], c[1], http.requests("PUT").get(0).headers.get("X-Application-Version"));
            context.close();
            sdk.close();
            sdk = null;
        }
    }

    private int treatment(boolean includeSystemAttributes, String condition, String environments,
            String userEnvironment) throws IOException {
        final com.absmartly.sdk.Context context = sdk.createContextWith(config(includeSystemAttributes),
                ruleData(condition, environments));
        if (userEnvironment != null) {
            context.setAttribute("environment", userEnvironment);
        }
        final int treatment = context.getTreatment(EXP);
        context.close();
        return treatment;
    }

    @Test
    public void configuredApplicationAndEnvironmentAreAlwaysEvaluated() throws IOException {
        create("2.4.1");
        for (final boolean flag : new boolean[]{false, true}) {
            assertEquals(1, treatment(flag, "{\"eq\":[{\"var\":\"application\"},{\"value\":\"android-app\"}]}", null, null));
            assertEquals(1, treatment(flag, "{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}", null, null));
        }
    }

    @Test
    public void appVersionIsNotProvidedToConditions() throws IOException {
        // A presence check, not a comparison: any injected value, whatever it is, makes it fail.
        create("2.4.1");
        for (final boolean flag : new boolean[]{false, true}) {
            assertEquals(1, treatment(flag, "{\"null\":{\"var\":\"app_version\"}}", null, null));
        }
    }

    @Test
    public void userEnvironmentOverridesTheConfiguredOneInConditions() throws IOException {
        create(null);
        assertEquals(1, treatment(false, "{\"eq\":[{\"var\":\"environment\"},{\"value\":\"staging\"}]}", null, "staging"));
        assertEquals(0, treatment(false, "{\"eq\":[{\"var\":\"environment\"},{\"value\":\"production\"}]}", null, "staging"));
    }

    @Test
    public void ruleScopeUsesTheConfiguredEnvironment() throws IOException {
        create(null);
        // The condition matches either way; only the scope decides.
        assertEquals(1, treatment(false, "null", "[\"production\"]", "staging"));
        assertEquals(0, treatment(false, "null", "[\"staging\"]", "staging"));
    }

    @Test
    public void exposedRuleWinIsNotExposedAgainAfterARefreshWithTheSameOutcome() throws IOException {
        http.setContextData(ruleJson("null", null, "first"));
        final com.absmartly.sdk.Context context = create(null).createContext(config(false));
        context.waitUntilReady();
        assertEquals(1, context.getTreatment(EXP));
        assertEquals(1, context.getPendingCount());

        http.setContextData(ruleJson("null", null, "renamed"));
        context.refresh();
        assertEquals(1, context.getTreatment(EXP));
        assertEquals(1, context.getPendingCount());

        context.publish();
        assertEquals(1, lastPublish().get("exposures").size());
        context.close();
    }

    @Test
    public void exposedRuleWinKeepsItsExposureWhenADifferentRuleGivesTheSameVariant() throws IOException {
        // First a split rule whose unit (session_id) is not set gives the no-unit variant 0. Setting
        // country makes an earlier assign rule with the same variant 0 match: the exposed assignment
        // is kept, so the decision is published once.
        final String rules = "{\"rules\":["
                + "{\"name\":\"us\",\"type\":\"assign\",\"variant\":0,"
                + "\"conditions\":{\"eq\":[{\"var\":\"country\"},{\"value\":\"US\"}]}},"
                + "{\"name\":\"split\",\"type\":\"split\",\"percentages\":\"0/100\"}]}";
        final String json = "{\"experiments\":[{\"id\":1,\"name\":\"" + EXP + "\",\"iteration\":1,"
                + "\"unitType\":\"session_id\",\"seedHi\":0,\"seedLo\":0,\"split\":[0,1],"
                + "\"trafficSeedHi\":0,\"trafficSeedLo\":0,\"trafficSplit\":[0,1],\"fullOnVariant\":0,"
                + "\"applications\":[{\"name\":\"android-app\"}],"
                + "\"variants\":[{\"name\":\"A\",\"config\":null},{\"name\":\"B\",\"config\":null}],"
                + "\"audience\":null,\"audienceStrict\":false,"
                + "\"assignmentRules\":" + MAPPER.writeValueAsString(rules) + "}]}";
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        final com.absmartly.sdk.Context context = create(null).createContextWith(
                ContextConfig.create().setUnit("user_id", "u1").setPublishDelay(-1),
                new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length));

        assertEquals(0, context.getTreatment(EXP));
        context.setAttribute("country", "US");
        assertEquals(0, context.getTreatment(EXP));
        assertEquals(1, context.getPendingCount());

        context.publish();
        final JsonNode exposures = lastPublish().get("exposures");
        assertEquals(1, exposures.size());
        assertTrue(exposures.get(0).get("ruleOverride").asBoolean());
        context.close();
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
