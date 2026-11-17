package com.absmartly.android.sdk;

import com.absmartly.sdk.ContextConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Holdouts through ABSmartlyAndroid: the context payload is fetched and parsed by the real Client
 * path and the exposures are read from the published request body. The holdout logic itself is in
 * core-api-java6 (com.absmartly.sdk.ContextHoldoutTest runs its unit tests against the same jar).
 */
@RunWith(RobolectricTestRunner.class)
public class HoldoutsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // holdout_a in holdouts_context.json (split 0.1/0.9, seeds 13/111) holds UID out (arm 0) and
    // not UID_NOT_HELD_OUT (arm 1).
    private static final String UID = "e791e240fcd3df7d238cfc285f475e8152fcc0ec";
    private static final String UID_NOT_HELD_OUT = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3";

    private static final String UNKNOWN = "\"futureScalar\":1,\"futureObject\":{\"a\":[1,{\"b\":null}]},"
            + "\"futureArray\":[{\"x\":\"y\"},2]";

    private RecordingHTTPClient http;
    private ABSmartlyAndroid sdk;

    @Before
    public void setUp() {
        http = new RecordingHTTPClient();
        sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "website", "production", null,
                RuntimeEnvironment.getApplication(), () -> http);
    }

    @After
    public void tearDown() throws IOException {
        sdk.close();
    }

    private com.absmartly.sdk.Context fetch(String uid, String payload) {
        http.setContextData(payload);
        final com.absmartly.sdk.Context context = sdk.createContext(ContextConfig.create()
                .setUnit("session_id", uid)
                .setPublishDelay(-1));
        context.waitUntilReady();
        assertTrue(context.isReady());
        assertFalse(context.isFailed());
        return context;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = HoldoutsTest.class.getClassLoader().getResourceAsStream(name)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private List<JsonNode> publishedExposures() throws IOException {
        final List<JsonNode> exposures = new ArrayList<>();
        for (final RecordingHTTPClient.Request put : http.requests("PUT")) {
            for (final JsonNode exposure : MAPPER.readTree(put.bodyString()).path("exposures")) {
                exposures.add(exposure);
            }
        }
        return exposures;
    }

    private static void assertExposure(JsonNode exposure, int id, String name, int variant, boolean assigned) {
        assertEquals(exposure.toString(), id, exposure.get("id").asInt());
        assertEquals(exposure.toString(), name, exposure.get("name").asText());
        assertEquals(exposure.toString(), "session_id", exposure.get("unit").asText());
        assertEquals(exposure.toString(), variant, exposure.get("variant").asInt());
        assertEquals(exposure.toString(), assigned, exposure.get("assigned").asBoolean());
        assertTrue(exposure.toString(), exposure.get("eligible").asBoolean());
        assertFalse(exposure.toString(), exposure.get("overridden").asBoolean());
        assertFalse(exposure.toString(), exposure.get("fullOn").asBoolean());
        assertFalse(exposure.toString(), exposure.get("custom").asBoolean());
        assertFalse(exposure.toString(), exposure.get("audienceMismatch").asBoolean());
        assertFalse(exposure.toString(), exposure.get("ruleOverride").asBoolean());
    }

    @Test
    public void heldOutUnitGetsControlAndPublishesOnlyTheHoldoutExposure() throws IOException {
        final com.absmartly.sdk.Context context = fetch(UID, resource("holdouts_context.json"));
        assertEquals(0, context.peekTreatment("exp_test_holdout"));
        assertEquals(0, context.getPendingCount());
        assertEquals(0, context.getTreatment("exp_test_holdout"));
        // The suppressed experiment reads control (variant A), which has no variables.
        assertEquals("default", context.getVariableValue("banner.size", "default"));
        assertEquals(0, context.getTreatment("exp_test_holdout"));
        assertEquals(1, context.getPendingCount());
        context.publish();

        final List<JsonNode> exposures = publishedExposures();
        assertEquals(exposures.toString(), 1, exposures.size());
        assertExposure(exposures.get(0), 11, "holdout_a", 0, true);
        context.close();
    }

    @Test
    public void notHeldOutUnitAssignsNormallyAndPublishesTheHoldoutExposureAfterTheExperiment()
            throws IOException {
        final String payload = resource("holdouts_context.json");
        final int normal = fetch(UID_NOT_HELD_OUT, payload.replace("\"holdoutIds\":[11]", "\"holdoutIds\":[]"))
                .peekTreatment("exp_test_holdout");

        final com.absmartly.sdk.Context context = fetch(UID_NOT_HELD_OUT, payload);
        assertEquals(normal, context.getTreatment("exp_test_holdout"));
        context.publish();

        final List<JsonNode> exposures = publishedExposures();
        assertEquals(exposures.toString(), 2, exposures.size());
        assertExposure(exposures.get(0), 1, "exp_test_holdout", normal, true);
        assertExposure(exposures.get(1), 11, "holdout_a", 1, true);
        context.close();
    }

    // Two covered experiments: holdout_a holds UID out of the first, holdout_b does not hold it out of
    // the second. One uncovered experiment. Every holdout object, the holdouts array
    // and the root carry unknown scalar, object and array keys, and an unknown holdoutType.
    private static String forwardCompatiblePayload() {
        return "{" + UNKNOWN + ",\"experiments\":["
                + experiment(1, "exp_covered_a", "[11]") + ","
                + experiment(2, "exp_covered_b", "[12,99]") + ","
                + experiment(3, "exp_uncovered", null)
                + "],\"holdouts\":["
                + "{" + UNKNOWN + ",\"id\":11,\"name\":\"holdout_a\",\"iteration\":1,\"unitType\":\"session_id\","
                + "\"seedHi\":13,\"seedLo\":111,\"split\":[0.1,0.9],\"holdoutType\":\"future_type\","
                + "\"futureHoldoutArms\":[{\"arm\":0,\"label\":\"held\"}]},"
                + "{" + UNKNOWN + ",\"id\":12,\"name\":\"holdout_b\",\"iteration\":1,\"unitType\":\"session_id\","
                + "\"seedHi\":1,\"seedLo\":222,\"split\":[0.1,0.9],\"futureSchedule\":{\"start\":0}}"
                + "],\"futureHoldoutGroups\":[{\"id\":1,\"holdoutIds\":[11]}]}";
    }

    private static String experiment(int id, String name, String holdoutIds) {
        return "{" + UNKNOWN + ",\"id\":" + id + ",\"name\":\"" + name + "\",\"iteration\":1,"
                + "\"unitType\":\"session_id\",\"seedHi\":0,\"seedLo\":0,\"split\":[0,1],"
                + "\"trafficSeedHi\":0,\"trafficSeedLo\":0,\"trafficSplit\":[0,1],\"fullOnVariant\":0,"
                + "\"applications\":[{\"name\":\"website\"}],"
                + "\"variants\":[{\"name\":\"A\",\"config\":null},{\"name\":\"B\",\"config\":null}],"
                + "\"audience\":null,\"audienceStrict\":false"
                + (holdoutIds != null ? ",\"holdoutIds\":" + holdoutIds + ",\"futureHoldoutMode\":\"x\"" : "")
                + "}";
    }

    @Test
    public void unknownFieldsInHoldoutObjectsAreIgnored() throws IOException {
        final com.absmartly.sdk.Context context = fetch(UID, forwardCompatiblePayload());
        assertEquals(0, context.getTreatment("exp_covered_a"));
        assertEquals(1, context.getTreatment("exp_covered_b"));
        assertEquals(1, context.getTreatment("exp_uncovered"));
        context.publish();

        final List<JsonNode> exposures = publishedExposures();
        assertEquals(exposures.toString(), 4, exposures.size());
        assertExposure(exposures.get(0), 11, "holdout_a", 0, true);
        assertExposure(exposures.get(1), 2, "exp_covered_b", 1, true);
        assertExposure(exposures.get(2), 12, "holdout_b", 1, true);
        assertExposure(exposures.get(3), 3, "exp_uncovered", 1, true);
        context.close();
    }

    private static String holdout(int id, String name, int seedHi, int seedLo, String split, String extra) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"iteration\":1,\"unitType\":\"session_id\","
                + "\"seedHi\":" + seedHi + ",\"seedLo\":" + seedLo + ",\"split\":" + split + extra + "}";
    }

    // The arm count comes from the length of split, never from holdoutType. For UID, a 0.3/0.3/0.4
    // split with seeds 0/3 gives arm 1, which holds out a non-full-on experiment; holdout_b's
    // 0.1/0.9 split gives arm 1 of 2, which holds nothing out whatever holdoutType says.
    @Test
    public void holdoutArityComesFromSplit() throws IOException {
        final com.absmartly.sdk.Context context = fetch(UID, "{\"experiments\":["
                + experiment(1, "exp_three_arm_no_type", "[21]") + ","
                + experiment(2, "exp_three_arm_full_type", "[22]") + ","
                + experiment(3, "exp_two_arm_all_full_on_type", "[23]")
                + "],\"holdouts\":["
                + holdout(21, "holdout_three_arm_no_type", 0, 3, "[0.3,0.3,0.4]", "") + ","
                + holdout(22, "holdout_three_arm_full_type", 0, 3, "[0.3,0.3,0.4]", ",\"holdoutType\":\"full\"") + ","
                + holdout(23, "holdout_two_arm_all_full_on_type", 1, 222, "[0.1,0.9]",
                        ",\"holdoutType\":\"all_full_on\"")
                + "]}");
        assertEquals(0, context.getTreatment("exp_three_arm_no_type"));
        assertEquals(0, context.getTreatment("exp_three_arm_full_type"));
        assertEquals(1, context.getTreatment("exp_two_arm_all_full_on_type"));
        context.publish();

        final List<JsonNode> exposures = publishedExposures();
        assertEquals(exposures.toString(), 4, exposures.size());
        assertExposure(exposures.get(0), 21, "holdout_three_arm_no_type", 1, true);
        assertExposure(exposures.get(1), 22, "holdout_three_arm_full_type", 1, true);
        assertExposure(exposures.get(2), 3, "exp_two_arm_all_full_on_type", 1, true);
        assertExposure(exposures.get(3), 23, "holdout_two_arm_all_full_on_type", 1, true);
        context.close();
    }

    // An experiment's holdouts fire in id order, whatever the order of holdoutIds and holdouts.
    @Test
    public void holdoutExposuresFireInIdOrder() throws IOException {
        final com.absmartly.sdk.Context context = fetch(UID_NOT_HELD_OUT, "{\"experiments\":["
                + experiment(1, "exp_two_holdouts", "[12,11]")
                + "],\"holdouts\":["
                + holdout(12, "holdout_b", 1, 222, "[0.1,0.9]", "") + ","
                + holdout(11, "holdout_a", 13, 111, "[0.1,0.9]", "")
                + "]}");
        assertEquals(1, context.getTreatment("exp_two_holdouts"));
        context.publish();

        final List<JsonNode> exposures = publishedExposures();
        assertEquals(exposures.toString(), 3, exposures.size());
        assertExposure(exposures.get(0), 1, "exp_two_holdouts", 1, true);
        assertExposure(exposures.get(1), 11, "holdout_a", 1, true);
        assertExposure(exposures.get(2), 12, "holdout_b", 1, true);
        context.close();
    }

    // The same reads on payloads that differ only in how they say "no holdouts". Each must publish the
    // same body as a payload from before holdouts existed, which is pinned below byte for byte (apart
    // from the clock readings).
    private String publishBody(String holdouts, String holdoutIds) throws IOException {
        http = new RecordingHTTPClient();
        sdk.close();
        sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "website", "production", null,
                RuntimeEnvironment.getApplication(), () -> http);
        final String experiment = "{\"id\":1,\"name\":\"exp_a\",\"iteration\":1,\"unitType\":\"session_id\","
                + "\"seedHi\":0,\"seedLo\":0,\"split\":[0,1],\"trafficSeedHi\":0,\"trafficSeedLo\":0,"
                + "\"trafficSplit\":[0,1],\"fullOnVariant\":0,\"applications\":[{\"name\":\"website\"}],"
                + "\"variants\":[{\"name\":\"A\",\"config\":null},{\"name\":\"B\",\"config\":\"{\\\"color\\\":\\\"blue\\\"}\"}],"
                + "\"audience\":null,\"audienceStrict\":false" + holdoutIds + "}";
        final com.absmartly.sdk.Context context = fetch(UID,
                "{\"experiments\":[" + experiment + "]" + holdouts + "}");
        context.setAttribute("country", "PT");
        assertEquals(1, context.getTreatment("exp_a"));
        assertEquals("blue", context.getVariableValue("color", "default"));
        assertEquals(0, context.peekTreatment("missing"));
        context.track("goal", null);
        context.publish();
        context.close();

        final List<RecordingHTTPClient.Request> puts = http.requests("PUT");
        assertEquals(1, puts.size());
        return puts.get(0).bodyString()
                .replaceAll("\"(publishedAt|exposedAt|achievedAt|setAt)\":\\d+", "\"$1\":0");
    }

    @Test
    public void publishBodyWithoutHoldoutsIsUnchanged() throws IOException {
        final String expected = "{\"hashed\":true,\"units\":[{\"type\":\"session_id\","
                + "\"uid\":\"" + new String(com.absmartly.sdk.internal.hashing.Hashing.hashUnit(UID),
                StandardCharsets.US_ASCII) + "\"}],\"publishedAt\":0,"
                + "\"exposures\":[{\"id\":1,\"name\":\"exp_a\",\"unit\":\"session_id\",\"variant\":1,"
                + "\"exposedAt\":0,\"assigned\":true,\"eligible\":true,\"overridden\":false,\"fullOn\":false,"
                + "\"custom\":false,\"audienceMismatch\":false,\"ruleOverride\":false}],"
                + "\"goals\":[{\"name\":\"goal\",\"achievedAt\":0}],"
                + "\"attributes\":[{\"name\":\"country\",\"value\":\"PT\",\"setAt\":0}]}";
        assertEquals(expected, publishBody("", ""));
        assertEquals(expected, publishBody(",\"holdouts\":null", ",\"holdoutIds\":null"));
        assertEquals(expected, publishBody(",\"holdouts\":[]", ",\"holdoutIds\":[]"));
        // A holdout no experiment references.
        assertEquals(expected, publishBody(",\"holdouts\":[{\"id\":11,\"name\":\"holdout_a\",\"iteration\":1,"
                + "\"unitType\":\"session_id\",\"seedHi\":13,\"seedLo\":111,\"split\":[0.1,0.9]}]", ""));
        // A dangling id and a holdout without a split, both ignored.
        assertEquals(expected, publishBody(",\"holdouts\":[{\"id\":11,\"name\":\"holdout_a\",\"iteration\":1,"
                + "\"unitType\":\"session_id\",\"seedHi\":13,\"seedLo\":111,\"split\":[]}]", ",\"holdoutIds\":[11,99]"));
    }
}
