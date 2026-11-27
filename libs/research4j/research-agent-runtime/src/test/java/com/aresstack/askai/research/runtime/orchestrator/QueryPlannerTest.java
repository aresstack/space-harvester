package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan PR 13: the concept query planner with a fake model. */
public class QueryPlannerTest {

    /** Answers from a script, one per call; records the prompts and budgets it was given. */
    static final class ScriptedChat implements MainModelChat {
        final LinkedList<MainModelChatResult> answers = new LinkedList<MainModelChatResult>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();
        final List<Integer> budgets = new ArrayList<Integer>();

        ScriptedChat answer(String json) {
            answers.add(MainModelChatResult.ok(json));
            return this;
        }

        ScriptedChat answer(MainModelChatResult result) {
            answers.add(result);
            return this;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            calls.add(new ArrayList<ChatMessage>(messages));
            budgets.add(maxOutputTokens);
            if (answers.isEmpty()) {
                throw new IllegalStateException("no scripted answer left");
            }
            return answers.removeFirst();
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    private static ConceptQueryPlan.Node node(String id, String label, String base) {
        return new ConceptQueryPlan.Node(id, label, Arrays.asList("Java-Plugins", label), "root", base);
    }

    private static ConceptQueryPlan.Request request(String language, int max, ConceptQueryPlan.Node... nodes) {
        return new ConceptQueryPlan.Request(language, "Java-Plugins isolieren", Arrays.asList(nodes),
                Collections.singletonList("java classloader"), Collections.singletonList("Spring"), max);
    }

    @Test
    public void theRoleRunsOnTheSourcesPlannerSlotWithThinkingOff() {
        assertEquals(ResearchModelSlots.PHASE_RESEARCH_PLANNER, QueryPlanner.CONCEPT_ROLE.getSlot());
        assertEquals(ThinkingMode.OFF, QueryPlanner.CONCEPT_ROLE.getThinking());
        assertEquals(256, QueryPlanner.CONCEPT_ANSWER_TOKENS);
    }

    @Test
    public void everyNodeGetsItsBaseQueryPlusAtMostTheMaximumOfValidVariants() {
        ScriptedChat chat = new ScriptedChat()
                .answer("{\"queries\":[\"Classloader Hierarchie Plugins\",\"ab\","
                        + "\"Spring Boot Plugin Classloader\",\"classloader hierarchie   plugins\","
                        + "\"Java Classloader\",\"Plugin Isolation Classloader Leck\",\"OSGi Bundle Classloader\"]}")
                .answer("{\"queries\":[\"JPMS Module Layer Plugins\"]}");
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("de", 2,
                node("n1", "Classloader", "Java Classloader Plugins"),
                node("n2", "Modulsystem", "Java Modulsystem")));

        assertEquals(2, result.getNodes().size());
        ConceptQueryPlan.NodePlan first = result.getNodes().get(0);
        assertEquals("n1", first.getNodeId());
        assertEquals(Arrays.asList("Java Classloader Plugins", "Classloader Hierarchie Plugins",
                "Plugin Isolation Classloader Leck"), first.queries());
        assertEquals("", first.getNote());
        assertEquals(Arrays.asList("Java Modulsystem", "JPMS Module Layer Plugins"),
                result.getNodes().get(1).queries());
        assertEquals(Arrays.asList(256, 256), chat.budgets);
    }

    @Test
    public void anInvalidAnswerIsRepairedOnceThenTheBaseQueryStaysAlone() {
        ScriptedChat chat = new ScriptedChat().answer("not json").answer("{\"queries\":[\"Spring Plugins\"]}");
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("de", 3,
                node("n1", "Classloader", "Java Classloader Plugins")));

        ConceptQueryPlan.NodePlan plan = result.getNodes().get(0);
        assertEquals(Collections.singletonList("Java Classloader Plugins"), plan.queries());
        assertTrue(plan.getNote(), plan.getNote().contains("blacklisted"));
        assertEquals(2, chat.calls.size());
        assertTrue(chat.calls.get(1).get(3).getContent().startsWith("Your answer was rejected"));
    }

