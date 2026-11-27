package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SourceVerifierWorkerTest {

    private static final List<String> SECTIONS = Arrays.asList("s1", "s2");

    private final FakeWorkerContext.SourceLane lane = new FakeWorkerContext.SourceLane()
            .text("src-a", "The grant covers 30 percent of the cost.")
            .text("src-b", "The grant covers 50 percent of the cost.")
            .text("src-c", "Ten tips for a cosy living room.")
            .text("src-d", "");

    private FakeWorkerContext context() {
        ResearchTask web = FakeWorkerContext.completed("run.web", WebResearchWorker.ROLE.getId(), SECTIONS,
                FakeWorkerContext.source("src-a", "https://a.test/1", "Grant A"),
                FakeWorkerContext.source("src-b", "https://b.test/1", "Grant B"),
                FakeWorkerContext.source("src-c", "https://c.test/1", "Living room"),
                FakeWorkerContext.source("src-d", "https://d.test/1", "Empty"));
        ResearchTask verify = FakeWorkerContext.task("run.verify", SourceVerifierWorker.ROLE.getId(),
                "heat pump grant share", SECTIONS, "run.web");
        FakeWorkerContext context = new FakeWorkerContext(verify, SourceVerifierWorker.ROLE);
        context.run.add(web);
        return context;
    }

    private static final String GOOD = "{\"verdicts\":["
            + "{\"source\":\"src-a\",\"verdict\":\"supports\",\"section\":\"s1\",\"claim\":\"The grant covers 30 "
            + "percent.\",\"reason\":\"concrete figure\"},"
            + "{\"source\":\"src-b\",\"verdict\":\"supports\",\"section\":\"s1\",\"claim\":\"The grant covers 50 "
            + "percent.\",\"reason\":\"concrete figure\"},"
            + "{\"source\":\"src-c\",\"verdict\":\"off-topic\",\"section\":\"\",\"claim\":\"\",\"reason\":\"interior "
            + "design\"}],"
            + "\"contradictions\":[{\"sources\":[\"src-a\",\"src-b\"],\"section\":\"s1\",\"description\":\"30 vs 50 "
            + "percent\"}]}";

    @Test
    public void theRoleReadsSourceContentOnlyAndRunsOnItsOwnSlot() {
        assertEquals(ResearchModelSlots.VERIFIER, SourceVerifierWorker.ROLE.getSlot());
        assertEquals(Collections.singletonList("source_review_context"),
                SourceVerifierWorker.ROLE.getTools().getToolNames());
    }

    @Test
    public void verdictsClaimsAndContradictionsLandOnTheBlackboard() {
        FakeWorkerContext context = context();
        context.chats.answer(GOOD);

        WorkerResult result = new SourceVerifierWorker(lane, 8, 1, 0).execute(context.task, context);

        assertEquals(WorkerResult.Kind.COMPLETED, result.getKind());
        assertEquals("exactly the upstream ids are read", "src-a,src-b,src-c,src-d",
                lane.calls.get(0).get("source_ids"));
        String prompt = context.chats.requests.get(0).get(1).getContent();
        assertTrue(prompt.contains("--- src-a") && prompt.contains("30 percent"));
        assertFalse("an unreadable source never reaches the model", prompt.contains("--- src-d"));
        assertEquals(WorkerArtifacts.Verdict.OFF_TOPIC,
                WorkerArtifacts.verdicts(Collections.singletonList(context.recorded)).get("src-c"));
        assertEquals(WorkerArtifacts.Verdict.UNREADABLE,
                WorkerArtifacts.verdicts(Collections.singletonList(context.recorded)).get("src-d"));
        assertEquals(new LinkedHashSet<String>(Collections.singletonList("src-c")),
                WorkerArtifacts.rejectedSources(Collections.singletonList(context.recorded)));
        assertEquals(2, context.artifacts(TaskArtifact.Kind.FINDING).size());
        boolean contradiction = false;
        for (TaskArtifact verdict : context.artifacts(TaskArtifact.Kind.VERDICT)) {
            if (verdict.getProjectRef().equals("contradiction:s1")) {
                contradiction = true;
                assertTrue(verdict.getContent().contains("src-a, src-b"));
            }
        }
        assertTrue(contradiction);
        assertTrue(result.getDetail(), result.getDetail().contains("2 supporting, 1 rejected, 1 unreadable, "
                + "1 contradiction"));
        assertTrue(context.receipts.get(0).isSucceeded());
    }

    /** The host's review-context cap holds sources back; they are read in further calls, not marked unreadable. */
    @Test
    public void sourcesTheHostHeldBackAreReadInFurtherCallsNotMarkedUnreadable() {
        FakeWorkerContext context = context();
        lane.maxSources(2);
        context.chats.answer(GOOD);

        WorkerResult result = new SourceVerifierWorker(lane, 8, 1, 0).execute(context.task, context);

        assertEquals(result.toString(), WorkerResult.Kind.COMPLETED, result.getKind());
        assertEquals(2, lane.calls.size());
        assertEquals("src-c,src-d", lane.calls.get(1).get("source_ids"));
        int unreadable = 0;
        for (TaskArtifact verdict : context.artifacts(TaskArtifact.Kind.VERDICT)) {
            if (verdict.getContent().startsWith("unreadable")) {
                unreadable++;
                assertEquals("only the source without text", "src-d", verdict.getProjectRef());
            }
        }
        assertEquals(1, unreadable);
        assertTrue(context.chats.requests.get(0).get(1).getContent().contains("cosy living room"));
    }

    @Test
    public void aMissingVerdictIsSentBackThenAccepted() {
        FakeWorkerContext context = context();
        context.chats.answer("{\"verdicts\":[{\"source\":\"src-a\",\"verdict\":\"supports\",\"section\":\"s1\","
                + "\"claim\":\"x\",\"reason\":\"y\"}],\"contradictions\":[]}").answer(GOOD);

        WorkerResult result = new SourceVerifierWorker(lane, 8, 1, 0).execute(context.task, context);

        assertEquals(WorkerResult.Kind.COMPLETED, result.getKind());
        String repair = context.chats.requests.get(1).get(context.chats.requests.get(1).size() - 1).getContent();
        assertTrue(repair, repair.contains("no verdict for src-b, src-c"));
    }

    @Test
    public void alreadyVerifiedSourcesAreNotJudgedAgainAndTheLimitHolds() {
        FakeWorkerContext context = context();
        context.run.add(FakeWorkerContext.completed("run.verify0", SourceVerifierWorker.ROLE.getId(), SECTIONS,
                FakeWorkerContext.artifact(TaskArtifact.Kind.VERDICT, "supports: fine", "src-a", 4)));
        context.chats.answer("{\"verdicts\":[{\"source\":\"src-b\",\"verdict\":\"partial\",\"section\":\"s2\","
                + "\"claim\":\"half\",\"reason\":\"vague\"}],\"contradictions\":[]}");

        WorkerResult result = new SourceVerifierWorker(lane, 1, 0, 0).execute(context.task, context);

        assertEquals(WorkerResult.Kind.COMPLETED, result.getKind());
        assertEquals("src-b", lane.calls.get(0).get("source_ids"));
    }

    @Test
    public void anUnreachableLaneIsRetryableAndNothingUpstreamIsNothingToVerify() {
        FakeWorkerContext context = context();
        lane.down = true;
        assertEquals(WorkerResult.Kind.RETRYABLE,
                new SourceVerifierWorker(lane, 8, 1, 0).execute(context.task, context).getKind());
        assertFalse(context.receipts.get(0).isSucceeded());

        ResearchTask lonely = FakeWorkerContext.task("run.v2", SourceVerifierWorker.ROLE.getId(), "x", SECTIONS);
        FakeWorkerContext empty = new FakeWorkerContext(lonely, SourceVerifierWorker.ROLE);
        WorkerResult nothing = new SourceVerifierWorker(lane, 8, 1, 0).execute(lonely, empty);
        assertEquals(WorkerResult.Kind.COMPLETED, nothing.getKind());
        assertEquals("nothing to verify", nothing.getDetail());
    }

    @Test
    public void theReaderRefusesUnknownIdsAndOneSidedContradictions() {
        LinkedHashSet<String> ids = new LinkedHashSet<String>(Arrays.asList("a", "b"));
        try {
            SourceVerifierWorker.read("{\"verdicts\":[{\"source\":\"z\",\"verdict\":\"supports\"}],"
                    + "\"contradictions\":[]}", ids, SECTIONS);
            fail("unknown id");
        } catch (WorkerAnswerException expected) {
            assertTrue(expected.getMessage().contains("unknown source id"));
        }
        try {
            SourceVerifierWorker.read("{\"verdicts\":[{\"source\":\"a\",\"verdict\":\"supports\"},"
                    + "{\"source\":\"b\",\"verdict\":\"partial\"}],\"contradictions\":[{\"sources\":[\"a\",\"a\"],"
                    + "\"section\":\"s1\",\"description\":\"d\"}]}", ids, SECTIONS);
            fail("one-sided");
        } catch (WorkerAnswerException expected) {
            assertTrue(expected.getMessage().contains("two different sources"));
        }
    }

    /** The envelope measures on verified evidence: a rejected source no longer covers its section. */
    @Test
    public void theEvidenceMeterNoLongerCountsARejectedSource() {
        OrchestratorRun run = OrchestratorRun.builder("run", "q", 1L).state(OrchestratorRun.State.RUNNING)
                .sections(ResearchOutline.ofQuestion("q").getSections()).envelope(4, 12, 0, 0).build();
        List<String> section = Collections.singletonList("question");
        ResearchTask web = FakeWorkerContext.completed("run.web", WebResearchWorker.ROLE.getId(), section,
                FakeWorkerContext.source("src-a", "https://a.test/1", "A"),
                FakeWorkerContext.source("src-c", "https://c.test/1", "C"));
        EvidenceMeter meter = new EvidenceMeter(1, 1, 1);
        assertEquals(2, meter.measure(run, Collections.singletonList(web)).getDistinctSources());

        ResearchTask verify = FakeWorkerContext.completed("run.verify", SourceVerifierWorker.ROLE.getId(), section,
                FakeWorkerContext.artifact(TaskArtifact.Kind.VERDICT, "off-topic: interior design", "src-c", 5),
                FakeWorkerContext.artifact(TaskArtifact.Kind.VERDICT, "supports: fine", "src-a", 6));
        EvidenceSnapshot after = meter.measure(run, Arrays.asList(web, verify));
        assertEquals(1, after.getDistinctSources());
        assertEquals(1, after.getDistinctHosts());
    }
}
