package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The action union of a phase profile (plan D11, PR 16): the tools the host's {@code ResearchToolPolicy} offers
 * right now, limited to the in-app assistant column of the phase. {@code source_accept} and {@code source_park}
 * are no assistant action in any phase: they stay with the {@code research:} loop, which reads the policy
 * itself. Phase-1 concept editing (the concept edit rules and the concept move path) exists only in Concept.
 */
public final class PhaseActionUnion {

    /** Concept: today's set; the legacy {@code concept_add} is gone. */
    static final List<String> CONCEPT = Collections.unmodifiableList(Arrays.asList(
            "concept_add_cards", "concept_rename", "concept_move_leaf", "exclude_topic",
            "resolve_concept_conflict", "offer_searches", "scope_probe"));
    /** Sources: the assistant only adds plan entries and blacklist entries (PR 14, PR 15). */
    static final List<String> SOURCES = Collections.unmodifiableList(Arrays.asList(
            "request_research", "blacklist_add"));
    /** Outline: no assistant action before PR 25. */
    static final List<String> OUTLINE = Collections.<String>emptyList();
    /** Document: one section rewrite with a comment (PR 29). */
    static final List<String> DOCUMENT = Collections.unmodifiableList(Arrays.asList("section_rewrite"));

    private PhaseActionUnion() {
    }

    /** Whether {@code phaseId} is Concept ({@code scoping}; a blank id is the session's opening phase). */
    public static boolean isConcept(String phaseId) {
        return phaseId == null || phaseId.trim().isEmpty()
                || PhaseAssistantProfileRegistry.SCOPING_PHASE_ID.equalsIgnoreCase(phaseId.trim());
    }

    /** Whether the concept edit rules and the concept move path apply in {@code phaseId}: only in Concept. */
    public static boolean conceptEditing(String phaseId) {
        return isConcept(phaseId);
    }

    /** The assistant column of D11 for a phase (empty for an unknown phase). */
    public static List<String> column(String phaseId) {
        if (isConcept(phaseId)) {
            return CONCEPT;
        }
        String phase = phaseId.trim().toLowerCase(Locale.ROOT);
        if (PhaseAssistantProfileRegistry.RESEARCH_PHASE_ID.equals(phase)) {
            return SOURCES;
        }
        if ("outline".equals(phase)) {
            return OUTLINE;
        }
        if (PhaseAssistantProfileRegistry.DRAFT_PHASE_ID.equals(phase)
                || PhaseAssistantProfileRegistry.FINALIZATION_PHASE_ID.equals(phase)) {
            return DOCUMENT;
        }
        return Collections.emptyList();
    }

    /** The live offered tools limited to the phase's assistant column, in column order. */
    public static List<String> forPhase(String phaseId, Collection<String> offeredTools) {
        Set<String> offered = new LinkedHashSet<String>();
        if (offeredTools != null) {
            for (String tool : offeredTools) {
                if (tool != null) {
                    offered.add(tool.trim());
                }
            }
        }
        List<String> union = new ArrayList<String>();
        for (String action : column(phaseId)) {
            if (offered.contains(action)) {
                union.add(action);
            }
        }
        return Collections.unmodifiableList(union);
    }
}
