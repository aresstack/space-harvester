package com.aresstack.knowledge4j.model.guard;

import com.aresstack.knowledge4j.model.ChatListener;
import com.aresstack.knowledge4j.model.ChatRequest;
import com.aresstack.knowledge4j.model.ModelCall;
import com.aresstack.knowledge4j.model.ModelFailure;
import com.aresstack.knowledge4j.model.ModelMessage;
import com.aresstack.knowledge4j.model.ModelPort;
import com.aresstack.knowledge4j.model.ModelSlot;
import com.aresstack.knowledge4j.model.ToolCall;
import com.aresstack.knowledge4j.model.resource.ResourceSnapshot;
import com.aresstack.knowledge4j.model.resource.SlotRequest;
import com.aresstack.knowledge4j.model.resource.SlotReservation;
import com.aresstack.knowledge4j.policy.PayloadKind;
import com.aresstack.knowledge4j.policy.RunGuard;
import com.aresstack.knowledge4j.policy.TransferDecision;
import com.aresstack.knowledge4j.policy.TransferTarget;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A model port behind the run's transfer guard. Every chat call is judged before it reaches the slot's endpoint:
 * the prompt carries the run's high-water mark, so a conversation that has seen internal passages, tool results
 * or an internal task never reaches an external endpoint the policy forbids. A denied call ends at once with
 * {@link ModelFailure.Kind#TRANSFER_DENIED} through the listener; nothing is sent.
 */
public final class GuardedModelPort implements ModelPort {

    private final ModelPort delegate;
    private final Function<ModelSlot, TransferTarget> targets;
    private final RunGuard guard;

    /**
     * @param targets the exit behind each slot as the host bound it (a local model is internal, a cloud model
     *                external); a slot the host does not know maps to an external target
     */
    public GuardedModelPort(ModelPort delegate, Function<ModelSlot, TransferTarget> targets, RunGuard guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public CompletableFuture<SlotReservation> reserve(SlotRequest request) {
        return delegate.reserve(request);
    }

    @Override
    public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
        TransferDecision decision = judge(request);
        return decision.allowed() ? delegate.chat(reservation, request, listener) : deny(decision, request, listener);
    }

    @Override
    public ModelCall chat(ChatRequest request, ChatListener listener) {
        TransferDecision decision = judge(request);
        return decision.allowed() ? delegate.chat(request, listener) : deny(decision, request, listener);
    }

    @Override
    public CompletableFuture<Void> warm(ModelSlot slot) {
        return delegate.warm(slot);
    }

    @Override
    public ResourceSnapshot resources() {
        return delegate.resources();
    }

    private TransferDecision judge(ChatRequest request) {
        boolean tools = false;
        long chars = 0;
        for (ModelMessage message : request.getMessages()) {
            chars += message.getContent().length();
            for (ToolCall call : message.getToolCalls()) {
                tools = true;
                chars += call.getArgumentsJson().length();
            }
        }
        return guard.check(target(request.getSlot()), tools ? PayloadKind.TOOL_CALL : PayloadKind.MODEL_PROMPT,
                null, chars);
    }

    private TransferTarget target(ModelSlot slot) {
        TransferTarget target = targets.apply(slot);
        return target == null ? TransferTarget.external("slot " + slot.getName()) : target;
    }

    private ModelCall deny(TransferDecision decision, ChatRequest request, ChatListener listener) {
        listener.onFailed(new ModelFailure(ModelFailure.Kind.TRANSFER_DENIED, "slot " + request.getSlot().getName()
                + " " + decision.reason() + (decision.detail().isEmpty() ? "" : ": " + decision.detail())));
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
}
