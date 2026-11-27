package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskState;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrchestratedResearchRunTest {

    private InMemoryTaskBoard board;
    private ScriptedChats chats;
    private FakeWorker worker;
    private final List<String> visible = new ArrayList<String>();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private ResearchOutline outline;

    @Before
    public void setUp() {
        board = new InMemoryTaskBoard();
        chats = new ScriptedChats(10);
        worker = new FakeWorker();
        outline = new ResearchOutline("How do plugins load?", Arrays.asList(
                new ResearchOutline.Section("loading", "Loading"),
                new ResearchOutline.Section("isolation", "Isolation")));
    }

    private OrchestratedResearchRun run(OrchestratorSettings settings) {
        final AtomicLong orchestratorTokens = new AtomicLong();
        final OrchestratedResearchRun[] self = new OrchestratedResearchRun[1];
        TaskExecutor executor = new TaskExecutor(board, Collections.<TaskWorker>singletonList(worker), chats,
                ExecutorCapacity.fromPort(null, 1), () -> clock.addAndGet(5), new TaskExecutor.Listener() {
            public void taskStarted(ResearchTask task) {
            }

            public void taskFinished(ResearchTask task, WorkerResult result) {
            }

            public void tokensSpent(long tokens) {
                self[0].bookTaskTokens(tokens);
            }

            public void log(String line) {
            }
        });
        ResearchOrchestrator orchestrator = new ResearchOrchestrator(
                chats.chatFor(ResearchOrchestrator.ROLE, orchestratorTokens), settings.getDecisionRepairAttempts(), 0);
        self[0] = new OrchestratedResearchRun(board, executor, orchestrator, orchestratorTokens,
                new EvidenceMeter(2, 2, 1), settings, () -> clock.addAndGet(5), new OrchestratedResearchRun.Listener() {
            public void visible(String message) {
                visible.add(message);
            }

            public void log(String line) {
            }
        });
        return self[0];
    }

    private static String plan() {
        return "{\"rationale\":\"one package per section\",\"tasks\":["
                + "{\"id\":\"p1\",\"role\":\"web-researcher\",\"query\":\"plugin loading\",\"brief\":\"how\","
                + "\"sections\":[\"loading\"],\"dependsOn\":[]},"
                + "{\"id\":\"p2\",\"role\":\"web-researcher\",\"query\":\"plugin isolation\",\"brief\":\"why\","
                + "\"sections\":[\"isolation\"],\"dependsOn\":[\"p1\"]}]}";
    }

    private static String round(String action, String followUps, String question) {
        return "{\"evaluations\":[{\"taskId\":\"p1\",\"verdict\":\"useful\",\"note\":\"fine\"}],"
                + "\"gaps\":[],\"contradictions\":[],\"quality\":{\"sufficient\":true,\"note\":\"good\"},"
                + "\"action\":\"" + action + "\",\"question\":\"" + question + "\",\"followUps\":[" + followUps + "]}";
    }

    private void bothSectionsFindSources() {
        worker.sources("plugin loading", Arrays.asList("https://a.example/1", "https://b.example/2"));
        worker.sources("plugin isolation", Arrays.asList("https://c.example/3"));
    }

    @Test
    public void nothingRunsBeforeTheUserApprovesThePlan() {
        chats.answer(plan());
        OrchestratorRun run = run(OrchestratorSettings.defaults()).start("How do plugins load?", outline);

        assertEquals(OrchestratorRun.State.PLAN_PROPOSED, run.getState());
        assertEquals(2, run.getPlan().size());
        assertTrue("no task is submitted before the approval", board.tasks().isEmpty());
        assertTrue(worker.executed.isEmpty());
        assertTrue(visible.get(0).startsWith("Research plan for approval"));
        assertTrue("the plan schema carries the outline's section ids",
                chats.schemas.get(0).contains("\"enum\":[\"loading\",\"isolation\"]"));
    }

    @Test
    public void anApprovedRunClosesOnlyWhenTheMeasurementsAgree() {
        bothSectionsFindSources();
        chats.answer(plan()).answer(round("close", "", ""));
        OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("How do plugins load?", outline);
        OrchestratorRun run = runner.approve(new AtomicBoolean());

        assertEquals(OrchestratorRun.State.CLOSED, run.getState());
        assertEquals("SEQUENTIAL: p2 waits for p1", Arrays.asList(run.taskIdOf("p1"), run.taskIdOf("p2")),
                worker.executed);
        assertEquals(1, run.getRoundsUsed());
        assertTrue("orchestrator and task tokens are booked on the run", run.getTokensUsed() >= 20);
        for (ResearchTask task : board.tasks()) {
            assertEquals(TaskState.COMPLETED, task.getState());
        }
        assertTrue(visible.get(visible.size() - 1).contains("your decision"));
    }

    @Test
    public void aCloseAgainstInsufficientMeasurementsGoesBackToTheUser() {
        worker.sources("plugin loading", Arrays.asList("https://a.example/1"));
        chats.answer(plan()).answer(round("close", "", ""));
        OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("How do plugins load?", outline);
        OrchestratorRun run = runner.approve(new AtomicBoolean());

        assertEquals(OrchestratorRun.State.AWAITING_APPROVAL, run.getState());
        assertTrue(run.getWaitingReason(), run.getWaitingReason().contains("not sufficient"));
    }

    @Test
    public void followUpsInsideTheApprovedPlanRunWithoutAskingFollowUpsBeyondItWait() {
        worker.sources("plugin loading", Arrays.asList("https://a.example/1"));
        worker.sources("more loading", Arrays.asList("https://b.example/2"));
        worker.sources("plugin isolation", Arrays.asList("https://c.example/3"));
        // plan covers only "loading"; the follow-up for "isolation" is outside the approved scope
        chats.answer("{\"rationale\":\"r\",\"tasks\":[{\"id\":\"p1\",\"role\":\"web-researcher\","
                + "\"query\":\"plugin loading\",\"brief\":\"b\",\"sections\":[\"loading\"],\"dependsOn\":[]}]}");
        chats.answer(round("continue", "{\"id\":\"f1\",\"role\":\"web-researcher\",\"query\":\"more loading\","
                + "\"brief\":\"b\",\"sections\":[\"loading\"],\"dependsOn\":[]}", ""));
        chats.answer(round("continue", "{\"id\":\"f2\",\"role\":\"web-researcher\",\"query\":\"plugin isolation\","
                + "\"brief\":\"b\",\"sections\":[\"isolation\"],\"dependsOn\":[]}", ""));
        chats.answer(round("close", "", ""));
        OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("q", outline);
        OrchestratorRun run = runner.approve(new AtomicBoolean());

        assertEquals(OrchestratorRun.State.AWAITING_APPROVAL, run.getState());
        assertEquals(1, run.getPending().size());
        assertEquals("f2", run.getPending().get(0).getId());
        assertEquals("f1 ran on its own inside the approved scope", 2, worker.executed.size());

        run = runner.approve(new AtomicBoolean());
        assertEquals(OrchestratorRun.State.CLOSED, run.getState());
        assertTrue(run.getApprovedSections().contains("isolation"));
        assertEquals(3, worker.executed.size());
    }

    @Test
    public void theRoundLimitHandsTheRunBackAndAnApprovalAddsRounds() {
        worker.sources("plugin loading", Arrays.asList("https://a.example/1"));
        chats.answer(plan()).answer(round("continue", "", ""));
        OrchestratedResearchRun runner = run(OrchestratorSettings.builder().maxRounds(1).extensionRounds(2).build());
        runner.start("q", outline);
        OrchestratorRun run = runner.approve(new AtomicBoolean());

        assertEquals(OrchestratorRun.State.AWAITING_APPROVAL, run.getState());
        // nothing open, no follow-ups, not sufficient → back to the user after the single round
        assertEquals(1, run.getRoundsUsed());

        chats.answer(round("continue", "", ""));
        run = runner.approve(new AtomicBoolean());
        assertEquals(1 + 2, run.getMaxRounds());
    }

    @Test
    public void askUserWaitsForTheAnswerAndTheAnswerReachesTheNextRound() {
        bothSectionsFindSources();
        chats.answer(plan()).answer(round("ask-user", "", "Which plugin framework?"));
        OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("q", outline);
        OrchestratorRun run = runner.approve(new AtomicBoolean());
        assertEquals(OrchestratorRun.State.AWAITING_INPUT, run.getState());
        assertEquals("Which plugin framework?", run.getWaitingReason());
        assertEquals("approve does not answer a question", OrchestratorRun.State.AWAITING_INPUT,
                runner.approve(new AtomicBoolean()).getState());

        chats.answer(round("close", "", ""));
        run = runner.answer("PF4J", new AtomicBoolean());
        assertEquals(OrchestratorRun.State.CLOSED, run.getState());
        String lastPrompt = chats.requests.get(chats.requests.size() - 1).get(1).getContent();
        assertTrue(lastPrompt.contains("USER ANSWERS") && lastPrompt.contains("PF4J"));
    }

    @Test
    public void anInvalidDecisionIsRepairedOnceThenTheRunWaits() {
        chats.answer("{\"rationale\":\"r\",\"tasks\":[{\"id\":\"p1\",\"role\":\"web-researcher\","
                + "\"query\":\"q\",\"brief\":\"b\",\"sections\":[\"nowhere\"],\"dependsOn\":[]}]}");
        chats.answer(plan());
        OrchestratorRun run = run(OrchestratorSettings.defaults()).start("q", outline);
        assertEquals(OrchestratorRun.State.PLAN_PROPOSED, run.getState());
        assertTrue("the repair turn names the reason", chats.requests.get(1).get(3).getContent()
                .contains("unknown section 'nowhere'"));

        setUp();
        chats.answer("not json").answer("still not json");
        run = run(OrchestratorSettings.defaults()).start("q", outline);
        assertEquals(OrchestratorRun.State.FAILED, run.getState());
    }

    @Test
    public void stopCancelsOpenTasksAndKeepsTheRecord() {
        chats.answer(plan());
        OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("q", outline);
        OrchestratorRun run = runner.stop();
        assertEquals(OrchestratorRun.State.STOPPED, run.getState());
        assertTrue(run.getJournal().contains("Plan rejected by the user."));
    }

    @Test
    public void anInterruptedRunResumesWithoutRepeatingCompletedWork() {
        bothSectionsFindSources();
        chats.answer(plan());
        OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("q", outline);
        final AtomicBoolean cancelled = new AtomicBoolean();
        worker.override = task -> {
            if (task.getTaskId().endsWith(".p2")) {
                cancelled.set(true); // the user pressed Stop on the turn while p2 runs
                return WorkerResult.canceled("USER_CANCELLED");
            }
            return null;
        };
        OrchestratorRun run = runner.approve(cancelled);
        assertEquals("an interrupted turn leaves the run running", OrchestratorRun.State.RUNNING, run.getState());
        worker.override = null;

        chats.answer(round("close", "", ""));
        run = runner.approve(new AtomicBoolean());
        assertEquals(OrchestratorRun.State.CLOSED, run.getState());
        int p1Runs = 0;
        for (String id : worker.executed) {
            p1Runs += id.endsWith(".p1") ? 1 : 0;
        }
        assertEquals("completed work is never re-executed", 1, p1Runs);
        int p2Runs = 0;
        for (String id : worker.executed) {
            p2Runs += id.endsWith(".p2") ? 1 : 0;
        }
        assertEquals("the interrupted package runs again instead of staying cancelled", 2, p2Runs);
    }

    @Test
    public void theRunBudgetStopsDispatchingAndGoesBackToTheUser() {
        bothSectionsFindSources();
        chats.answer(plan());
        // the plan call alone (10 tokens) spends the budget
        OrchestratedResearchRun runner = run(OrchestratorSettings.builder().tokenBudget(10).build());
        runner.start("q", outline);
        OrchestratorRun run = runner.approve(new AtomicBoolean());
        assertEquals(OrchestratorRun.State.AWAITING_APPROVAL, run.getState());
        assertTrue(run.getWaitingReason().contains("token budget"));
        assertTrue(worker.executed.isEmpty());
        assertFalse(run.getState().isFinal());
    }

    @Test
    public void aSecondApprovalWhileTheCycleRunsIsRefused() throws Exception {
        bothSectionsFindSources();
        chats.answer(plan());
        final OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("q", outline);
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        worker.override = task -> {
            entered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            worker.override = null;
            return null;
        };
        chats.answer(round("close", "", ""));
        Thread first = new Thread(() -> runner.approve(new AtomicBoolean()));
        first.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        OrchestratorRun during = runner.approve(new AtomicBoolean());
        assertEquals(OrchestratorRun.State.RUNNING, during.getState());
        assertTrue(visible.get(visible.size() - 1).contains("already executing"));
        release.countDown();
        first.join(5_000L);
        assertEquals(OrchestratorRun.State.CLOSED, runner.current().getState());
        assertEquals("no package ran twice", 2, worker.executed.size());
    }

    @Test
    public void aStopWhileAPackageRunsKeepsItCancelledAndEndsTheCycle() {
        bothSectionsFindSources();
        chats.answer(plan());
        final OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("q", outline);
        worker.override = task -> {
            runner.stop(); // the user presses Stop run while p1 works
            return null;
        };
        OrchestratorRun run = runner.approve(new AtomicBoolean());
        assertEquals(OrchestratorRun.State.STOPPED, run.getState());
        for (ResearchTask task : board.tasks()) {
            assertEquals(task.getTaskId(), com.aresstack.askai.research.domain.task.TaskState.CANCELED,
                    task.getState());
        }
        assertEquals(1, worker.executed.size());
    }

    @Test
    public void planningTimeCountsAgainstTheRunsTimeBudget() {
        chats.answer(plan());
        OrchestratorRun run = run(OrchestratorSettings.defaults()).start("q", outline);
        assertTrue(run.getTimeUsedMillis() > 0);
    }

    @Test
    public void aStopDuringTheEvaluationIsNotOverwrittenByItsDecision() {
        bothSectionsFindSources();
        chats.answer(plan());
        final OrchestratedResearchRun runner = run(OrchestratorSettings.defaults());
        runner.start("q", outline);
        chats.answer(round("continue", "{\"id\":\"f1\",\"role\":\"web-researcher\",\"query\":\"more\","
                + "\"brief\":\"\",\"sections\":[\"loading\"],\"dependsOn\":[]}", ""));
        chats.beforeAnswer = runner::stop; // the user presses Stop run while the orchestrator decides
        OrchestratorRun run = runner.approve(new AtomicBoolean());
        assertEquals(OrchestratorRun.State.STOPPED, run.getState());
        assertEquals(OrchestratorRun.State.STOPPED, runner.current().getState());
        assertFalse("the follow-up decided after the stop never runs", worker.executed.contains("run-"
                + runner.current().getRunId().substring(4) + ".f1"));
        assertEquals(2, board.tasks().size());
    }
}
