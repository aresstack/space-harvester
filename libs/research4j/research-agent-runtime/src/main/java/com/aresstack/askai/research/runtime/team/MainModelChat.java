package com.aresstack.askai.research.runtime.team;

import java.util.List;

/**
 * The seam the TeamAgent talks to for main-model chat completions. The productive implementation is
 * {@link PortMainModelChat} over the host's model port (ADR 0001, 3.2); tests inject a scripted fake so the
 * whole conversation engine runs without a real model. The agent performs NO model management — which model
 * fills the role's slot is entirely AskAI's configuration.
 */
public interface MainModelChat {

    /**
     * Complete a chat exchange. {@code messages} is the full ordered context (a leading system message, then
     * the alternating user/assistant history ending with the latest user turn). Never throws for a model or
     * transport problem — every such failure comes back as a non-OK {@link MainModelChatResult}.
     */
    MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens);

    /**
     * Like {@link #complete}, but the answer MUST be a JSON value — transports that support it
     * (Ollama structured outputs) enforce this AT GENERATION TIME: {@code schemaJson == null}
     * requests generic JSON mode, a non-null JSON-schema string constrains the exact shape
     * (enums, maxItems). This is the deterministic fix for small models hand-rolling broken JSON
     * (live: gemma4:e2b broke mid-object even at width 20, and generic json mode cannot stop a
     * model that loses count — a {@code maxItems} grammar can). The default falls back to a plain
     * completion, so fakes and the unavailable transport stay untouched; strict validation
     * downstream still applies either way.
     */
    default MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                             int maxOutputTokens, String schemaJson) {
        return complete(messages, temperature, maxOutputTokens);
    }

    /**
     * The context in tokens the call runs with (the bound model's {@code num_ctx}, plan 4.1 rule 1), for the
     * overflow guard of the caller; 0 = unknown, then no guard runs.
     */
    default int contextTokens() {
        return 0;
    }

    /** What this client calls, for the readiness line and diagnostics (a slot name, never a secret). */
    String modelName();

    /**
     * Abort the call(s) in flight, so a session close or a pause returns promptly instead of waiting out the
     * model timeout; the aborted call returns an honest non-OK result. A no-op for clients without a
     * transport (fakes, the unavailable client).
     */
    default void cancelInFlight() {
    }
}
