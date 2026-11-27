package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Outline, evidence, run record, briefs and settings: the deterministic parts of the orchestrated run. */
public class OrchestratorModelTest {

    @Test
    public void theOutlineIsTheConceptsFirstLevel() {
        ResearchOutline outline = ResearchOutline.fromConceptRead("q", "revision=4\nparent=x\n"
                + "{\"FreeRTOS\":[\"Scheduling\",{\"Kommunikation & IPC\":[\"Queues\"]},\"Scheduling\",\"\"]}"
                + "\nSUPPRESSED IN THIS BRANCH: Linux");
        assertEquals(Arrays.asList("scheduling", "kommunikation-ipc", "scheduling-2"), outline.sectionIds());
        assertEquals("Kommunikation & IPC", outline.titleOf("kommunikation-ipc"));
        assertEquals(Collections.singletonList(ResearchOutline.QUESTION_SECTION),
                ResearchOutline.fromConceptRead("q", "no concept").sectionIds());
        assertEquals(Collections.singletonList(ResearchOutline.QUESTION_SECTION),
                ResearchOutline.fromConceptRead("q", "{\"Root\":[]}").sectionIds());
    }

    @Test
    public void evidenceIsMeasuredPerSectionFromTheBoard() {
        OrchestratorRun run = OrchestratorRun.builder("run-1", "q", 1L).state(OrchestratorRun.State.RUNNING)
                .sections(Arrays.asList(new ResearchOutline.Section("a", "A"), new ResearchOutline.Section("b", "B")))
                .build();
        ResearchTask t1 = submitted(run, "t1", "a").start("e", 2L)
                .withArtifact(new TaskArtifact("x1", TaskArtifact.Kind.SOURCE_REFERENCE,
                        "https://www.one.example/p Title", "s1", 3L))
                .withArtifact(new TaskArtifact("x2", TaskArtifact.Kind.SOURCE_REFERENCE,
                        "http://127.0.0.1:8081/p", "s2", 3L)).complete(4L);
        ResearchTask t2 = submitted(run, "t2", "a").start("e", 2L)
                .withArtifact(new TaskArtifact("x3", TaskArtifact.Kind.SOURCE_REFERENCE, "https://one.example/q",
                        "s1", 3L)).fail("x", 4L);
        ResearchTask foreign = ResearchTask.submit("other.t", "research", "web-researcher", null,
                TaskBriefs.inputsOf(new PlannedTask("t", "web-researcher", "q", "", Arrays.asList("b"), null)),
                "", 0, TaskBudget.unlimited(), 0, 1L);
        EvidenceSnapshot evidence = new EvidenceMeter(2, 2, 1).measure(run, Arrays.asList(t1, t2, foreign));
        assertEquals("a duplicate source counts once", 2, evidence.getDistinctSources());
        assertEquals("www. is dropped, an explicit port is kept", 2, evidence.getDistinctHosts());
        assertTrue(evidence.meetsRunRule());
        assertEquals(2, evidence.getSections().get(0).getSources());
        assertEquals(1, evidence.getSections().get(0).getFailedTasks());
        assertEquals(0, evidence.getSections().get(1).getSources());
        assertFalse("section b is uncovered", evidence.isSufficient());
        assertTrue(evidence.insufficiencyReason().contains("B"));
    }

    private static ResearchTask submitted(OrchestratorRun run, String id, String section) {
        return ResearchTask.submit(run.taskIdOf(id), "research", "web-researcher", null,
                TaskBriefs.inputsOf(new PlannedTask(id, "web-researcher", "query " + id, "brief",
                        Collections.singletonList(section), null)), "judge", 0, TaskBudget.unlimited(), 1, 1L);
    }

