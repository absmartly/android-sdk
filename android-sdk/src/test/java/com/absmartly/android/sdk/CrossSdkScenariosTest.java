package com.absmartly.android.sdk;

import android.content.Context;

import com.absmartly.sdk.ContextConfig;
import com.absmartly.sdk.ContextEventLogger;
import com.absmartly.sdk.DefaultContextDataDeserializer;
import com.absmartly.sdk.json.ContextData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Replays the cross-SDK assignment-rule, semver, audience re-evaluation, system-attribute and
 * X-Agent scenarios (230-253 and 300-323 of absmartly/cross-sdk-tests) and the holdout scenarios
 * (203-227), recorded from the JavaScript SDK, against ABSmartlyAndroid. The fixtures are pinned
 * copies; CI checks each against the upstream file at its recorded commit.
 *
 * The scenarios expect an exposure event from each step that queues one. Here the exposures are
 * collected in order and compared with what the context actually publishes at the end, which also
 * checks that the exposure flags, including ruleOverride, survive serialization.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class CrossSdkScenariosTest {
    private static final String[] RESOURCES = {
            "cross-sdk/assignment_rules_semver_scenarios.json",
            "cross-sdk/holdout_scenarios.json",
    };
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Default client names for scenarios without a "client" object, as in the JavaScript wrapper.
    private static final String DEFAULT_APPLICATION = "test";
    private static final String DEFAULT_ENVIRONMENT = "test";

    // Scenario capabilities ABSmartlyAndroid cannot provide, with the reason. A scenario that requires
    // one of them is skipped, as the cross-SDK orchestrator does for an SDK that does not advertise it.
    static final Map<String, String> UNSUPPORTED = new HashMap<>();

    static {
        UNSUPPORTED.put("clientNoEnvironment", "ABSmartlyAndroid requires a non-empty environment, so there is"
                + " no client without one (scenarios 253 and 302)");
        UNSUPPORTED.put("clientAcceptsBlankApplication", "ABSmartlyAndroid rejects a blank application, so"
                + " there is no context to evaluate (scenario 323); scenario 322 covers the rejection");
    }

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() throws IOException {
        final List<Object[]> result = new ArrayList<>();
        for (final String resource : RESOURCES) {
            for (final JsonNode scenario : loadResource(resource).get("scenarios")) {
                result.add(new Object[]{scenario.get("name").asText(), scenario});
            }
        }
        return result;
    }

    static String unsupportedReason(JsonNode scenario) {
        for (final JsonNode capability : scenario.path("requires")) {
            final String reason = UNSUPPORTED.get(capability.asText());
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static JsonNode loadResource(String resource) throws IOException {
        try (InputStream in = CrossSdkScenariosTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing test resource " + resource);
            }
            return MAPPER.readTree(in);
        }
    }

    private final String name;
    private final JsonNode scenario;
    private RecordingHTTPClient http;
    private ABSmartlyAndroid sdk;
    private com.absmartly.sdk.Context context;
    private final List<JsonNode> expectedExposures = new ArrayList<>();
    private int exposuresExpectedAtLastPublish;
    private final List<String> loggedEvents = new CopyOnWriteArrayList<>();

    public CrossSdkScenariosTest(String name, JsonNode scenario) {
        this.name = name;
        this.scenario = scenario;
    }

    @Before
    public void setUp() {
        http = new RecordingHTTPClient();
    }

    @After
    public void tearDown() throws IOException {
        if (context != null && !context.isClosed()) {
            context.close();
        }
        if (sdk != null) {
            sdk.close();
        }
    }

    // Creates the SDK with the scenario's client names. Like the cross-SDK wrappers, only an omitted
    // name falls back to the default; any string, including "", is passed on unchanged.
    private void createSdk(JsonNode params) {
        final JsonNode client = params.path("client");
        final String application = client.has("application") ? client.get("application").asText()
                : DEFAULT_APPLICATION;
        final String environment = client.has("environment") ? client.get("environment").asText()
                : DEFAULT_ENVIRONMENT;
        final Context androidContext = RuntimeEnvironment.getApplication();
        sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", application, environment, null,
                androidContext, () -> http);
    }

    @Test
    public void scenario() throws IOException, InterruptedException {
        final String unsupported = unsupportedReason(scenario);
        Assume.assumeTrue(name + ": " + unsupported, unsupported == null);

        int index = 0;
        for (final JsonNode step : scenario.get("steps")) {
            runStep(index++, step);
        }
        assertPublishedExposures();
    }

    private void runStep(int index, JsonNode step) throws IOException, InterruptedException {
        final String action = step.get("action").asText();
        final JsonNode params = step.get("params");
        final JsonNode expect = step.get("expect");
        final String where = name + " step " + index + " (" + action + ")";
        loggedEvents.clear();

        Object result = null;
        switch (action) {
            case "createContextWith":
            case "createContext":
                if (expect.has("status")) {
                    assertCreationRejected(where, params, expect);
                    return;
                }
                createSdk(params);
                if ("createContext".equals(action)) {
                    http.setContextData(MAPPER.writeValueAsString(scenario.get("contextData")));
                    context = sdk.createContext(contextConfig(params));
                    context.waitUntilReady();
                } else {
                    context = sdk.createContextWith(contextConfig(params), contextData(scenario.get("contextData")));
                }
                assertTrue(where, context.isReady());
                assertCreateResult(where, expect.get("result"));
                assertLoggedEvents(where, expect);
                for (final JsonNode event : expect.get("events")) {
                    // A ready event without data (scenarios 214-222) only counts.
                    if ("ready".equals(event.get("type").asText()) && event.has("data")) {
                        assertReadyExperiments(where, event.get("data").get("experiments"));
                    }
                }
                return;
            case "attribute":
                context.setAttribute(params.get("name").asText(), MAPPER.treeToValue(params.get("value"), Object.class));
                break;
            case "treatment":
                result = context.getTreatment(params.get("experimentName").asText());
                break;
            case "peek":
                result = context.peekTreatment(params.get("experimentName").asText());
                break;
            case "variableValue":
                result = context.getVariableValue(params.get("key").asText(),
                        MAPPER.treeToValue(params.get("defaultValue"), Object.class));
                break;
            case "peekVariableValue":
                result = context.peekVariableValue(params.get("key").asText(),
                        MAPPER.treeToValue(params.get("defaultValue"), Object.class));
                break;
            case "pending":
                result = context.getPendingCount();
                break;
            case "override":
                context.setOverride(params.get("experimentName").asText(), params.get("variant").asInt());
                break;
            case "customAssignment":
                context.setCustomAssignment(params.get("experimentName").asText(), params.get("variant").asInt());
                break;
            case "setUnit":
                context.setUnit(params.get("unitType").asText(), params.get("uid").asText());
                break;
            case "publish":
                context.publish();
                break;
            case "capturedRequests":
                assertCapturedRequests(where, params.get("method").asText(), expect);
                return;
            case "refresh":
                http.setContextData(MAPPER.writeValueAsString(params.get("newData")));
                context.refresh();
                break;
            default:
                fail(where + ": unsupported action");
        }

        // valueToTree(null) is null, not a NullNode.
        final JsonNode actual = result != null ? MAPPER.valueToTree(result) : NullNode.getInstance();
        assertEquals(where, expect.get("result"), actual);
        for (final JsonNode event : expect.get("events")) {
            if ("exposure".equals(event.get("type").asText())) {
                // An exposure without data (scenario 321) only counts.
                expectedExposures.add(event.has("data") ? event.get("data") : MAPPER.createObjectNode());
            }
        }
        assertLoggedEvents(where, expect);
        if ("publish".equals(action)) {
            exposuresExpectedAtLastPublish = expectedExposures.size();
        }
        // Checked per step, so an exposure queued a step early (peekVariableValue exposing like
        // getVariableValue) fails here instead of being hidden by later deduplication.
        assertEquals(where + ": pending exposures", expectedExposures.size() - exposuresExpectedAtLastPublish,
                context.getPendingCount());
    }

    // The event types the context logged during this step, in order. Payloads are compared through
    // the published exposures and the ready experiments. Core logs ready and refresh on the HTTP
    // thread after completing the future the step waited on, so those can arrive a little later.
    private void assertLoggedEvents(String where, JsonNode expect) throws InterruptedException {
        final List<String> expected = new ArrayList<>();
        for (final JsonNode event : expect.get("events")) {
            expected.add(event.get("type").asText());
        }
        final long deadline = System.currentTimeMillis() + 5000;
        while (loggedEvents.size() < expected.size() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(where + ": events", expected, new ArrayList<>(loggedEvents));
    }

    // The SDK rejects the client configuration, so no context is created. The cross-SDK wrappers
    // report this as a 400 carrying the SDK's message; ABSmartlyAndroid throws IllegalArgumentException
    // with its own message ("application is required").
    private void assertCreationRejected(String where, JsonNode params, JsonNode expect) {
        assertEquals(where, 400, expect.get("status").asInt());
        try {
            createSdk(params);
            fail(where + ": expected the client configuration to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(where, expected.getMessage().endsWith("is required"));
        }
    }

    private void assertCapturedRequests(String where, String method, JsonNode expect) {
        final List<RecordingHTTPClient.Request> requests = http.requests(method);
        assertTrue(where + ": requests", requests.size() >= expect.get("minRequests").asInt());
        final Iterator<Map.Entry<String, JsonNode>> patterns = expect.get("headerPatterns").fields();
        while (patterns.hasNext()) {
            final Map.Entry<String, JsonNode> pattern = patterns.next();
            final Pattern regex = Pattern.compile(pattern.getValue().asText().replace("{lang}", "android"));
            for (final RecordingHTTPClient.Request request : requests) {
                final String value = request.headers.get(pattern.getKey());
                assertTrue(where + ": " + pattern.getKey() + "=" + value, value != null && regex.matcher(value).matches());
            }
        }
    }

    private ContextConfig contextConfig(JsonNode params) {
        final ContextConfig config = ContextConfig.create().setEventLogger(new ContextEventLogger() {
            @Override
            public void handleEvent(com.absmartly.sdk.Context context, EventType event, Object data) {
                loggedEvents.add(event.name().toLowerCase(Locale.ROOT));
            }
        });
        final Iterator<Map.Entry<String, JsonNode>> units = params.get("units").fields();
        while (units.hasNext()) {
            final Map.Entry<String, JsonNode> unit = units.next();
            config.setUnit(unit.getKey(), unit.getValue().asText());
        }
        final JsonNode options = params.get("options");
        if (options != null && options.has("publishDelay")) {
            config.setPublishDelay(options.get("publishDelay").asLong());
        }
        if (options != null && options.has("refreshPeriod")) {
            config.setRefreshInterval(options.get("refreshPeriod").asLong());
        }
        return config;
    }

    private static ContextData contextData(JsonNode json) throws IOException {
        final byte[] bytes = MAPPER.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
        return new DefaultContextDataDeserializer().deserialize(bytes, 0, bytes.length);
    }

    private void assertCreateResult(String where, JsonNode expected) {
        assertEquals(where, expected.get("ready").asBoolean(), context.isReady());
        assertEquals(where, expected.get("failed").asBoolean(), context.isFailed());
        assertEquals(where, expected.get("finalized").asBoolean(), context.isClosed());
    }

    private void assertReadyExperiments(String where, JsonNode expected) {
        final List<String> names = new ArrayList<>();
        for (final JsonNode experiment : expected) {
            names.add(experiment.get("name").asText());
        }
        final List<String> actual = new ArrayList<>();
        for (final String experiment : context.getExperiments()) {
            actual.add(experiment);
        }
        assertEquals(where, names, actual);
    }

    private void assertPublishedExposures() throws IOException {
        if (context == null) {
            assertTrue(name + ": exposures without a context", expectedExposures.isEmpty());
            return;
        }
        if (context.getPendingCount() > 0) {
            context.publish();
        }
        final List<JsonNode> published = new ArrayList<>();
        for (final RecordingHTTPClient.Request request : http.requests("PUT")) {
            final JsonNode exposures = MAPPER.readTree(request.bodyString()).get("exposures");
            if (exposures != null) {
                for (final JsonNode exposure : exposures) {
                    published.add(exposure);
                }
            }
        }

        assertEquals(name + ": published exposure count", expectedExposures.size(), published.size());
        for (int i = 0; i < expectedExposures.size(); ++i) {
            final JsonNode expected = expectedExposures.get(i);
            final JsonNode actual = published.get(i);
            final Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> field = fields.next();
                assertEquals(name + ": exposure " + i + " " + field.getKey(), field.getValue(),
                        actual.get(field.getKey()));
            }
            // Scenarios recorded before ruleOverride existed leave it out; it must then be false.
            if (expected.size() > 0 && !expected.has("ruleOverride")) {
                assertEquals(name + ": exposure " + i + " ruleOverride", false,
                        actual.path("ruleOverride").asBoolean(false));
            }
        }
    }
}
