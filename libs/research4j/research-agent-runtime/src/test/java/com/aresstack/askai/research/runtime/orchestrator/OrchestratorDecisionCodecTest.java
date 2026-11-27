package com.aresstack.askai.research.runtime.orchestrator;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OrchestratorDecisionCodecTest {

    private static final OrchestratorDecisionCodec.Constraints PLAN = new OrchestratorDecisionCodec.Constraints(
            Collections.singletonList("web-researcher"), Arrays.asList("a", "b"), null, null, 3);

    private static String task(String id, String section, String dependsOn) {
        return "{\"id\":\"" + id + "\",\"role\":\"web-researcher\",\"query\":\"q " + id + "\",\"brief\":\"\","
                + "\"sections\":[\"" + section + "\"],\"dependsOn\":[" + dependsOn + "]}";
    }

    private static void rejects(String json, String reason) {
        try {
            OrchestratorDecisionCodec.readPlan(json, PLAN);
            fail("accepted: " + json);
        } catch (OrchestratorDecisionCodec.InvalidDecisionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(reason));
        }
    }

    @Test
    public void theSchemaCarriesTheRunsFacts() {
        String schema = OrchestratorDecisionCodec.planSchema(PLAN);
        assertTrue(schema.contains("\"enum\":[\"web-researcher\"]"));
        assertTrue(schema.contains("\"enum\":[\"a\",\"b\"]"));
        assertTrue(schema.contains("\"maxItems\":3"));
        String round = OrchestratorDecisionCodec.roundSchema(new OrchestratorDecisionCodec.Constraints(
                Collections.singletonList("web-researcher"), Arrays.asList("a"), Arrays.asList("p1"),
                Arrays.asList("p1"), 2));
        assertTrue(round.contains("\"enum\":[\"continue\",\"close\",\"ask-user\"]"));
        assertTrue(round.contains("\"enum\":[\"p1\"]"));
    }

    @Test
    public void aValidPlanIsRead() throws Exception {
        RunPlan plan = OrchestratorDecisionCodec.readPlan("```json\n{\"rationale\":\"r\",\"tasks\":["
                + task("p1", "a", "") + "," + task("P2", "b", "\"p1\"") + "]}\n```", PLAN);
        assertEquals(2, plan.getTasks().size());
        assertEquals("ids are normalized to lower case", "p2", plan.getTasks().get(1).getId());
        assertEquals(Collections.singletonList("p1"), plan.getTasks().get(1).getDependsOn());
    }

    @Test
    public void whatTheSchemaCannotSayIsCheckedHere() {
        rejects("{\"rationale\":\"r\",\"tasks\":[]}", "no work package");
        rejects("{\"rationale\":\"r\",\"tasks\":[" + task("p1", "a", "") + "," + task("p1", "b", "") + "]}",
                "used twice");
        rejects("{\"rationale\":\"r\",\"tasks\":[" + task("p1", "a", "\"p9\"") + "]}", "unknown package");
        rejects("{\"rationale\":\"r\",\"tasks\":[" + task("p1", "a", "\"p2\"") + "," + task("p2", "b", "\"p1\"")
                + "]}", "cycle");
        rejects("{\"rationale\":\"r\",\"tasks\":[" + task("p1", "zz", "") + "]}", "unknown section");
        rejects("{\"rationale\":\"r\",\"tasks\":[" + task("bad id!", "a", "") + "]}", "lower-case");
        rejects("{\"rationale\":\"r\",\"tasks\":[" + task("p1", "a", "") + "," + task("p2", "a", "") + ","
                + task("p3", "a", "") + "," + task("p4", "a", "") + "]}", "at most 3");
        rejects("[1,2]", "not a JSON object");
    }

    @Test
    public void aRoundDecisionIsReadAndAskUserNeedsAQuestion() throws Exception {
        OrchestratorDecisionCodec.Constraints c = new OrchestratorDecisionCodec.Constraints(
                Collections.singletonList("web-researcher"), Arrays.asList("a", "b"), Arrays.asList("p1"),
                Arrays.asList("p1"), 2);
        RoundDecision decision = OrchestratorDecisionCodec.readRound("{\"evaluations\":["
                + "{\"taskId\":\"p1\",\"verdict\":\"partial\",\"note\":\"n\"},"
                + "{\"taskId\":\"ghost\",\"verdict\":\"useful\",\"note\":\"n\"}],"
                + "\"gaps\":[{\"section\":\"b\",\"description\":\"nothing yet\"}],\"contradictions\":[],"
                + "\"quality\":{\"sufficient\":false,\"note\":\"thin\"},\"action\":\"continue\",\"question\":\"\","
                + "\"followUps\":[" + task("f1", "b", "\"p1\"") + "]}", c);
        assertEquals(RoundDecision.Action.CONTINUE, decision.getAction());
        assertEquals("evaluations of unknown packages are dropped", 1, decision.getEvaluations().size());
        assertEquals("b", decision.getGaps().get(0).getSection());
        assertEquals("f1", decision.getFollowUps().get(0).getId());
        try {
            OrchestratorDecisionCodec.readRound("{\"action\":\"ask-user\",\"question\":\"\"}", c);
            fail();
        } catch (OrchestratorDecisionCodec.InvalidDecisionException expected) {
            assertTrue(expected.getMessage().contains("question"));
        }
        try {
            OrchestratorDecisionCodec.readRound("{\"action\":\"continue\",\"question\":\"\",\"followUps\":["
                    + task("p1", "a", "") + "]}", c);
            fail("a follow-up must not reuse an existing package id");
        } catch (OrchestratorDecisionCodec.InvalidDecisionException expected) {
            assertTrue(expected.getMessage().contains("used twice"));
        }
    }
}
