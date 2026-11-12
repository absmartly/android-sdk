package com.absmartly.android.sdk;

import com.absmartly.sdk.jsonexpr.JsonExpr;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The semver_* JSON-expression operators as shipped to Android consumers through core-api. Cases
 * mirror the JavaScript and Java SDK semver comparator tests so all three order versions alike.
 */
public class SemverOperatorsTest {
    private final JsonExpr jsonExpr = new JsonExpr();

    private static Map<String, Object> op(String operator, Object lhs, Object rhs) {
        final Map<String, Object> l = Collections.<String, Object>singletonMap("value", lhs);
        final Map<String, Object> r = Collections.<String, Object>singletonMap("value", rhs);
        final List<Object> args = Arrays.<Object>asList(l, r);
        return Collections.<String, Object>singletonMap(operator, args);
    }

    private Object eval(String operator, Object lhs, Object rhs) {
        return jsonExpr.evaluateExpr(op(operator, lhs, rhs), new HashMap<String, Object>());
    }

    private void assertOrder(String lower, String higher) {
        final String pair = lower + " < " + higher;
        assertEquals(pair, true, eval("semver_lt", lower, higher));
        assertEquals(pair, true, eval("semver_lte", lower, higher));
        assertEquals(pair, false, eval("semver_eq", lower, higher));
        assertEquals(pair, false, eval("semver_gte", lower, higher));
        assertEquals(pair, false, eval("semver_gt", lower, higher));
        assertEquals(pair, true, eval("semver_gt", higher, lower));
        assertEquals(pair, true, eval("semver_gte", higher, lower));
    }

    private void assertSame(String a, String b) {
        final String pair = a + " == " + b;
        assertEquals(pair, true, eval("semver_eq", a, b));
        assertEquals(pair, true, eval("semver_lte", a, b));
        assertEquals(pair, true, eval("semver_gte", a, b));
        assertEquals(pair, false, eval("semver_lt", a, b));
        assertEquals(pair, false, eval("semver_gt", a, b));
    }

    @Test
    public void comparesCoreVersionsNumerically() {
        assertOrder("1.0.0", "2.0.0");
        assertOrder("1.1.0", "1.2.0");
        assertOrder("1.0.1", "1.0.2");
        assertOrder("1.9.0", "1.10.0");
        assertOrder("9.0.0", "10.0.0");
    }

    @Test
    public void missingPartsAndLeadingZerosAndPrefix() {
        assertSame("1.2", "1.2.0");
        assertSame("1", "1.0.0");
        assertSame("1.02.0", "1.2.0");
        assertSame("v1.0.0", "1.0.0");
        assertSame("V1.0.0", "1.0.0");
    }

    @Test
    public void preReleasesRankBelowTheRelease() {
        assertOrder("1.0.0-alpha", "1.0.0");
        assertOrder("1.0.0-alpha", "1.0.0-beta");
        assertOrder("1.0.0-alpha.1", "1.0.0-alpha.2");
        assertOrder("1.0.0-1", "1.0.0-2");
        assertOrder("1.0.0-1", "1.0.0-alpha");
        assertOrder("1.0.0-alpha", "1.0.0-alpha.1");
        assertOrder("2.4.1-beta.1", "2.4.1-beta.2");
    }

    @Test
    public void buildMetadataIsIgnored() {
        assertSame("1.0.0+build1", "1.0.0+build2");
        assertSame("1.0.0+build1", "1.0.0");
        assertSame("v1.2", "1.2.0+build");
        assertOrder("1.0.0-alpha+build1", "1.0.0");
    }

    @Test
    public void numbersAndBooleansAreCompared() {
        assertEquals(true, eval("semver_eq", 1, "1.0.0"));
        assertEquals(true, eval("semver_eq", 2.5, "2.5.0"));
        assertEquals(true, eval("semver_eq", true, "true"));
    }

    @Test
    public void nonNumericPartsAreComparedAsTextAboveNumbers() {
        // Documented in the README: permissive like the JavaScript and Java SDKs, not rejected.
        assertEquals(true, eval("semver_gte", "unknown", "2.4.0"));
        assertEquals(true, eval("semver_gte", "1.x", "1.2.0"));
        assertOrder("1.9.0", "1.x");
    }

    @Test
    public void invalidOperandsAreNull() {
        assertEquals(null, eval("semver_eq", null, "1.0.0"));
        assertEquals(null, eval("semver_gt", "1.0.0", null));
        assertEquals(null, eval("semver_eq", "", "1.0.0"));
        assertEquals(null, eval("semver_lt", "v", "1.0.0"));
        assertEquals(null, eval("semver_lte", "+build", "1.0.0"));
        assertEquals(null, eval("semver_gte", new HashMap<String, Object>(), "1.0.0"));
    }

    @Test
    public void variableOperandsInBooleanExpressions() {
        final Map<String, Object> vars = new HashMap<>();
        vars.put("app_version", "2.4.1-beta.2");
        final Map<String, Object> appVersion = Collections.<String, Object>singletonMap("var", "app_version");

        assertTrue(jsonExpr.evaluateBooleanExpr(Collections.<String, Object>singletonMap("semver_gte",
                Arrays.<Object>asList(appVersion, Collections.singletonMap("value", "2.4.0"))), vars));
        assertTrue(jsonExpr.evaluateBooleanExpr(Collections.<String, Object>singletonMap("semver_lt",
                Arrays.<Object>asList(appVersion, Collections.singletonMap("value", "2.4.1"))), vars));
        // A missing variable never matches.
        assertFalse(jsonExpr.evaluateBooleanExpr(Collections.<String, Object>singletonMap("semver_eq",
                Arrays.<Object>asList(Collections.singletonMap("var", "missing"),
                        Collections.singletonMap("value", "2.4.1"))), vars));
    }
}
