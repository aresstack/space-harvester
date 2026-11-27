package com.aresstack.askai.research.domain.task;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The task model of ADR 0001, section 4.4: checked transitions, retries, budgets, history, restore. */
public class ResearchTaskTest {

    private static ResearchTask task(int retryLimit) {
        return ResearchTask.submit("task-2", "RESEARCH", "source-verifier", Arrays.asList("task-1"),
                new TaskInputs("verify the claim", Arrays.asList("src-1", "src-1", "sec-3")),
                "judge", 8192, TaskBudget.of(60000L, 4000L), retryLimit, 100L);
    }

    @Test
    public void aSubmittedTaskCarriesEverythingTheAdrNames() {
        ResearchTask task = task(2);
        assertEquals(TaskState.SUBMITTED, task.getState());
        assertEquals("RESEARCH", task.getPhase());
        assertEquals("source-verifier", task.getRole());
        assertEquals(Collections.singletonList("task-1"), task.getDependsOn());
        assertEquals("verify the claim", task.getInputs().getBrief());
        assertEquals("refs are de-duplicated", Arrays.asList("src-1", "sec-3"), task.getInputs().getArtifactRefs());
        assertEquals("judge", task.getModelSlot());
        assertEquals(8192, task.getContextSize());
        assertEquals(60000L, task.getBudget().getTimeLimitMillis());
        assertEquals(4000L, task.getBudget().getTokenLimit());
        assertEquals(0, task.getRetryCount());
        assertEquals(2, task.getRetryLimit());
        assertEquals(100L, task.getCreatedAtMillis());
        assertEquals(0L, task.getStartedAtMillis());
        assertEquals(0L, task.getFinishedAtMillis());
        assertEquals("", task.getEndpoint());
        assertEquals(1, task.getTransitions().size());
    }

    @Test
    public void theHappyPathRecordsEveryStepAndTheEndpoint() {
        ResearchTask submitted = task(1);
        ResearchTask working = submitted.start("ollama-local", 200L);
        ResearchTask done = working.complete(300L);

        assertNotSame("transitions return new objects", submitted, working);
        assertEquals(TaskState.SUBMITTED, submitted.getState());
        assertEquals(TaskState.COMPLETED, done.getState());
        assertEquals("ollama-local", done.getEndpoint());
        assertEquals(200L, done.getStartedAtMillis());
        assertEquals(300L, done.getFinishedAtMillis());
        assertEquals(3, done.getTransitions().size());
        assertEquals(TaskState.WORKING, done.getTransitions().get(2).getFrom());
    }

    @Test
    public void invalidTransitionsAreRejected() {
        ResearchTask submitted = task(1);
        expectRejected(() -> submitted.complete(200L), TaskState.SUBMITTED, TaskState.COMPLETED);
        expectRejected(() -> submitted.requireInput("?", 200L), TaskState.SUBMITTED, TaskState.INPUT_REQUIRED);
        expectRejected(() -> submitted.fail("x", 200L), TaskState.SUBMITTED, TaskState.FAILED);

        ResearchTask working = submitted.start("ep", 200L);
        expectRejected(() -> working.reject("x", 300L), TaskState.WORKING, TaskState.REJECTED);
        expectRejected(() -> working.start("ep", 300L), TaskState.WORKING, TaskState.WORKING);
        expectRejected(() -> working.inputProvided("a", 300L), TaskState.WORKING, TaskState.SUBMITTED);
    }

    @Test
    public void terminalStatesAreFinal() {
        ResearchTask completed = task(3).start("ep", 200L).complete(300L);
        expectRejected(() -> completed.start("ep", 400L), TaskState.COMPLETED, TaskState.WORKING);
        expectRejected(() -> completed.requeue("again", 400L), TaskState.COMPLETED, TaskState.SUBMITTED);
        expectRejected(() -> completed.cancel("x", 400L), TaskState.COMPLETED, TaskState.CANCELED);

        ResearchTask rejected = task(1).reject("budget", 200L);
        expectRejected(() -> rejected.start("ep", 300L), TaskState.REJECTED, TaskState.WORKING);
        ResearchTask canceled = task(1).cancel("user", 200L);
        expectRejected(() -> canceled.start("ep", 300L), TaskState.CANCELED, TaskState.WORKING);
        ResearchTask failed = task(1).start("ep", 200L).fail("boom", 300L);
        expectRejected(() -> failed.requeue("retry", 400L), TaskState.FAILED, TaskState.SUBMITTED);
    }

