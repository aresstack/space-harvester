package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A pass-through {@link ModelPort} that adds the token usage of every completed call to one counter: the
 * accounting of one task (or of the orchestrator itself) against its budget. Usage the platform does not know
 * counts as zero; the wall-time budget still applies. Nothing else is changed.
 */
public final class MeteredModelPort implements ModelPort {

    private final ModelPort delegate;
    private final AtomicLong tokens;
    private final java.util.function.BooleanSupplier exhausted;

    public MeteredModelPort(ModelPort delegate, AtomicLong tokens) {
        this(delegate, tokens, null);
    }

    /**
     * @param exhausted true once the account's budget is spent; every further call then fails fast as
     *                  {@link ModelFailure.Kind#CANCELLED} instead of reaching the model (null: never)
     */
    public MeteredModelPort(ModelPort delegate, AtomicLong tokens, java.util.function.BooleanSupplier exhausted) {
        this.delegate = delegate;
        this.tokens = tokens;
        this.exhausted = exhausted;
    }

    @Override
    public CompletableFuture<SlotReservation> reserve(SlotRequest request) {
        return delegate.reserve(request);
    }

    @Override
    public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
        if (isExhausted()) {
            return refused(listener);
        }
        return delegate.chat(reservation, request, metered(listener));
    }

    @Override
    public ModelCall chat(ChatRequest request, ChatListener listener) {
        if (isExhausted()) {
            return refused(listener);
        }
        return delegate.chat(request, metered(listener));
    }

    private boolean isExhausted() {
        return exhausted != null && exhausted.getAsBoolean();
    }

    /** The budget is spent: the call ends at once with an honest failure and never reaches the model. */
    private static ModelCall refused(ChatListener listener) {
        listener.onFailed(new ModelFailure(ModelFailure.Kind.CANCELLED, "the task's budget is spent"));
        return new ModelCall() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isDone() {
                return true;
            }
        };
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> warm(com.aresstack.askai.agent.model.port.ModelSlot slot) {
        return delegate.warm(slot);
    }

    @Override
    public ResourceSnapshot resources() {
        return delegate.resources();
    }

    private ChatListener metered(final ChatListener listener) {
        return new ChatListener() {
            @Override
            public void onThinkingDelta(String delta) {
                listener.onThinkingDelta(delta);
            }

            @Override
            public void onContentDelta(String delta) {
                listener.onContentDelta(delta);
            }

            @Override
            public void onCompleted(ChatResult result) {
                if (result != null) {
                    tokens.addAndGet(Math.max(0, result.getUsage().getPromptTokens())
                            + (long) Math.max(0, result.getUsage().getOutputTokens()));
                }
                listener.onCompleted(result);
            }

            @Override
            public void onFailed(ModelFailure failure) {
                listener.onFailed(failure);
            }
        };
    }
}
