package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The live regression behind "Die neuen Quellen wurden übernommen; die Auswertung konnte diesmal nicht
 * erstellt werden": the post-search review is the LONGEST contracted answer (summary of up to 12 sources
 * plus brief/suggestions/scope patch in ONE JSON), and a 1024-token output budget truncated it
 * mid-string — unparseable, the repair truncated identically, every review ended UNUSABLE_ANSWER. This
 * pins the granted output budget and the truncation-diagnosable trace excerpt.
 */
public class TeamAgentOutputBudgetTest {

    /** Captures the output budget the agent actually grants the model. */
    private static final class BudgetCapturingModel implements MainModelChat {
        final List<Integer> grantedBudgets = new ArrayList<Integer>();

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            grantedBudgets.add(maxOutputTokens);
            return MainModelChatResult.ok("{\"assistantMessage\":\"Zusammenfassung der Quellen.\"}");
        }

        public String modelName() {
            return "gemma4:e2b";
        }
    }

    private static TeamAgentStateView in(String phase) {
        return new TeamAgentStateView(phase, "running", Arrays.asList("CANCEL"));
    }

    /** The budgets one step granted (a parse repair repeats the call with the same budget). */
    private static List<Integer> budgetsOf(BudgetCapturingModel model, Runnable step) {
        int before = model.grantedBudgets.size();
        step.run();
        return new ArrayList<Integer>(model.grantedBudgets.subList(before, model.grantedBudgets.size()));
    }

    private static void assertAll(String what, int expected, List<Integer> budgets) {
        assertTrue(what + ": at least one call", !budgets.isEmpty());
        for (Integer budget : budgets) {
            assertEquals(what, Integer.valueOf(expected), budget);
        }
    }

    @Test
    public void theReviewGetsItsOwnBudgetPerPhase() {
        final BudgetCapturingModel model = new BudgetCapturingModel();
        final ResearchTeamAgent agent = new ResearchTeamAgent(model);
        assertAll("Concept review: summary and suggestions", 4096, budgetsOf(model,
                () -> agent.reviewTurn(TeamAgentPlaybook.sourceReviewInstruction(), in("scoping"))));
        assertAll("Sources review: summary only", 3072, budgetsOf(model,
                () -> agent.reviewTurn(TeamAgentPlaybook.sourceReviewInstruction(), in("research"))));
    }

    @Test
    public void chatTurnsAndToolRoundsTakeTheBudgetOfTheirPhase() {
        final BudgetCapturingModel model = new BudgetCapturingModel();
        final ResearchTeamAgent agent = new ResearchTeamAgent(model);
        assertAll("Concept chat turn", 4096, budgetsOf(model, () -> agent.respond("Hallo", in("scoping"))));
        assertAll("a tool round belongs to the Concept turn", 4096,
                budgetsOf(model, () -> agent.internalTurn("tool feedback", in("scoping"))));
        assertAll("Sources chat turn", 1536, budgetsOf(model, () -> agent.respond("Weiter", in("research"))));
    }

    @Test
    public void theParseFailureExcerptShowsHeadAndTailSoTruncationIsVisible() {
        StringBuilder longAnswer = new StringBuilder("{\"assistantMessage\":\"");
        for (int i = 0; i < 200; i++) {
            longAnswer.append("wort").append(i).append(' ');
        }
        // No closing brace/quote — the shape of an output-budget truncation.
        String excerpt = ResearchTeamAgent.excerptOf(longAnswer.toString());
        assertTrue("the head is visible", excerpt.startsWith("{\"assistantMessage\":"));
        assertTrue("the TAIL is visible — that is where a truncation shows",
                excerpt.endsWith(longAnswer.substring(longAnswer.length() - 300).replace('\n', '⏎')));
        assertTrue("the total length is named", excerpt.contains("chars]"));
    }
}
