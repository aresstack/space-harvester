package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SearchPlannerWorkerTest {

    private static final List<String> SECTIONS = Collections.singletonList("s1");

    private FakeWorkerContext context() {
        ResearchTask task = FakeWorkerContext.task("run.plan", SearchPlannerWorker.ROLE.getId(),
                "heat pump subsidies germany", SECTIONS);
        return new FakeWorkerContext(task, SearchPlannerWorker.ROLE);
    }

    @Test
    public void theRoleIsANarrowToolFreePlannerOnItsOwnSlot() {
        assertEquals(ResearchModelSlots.PLANNER, SearchPlannerWorker.ROLE.getSlot());
        assertTrue(SearchPlannerWorker.ROLE.getTools().isEmpty());
        assertTrue(SearchPlannerWorker.schema(3).contains("\"maxItems\":3"));
    }

    @Test
    public void plannedQueriesBecomeArtifactsTheWebResearchPackageCanRead() {
        FakeWorkerContext context = context();
        context.chats.answer("{\"queries\":[\"BEG heat pump subsidy 2024 rates\",\"KfW 458 heat pump grant\"]}");

        WorkerResult result = new SearchPlannerWorker(3, 1, 0).execute(context.task, context);

        assertEquals(WorkerResult.Kind.COMPLETED, result.getKind());
        assertEquals(Arrays.asList("BEG heat pump subsidy 2024 rates", "KfW 458 heat pump grant"),
                WorkerArtifacts.plannedQueries(Collections.singletonList(context.recorded)));
        assertTrue("the aspect is in the prompt", context.chats.requests.get(0).get(1).getContent()
                .contains("heat pump subsidies germany"));
    }

    @Test
    public void aDuplicateAnswerIsSentBackWithTheReasonThenAccepted() {
        FakeWorkerContext context = context();
        context.run.add(FakeWorkerContext.completed("run.web1", WebResearchWorker.ROLE.getId(), SECTIONS));
        // The web package already searched "q run.web1"; the first answer repeats it.
        context.chats.answer("{\"queries\":[\"q run.web1\",\"Q  RUN.web1\"]}")
                .answer("{\"queries\":[\"heat pump grant amount 2024\"]}");

        WorkerResult result = new SearchPlannerWorker(3, 1, 0).execute(context.task, context);

        assertEquals(WorkerResult.Kind.COMPLETED, result.getKind());
        assertEquals(2, context.chats.requests.size());
        String repair = context.chats.requests.get(1).get(context.chats.requests.get(1).size() - 1).getContent();
        assertTrue(repair, repair.contains("already searched"));
        assertEquals(Collections.singletonList("heat pump grant amount 2024"),
                WorkerArtifacts.plannedQueries(Collections.singletonList(context.recorded)));
    }

    @Test
    public void aStillInvalidAnswerFailsThePackageVisibly() {
        FakeWorkerContext context = context();
        context.chats.answer("not json").answer("{\"queries\":[]}");

        WorkerResult result = new SearchPlannerWorker(3, 1, 0).execute(context.task, context);

        assertEquals(WorkerResult.Kind.FAILED, result.getKind());
        assertTrue(result.getDetail(), result.getDetail().startsWith("invalid search plan"));
        assertTrue(context.artifacts(TaskArtifact.Kind.TEXT).isEmpty());
    }

    @Test
    public void theReaderKeepsAtMostTheSettingAndRefusesOverlongQueries() throws Exception {
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longQuery.append("word ");
        }
        List<String> queries = SearchPlannerWorker.read("{\"queries\":[\"" + longQuery + "\",\"a b c\",\"d e f\","
                + "\"g h i\"]}", 2, new LinkedHashSet<String>());
        assertEquals(Arrays.asList("a b c", "d e f"), queries);
        try {
            SearchPlannerWorker.read("{\"queries\":\"x\"}", 2, new LinkedHashSet<String>());
            fail("not an array");
        } catch (WorkerAnswerException expected) {
            assertTrue(expected.getMessage().contains("array"));
        }
    }

    /** SEQUENTIAL inside a package chain: the web package searches the planner's queries, not its own. */
    @Test
    public void aWebPackageDependingOnAPlannerSearchesThePlannedQueries() {
        ResearchTask planner = FakeWorkerContext.completed("run.plan", SearchPlannerWorker.ROLE.getId(), SECTIONS,
                FakeWorkerContext.artifact(TaskArtifact.Kind.TEXT, "first planned", WorkerArtifacts.PLANNED_QUERY_REF, 5),
                FakeWorkerContext.artifact(TaskArtifact.Kind.TEXT, "second planned", WorkerArtifacts.PLANNED_QUERY_REF, 6));
        ResearchTask web = FakeWorkerContext.task("run.web", WebResearchWorker.ROLE.getId(), "own query", SECTIONS,
                "run.plan");
        FakeWorkerContext context = new FakeWorkerContext(web, WebResearchWorker.ROLE);
        context.run.add(planner);
        final List<String> searched = new ArrayList<String>();
        WebAcquisition acquisition = new WebAcquisition() {
            public Result acquire(String query, ToolInvoker research,
                                  Consumer<WebSearchApplicationService.AcceptedSource> accepted,
                                  long maxDurationMillis, AtomicLong tokens,
                                  java.util.function.BooleanSupplier exhausted) {
                searched.add(query);
                return new Result(ResearchStopReason.SUFFICIENT_EVIDENCE, "pages 1");
            }
        };

        WorkerResult result = new WebResearchWorker(acquisition, null).execute(web, context);

        assertEquals(Arrays.asList("first planned", "second planned"), searched);
        assertEquals(WorkerResult.Kind.COMPLETED, result.getKind());
        assertTrue(result.getDetail(), result.getDetail().startsWith("2 planned queries"));
    }

    @Test
    public void withoutAPlannerTheWebPackageSearchesItsOwnQueryAsBefore() {
        ResearchTask web = FakeWorkerContext.task("run.web", WebResearchWorker.ROLE.getId(), "own query", SECTIONS);
        FakeWorkerContext context = new FakeWorkerContext(web, WebResearchWorker.ROLE);
        final List<String> searched = new ArrayList<String>();
        WebAcquisition acquisition = new WebAcquisition() {
            public Result acquire(String query, ToolInvoker research,
                                  Consumer<WebSearchApplicationService.AcceptedSource> accepted,
                                  long maxDurationMillis, AtomicLong tokens,
                                  java.util.function.BooleanSupplier exhausted) {
                searched.add(query);
                return new Result(ResearchStopReason.NO_SEMANTIC_MATCHES, "");
            }
        };

        WorkerResult result = new WebResearchWorker(acquisition, null).execute(web, context);

        assertEquals(Collections.singletonList("own query"), searched);
        assertEquals("search run stopped: NO_SEMANTIC_MATCHES, 0 source(s) accepted", result.getDetail());
    }
}
