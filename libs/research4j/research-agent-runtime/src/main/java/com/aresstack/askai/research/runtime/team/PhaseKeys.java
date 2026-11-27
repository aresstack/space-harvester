package com.aresstack.askai.research.runtime.team;

import java.util.Locale;

/** The phase key a history entry is tagged with: the host's phase id, with "" meaning Concept. */
final class PhaseKeys {

    private PhaseKeys() {
    }

    static String normalize(String phaseId) {
        String trimmed = phaseId == null ? "" : phaseId.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? PhaseAssistantProfileRegistry.SCOPING_PHASE_ID : trimmed;
    }

    static boolean isConcept(String phaseId) {
        return PhaseAssistantProfileRegistry.SCOPING_PHASE_ID.equals(normalize(phaseId));
    }
}