    @Test
    public void theRunRecordSurvivesItsJsonForm() {
        OrchestratorRun run = OrchestratorRun.builder("run-7", "Why?", 10L)
                .state(OrchestratorRun.State.AWAITING_APPROVAL)
                .sections(Arrays.asList(new ResearchOutline.Section("a", "A")))
                .plan("because", Arrays.asList(new PlannedTask("p1", "web-researcher", "q1", "b1",
                        Arrays.asList("a"), null)))
                .pending(Arrays.asList(new PlannedTask("f1", "web-researcher", "q2", "b2", Arrays.asList("a"),
                        Arrays.asList("p1"))), "over budget")
                .envelope(4, 12, 1000, 60_000L).spent(2, 3, 500, 30_000L)
                .approved(new java.util.LinkedHashSet<String>(Arrays.asList("a")),
                        new java.util.LinkedHashSet<String>(Arrays.asList("web-researcher")))
                .findings(Arrays.asList(new RoundDecision.Finding("a", "thin")),
                        Collections.<RoundDecision.Finding>emptyList(), "note")
                .evaluated(Arrays.asList("p1")).userNote("PF4J")
                .journal("same").journal("same").updatedAt(20L).build();
        OrchestratorRun back = OrchestratorRunCodec.decode(OrchestratorRunCodec.encode(run));
        assertEquals(OrchestratorRunCodec.encode(run), OrchestratorRunCodec.encode(back));
        assertEquals(Arrays.asList("same", "same"), back.getJournal());
        assertEquals("p1", back.getPending().get(0).getDependsOn().get(0));
        assertEquals(10, back.remainingTasks() + 1);
    }

    @Test
    public void briefsCarryQueryAndSections() {
        PlannedTask planned = new PlannedTask("p1", "web-researcher", "plugin\nloading", "read the docs",
                Arrays.asList("a", "b"), null);
        assertEquals("plugin loading", TaskBriefs.queryOf(TaskBriefs.inputsOf(planned)));
        assertEquals("read the docs", TaskBriefs.briefOf(TaskBriefs.inputsOf(planned)));
        assertEquals(Arrays.asList("a", "b"), TaskBriefs.sectionsOf(TaskBriefs.inputsOf(planned)));
    }

    @Test
    public void settingsComeFromTheEnvironmentWithDefaultsForAnythingInvalid() {
        Map<String, String> env = new HashMap<String, String>();
        env.put(OrchestratorSettings.ENV_MODE, "orchestrated");
        env.put(OrchestratorSettings.ENV_MAX_ROUNDS, "7");
        env.put(OrchestratorSettings.ENV_TOKEN_BUDGET, "0");
        env.put(OrchestratorSettings.ENV_MAX_TASKS, "-3");
        env.put(OrchestratorSettings.ENV_BROWSER_SLOTS, "abc");
        OrchestratorSettings settings = OrchestratorSettings.from(env);
        assertEquals(OrchestratorSettings.Mode.ORCHESTRATED, settings.getMode());
        assertEquals(7, settings.getMaxRounds());
        assertEquals("0 = not limited", 0, settings.getTokenBudget());
        assertEquals(OrchestratorSettings.DEFAULT_MAX_TASKS, settings.getMaxTasks());
        assertEquals(OrchestratorSettings.DEFAULT_BROWSER_SLOTS, settings.getBrowserSlots());
        assertEquals(OrchestratorSettings.Mode.LOOP, OrchestratorSettings.from(null).getMode());
    }

