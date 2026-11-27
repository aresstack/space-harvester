package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TaskExecutorTest {

    private InMemoryTaskBoard board;
    private FakeWorker worker;
    private final AtomicLong clock = new AtomicLong(1_000L);
    private OrchestratorRun run;

    @Before
    public void setUp() {
        board = new InMemoryTaskBoard();
        worker = new FakeWorker();
        run = OrchestratorRun.builder("run-1", "q", 1_000L).state(OrchestratorRun.State.RUNNING)
                .sections(ResearchOutline.ofQuestion("q").getSections()).envelope(4, 12, 0, 0).build();
    }

    private TaskExecutor executor(final int capacity) {
        return new TaskExecutor(board, Collections.<TaskWorker>singletonList(worker), new ScriptedChats(0),
                new ExecutorCapacity() {
                    public List<Resource> resourcesOf(TaskWorker w) {
                        return Collections.singletonList(new Resource("model:local/m", capacity));
                    }

                    public String endpointOf(TaskWorker w) {
                        return "local";
                    }
                }, () -> clock.addAndGet(1), new TaskExecutor.Listener() {
            public void taskStarted(ResearchTask task) {
            }

            public void taskFinished(ResearchTask task, WorkerResult result) {
            }

            public void tokensSpent(long tokens) {
            }

            public void log(String line) {
            }
        });
    }

    private static PlannedTask task(String id, String... dependsOn) {
        return new PlannedTask(id, "web-researcher", "query " + id, "", Collections.singletonList("question"),
                Arrays.asList(dependsOn));
    }

    private Map<String, TaskState> states() {
        Map<String, TaskState> states = new HashMap<String, TaskState>();
        for (ResearchTask task : board.tasks()) {
            states.put(run.plannedIdOf(task.getTaskId()), task.getState());
        }
        return states;
    }

    @Test
    public void capacityOneRunsOneAfterAnotherCapacityFourRunsTogether() {
        worker.workMillis = 80;
        TaskExecutor one = executor(1);
        one.submit(run, Arrays.asList(task("a"), task("b"), task("c"), task("d")), OrchestratorSettings.defaults());
        assertEquals(TaskExecutor.Stop.IDLE, one.runReady(run, new AtomicBoolean(), () -> true));
        assertEquals(1, worker.maxRunning.get());

        setUp();
        worker.workMillis = 80;
        TaskExecutor four = executor(4);
        four.submit(run, Arrays.asList(task("a"), task("b"), task("c"), task("d")), OrchestratorSettings.defaults());
        four.runReady(run, new AtomicBoolean(), () -> true);
        assertEquals(4, worker.maxRunning.get());
        for (TaskState state : states().values()) {
            assertEquals(TaskState.COMPLETED, state);
        }
    }

    @Test
    public void dependenciesRunInOrderAndABrokenDependencyCancelsTheDependent() {
        TaskExecutor executor = executor(4);
        worker.override = t -> t.getTaskId().endsWith(".a") ? WorkerResult.failed("no luck") : null;
        executor.submit(run, Arrays.asList(task("a"), task("b", "a"), task("c")), OrchestratorSettings.defaults());
        executor.runReady(run, new AtomicBoolean(), () -> true);
        Map<String, TaskState> states = states();
        assertEquals(TaskState.FAILED, states.get("a"));
        assertEquals(TaskState.CANCELED, states.get("b"));
        assertEquals(TaskState.COMPLETED, states.get("c"));
        assertTrue(!worker.executed.contains("run-1.b"));
    }

    @Test
    public void aRetryableFailureIsRetriedWithinTheLimitThenFails() {
        final AtomicInteger attempts = new AtomicInteger();
        worker.override = t -> {
            attempts.incrementAndGet();
            return WorkerResult.retryable("browser hiccup");
        };
        TaskExecutor executor = executor(1);
        executor.submit(run, Collections.singletonList(task("a")),
                OrchestratorSettings.builder().taskRetryLimit(2).build());
        executor.runReady(run, new AtomicBoolean(), () -> true);
        assertEquals(3, attempts.get());
        ResearchTask a = board.tasks().get(0);
        assertEquals(TaskState.FAILED, a.getState());
        assertEquals(2, a.getRetryCount());
    }

    @Test
    public void resumeRequeuesWorkingTasksAndNeverTouchesCompletedOnes() {
        TaskExecutor executor = executor(1);
        List<ResearchTask> submitted = executor.submit(run, Arrays.asList(task("a"), task("b")),
                OrchestratorSettings.builder().taskRetryLimit(1).build());
        board.save(submitted.get(0).start("local", 2_000L).complete(2_100L));
        board.save(submitted.get(1).start("local", 2_000L)); // the session died here
        assertEquals(1, executor.resume(run));
        executor.runReady(run, new AtomicBoolean(), () -> true);
        assertEquals(Collections.singletonList("run-1.b"), worker.executed);
        assertEquals(TaskState.COMPLETED, states().get("b"));
        assertEquals(1, board.tasks().get(1).getRetryCount());
    }

    @Test
    public void anInterruptedTurnLeavesThePackageResumableNotCancelled() {
        TaskExecutor executor = executor(1);
        final AtomicBoolean cancelled = new AtomicBoolean();
        worker.override = t -> {
            cancelled.set(true);
            return WorkerResult.canceled("USER_CANCELLED");
        };
        executor.submit(run, Collections.singletonList(task("a")), OrchestratorSettings.defaults());
        assertEquals(TaskExecutor.Stop.CANCELLED, executor.runReady(run, cancelled, () -> true));
        assertEquals(TaskState.WORKING, board.tasks().get(0).getState());
        assertTrue(board.tasks().get(0).getBudget().getTimeUsedMillis() > 0);

        worker.override = null;
        assertEquals(1, executor.resume(run));
        executor.runReady(run, new AtomicBoolean(), () -> true);
        assertEquals(TaskState.COMPLETED, board.tasks().get(0).getState());
    }

    @Test
    public void aRoleWithoutWorkerIsRejectedBeforeItRuns() {
        TaskExecutor executor = executor(1);
        executor.submit(run, Collections.singletonList(new PlannedTask("x", "source-verifier", "q", "",
                Collections.singletonList("question"), null)), OrchestratorSettings.defaults());
        assertEquals(TaskState.REJECTED, board.tasks().get(0).getState());
    }

    @Test
    public void theBudgetGateStopsNewDispatches() {
        TaskExecutor executor = executor(1);
        executor.submit(run, Arrays.asList(task("a"), task("b")), OrchestratorSettings.defaults());
        final AtomicInteger checks = new AtomicInteger();
        assertEquals(TaskExecutor.Stop.BUDGET, executor.runReady(run, new AtomicBoolean(),
                () -> checks.incrementAndGet() == 1));
        assertEquals(1, worker.executed.size());
    }

    @Test
    public void tasksSpendTheirOwnBudgetAndRecordArtifacts() {
        worker.sources("query a", Arrays.asList("https://a.example/x"));
        TaskExecutor executor = executor(1);
        executor.submit(run, Collections.singletonList(task("a")), OrchestratorSettings.defaults());
        executor.runReady(run, new AtomicBoolean(), () -> true);
        ResearchTask a = board.tasks().get(0);
        assertEquals(1, a.getArtifacts().size());
        assertEquals("local", a.getEndpoint());
        assertTrue(a.getBudget().getTimeUsedMillis() > 0);
        assertEquals("query a", TaskBriefs.queryOf(a.getInputs()));
    }

    @Test
    public void theRoleScopedLaneRefusesToolsOutsideTheCatalog() throws Exception {
        final List<String> reached = new ArrayList<String>();
        ToolInvoker lane = new ToolInvoker() {
            public String call(String toolName, Map<String, Object> args) {
                reached.add(toolName);
                return "ok";
            }

            public List<String> toolNames() {
                return Arrays.asList("source_accept", "document_save");
            }
        };
        ToolInvoker scoped = new RoleScopedToolInvoker(lane, FakeWorker.ROLE.getTools());
        assertEquals("ok", scoped.call("source_accept", new HashMap<String, Object>()));
        try {
            scoped.call("document_save", new HashMap<String, Object>());
            fail("document_save is not in the role's catalog");
        } catch (ToolInvoker.ToolFailure expected) {
            assertTrue(expected.getMessage().contains("not permitted"));
        }
        assertEquals(Collections.singletonList("source_accept"), reached);
        assertEquals(Collections.singletonList("source_accept"), scoped.toolNames());
    }
}