    @Test
    public void aRequeueCountsARetryAndStopsAtTheLimit() {
        ResearchTask first = task(1).start("ep-a", 200L);
        ResearchTask requeued = first.requeue("interrupted", 300L);
        assertEquals(TaskState.SUBMITTED, requeued.getState());
        assertEquals(1, requeued.getRetryCount());
        assertFalse(requeued.hasRetriesLeft());
        assertEquals("interrupted", requeued.getTransitions().get(2).getReason());

        ResearchTask second = requeued.start("ep-b", 400L);
        assertEquals("the latest attempt's endpoint", "ep-b", second.getEndpoint());
        assertEquals("the first attempt's start", 200L, second.getStartedAtMillis());
        expectRejected(() -> second.requeue("again", 500L), TaskState.WORKING, TaskState.SUBMITTED);
        assertEquals(TaskState.FAILED, second.fail("retry limit spent", 500L).getState());
    }

    @Test
    public void aZeroRetryLimitAdmitsNoRequeue() {
        ResearchTask working = task(0).start("ep", 200L);
        expectRejected(() -> working.requeue("interrupted", 300L), TaskState.WORKING, TaskState.SUBMITTED);
    }

    @Test
    public void inputRequiredWaitsForTheUserAndReturnsToTheQueue() {
        ResearchTask waiting = task(1).start("ep", 200L).requireInput("which edition?", 300L);
        assertEquals(TaskState.INPUT_REQUIRED, waiting.getState());
        expectRejected(() -> waiting.complete(400L), TaskState.INPUT_REQUIRED, TaskState.COMPLETED);
        expectRejected(() -> waiting.start("ep", 400L), TaskState.INPUT_REQUIRED, TaskState.WORKING);

        ResearchTask answered = waiting.inputProvided("the 2024 edition", 400L);
        assertEquals(TaskState.SUBMITTED, answered.getState());
        assertEquals("answering is not a retry", 0, answered.getRetryCount());
        assertEquals("the 2024 edition", answered.getTransitions().get(3).getReason());
        assertEquals(TaskState.CANCELED, waiting.cancel("user dropped it", 400L).getState());
    }

    @Test
    public void aTaskStartsOnlyOnAnAssignedEndpoint() {
        expectRejected(() -> task(1).start(" ", 200L), TaskState.SUBMITTED, TaskState.WORKING);
    }

    @Test
    public void timeNeverRunsBackwards() {
        ResearchTask working = task(1).start("ep", 200L);
        expectRejected(() -> working.complete(150L), TaskState.WORKING, TaskState.COMPLETED);
    }

