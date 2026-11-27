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

public class WriterWorkerTest {

    private static final List<String> SECTIONS = Arrays.asList("s1", "s2");

    private final FakeWorkerContext.SourceLane lane = new FakeWorkerContext.SourceLane()
            .text("src-a", "The grant covers 30 percent of the cost.")
            .text("src-b", "Applications go through the KfW portal.")
            .text("src-c", "Ten tips for a cosy living room.");

    /** web → verify → write: the writer follows the chain to the web package's sources. */
    private FakeWorkerContext context() {
        ResearchTask web = FakeWorkerContext.completed("run.web", WebResearchWorker.ROLE.getId(), SECTIONS,
                FakeWorkerContext.source("src-c", "https://c.test/1", "Living room"),
                FakeWorkerContext.source("src-a", "https://a.test/1", "Grant A"),
                FakeWorkerContext.source("src-b", "https://b.test/1", "Portal"));
        ResearchTask verify = FakeWorkerContext.completed("run.verify", SourceVerifierWorker.ROLE.getId(), SECTIONS,
                FakeWorkerContext.artifact(TaskArtifact.Kind.VERDICT, "off-topic: interior", "src-c", 5),
                FakeWorkerContext.artifact(TaskArtifact.Kind.VERDICT, "supports: figure", "src-a", 6),
                FakeWorkerContext.artifact(TaskArtifact.Kind.FINDING, "The grant covers 30 percent.", "src-a", 7));
        ResearchTask write = FakeWorkerContext.task("run.write", WriterWorker.ROLE.getId(), "heat pump grants",
                SECTIONS, "run.verify");
        FakeWorkerContext context = new FakeWorkerContext(write, WriterWorker.ROLE);
        context.run.add(web);
        context.run.add(withDependency(verify));
        return context;
    }

    private static ResearchTask withDependency(ResearchTask verify) {
        // The verifier depends on the web package (rebuilt with the dependency, same artifacts).
        ResearchTask task = ResearchTask.submit(verify.getTaskId(), "research", verify.getRole(),
                Collections.singletonList("run.web"), verify.getInputs(), "", 0,
                com.aresstack.askai.research.domain.task.TaskBudget.unlimited(), 1, 1L).start("local", 2L);
        for (TaskArtifact artifact : verify.getArtifacts()) {
            task = task.withArtifact(artifact);
        }
        return task.complete(3L);
    }

    private static final String GOOD = "{\"sections\":["
            + "{\"section\":\"s1\",\"paragraphs\":[{\"text\":\"The grant covers 30 percent of the cost.\","
            + "\"sources\":[\"src-a\"]}]},"
            + "{\"section\":\"s2\",\"paragraphs\":[{\"text\":\"Applications go through the KfW portal.\","
            + "\"sources\":[\"src-b\"]}]}],"
            + "\"gaps\":[{\"section\":\"s2\",\"description\":\"no processing times\"}]}";

    @Test
    public void theRoleRunsOnTheSynthesisSlotWithReadOnlySourceContent() {
        assertEquals(ResearchModelSlots.SYNTHESIS, WriterWorker.ROLE.getSlot());
        assertEquals(Collections.singletonList("source_review_context"), WriterWorker.ROLE.getTools().getToolNames());
    }

    @Test
    public void theDraftCitesItsSourcesAndLeavesRejectedOnesOut() {
        FakeWorkerContext context = context();
        context.chats.answer(GOOD);

        WorkerResult result = new WriterWorker(lane, 8, 1, 0).execute(context.task, context);

        assertEquals(result.toString(), WorkerResult.Kind.COMPLETED, result.getKind());
        assertEquals("verified first, the rejected source never read", "src-a,src-b",
                lane.calls.get(0).get("source_ids"));
        String prompt = context.chats.requests.get(0).get(1).getContent();
        assertTrue("the verifier's claim travels with its source",
                prompt.contains("verified claim: The grant covers 30 percent."));
        assertFalse(prompt.contains("cosy living room"));
        List<TaskArtifact> drafts = context.artifacts(TaskArtifact.Kind.TEXT);
        assertEquals(2, drafts.size());
        assertEquals("draft:s1", drafts.get(0).getProjectRef());
        assertEquals("The grant covers 30 percent of the cost. [src-a]", drafts.get(0).getContent());
        boolean gap = false;
        for (TaskArtifact verdict : context.artifacts(TaskArtifact.Kind.VERDICT)) {
            gap |= verdict.getProjectRef().equals("gap:s2") && verdict.getContent().contains("no processing times");
        }
        assertTrue(gap);
        assertTrue(result.getDetail(), result.getDetail().contains("2 paragraph(s) citing 2 source(s); 1 gap(s)"));
    }

