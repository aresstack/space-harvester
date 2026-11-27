package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SC1 transport pins: the internal service-lane replies parse into typed decisions;
 * UNAVAILABLE/UNKNOWN_HANDLE are TOOL FAILURES (the caller ends the run fail-closed);
 * INACTIVE is the honest no-op fast path.
 */
public class ServiceSearchScopeControlPortTest {

    private static final class ScriptedInvoker implements ToolInvoker {
        String reply;
        String lastTool;
        Map<String, Object> lastArgs;

        public String call(String tool, Map<String, Object> args)
                throws ToolFailure, EndpointUnavailable {
            lastTool = tool;
            lastArgs = args;
            return reply;
        }
    }

    @Test
    public void beginParsesActiveInactiveAndFailsClosedOnUnavailable() throws Exception {
        ScriptedInvoker invoker = new ScriptedInvoker();
        ServiceSearchScopeControlPort port = new ServiceSearchScopeControlPort(invoker);

        invoker.reply = "ACTIVE handle=ss-1 scopeRev=4 concept=e-1#2 embedding=fp "
                + "inAnchors=8 outAnchors=2";
        SearchScopeControlPort.Session active = port.begin();
        assertTrue(active.active);
        assertEquals("ss-1", active.handle);
        assertTrue(active.summary.startsWith("handle=ss-1 scopeRev=4"));
        assertTrue("an SC1-era host without flags reads as out-only", active.outFilter);
        assertFalse(active.inAffinity);

        // SC2a capability split: IN affinity works with an EMPTY blacklist.
        invoker.reply = "ACTIVE handle=ss-2 scopeRev=4 concept=e-1#2 embedding=fp "
                + "inAnchors=6 outAnchors=0 outFilter=false inAffinity=true";
        SearchScopeControlPort.Session inOnly = port.begin();
        assertTrue(inOnly.active);
        assertFalse("no OUT anchors -> the fail-closed boundary is OFF", inOnly.outFilter);
        assertTrue(inOnly.inAffinity);

        invoker.reply = "INACTIVE outAnchors=0";
        SearchScopeControlPort.Session inactive = port.begin();
        assertFalse(inactive.active);
        assertNull(inactive.handle);

        invoker.reply = "UNAVAILABLE no embedding model";
        try {
            port.begin();
            fail("UNAVAILABLE must be a tool failure — fail-closed at the caller");
        } catch (ToolInvoker.ToolFailure expected) {
            assertTrue(expected.getMessage().contains("no embedding model"));
        }
    }

    @Test
    public void evaluateParsesVerdictLinesAndTravelsOneJsonBatch() throws Exception {
        ScriptedInvoker invoker = new ScriptedInvoker();
        ServiceSearchScopeControlPort port = new ServiceSearchScopeControlPort(invoker);
        invoker.reply = "EVALUATED items=3\n"
                + "OUT nearest=\"GUI\" id=https://a.example/gui\n"
                + "KEEP in=NEAR nearest_in=\"Scheduling\" id=https://b.example/scheduling\n"
                + "UNCLASSIFIED id=https://c.example/empty";

        List<SearchScopeControlPort.Decision> decisions = port.evaluate("ss-1", "serp",
                Arrays.asList(
                        new SearchScopeControlPort.Item("https://a.example/gui", "GUI stuff"),
                        new SearchScopeControlPort.Item("https://b.example/scheduling",
                                "Scheduling"),
                        new SearchScopeControlPort.Item("https://c.example/empty", "")));

        assertEquals("search_scope_evaluate", invoker.lastTool);
        assertEquals("ss-1", invoker.lastArgs.get("handle"));
        assertEquals("serp", invoker.lastArgs.get("lane"));
        assertTrue(String.valueOf(invoker.lastArgs.get("items_json"))
                .contains("{\"id\":\"https://a.example/gui\",\"text\":\"GUI stuff\"}"));
        assertEquals(3, decisions.size());
        assertTrue(decisions.get(0).out);
        assertEquals("GUI", decisions.get(0).nearestOutLabel);
        assertFalse(decisions.get(1).out);
        assertFalse(decisions.get(1).unclassified);
        assertTrue("the shadow affinity travels on the KEEP line", decisions.get(1).nearIn);
        assertEquals("Scheduling", decisions.get(1).nearestInLabel);
        assertFalse(decisions.get(0).nearIn);
        assertTrue(decisions.get(2).unclassified);
    }

    @Test
    public void evaluateFailsClosedOnUnknownHandleAndEndNeverThrows() throws Exception {
        ScriptedInvoker invoker = new ScriptedInvoker();
        ServiceSearchScopeControlPort port = new ServiceSearchScopeControlPort(invoker);
        invoker.reply = "UNKNOWN_HANDLE";
        try {
            port.evaluate("ss-gone", "links", Arrays.asList(
                    new SearchScopeControlPort.Item("u", "t")));
            fail("an unknown handle must be a tool failure");
        } catch (ToolInvoker.ToolFailure expected) {
            assertTrue(expected.getMessage().contains("UNKNOWN_HANDLE"));
        }

        ServiceSearchScopeControlPort broken = new ServiceSearchScopeControlPort(
                new ToolInvoker() {
                    public String call(String tool, Map<String, Object> args)
                            throws ToolFailure {
                        throw new ToolFailure("gone");
                    }
                });
        broken.end("ss-1"); // best effort — never throws
    }
}
