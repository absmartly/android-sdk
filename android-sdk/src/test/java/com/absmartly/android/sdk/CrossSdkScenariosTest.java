package com.absmartly.android.sdk;

import android.content.Context;

import com.absmartly.sdk.ContextConfig;
import com.absmartly.sdk.DefaultContextDataDeserializer;
import com.absmartly.sdk.json.ContextData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.After;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Replays the cross-SDK assignment-rule, semver and audience re-evaluation scenarios (230-247 of
 * absmartly/cross-sdk-tests, recorded from the JavaScript SDK) against ABSmartlyAndroid.
 *
 * The scenarios expect an exposure event from each step that queues one. Here the exposures are
 * collected in order and compared with what the context actually publishes at the end, which also
 * checks that the exposure flags, including ruleOverride, survive serialization.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class CrossSdkScenariosTest {
    private static final String RESOURCE = "cross-sdk/assignment_rules_semver_scenarios.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() throws IOException {
        final List<Object[]> result = new ArrayList<>();
        for (final JsonNode scenario : loadResource().get("scenarios")) {
            result.add(new Object[]{scenario.get("name").asText(), scenario});
        }
        return result;
    }

    private static JsonNode loadResource() throws IOException {
        try (InputStream in = CrossSdkScenariosTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing test resource " + RESOURCE);
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

    public CrossSdkScenariosTest(String name, JsonNode scenario) {
        this.name = name;
        this.scenario = scenario;
    }

    @Before
    public void setUp() {
        final Context androidContext = RuntimeEnvironment.getApplication();
        http = new RecordingHTTPClient();
        // "development" matches none of the environments the scenarios scope rules to.
        sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "website", "development", null,
                androidContext, http);
    }

    @After
    public void tearDown() throws IOException {
        if (context != null && !context.isClosed()) {
            context.close();
        }
        sdk.close();
    }

    @Test
    public void scenario() throws IOException {
        int index = 0;
        for (final JsonNode step : scenario.get("steps")) {
            runStep(index++, step);
        }
        assertPublishedExposures();
    }

    private void runStep(int index, JsonNode step) throws IOException {
        final String action = step.get("action").asText();
        final JsonNode params = step.get("params");
        final JsonNode expect = step.get("expect");
        final String where = name + " step " + index + " (" + action + ")";

        Object result = null;
        switch (action) {
            case "createContextWith":
                context = sdk.createContextWith(contextConfig(params), contextData(scenario.get("contextData")));
                assertTrue(where, context.isReady());
                assertCreateResult(where, expect.get("result"));
                for (final JsonNode event : expect.get("events")) {
                    if ("ready".equals(event.get("type").asText())) {
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
            case "pending":
                result = context.getPendingCount();
                break;
            case "override":
                context.setOverride(params.get("experimentName").asText(), params.get("variant").asInt());
                break;
            case "customAssignment":
                context.setCustomAssignment(params.get("experimentName").asText(), params.get("variant").asInt());
                break;
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
                expectedExposures.add(event.get("data"));
            }
        }
    }

    private static ContextConfig contextConfig(JsonNode params) {
        final ContextConfig config = ContextConfig.create();
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
            if (!expected.has("ruleOverride")) {
                assertEquals(name + ": exposure " + i + " ruleOverride", false,
                        actual.path("ruleOverride").asBoolean(false));
            }
        }
    }
}
