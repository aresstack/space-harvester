package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The lane G roles through the real {@link TaskExecutor}: plan → search → verify → write as one dependency chain
 * on the blackboard, each worker reading what its upstream recorded, and the session's worker set honouring the
 * role settings.
 */
public class WorkerChainTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    @Test
    public void planSearchVerifyWriteRunsAsOneChainOnTheBlackboard() {
        final List<String> searched = new ArrayList<String>();
        WebAcquisition acquisition = new WebAcquisition() {
            public Result acquire(String query, ToolInvoker research,
                                  Consumer<WebSearchApplicationService.AcceptedSource> accepted,
                                  long maxDurationMillis, AtomicLong tokens,
                                  java.util.function.BooleanSupplier exhausted) {
                searched.add(query);
                if (query.startsWith("grant")) {
                    accepted.accept(source("src-a", "https://a.test/1", "Grant rate"));
                    accepted.accept(source("src-c", "https://c.test/1", "Living room"));
                } else {
                    accepted.accept(source("src-b", "https://b.test/1", "Portal"));
                }
                return new Result(ResearchStopReason.SUFFICIENT_EVIDENCE, "");
            }
        };
        FakeWorkerContext.SourceLane lane = new FakeWorkerContext.SourceLane()
                .text("src-a", "The grant covers 30 percent of the cost.")
                .text("src-b", "Applications go through the KfW portal.")
                .text("src-c", "Ten tips for a cosy living room.");
        ScriptedChats chats = new ScriptedChats(5)
                .answer("{\"queries\":[\"grant rate heat pump\",\"kfw portal application\"]}")
                .answer("{\"verdicts\":[{\"source\":\"src-a\",\"verdict\":\"supports\",\"section\":\"question\","
                        + "\"claim\":\"30 percent\",\"reason\":\"figure\"},{\"source\":\"src-c\",\"verdict\":"
                        + "\"off-topic\",\"section\":\"\",\"claim\":\"\",\"reason\":\"interior\"},{\"source\":"
                        + "\"src-b\",\"verdict\":\"supports\",\"section\":\"question\",\"claim\":\"portal\","
                        + "\"reason\":\"process\"}],\"contradictions\":[]}")
                .answer("{\"sections\":[{\"section\":\"question\",\"paragraphs\":[{\"text\":\"The grant covers 30 "
                        + "percent; applications go through the KfW portal.\",\"sources\":[\"src-a\",\"src-b\"]}]}],"
                        + "\"gaps\":[]}");
        InMemoryTaskBoard board = new InMemoryTaskBoard();
        List<TaskWorker> workers = OrchestratorSession.workers(acquisition, lane, OrchestratorSettings.defaults(),
                WorkerRoleSettings.defaults(), 0);
        TaskExecutor executor = new TaskExecutor(board, workers, chats, new ExecutorCapacity() {
            public List<Resource> resourcesOf(TaskWorker worker) {
                return Collections.singletonList(new Resource("model:local/m", 4));
            }

            public String endpointOf(TaskWorker worker) {
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
        OrchestratorRun run = OrchestratorRun.builder("run-1", "heat pump grants", 1_000L)
                .state(OrchestratorRun.State.RUNNING)
                .sections(ResearchOutline.ofQuestion("heat pump grants").getSections()).envelope(4, 12, 0, 0).build();
        List<String> section = Collections.singletonList(ResearchOutline.QUESTION_SECTION);
        executor.submit(run, Arrays.asList(
                new PlannedTask("plan", "search-planner", "heat pump grants", "", section,
                        Collections.<String>emptyList()),
                new PlannedTask("web", "web-researcher", "heat pump grants", "", section,
                        Collections.singletonList("plan")),
                new PlannedTask("verify", "source-verifier", "heat pump grants", "", section,
                        Collections.singletonList("web")),
                new PlannedTask("write", "writer", "heat pump grants", "", section,
                        Collections.singletonList("verify"))), OrchestratorSettings.defaults());

        executor.runReady(run, new AtomicBoolean(), () -> true);

        Map<String, ResearchTask> tasks = new HashMap<String, ResearchTask>();
        for (ResearchTask task : board.tasks()) {
            tasks.put(run.plannedIdOf(task.getTaskId()), task);
            assertEquals(task.getTaskId() + ": " + task.getTransitions(), TaskState.COMPLETED, task.getState());
        }
        assertEquals("the web package searched the planned queries", Arrays.asList("grant rate heat pump",
                "kfw portal application"), searched);
        assertEquals("the verifier read exactly the web package's sources", "src-a,src-c,src-b",
                lane.calls.get(0).get("source_ids"));
        assertEquals("the writer left the rejected source out", "src-a,src-b", lane.calls.get(1).get("source_ids"));
        TaskArtifact draft = null;
        for (TaskArtifact artifact : tasks.get("write").getArtifacts()) {
            if (artifact.getProjectRef().equals("draft:question")) {
                draft = artifact;
            }
        }
        assertTrue(draft.getContent().endsWith("[src-a, src-b]"));
        assertEquals("the planner's tokens are booked on its own task", 5L,
                tasks.get("plan").getBudget().getTokensUsed());
        EvidenceSnapshot evidence = new EvidenceMeter(1, 1, 1).measure(run, board.tasks());
        assertEquals("the off-topic source does not count", 2, evidence.getDistinctSources());
    }

    @Test
    public void theSettingsDecideWhichRolesTheOrchestratorCanDelegateTo() {
        Map<String, String> env = new HashMap<String, String>();
        env.put(WorkerRoleSettings.ENV_SEARCH_PLANNER, "off");
        env.put(WorkerRoleSettings.ENV_WRITER, "no");
        env.put(WorkerRoleSettings.ENV_VERIFIER_MAX_SOURCES, "-3");
        WorkerRoleSettings settings = WorkerRoleSettings.from(env);
        assertFalse(settings.isSearchPlanner());
        assertTrue(settings.isSourceVerifier());
        assertFalse(settings.isWriter());
        assertEquals(WorkerRoleSettings.DEFAULT_VERIFIER_MAX_SOURCES, settings.getVerifierMaxSources());

        List<String> roles = new ArrayList<String>();
        for (TaskWorker worker : OrchestratorSession.workers(null, null, OrchestratorSettings.defaults(), settings, 0)) {
            roles.add(worker.role().getId());
        }
        assertEquals(Arrays.asList("web-researcher", "source-verifier"), roles);

        roles.clear();
        for (TaskWorker worker : OrchestratorSession.workers(null, null, OrchestratorSettings.defaults(),
                WorkerRoleSettings.defaults(), 0)) {
            roles.add(worker.role().getId());
            assertEquals("only the web researcher needs the browser",
                    worker.role().getId().equals("web-researcher"), worker.needsBrowser());
        }
        assertEquals(Arrays.asList("web-researcher", "search-planner", "source-verifier", "writer"), roles);
    }

    private static WebSearchApplicationService.AcceptedSource source(String id, String url, String title) {
        return new WebSearchApplicationService.AcceptedSource(id, false, "cap-" + id, url, "", title, "");
    }
}
