package com.aresstack.research4j.plan;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.research4j.concept.SearchConcept;
import com.aresstack.research4j.run.ModelAsk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConceptQueryPlannerTest {

    private static final SearchConcept CONCEPT = new SearchConcept(List.of(
            SearchConcept.Node.root("a", "Obstbaumschnitt"),
            SearchConcept.Node.child("b", "Sommerschnitt", "a")));

    private final List<String> prompts = new CopyOnWriteArrayList<>();

    private MainModelChat answering(Function<String, String> answer) {
        return new MainModelChat() {
            @Override
            public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
                String prompt = messages.get(messages.size() - 1).getContent();
                prompts.add(prompt);
                return MainModelChatResult.ok(answer.apply(prompt));
            }

            @Override
            public String modelName() {
                return "test";
            }
        };
    }

    private ConceptQueryPlanner planner(MainModelChat chat) {
        return new ConceptQueryPlanner(new ModelAsk(chat), ConceptQueryPlanner.Settings.defaults(), "de",
                Fence.of("Obstbäume im Hausgarten schneiden"));
    }

    private List<ConceptQueryPlanner.NodeRequest> all() {
        List<ConceptQueryPlanner.NodeRequest> requests = new ArrayList<>();
        for (SearchConcept.Node node : CONCEPT.nodes()) {
            requests.add(new ConceptQueryPlanner.NodeRequest(node, List.of()));
        }
        return requests;
    }

    @Test
    void everyNodeGetsItsBaseQueryAndTheModelsVariants() {
        List<ConceptQueryPlanner.NodePlan> plans = planner(answering(p -> p.contains("NODE\nObstbaumschnitt")
                ? "{\"queries\":[\"Obstbaum schneiden Zeitpunkt\",\"Schnittwerkzeug Obstbaum\"]}"
                : "{\"queries\":[\"Sommerschnitt Apfelbaum\"]}"))
                .plan(CONCEPT, all(), 1, List.of(), 1, CancellationSignal.NEVER);

        assertEquals(List.of("Obstbaumschnitt", "Obstbaum schneiden Zeitpunkt", "Schnittwerkzeug Obstbaum"),
                plans.get(0).queries().stream().map(PlannedQuery::text).toList());
        assertTrue(plans.get(0).queries().get(0).base());
        assertEquals(List.of("Sommerschnitt", "Sommerschnitt Apfelbaum"),
                plans.get(1).queries().stream().map(PlannedQuery::text).toList());
        assertTrue(prompts.get(0).contains("Obstbäume im Hausgarten schneiden"), "the fence bounds every query");
        assertTrue(prompts.stream().anyMatch(p -> p.contains("PATH\nObstbaumschnitt > Sommerschnitt")));
    }

    @Test
    void aQueryAlreadySearchedOrPlannedForAnotherNodeIsNotRepeated() {
        List<ConceptQueryPlanner.NodePlan> plans = planner(answering(p ->
                "{\"queries\":[\"Sommerschnitt\",\"Gemeinsame Anfrage\",\"Obstbaumschnitt Zeitpunkt\"]}"))
                .plan(CONCEPT, all(), 2, List.of("obstbaumschnitt zeitpunkt"), 1, CancellationSignal.NEVER);

        List<String> all = plans.stream().flatMap(p -> p.queries().stream()).map(PlannedQuery::text).toList();
        assertEquals(List.of("Obstbaumschnitt", "Gemeinsame Anfrage", "Sommerschnitt"), all);
        assertTrue(prompts.get(0).contains("RESEARCH CYCLE\n2"));
    }

    @Test
    void anInvalidAnswerGoesBackOnceThenTheBaseQueryStaysAlone() {
        List<ConceptQueryPlanner.NodePlan> plans = planner(answering(p -> "keine Ahnung"))
                .plan(CONCEPT, all().subList(0, 1), 1, List.of(), 1, CancellationSignal.NEVER);

        assertEquals(2, prompts.size(), "one repair");
        assertEquals(List.of("Obstbaumschnitt"), plans.get(0).queries().stream().map(PlannedQuery::text).toList());
        assertTrue(plans.get(0).note().startsWith("base query only"), plans.get(0).note());
    }

    @Test
    void thePlanIsTheSameAtEveryCapacity() {
        Function<String, String> answer = p -> "{\"queries\":[\"Obstbaum Pflege Leitfaden\",\"Schnitt "
                + (p.contains("NODE\nObstbaumschnitt") ? "Winter" : "Sommer") + "\"]}";
        List<ConceptQueryPlanner.NodePlan> one = planner(answering(answer))
                .plan(CONCEPT, all(), 1, List.of(), 1, CancellationSignal.NEVER);
        List<ConceptQueryPlanner.NodePlan> many = planner(answering(answer))
                .plan(CONCEPT, all(), 1, List.of(), 8, CancellationSignal.NEVER);

        assertEquals(one, many);
    }
}