    @Test
    public void aFailedOrThrowingModelKeepsTheBaseQueryAndNeverThrows() {
        ScriptedChat chat = new ScriptedChat()
                .answer(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "offline"));
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("en", 2,
                node("n1", "Class loading", "java class loading"),
                node("n2", "Modules", "java module system")));

        assertEquals(Collections.singletonList("java class loading"), result.getNodes().get(0).queries());
        assertTrue(result.getNodes().get(0).getNote().contains("offline"));
        assertEquals(Collections.singletonList("java module system"), result.getNodes().get(1).queries());
        assertTrue(result.getNodes().get(1).getNote().contains("planner failed"));
    }

    @Test
    public void aVariantNeverDuplicatesALaterNodesBaseQuery() {
        ScriptedChat chat = new ScriptedChat()
                .answer("{\"queries\":[\"Java Modulsystem\",\"Classloader Hierarchie Plugins\"]}")
                .answer("{\"queries\":[\"JPMS Module Layer Plugins\"]}");
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("de", 2,
                node("n1", "Classloader", "Java Classloader Plugins"),
                node("n2", "Modulsystem", "Java Modulsystem")));
        assertEquals(Arrays.asList("Java Classloader Plugins", "Classloader Hierarchie Plugins"),
                result.getNodes().get(0).queries());
    }

    @Test
    public void afterCancellationNoFurtherModelCallStarts() {
        final ScriptedChat chat = new ScriptedChat().answer("{\"queries\":[\"Classloader Hierarchie Plugins\"]}");
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("de", 2,
                        node("n1", "Classloader", "Java Classloader Plugins"),
                        node("n2", "Modulsystem", "Java Modulsystem")),
                () -> !chat.calls.isEmpty());
        assertEquals(1, chat.calls.size());
        assertEquals(Collections.singletonList("Java Modulsystem"), result.getNodes().get(1).queries());
        assertTrue(result.getNodes().get(1).getNote().contains("stopped"));
    }

    @Test
    public void theNodeLabelStandsInForAnUnusableBaseQuery() {
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(null, request("de", 2,
                node("n1", "Classloader", "")));
        assertEquals(Collections.singletonList("Classloader"), result.getNodes().get(0).queries());
    }

    @Test
    public void thePromptAsksForTheRequestedLanguageAndCarriesTheContext() {
        ScriptedChat chat = new ScriptedChat().answer("{\"queries\":[\"Classloader Hierarchie Plugins\"]}");
        QueryPlanner.planConcept(chat, request("de", 2, node("n1", "Classloader", "Java Classloader Plugins")));

        assertTrue(chat.calls.get(0).get(0).getContent().contains("LANGUAGE"));
        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue(prompt, prompt.startsWith("LANGUAGE\nGerman"));
        assertTrue(prompt, prompt.contains("in German."));
        assertTrue(prompt, prompt.contains("Java-Plugins isolieren"));
        assertTrue(prompt, prompt.contains("Java-Plugins > Classloader"));
        assertTrue(prompt, prompt.contains("- java classloader"));
        assertTrue(prompt, prompt.contains("BLACKLIST\n- Spring"));
        assertTrue(QueryPlanner.conceptPrompt(request("en", 1), node("n", "x", "abc"), "abc",
                Collections.<String>emptyList()).startsWith("LANGUAGE\nEnglish"));
        assertFalse(prompt, prompt.contains("RESEARCH CYCLE"));
    }

    @Test
    public void aLaterCyclePromptNamesTheCycleTheDomainsFoundAndAsksForAnotherAngle() {
        ConceptQueryPlan.Node node = new ConceptQueryPlan.Node("n1", "Sensorarten",
                Arrays.asList("Wearables", "Sensorarten"), "root", "Sensorarten Wearables Baustelle",
                Arrays.asList("amazon.de", "thalia.de", "beck-shop.de"));
        ConceptQueryPlan.Request request = new ConceptQueryPlan.Request("de", "Wearables am Bau",
                Collections.singletonList(node), Arrays.asList("Sensorarten Wearables Baustelle",
                        "Wearables Bau Sensoren Arten"), Collections.<String>emptyList(), 3, 2);
        String prompt = QueryPlanner.conceptPrompt(request, node, "Sensorarten Wearables Baustelle",
                request.getQueriesRun());
        assertTrue(prompt, prompt.contains("RESEARCH CYCLE\n2"));
        assertTrue(prompt, prompt.contains("DOMAINS FOUND SO FAR\n- amazon.de\n- thalia.de\n- beck-shop.de"));
        assertTrue(prompt, prompt.contains("ALREADY SEARCHED\n- Sensorarten Wearables Baustelle"));
        assertTrue(prompt, prompt.endsWith("Take another angle than the queries already searched: other publishers "
                + "of evidence, other kinds of document, other sub-aspects."));
        assertTrue(QueryPlanner.CONCEPT_SYSTEM.contains("RESEARCH CYCLE is above 1"));
        assertTrue(QueryPlanner.CONCEPT_SYSTEM.contains("DOMAINS FOUND SO FAR"));
    }

    @Test
    public void theWeidezaunReachesThePlannerPromptAndCrossesTheWire() {
        ConceptQueryPlan.Node node = new ConceptQueryPlan.Node("n1", "Hitze", Arrays.asList("Hitze"), "", "Hitze Bau");
        ConceptQueryPlan.Request request = new ConceptQueryPlan.Request("de", "Wearables am Bau",
                Collections.singletonList(node), Collections.<String>emptyList(), Collections.<String>emptyList(), 2)
                .withArea("IN: Hitzeschutz am Bau\nOUT: Freizeitsport");
        String prompt = QueryPlanner.conceptPrompt(request, node, "Hitze Bau", request.getQueriesRun());
        assertTrue(prompt, prompt.contains("AREA OF THE BOOK\nIN: Hitzeschutz am Bau\nOUT: Freizeitsport"));
        assertTrue(QueryPlanner.CONCEPT_SYSTEM.contains("AREA OF THE BOOK"));
        assertEquals(request.getArea(), com.aresstack.askai.research.runtime.plan.QueryPlanWire.parseRequest(
                com.aresstack.askai.research.plan.QueryPlanWireCodec.encodeRequest(request)).getArea());
        assertTrue(!QueryPlanner.conceptPrompt(request.withArea(""), node, "Hitze Bau", request.getQueriesRun())
                .contains("AREA OF THE BOOK"));
    }

    @Test
    public void aLaterCyclePromptCarriesWhatThePageWorkersReported() {
        ConceptQueryPlan.Node node = new ConceptQueryPlan.Node("n1", "Hitze", Arrays.asList("Wearables", "Hitze"),
                "root", "Hitze Wearables Baustelle", Arrays.asList("a.example"),
                Arrays.asList("worker 1: search for DGUV heat guidelines"));
        ConceptQueryPlan.Request request = new ConceptQueryPlan.Request("de", "Wearables am Bau",
                Collections.singletonList(node), Collections.singletonList("Hitze Wearables Baustelle"),
                Collections.<String>emptyList(), 3, 2);
        String prompt = QueryPlanner.conceptPrompt(request, node, "Hitze Wearables Baustelle",
                request.getQueriesRun());
        assertTrue(prompt, prompt.contains("WHAT THE PAGE WORKERS REPORTED\n- worker 1: search for DGUV heat guidelines"));
        assertTrue(QueryPlanner.CONCEPT_SYSTEM.contains("WHAT THE PAGE WORKERS REPORTED"));
        ConceptQueryPlan.Node without = new ConceptQueryPlan.Node("n2", "Kosten", Arrays.asList("Kosten"), "root",
                "Kosten", null);
        assertTrue(!QueryPlanner.conceptPrompt(request, without, "Kosten", request.getQueriesRun())
                .contains("WHAT THE PAGE WORKERS REPORTED"));
        assertEquals(node.getWorkerNotes(), com.aresstack.askai.research.runtime.plan.QueryPlanWire.parseRequest(
                com.aresstack.askai.research.runtime.plan.QueryPlanWire.renderRequest(request)).getNodes().get(0)
                .getWorkerNotes());
    }

    @Test
    public void theGapsOfTheEvidenceAnalysisReachThePromptAndCrossTheWire() {
        ConceptQueryPlan.Node node = new ConceptQueryPlan.Node("n1", "Hitze", Arrays.asList("Hitze"), "", "Hitze Bau")
                .withEvidence(Arrays.asList("THIN: 2 distinct passages from 1 sources", "found nothing: Hitze Norm"));
        ConceptQueryPlan.Request request = new ConceptQueryPlan.Request("de", "Wearables am Bau",
                Collections.singletonList(node), Collections.<String>emptyList(), Collections.<String>emptyList(), 2, 2)
                .withNewTopics(Collections.singletonList("Exoskelette: Heben, Rücken"))
                .withArea("IN: Arbeitsschutz");
        String prompt = QueryPlanner.conceptPrompt(request, node, "Hitze Bau", request.getQueriesRun());
        assertTrue(prompt, prompt.contains("WHAT THE MATERIAL SHOWS\n- THIN: 2 distinct passages from 1 sources\n"
                + "- found nothing: Hitze Norm"));
        assertTrue(prompt, prompt.contains("NEW TOPICS IN THE MATERIAL\n- Exoskelette: Heben, Rücken"));
        assertTrue(QueryPlanner.CONCEPT_SYSTEM.contains("WHAT THE MATERIAL SHOWS"));
        ConceptQueryPlan.Request crossed = com.aresstack.askai.research.runtime.plan.QueryPlanWire.parseRequest(
                com.aresstack.askai.research.plan.QueryPlanWireCodec.encodeRequest(request));
        assertEquals(node.getEvidence(), crossed.getNodes().get(0).getEvidence());
        assertEquals(request.getNewTopics(), crossed.getNewTopics());
        assertEquals("IN: Arbeitsschutz", crossed.getArea());
        ConceptQueryPlan.Node plain = new ConceptQueryPlan.Node("n2", "Kosten", Arrays.asList("Kosten"), "", "Kosten");
        assertTrue(!QueryPlanner.conceptPrompt(request.withNewTopics(null), plain, "Kosten",
                request.getQueriesRun()).contains("WHAT THE MATERIAL SHOWS"));
    }

    @Test
    public void theMissionsPreferredSourcesReachThePromptAndCrossTheWire() {
        ConceptQueryPlan.Node node = new ConceptQueryPlan.Node("n1", "Hitze", Arrays.asList("Hitze"), "", "Hitze Bau");
        ConceptQueryPlan.Request request = new ConceptQueryPlan.Request("de", "Wearables am Bau",
                Collections.singletonList(node), Collections.<String>emptyList(), Collections.<String>emptyList(), 2, 2)
                .withPreferredSources(Arrays.asList("DGUV", "BAuA", "wissenschaftliche Studien"))
                .withArea("IN: Arbeitsschutz");
        String prompt = QueryPlanner.conceptPrompt(request, node, "Hitze Bau", request.getQueriesRun());
        assertTrue(prompt, prompt.contains("PREFERRED SOURCES\n- DGUV\n- BAuA\n- wissenschaftliche Studien"));
        assertTrue(QueryPlanner.CONCEPT_SYSTEM.contains("PREFERRED SOURCES"));
        ConceptQueryPlan.Request crossed = com.aresstack.askai.research.runtime.plan.QueryPlanWire.parseRequest(
                com.aresstack.askai.research.plan.QueryPlanWireCodec.encodeRequest(request));
        assertEquals(request.getPreferredSources(), crossed.getPreferredSources());
        assertEquals(request.getPreferredSources(), crossed.withNewTopics(null).getPreferredSources());
        assertTrue(!QueryPlanner.conceptPrompt(request.withPreferredSources(null), node, "Hitze Bau",
                request.getQueriesRun()).contains("PREFERRED SOURCES\n"));
    }
}