    @Test
    public void artifactsReceiptsAndSpendingAccumulateWhileOpenAndFreezeWhenTerminal() {
        ResearchTask working = task(1).start("ep", 200L)
                .withArtifact(new TaskArtifact("art-1", TaskArtifact.Kind.VERDICT, "{\"supported\":true}",
                        "claim-4", 250L))
                .withReceipt(new TaskReceipt("research.accept_source", true, "accepted=1", 260L))
                .withSpending(1000L, 300L)
                .requeue("interrupted", 300L)
                .start("ep", 400L)
                .withSpending(2000L, 500L);
        assertEquals(1, working.getArtifacts().size());
        assertEquals(1, working.getReceipts().size());
        assertEquals("spending survives the interruption", 3000L, working.getBudget().getTimeUsedMillis());
        assertEquals(800L, working.getBudget().getTokensUsed());
        assertFalse(working.getBudget().isExhausted());
        assertTrue(working.withSpending(0L, 3200L).getBudget().isTokensExhausted());

        ResearchTask done = working.complete(500L);
        try {
            done.withArtifact(new TaskArtifact("art-2", TaskArtifact.Kind.TEXT, "late", "", 600L));
            fail("a completed task accepted an artifact");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("COMPLETED"));
        }
    }

    @Test
    public void dependenciesAreSatisfiedOnlyByCompletedTasks() {
        ResearchTask task = task(1);
        Map<String, TaskState> graph = new HashMap<String, TaskState>();
        assertFalse("unknown dependency", task.dependenciesSatisfiedBy(graph));
        graph.put("task-1", TaskState.WORKING);
        assertFalse(task.dependenciesSatisfiedBy(graph));
        graph.put("task-1", TaskState.FAILED);
        assertFalse(task.dependenciesSatisfiedBy(graph));
        graph.put("task-1", TaskState.COMPLETED);
        assertTrue(task.dependenciesSatisfiedBy(graph));
    }

    @Test
    public void aTaskMustNotDependOnItself() {
        try {
            ResearchTask.submit("t", "RESEARCH", "writer", Arrays.asList("t"), null, "", 0, null, 0, 1L);
            fail("self-dependency accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("itself"));
        }
    }

    @Test
    public void restoreReplaysAValidHistory() {
        ResearchTask original = task(2).start("ep", 200L).requeue("interrupted", 300L);
        ResearchTask restored = ResearchTask.restore(original.getTaskId(), original.getPhase(),
                original.getRole(), original.getDependsOn(), original.getState(), original.getInputs(),
                original.getArtifacts(), original.getModelSlot(), original.getContextSize(),
                original.getBudget(), original.getRetryCount(), original.getRetryLimit(),
                original.getReceipts(), original.getTransitions(), original.getEndpoint());
        assertEquals(TaskState.SUBMITTED, restored.getState());
        assertEquals(1, restored.getRetryCount());
        assertEquals(3, restored.getTransitions().size());
        assertEquals(TaskState.WORKING, restored.start("ep", 400L).getState());
    }

    @Test
    public void restoreRejectsAHistoryThatBreaksTheRules() {
        List<TaskTransition> forged = Arrays.asList(
                new TaskTransition(null, TaskState.SUBMITTED, 100L, ""),
                new TaskTransition(TaskState.SUBMITTED, TaskState.COMPLETED, 200L, ""));
        try {
            ResearchTask.restore("t", "RESEARCH", "writer", null, TaskState.COMPLETED, null, null, "", 0,
                    null, 0, 0, null, forged, "");
            fail("forged history accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("breaks the transition rules"));
        }
    }

    @Test
    public void restoreRejectsAStateThatDisagreesWithTheHistory() {
        List<TaskTransition> history = Collections.singletonList(
                new TaskTransition(null, TaskState.SUBMITTED, 100L, ""));
        try {
            ResearchTask.restore("t", "RESEARCH", "writer", null, TaskState.WORKING, null, null, "", 0,
                    null, 0, 0, null, history, "");
            fail("inconsistent state accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must end in the current state"));
        }
    }

    @Test
    public void restoreRejectsARetryCountThatDisagreesWithTheHistory() {
        List<TaskTransition> history = Arrays.asList(
                new TaskTransition(null, TaskState.SUBMITTED, 100L, ""),
                new TaskTransition(TaskState.SUBMITTED, TaskState.WORKING, 200L, ""),
                new TaskTransition(TaskState.WORKING, TaskState.SUBMITTED, 300L, "interrupted"));
        try {
            ResearchTask.restore("t", "RESEARCH", "writer", null, TaskState.SUBMITTED, null, null, "", 0,
                    null, 0, 1, null, history, "");
            fail("under-reported retry count accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("disagrees with the 1 re-queues"));
        }
    }

    @Test
    public void restoreRejectsARetryCountAboveTheLimit() {
        List<TaskTransition> history = Collections.singletonList(
                new TaskTransition(null, TaskState.SUBMITTED, 100L, ""));
        try {
            ResearchTask.restore("t", "RESEARCH", "writer", null, TaskState.SUBMITTED, null, null, "", 0,
                    null, 3, 2, null, history, "");
            fail("retry count above limit accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("exceeds retryLimit"));
        }
    }

    private static void expectRejected(Runnable transition, TaskState from, TaskState to) {
        try {
            transition.run();
            fail(from + " -> " + to + " was accepted");
        } catch (IllegalTaskTransitionException expected) {
            assertEquals(from, expected.getFrom());
            assertEquals(to, expected.getTo());
        }
    }
}
