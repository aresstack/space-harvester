package com.aresstack.askai.research.runtime.team;

/**
 * A phase's structured-output semantics: how the raw model text for THIS phase is parsed and validated into a
 * {@link PhaseAssistantOutput}. Selected together with the system prompt by the phase profile (RA-P6 §6), so
 * "active phase → prompt AND parser" is one decision at one point — never a scattered {@code if("scoping")}.
 */
public interface PhaseOutputContract {

    /** Parse+validate one raw model answer for this phase into a typed output, or a typed failure reason. */
    PhaseParseResult parse(String rawModelText);

    /**
     * An optional JSON schema enforced AT GENERATION TIME (Ollama structured outputs) — the
     * deterministic fix for small models hand-rolling broken JSON. {@code null} = plain
     * completion (the long-standing behaviour); strict parsing downstream applies either way.
     */
    default String outputSchemaJson() {
        return null;
    }

    /**
     * The generation-time schema for a machinery turn that must answer with ONE specific action type (a
     * nudge or repair that asks for exactly that action). Contracts without typed actions ignore the type.
     */
    default String outputSchemaJson(String requiredActionType) {
        return outputSchemaJson();
    }
}
