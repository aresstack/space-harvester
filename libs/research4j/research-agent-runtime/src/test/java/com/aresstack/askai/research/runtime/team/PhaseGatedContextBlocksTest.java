package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan PR 16: the Phase-1 machinery (scope fence with the conversation policy, current concept with its edit
 * rules) is sent only in Concept; every later phase gets the read-only frozen concept summary and the artifact
 * block of its own phase with the latest change request.
 */
public class PhaseGatedContextBlocksTest {

    private static final String FENCE = "FENCE-FACETS\nCONVERSATION POLICY (guidance): ask one boundary question";
    private static final String CONCEPT = TeamAgentPlaybook.currentConceptContext(
            "revision=4\n{\"concept\":[\"Class loaders\"]}", false);
    private static final String SUMMARY = "Approved concept revision 4\nMission: Plugin isolation\n"
            + "Cards:\n- Class loaders\nRecorded blacklist: OSGi\n";
    private static final String CHANGE_REQUEST = "Cover the JPMS layers too";

    private int liveConceptReads;
    private int fenceReads;

    private PhaseContextAssembler assembler() {
        return new PhaseContextAssembler(new PhaseContextAssembler.CurrentDate() {
            public String today() {
                return "2026-11-08";
            }
        }).withCurrentScope(new PhaseContextAssembler.CurrentScope() {
            public String rendered() {
                fenceReads++;
                return FENCE;
            }
        }).withCurrentConcept(new PhaseContextAssembler.CurrentConcept() {
            public String rendered() {
                liveConceptReads++;
                return CONCEPT;
            }
        }).withCurrentConceptSummary(new PhaseContextAssembler.CurrentConceptSummary() {
            public String rendered() {
                return SUMMARY;
            }
        }).withCurrentChangeRequest(new PhaseContextAssembler.CurrentChangeRequest() {
            public String forPhase(String phaseId) {
                return CHANGE_REQUEST;
            }
        });
    }

    private PromptBlockMeter meter;

    private List<ChatMessage> assemble(String phaseId) {
        meter = new PromptBlockMeter();
        TeamAgentStateView state = new TeamAgentStateView(phaseId, "running", Collections.<String>emptyList());
        PhaseAssistantProfile profile = PhaseAssistantProfileRegistry.defaults(false, true).forPhase(phaseId);
        return assembler().assemble(profile, state, Collections.singletonList(
                        ChatMessage.user("hello").tagged(phaseId, ChatMessage.Kind.USER)), meter);
    }

    private static String text(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(message.getContent()).append("\n---\n");
        }
        return sb.toString();
    }

    @Test
    public void snapshotOfTheBlocksSentPerPhase() {
        assemble("scoping");
        assertEquals(Arrays.asList("profile", "date", "language", "state", "fence", "concept", "history"),
                new ArrayList<String>(meter.blocks().keySet()));
        for (String later : new String[] {"research", "outline", "draft", "finalization"}) {
            assemble(later);
            assertEquals(later, Arrays.asList("profile", "date", "language", "state", "concept-summary",
                    "phase-artifact", "history"), new ArrayList<String>(meter.blocks().keySet()));
        }
    }

    @Test
    public void aDocumentPromptContainsNoFenceAndNoConceptEditRules() {
        String prompt = text(assemble("draft"));
        assertFalse(prompt.contains("FENCE-FACETS"));
        assertFalse(prompt.contains("AUTHORITATIVE research scope"));
        assertFalse(prompt.contains("CONVERSATION POLICY"));
        assertFalse(prompt.contains("CURRENT_CONCEPT"));
        assertFalse(prompt.contains("conceptAction"));
        assertFalse(prompt.contains("concept action"));
        assertEquals("the live concept is not even read", 0, liveConceptReads);
        assertEquals("the fence is not even read", 0, fenceReads);
        assertTrue(prompt.contains("APPROVED CONCEPT (read-only, frozen by the user)"));
        assertTrue(prompt.contains("Recorded blacklist: OSGi"));
        assertTrue(prompt.contains("DOCUMENT (current phase)"));
        assertTrue(prompt.contains("Latest change request from the user: " + CHANGE_REQUEST));
    }

    @Test
    public void conceptStillGetsFenceAndLiveConceptButNoSummary() {
        String prompt = text(assemble("scoping"));
        assertTrue(prompt.contains("FENCE-FACETS"));
        assertTrue(prompt.contains("CURRENT_CONCEPT"));
        assertFalse(prompt.contains("APPROVED CONCEPT"));
        assertFalse(prompt.contains(CHANGE_REQUEST));
    }

    @Test
    public void aLaterPhaseWithoutChangeRequestOrArtifactSendsNoEmptyArtifactBlock() {
        assertEquals("", TeamAgentPlaybook.phaseArtifactContext("research", "", " "));
        assertEquals("SOURCES (current phase)\nPlan: 3 entries\n",
                TeamAgentPlaybook.phaseArtifactContext("research", "Plan: 3 entries", ""));
    }

    @Test
    public void moveThisParagraphInDocumentDoesNotStartTheConceptMovePath() {
        String text = "move this paragraph to chapter 2";
        assertEquals("the words alone would arm the concept move path",
                ConceptTurnPolicy.Mode.MOVE_TRUTH, ConceptTurnPolicy.modeFor(text));
        assertFalse(PhaseActionUnion.conceptEditing("draft"));
        assertFalse(PhaseActionUnion.conceptEditing("research"));
        assertFalse(PhaseActionUnion.conceptEditing("outline"));
        assertTrue(PhaseActionUnion.conceptEditing("scoping"));
    }

    @Test
    public void noActionUnionOutsideTheResearchLoopContainsSourceAccept() {
        List<String> offered = Arrays.asList("research_status", "source_list", "source_accept", "source_park",
                "concept_read", "concept_add", "concept_add_cards", "concept_rename", "concept_move_leaf",
                "exclude_topic", "resolve_concept_conflict", "offer_searches", "scope_probe",
                "request_research", "blacklist_add", "document_read", "document_save", "section_rewrite");
        for (String phase : new String[] {"scoping", "research", "outline", "draft", "finalization", ""}) {
            List<String> union = PhaseActionUnion.forPhase(phase, offered);
            assertFalse(phase, union.contains("source_accept"));
            assertFalse(phase, union.contains("source_park"));
            assertFalse(phase + ": the legacy concept_add is gone", union.contains("concept_add"));
        }
        assertEquals(Arrays.asList("request_research", "blacklist_add"),
                PhaseActionUnion.forPhase("research", offered));
        assertEquals("the live policy limits the union: Sources/waiting offers no write",
                Collections.<String>emptyList(),
                PhaseActionUnion.forPhase("research", Arrays.asList("research_status", "source_list")));
        assertEquals(Collections.singletonList("section_rewrite"), PhaseActionUnion.forPhase("draft", offered));
        assertTrue(PhaseActionUnion.forPhase("outline", offered).isEmpty());
    }
}