    @Test
    public void webResearchStopReasonsMapOntoTheTaskTable() {
        assertEquals(WorkerResult.Kind.COMPLETED, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.NO_SEMANTIC_MATCHES, "").getKind());
        assertEquals(WorkerResult.Kind.RETRYABLE, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.SEARCH_TECHNICAL_PROBLEM, "").getKind());
        assertEquals(WorkerResult.Kind.FAILED, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.STATE_CHANGED, "").getKind());
        assertEquals(WorkerResult.Kind.CANCELED, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.USER_CANCELLED, "").getKind());
    }

    @Test
    public void theWebWorkerBoundsItsSearchRunByTheTasksRemainingTime() {
        final java.util.concurrent.atomic.AtomicLong passed = new java.util.concurrent.atomic.AtomicLong(-1);
        WebResearchWorker worker = new WebResearchWorker((query, research, accepted, maxDurationMillis, tokens, exhausted) -> {
            passed.set(maxDurationMillis);
            return new WebAcquisition.Result(
                    com.aresstack.askai.research.runtime.loop.ResearchStopReason.NO_SEMANTIC_MATCHES, "");
        }, null);
        ResearchTask task = ResearchTask.submit("r.p1", "research", "web-researcher", null,
                TaskBriefs.inputsOf(new PlannedTask("p1", "web-researcher", "q", "", Arrays.asList("a"), null)),
                "judge", 0, TaskBudget.of(60_000L, 0), 1, 1L).withSpending(15_000L, 0).start("e", 2L);
        WorkerContext context = new WorkerContext() {
            public String query() { return "q"; }
            public String brief() { return ""; }
            public com.aresstack.askai.research.runtime.team.MainModelChat chat() { return null; }
            public com.aresstack.askai.research.runtime.loop.ToolInvoker permitted(
                    com.aresstack.askai.research.runtime.loop.ToolInvoker lane) { return lane; }
            public void artifact(TaskArtifact.Kind kind, String content, String ref) { }
            public void receipt(String tool, boolean ok, String text) { }
            public void log(String line) { }
            public boolean isCancelled() { return false; }
            public boolean isBudgetExhausted() { return false; }
            public java.util.concurrent.atomic.AtomicLong tokens() {
                return new java.util.concurrent.atomic.AtomicLong();
            }
        };
        assertEquals(WorkerResult.Kind.COMPLETED, worker.execute(task, context).getKind());
        assertEquals(45_000L, passed.get());
    }

    @Test
    public void theEvidenceExcerptKeepsThePassagesThatMatchTheQuery() {
        String page = "Welcome to the shop.\nThe battery holds 4,200 mAh according to the maker. Shipping is free.\n"
                + "Reviews say the battery capacity is closer to 3,800 mAh in tests.";
        String excerpt = WebResearchWorker.excerpt(page, "battery capacity mAh", 200);
        assertTrue(excerpt, excerpt.contains("4,200 mAh"));
        assertTrue(excerpt, excerpt.contains("3,800 mAh"));
        assertFalse(excerpt, excerpt.contains("Welcome"));
        assertTrue(WebResearchWorker.excerpt(page, "battery capacity mAh", 40).length() <= 40);
        assertEquals("", WebResearchWorker.excerpt("  ", "q", 100));
    }

    @Test
    public void aSpentAccountRefusesFurtherModelCallsWithoutReachingTheModel() {
        final java.util.concurrent.atomic.AtomicInteger reached = new java.util.concurrent.atomic.AtomicInteger();
        com.aresstack.askai.agent.model.port.ModelPort port = new com.aresstack.askai.agent.model.port.ModelPort() {
            public java.util.concurrent.CompletableFuture<Void> warm(com.aresstack.askai.agent.model.port.ModelSlot slot) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            public java.util.concurrent.CompletableFuture<com.aresstack.askai.agent.model.port.resource.SlotReservation>
                    reserve(com.aresstack.askai.agent.model.port.resource.SlotRequest request) {
                throw new UnsupportedOperationException();
            }

            public com.aresstack.askai.agent.model.port.ModelCall chat(
                    com.aresstack.askai.agent.model.port.resource.SlotReservation reservation,
                    com.aresstack.askai.agent.model.port.ChatRequest request,
                    com.aresstack.askai.agent.model.port.ChatListener listener) {
                reached.incrementAndGet();
                return null;
            }

            public com.aresstack.askai.agent.model.port.ModelCall chat(
                    com.aresstack.askai.agent.model.port.ChatRequest request,
                    com.aresstack.askai.agent.model.port.ChatListener listener) {
                reached.incrementAndGet();
                return null;
            }

            public com.aresstack.askai.agent.model.port.resource.ResourceSnapshot resources() {
                return null;
            }
        };
        final java.util.concurrent.atomic.AtomicBoolean spent = new java.util.concurrent.atomic.AtomicBoolean();
        MeteredModelPort metered = new MeteredModelPort(port, new java.util.concurrent.atomic.AtomicLong(), spent::get);
        final java.util.List<com.aresstack.askai.agent.model.port.ModelFailure> failures =
                new java.util.ArrayList<com.aresstack.askai.agent.model.port.ModelFailure>();
        com.aresstack.askai.agent.model.port.ChatListener listener = new com.aresstack.askai.agent.model.port.ChatListener() {
            public void onCompleted(com.aresstack.askai.agent.model.port.ChatResult result) {
            }

            public void onFailed(com.aresstack.askai.agent.model.port.ModelFailure failure) {
                failures.add(failure);
            }
        };
        metered.chat(null, listener);
        assertEquals(1, reached.get());
        spent.set(true);
        assertTrue(metered.chat(null, listener).isDone());
        assertEquals("the spent account never reaches the model", 1, reached.get());
        assertEquals(com.aresstack.askai.agent.model.port.ModelFailure.Kind.CANCELLED, failures.get(0).getKind());
    }
}
