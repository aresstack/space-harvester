package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;

import java.time.Duration;

/**
 * The productive {@link StructuredInferencePort} (model-backed SERP layout repair) over the host's model port
 * (ADR 0001, 3.2). It replaces the former hand-written {@code /api/chat} client with the same call shape: a
 * system message (when present) plus the user message, the request's temperature and output budget, and
 * thinking switched OFF — a structured inference is a form to fill in, and a model that spends its whole
 * budget on reasoning answers with nothing readable.
 *
 * <p>Failures map onto the typed {@link StructuredInferenceStatus}, never a fabricated success: a cancelled
 * request is {@code CANCELLED} (the request's signal is honoured while the call runs, not only before it), a
 * time-out {@code TIMEOUT}, an unusable answer {@code INVALID_RESPONSE}, a slot without a model
 * {@code UNAVAILABLE}, everything else {@code PROVIDER_FAILURE}.</p>
 */
public final class PortStructuredInferenceClient implements StructuredInferencePort {

    private final ModelSlot slot;
    private final int contextTokens;
    private final Duration timeout;
    private final BlockingModelCall calls;

    /**
     * @param port    the host's model port
     * @param slot    the slot of the layout-repair role
     * @param timeout the per-call time limit (the user's main-model timeout setting), or null for the
     *                platform default
     */
    public PortStructuredInferenceClient(ModelPort port, ModelSlot slot, Duration timeout) {
        this(port, slot, 0, timeout);
    }

    /**
     * @param contextTokens the role's context size per call, 0 for the slot default (a platform setting)
     */
    public PortStructuredInferenceClient(ModelPort port, ModelSlot slot, int contextTokens, Duration timeout) {
        if (port == null || slot == null) {
            throw new IllegalArgumentException("port and slot must not be null");
        }
        if (contextTokens < 0) {
            throw new IllegalArgumentException("context tokens must be >= 0");
        }
        this.slot = slot;
        this.contextTokens = contextTokens;
        this.timeout = timeout;
        this.calls = new BlockingModelCall(port);
    }

    /** The transport of the SERP layout-repair role: its slot and its context size (ADR 0001, 4.1). */
    public static PortStructuredInferenceClient forRole(ModelPort port, ResearchRole role, Duration timeout) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        return new PortStructuredInferenceClient(port, role.getSlot(), role.getContextTokens(), timeout);
    }

    @Override
    public StructuredInferenceResult execute(final StructuredInferenceRequest request) {
        if (request.cancellationSignal.isCancelled()) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.CANCELLED,
                    "cancelled before the inference call");
        }
        ChatRequest.Builder chat = ChatRequest.builder(slot)
                .thinking(ThinkingMode.OFF)
                .temperature(request.temperature < 0 || Double.isNaN(request.temperature)
                        ? null : request.temperature)
                .maxOutputTokens(Math.max(0, request.maximumOutputTokens))
                .contextTokens(contextTokens)
                .timeout(timeout);
        if (!request.systemPrompt.trim().isEmpty()) {
            chat.message(ModelMessage.system(request.systemPrompt));
        }
        chat.message(ModelMessage.user(request.userPrompt));
        BlockingModelCall.Outcome outcome = calls.run(chat.build(), new BlockingModelCall.CancelCheck() {
            public boolean isCancelled() {
                return request.cancellationSignal.isCancelled();
            }
        });
        if (outcome.isOk()) {
            if (outcome.result.getFinishReason() == com.aresstack.askai.agent.model.port.ChatResult.FinishReason.LENGTH) {
                // A JSON decision cut off at its budget is broken: fail loud (plan 4.1, rule 3).
                return StructuredInferenceResult.of(StructuredInferenceStatus.INVALID_RESPONSE,
                        "the answer was cut off at its budget of " + request.maximumOutputTokens + " tokens");
            }
            return StructuredInferenceResult.success(outcome.result.getContent());
        }
        return StructuredInferenceResult.of(statusOf(outcome.failure.getKind()),
                "inference call failed: " + outcome.failure);
    }

    /** Aborts every inference call in flight. */
    public void cancelInFlight() {
        calls.cancelAll();
    }

    private static StructuredInferenceStatus statusOf(
            com.aresstack.askai.agent.model.port.ModelFailure.Kind kind) {
        switch (kind) {
            case CANCELLED:
                return StructuredInferenceStatus.CANCELLED;
            case TIMEOUT:
                return StructuredInferenceStatus.TIMEOUT;
            case INVALID_RESPONSE:
                return StructuredInferenceStatus.INVALID_RESPONSE;
            case NO_BINDING:
                return StructuredInferenceStatus.UNAVAILABLE;
            default:
                return StructuredInferenceStatus.PROVIDER_FAILURE;
        }
    }
}
