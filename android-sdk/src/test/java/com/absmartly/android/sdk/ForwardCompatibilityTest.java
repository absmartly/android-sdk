package com.absmartly.android.sdk;

import com.absmartly.sdk.ContextConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Context payloads from a newer collector, fetched and parsed through the real Client path. Unknown
 * fields at any level, and assignment rules the SDK does not understand, must not fail the context
 * or affect other experiments.
 */
@RunWith(RobolectricTestRunner.class)
public class ForwardCompatibilityTest {
    private static final String UNKNOWN = "\"futureScalar\":1,\"futureObject\":{\"a\":[1,{\"b\":null}]},"
            + "\"futureArray\":[{\"x\":\"y\"},2]";

    private RecordingHTTPClient http;
    private ABSmartlyAndroid sdk;

    @Before
    public void setUp() {
        http = new RecordingHTTPClient();
        sdk = new ABSmartlyAndroid("https://test.absmartly.io/v1", "key", "android-app", "production", null,
                RuntimeEnvironment.getApplication(), () -> http);
    }

    @After
    public void tearDown() throws IOException {
        sdk.close();
    }

    // A two-variant experiment whose normal split sends every unit to variant 1.
    private static String experiment(int id, String name, String extra) {
        return "{" + UNKNOWN + ",\"id\":" + id + ",\"name\":\"" + name + "\",\"iteration\":1,"
                + "\"unitType\":\"session_id\",\"seedHi\":0,\"seedLo\":0,\"split\":[0,1],"
                + "\"trafficSeedHi\":0,\"trafficSeedLo\":0,\"trafficSplit\":[0,1],\"fullOnVariant\":0,"
                + "\"applications\":[{" + UNKNOWN + ",\"name\":\"android-app\"}],"
                + "\"variants\":[{" + UNKNOWN + ",\"name\":\"A\",\"config\":null},"
                + "{" + UNKNOWN + ",\"name\":\"B\",\"config\":\"{\\\"color\\\":\\\"blue\\\"}\"}],"
                + "\"customFieldValues\":[{" + UNKNOWN + ",\"name\":\"owner\",\"type\":\"string\",\"value\":\"x\"}],"
                + "\"audience\":null,\"audienceStrict\":false,\"holdoutIds\":[],"
                + "\"futureSwitchback\":{\"periodMinutes\":60,\"clusters\":[]}" + extra + "}";
    }

    private com.absmartly.sdk.Context fetch(String experiments) {
        http.setContextData("{" + UNKNOWN + ",\"experiments\":[" + experiments + "],"
                + "\"holdouts\":[],\"futureHoldoutArms\":[{\"id\":1}]}");
        final com.absmartly.sdk.Context context = sdk.createContext(ContextConfig.create()
                .setUnit("session_id", "e791e240fcd3df7d238cfc285f475e8152fcc0ec")
                .setPublishDelay(-1));
        context.waitUntilReady();
        return context;
    }

    @Test
    public void unknownFieldsAtEveryLevelAreIgnored() {
        final com.absmartly.sdk.Context context = fetch(experiment(1, "exp_a", "") + ","
                + experiment(2, "exp_b", ""));
        assertTrue(context.isReady());
        assertFalse(context.isFailed());
        assertEquals(1, context.getTreatment("exp_a"));
        assertEquals(1, context.getTreatment("exp_b"));
        assertEquals("blue", context.getVariableValue("color", "default"));
        assertEquals("x", context.getCustomFieldValue("exp_a", "owner"));
        context.close();
    }

    @Test
    public void unusableAssignmentRulesOnlyAffectTheirExperiment() {
        final String[] rules = {
                "",
                ",\"assignmentRules\":null",
                ",\"assignmentRules\":\"\"",
                ",\"assignmentRules\":\"{}\"",
                ",\"assignmentRules\":\"not json{\"",
                ",\"assignmentRules\":{\"rules\":[]}",
                ",\"assignmentRules\":42",
                ",\"assignmentRules\":true",
        };
        for (final String rule : rules) {
            final com.absmartly.sdk.Context context = fetch(experiment(1, "exp_rules", rule) + ","
                    + experiment(2, "exp_other", ""));
            assertTrue(rule, context.isReady());
            assertEquals(rule, 1, context.getTreatment("exp_rules"));
            assertEquals(rule, 1, context.getTreatment("exp_other"));
            context.close();
        }
    }

    @Test
    public void unknownRuleTypesAndOperatorsAreSkipped() {
        // An unknown rule type and a rule whose condition uses an unknown operator are skipped; the
        // next rule (variant 0) applies.
        final String rules = ",\"assignmentRules\":\"{\\\"futureKey\\\":1,\\\"rules\\\":["
                + "{\\\"name\\\":\\\"t\\\",\\\"type\\\":\\\"future_type\\\",\\\"variant\\\":1},"
                + "{\\\"name\\\":\\\"o\\\",\\\"type\\\":\\\"assign\\\",\\\"variant\\\":1,"
                + "\\\"conditions\\\":{\\\"future_op\\\":[{\\\"var\\\":\\\"x\\\"}]}},"
                + "{\\\"name\\\":\\\"a\\\",\\\"type\\\":\\\"assign\\\",\\\"variant\\\":0,\\\"futureRuleField\\\":[1]}"
                + "]}\"";
        final com.absmartly.sdk.Context context = fetch(experiment(1, "exp_rules", rules) + ","
                + experiment(2, "exp_other", ""));
        assertTrue(context.isReady());
        assertEquals(0, context.getTreatment("exp_rules"));
        assertEquals(1, context.getTreatment("exp_other"));
        context.close();
    }
}
