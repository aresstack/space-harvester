package com.aresstack.askai.agent.model.port;

/**
 * Receives the stream of one chat call (ADR 0001, 3.2: streaming with thinking chunks). Exactly one
 * terminal callback ({@link #onCompleted} or {@link #onFailed}) arrives per call, after all deltas of that
 * call.
 *
 * <p>Callbacks arrive on an arbitrary worker thread of the platform, never on the caller's thread; callers
 * marshal (for example to the EDT). A listener must not block for long, since it delays the stream. After
 * {@link ModelCall#cancel()} the terminal callback is {@link ModelFailure.Kind#CANCELLED} unless the call had
 * already ended; deltas that were in flight may still arrive before it.</p>
 */
public interface ChatListener {

    /** A reasoning chunk of a thinking-capable model; may never be called. */
    default void onThinkingDelta(String delta) {
    }

    /** An answer chunk; may never be called (for example when the provider answers in one piece). */
    default void onContentDelta(String delta) {
    }

    /** Terminal: the complete, assembled result. */
    void onCompleted(ChatResult result);

    /** Terminal: the call failed. */
    void onFailed(ModelFailure failure);
}