    @Test
    public void anUncitedParagraphIsSentBackThenAccepted() {
        FakeWorkerContext context = context();
        context.chats.answer("{\"sections\":[{\"section\":\"s1\",\"paragraphs\":[{\"text\":\"Heat pumps are "
                + "great.\",\"sources\":[]}]}],\"gaps\":[]}").answer(GOOD);

        WorkerResult result = new WriterWorker(lane, 8, 1, 0).execute(context.task, context);

        assertEquals(WorkerResult.Kind.COMPLETED, result.getKind());
        String repair = context.chats.requests.get(1).get(context.chats.requests.get(1).size() - 1).getContent();
        assertTrue(repair, repair.contains("paragraph 1 of s1 cites no source"));
    }

    @Test
    public void theReaderRefusesUnknownCitationsAndSections() {
        LinkedHashSet<String> ids = new LinkedHashSet<String>(Arrays.asList("a", "b"));
        try {
            WriterWorker.read("{\"sections\":[{\"section\":\"s1\",\"paragraphs\":[{\"text\":\"x\","
                    + "\"sources\":[\"zz\"]}]}],\"gaps\":[]}", SECTIONS, ids);
            fail("unknown citation");
        } catch (WorkerAnswerException expected) {
            assertTrue(expected.getMessage().contains("cites unknown source \"zz\""));
        }
        try {
            WriterWorker.read("{\"sections\":[{\"section\":\"s9\",\"paragraphs\":[]}],\"gaps\":[]}", SECTIONS, ids);
            fail("unknown section");
        } catch (WorkerAnswerException expected) {
            assertTrue(expected.getMessage().contains("unknown section"));
        }
    }

    @Test
    public void everyRequestedSectionIsDraftedOrNamedAsAGap() throws Exception {
        LinkedHashSet<String> ids = new LinkedHashSet<String>(Arrays.asList("a"));
        String s1 = "{\"section\":\"s1\",\"paragraphs\":[{\"text\":\"x\",\"sources\":[\"a\"]}]}";
        try {
            WriterWorker.read("{\"sections\":[" + s1 + "],\"gaps\":[]}", SECTIONS, ids);
            fail("s2 dropped silently");
        } catch (WorkerAnswerException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("section s2 is missing"));
        }
        try {
            WriterWorker.read("{\"sections\":[" + s1 + ",{\"section\":\"s2\",\"paragraphs\":[]}],\"gaps\":[]}",
                    SECTIONS, ids);
            fail("s2 empty without a gap");
        } catch (WorkerAnswerException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("section s2 has no paragraphs"));
        }
        WriterWorker.read("{\"sections\":[" + s1 + "],\"gaps\":[{\"section\":\"s2\",\"description\":\"none\"}]}",
                SECTIONS, ids);
    }

    @Test
    public void withoutUsableSourcesTheWriterFailsInsteadOfInventing() {
        ResearchTask write = FakeWorkerContext.task("run.write", WriterWorker.ROLE.getId(), "x", SECTIONS);
        FakeWorkerContext context = new FakeWorkerContext(write, WriterWorker.ROLE);

        WorkerResult result = new WriterWorker(lane, 8, 1, 0).execute(write, context);

        assertEquals(WorkerResult.Kind.FAILED, result.getKind());
        assertTrue(context.chats.requests.isEmpty());
    }

    @Test
    public void aPackageWithoutSectionsWritesTheQuestionSection() throws Exception {
        assertTrue(WriterWorker.schema(Collections.singletonList(ResearchOutline.QUESTION_SECTION),
                Collections.singletonList("a")).contains("\"enum\":[\"question\"]"));
    }
}
