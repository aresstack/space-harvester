package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The post-manual-search review contract: the SUMMARY is phase-independent (a search during RESEARCH
 * gets a visible review too), only scoping additionally refreshes its search suggestions, and a
 * failed model turn is acknowledged VISIBLY (the sources are saved either way) — never silent.
 */
public class PostSearchReviewTest {

    private static final class RecordingModel implements PostSearchReview.Model {
        String instruction;
        TeamAgentResult result;

        RecordingModel(TeamAgentResult result) {
            this.result = result;
        }

        public TeamAgentResult internalTurn(String instruction, TeamAgentStateView view) {
            this.instruction = instruction;
            return result;
        }
    }

    private static final class RecordingEmitter implements PostSearchReview.Emitter {
        final List<String> resultPhases = new ArrayList<String>();
        final List<String> visible = new ArrayList<String>();
        final List<String> logs = new ArrayList<String>();

        public void emitResult(TeamAgentResult result, String phaseId) {
            resultPhases.add(phaseId);
        }

        public void emitVisible(String message) {
            visible.add(message);
        }

        public void emitLog(String line) {
            logs.add(line);
        }
    }

    private static TeamAgentStateView view(String phaseId) {
        return new TeamAgentStateView(phaseId, "running", Collections.<String>emptyList());
    }

    private static TeamAgentResult ok() {
        return TeamAgentResult.ok(new PhaseAssistantOutput() {
            public String getAssistantMessage() {
                return "summary";
            }

            public String canonicalJson() {
                return "{}";
            }
        }, null);
    }

    @Test
    public void scopingUsesTheSuggestionRefreshInstruction() {
        RecordingModel model = new RecordingModel(ok());
        RecordingEmitter emitter = new RecordingEmitter();
        PostSearchReview.run(view("scoping"), model, emitter, "de");

        assertEquals(TeamAgentPlaybook.sourceReviewInstruction(), model.instruction);
        assertEquals(Collections.singletonList("scoping"), emitter.resultPhases);
        assertTrue(emitter.visible.isEmpty());
        assertTrue(emitter.logs.isEmpty());
    }

    @Test
    public void researchGetsAPhaseIndependentSummaryWithoutSuggestionRefresh() {
        RecordingModel model = new RecordingModel(ok());
        RecordingEmitter emitter = new RecordingEmitter();
        PostSearchReview.run(view("research"), model, emitter, "de");

        assertEquals("the review must run outside scoping too",
                Collections.singletonList("research"), emitter.resultPhases);
        assertEquals(TeamAgentPlaybook.sourceSummaryInstruction(), model.instruction);
        assertFalse("the summary instruction must not refresh scoping suggestions",
                model.instruction.contains("REFRESH your search suggestions"));
    }

    @Test
    public void aFailedModelTurnIsVisiblyAcknowledgedNeverSilent() {
        RecordingModel model = new RecordingModel(TeamAgentResult.modelUnavailable("timeout"));
        RecordingEmitter emitter = new RecordingEmitter();
        PostSearchReview.run(view("research"), model, emitter, "de");

        assertTrue(emitter.resultPhases.isEmpty());
        assertEquals("one neutral visible acknowledgement", 1, emitter.visible.size());
        assertTrue(emitter.visible.get(0).contains("Auswertung"));
        assertEquals(1, emitter.logs.size());
        assertTrue(emitter.logs.get(0).contains("MODEL_UNAVAILABLE"));
        assertTrue(emitter.logs.get(0).contains("timeout"));
    }

    @Test
    public void theFallbackFollowsTheSessionLanguage() {
        assertTrue(PostSearchReview.fallbackMessage("de").contains("Quellen"));
        assertTrue(PostSearchReview.fallbackMessage("en").contains("sources"));
        assertTrue("unknown language falls back to English",
                PostSearchReview.fallbackMessage("").contains("sources"));
    }
}
