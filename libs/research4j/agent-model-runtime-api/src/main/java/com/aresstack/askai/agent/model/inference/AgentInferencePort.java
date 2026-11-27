package com.aresstack.askai.agent.model.inference;

/**
 * Narrow, host-provided STREAMING inference port for in-process agent plugins: one prompt in, streamed
 * thinking/content out, exactly one terminal callback, cancellable. The host implements it on its existing
 * chat stack (centrally selected main model, its executor, its cancellation) — plugins never see HTTP,
 * Ollama types or model names. Neutral contract only: no Swing, no Solon, no askai-app types.
 *
 * <p>Listener callbacks arrive on an arbitrary worker thread; callers marshal. After {@code cancel()} or
 * a terminal callback, further callbacks may still race in — consumers guard with their own staleness
 * check (cancel is a resource optimisation, not a correctness tool).</p>
 */
public interface AgentInferencePort {

    final class InferenceRequest {
        private final String systemPrompt;
        private final String userPrompt;

        public InferenceRequest(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
            this.userPrompt = userPrompt == null ? "" : userPrompt;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public String getUserPrompt() {
            return userPrompt;
        }
    }

    interface Listener {
        /** Reasoning chunk of a thinking-capable model; may never be called. */
        default void onThinkingDelta(String delta) {
        }

        /** Answer chunk; may never be called when the model answers in one piece. */
        default void onContentDelta(String delta) {
        }

        /** Terminal: the complete answer text (assembled by the implementation). */
        void onCompleted(String fullText);

        /** Terminal: generation failed (unreachable endpoint, no model selected, aborted, …). */
        void onFailed(String reason);
    }

    interface Cancellable {
        /** Best-effort abort of the generation; idempotent. Frees the (serial) local model. */
        void cancel();
    }

    /** Starts one generation; never blocks the calling thread. */
    Cancellable generate(InferenceRequest request, Listener listener);
}
