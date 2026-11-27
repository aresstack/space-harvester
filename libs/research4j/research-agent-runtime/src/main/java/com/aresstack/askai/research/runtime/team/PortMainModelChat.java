package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotBinding;
import com.aresstack.askai.research.runtime.inference.BlockingModelCall;
import com.aresstack.askai.research.runtime.role.ResearchRole;

import java.time.Duration;
import java.util.List;

/**
 * The productive {@link MainModelChat} over the host's model port (ADR 0001, 3.2). It replaces the former
 * hand-written {@code /api/chat} HTTP client: the runtime no longer knows Ollama, endpoints or model names, it
 * names a {@link ModelSlot} and the platform resolves it, schedules it against the model's capacity and keeps
 * the model warm.
 *
 * <p>The call shapes are exactly the former client's: the full message history, non-streaming from the
 * caller's view, the caller's temperature and output budget, the role's thinking mode (the slot's Auto unless
 * the role is a contract role, plan 4.1 rule 3), generic JSON or a JSON schema for {@link #completeJson}. An
 * answer cut off at the budget ({@code done_reason} length) fails for a JSON answer and is flagged for prose. Failures keep their honest typed status: a time-out
 * is {@code TIMEOUT}, an unusable answer (empty, reasoning only) is {@code INVALID_RESPONSE}, everything
 * else is {@code PROVIDER_FAILURE} with the port's named reason in the detail.</p>
 */
public final class PortMainModelChat implements MainModelChat {

    private final ModelPort port;
    private final ModelSlot slot;
    private final int contextTokens;
    private final Duration timeout;
    private final BlockingModelCall calls;
    private final String roleId;
    private final ThinkingMode thinking;

    /**
     * @param port    the host's model port
     * @param slot    the calling role's slot
     * @param timeout the per-call time limit (the user's main-model timeout setting), or null for the
     *                platform default
     */
    public PortMainModelChat(ModelPort port, ModelSlot slot, Duration timeout) {
        this(port, slot, 0, timeout);
    }

    /**
     * @param contextTokens the role's minimum context per call, 0 for none (the model's context is sent)
     */
    public PortMainModelChat(ModelPort port, ModelSlot slot, int contextTokens, Duration timeout) {
        this(port, slot, contextTokens, timeout, "", ThinkingMode.DEFAULT);
    }

    private PortMainModelChat(ModelPort port, ModelSlot slot, int contextTokens, Duration timeout,
                              String roleId, ThinkingMode thinking) {
        if (port == null || slot == null) {
            throw new IllegalArgumentException("port and slot must not be null");
        }
        if (contextTokens < 0) {
            throw new IllegalArgumentException("context tokens must be >= 0");
        }
        this.port = port;
        this.slot = slot;
        this.contextTokens = contextTokens;
        this.timeout = timeout;
        this.calls = new BlockingModelCall(port);
        this.roleId = roleId == null ? "" : roleId;
        this.thinking = thinking == null ? ThinkingMode.DEFAULT : thinking;
    }

    /** The transport of one research role: its slot and its context size (ADR 0001, 4.1). */
    public static PortMainModelChat forRole(ModelPort port, ResearchRole role, Duration timeout) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        return new PortMainModelChat(port, role.getSlot(), role.getContextTokens(), timeout, role.getId(),
                role.getThinking());
    }

    public ModelSlot getSlot() {
        return slot;
    }

    /** The role's minimum context per call; 0 = none. */
    public int getContextTokens() {
        return contextTokens;
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        return call(messages, temperature, maxOutputTokens, OutputFormat.text());
    }

    @Override
    public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens, String schemaJson) {
        return call(messages, temperature, maxOutputTokens,
                schemaJson == null || schemaJson.trim().isEmpty()
                        ? OutputFormat.json() : OutputFormat.jsonSchema(schemaJson));
    }

    /** The bound model's context for the overflow guard; 0 when no binding is known (no guard then). */
    @Override
    public int contextTokens() {
        try {
            ResourceSnapshot snapshot = port.resources();
            SlotBinding binding = snapshot == null ? null : snapshot.bindingOf(slot).orElse(null);
            return binding == null ? 0 : binding.getContextTokens();
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    /** The slot name: plugins never see which model fills it. */
    @Override
    public String modelName() {
        return "slot " + slot.getName();
    }

    /** Aborts every call in flight (session close, pause, cancel); each returns an honest non-OK result. */
    @Override
    public void cancelInFlight() {
        calls.cancelAll();
    }

    private MainModelChatResult call(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                     OutputFormat format) {
        if (messages == null || messages.isEmpty()) {
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "no messages to send");
        }
        ChatRequest.Builder request = ChatRequest.builder(slot)
                .outputFormat(format)
                .thinking(thinking)
                .temperature(temperature < 0 || Double.isNaN(temperature) ? null : temperature)
                .maxOutputTokens(Math.max(0, maxOutputTokens))
                .contextTokens(contextTokens)
                // Ollama refuses an oversized prompt instead of silently cutting it (plan 4.1 rule 2, PR 17).
                .truncate(Boolean.FALSE)
                .timeout(timeout);
        for (ChatMessage message : messages) {
            request.message(toModelMessage(message));
        }
        int budget = Math.max(0, maxOutputTokens);
        BlockingModelCall.Outcome outcome = calls.run(request.build());
        if (outcome.isOk()) {
            ModelCallMetrics metrics = metricsOf(outcome.result, budget);
            if (outcome.result.getFinishReason() == ChatResult.FinishReason.LENGTH
                    && format.getKind() != OutputFormat.Kind.TEXT) {
                // A JSON answer cut off at its budget is broken JSON: fail loud instead of a parse repair that
                // would be cut off the same way (plan 4.1, rule 3). Prose stays a result, flagged by its metrics.
                return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                        "the answer was cut off at its budget of " + budget + " tokens", metrics);
            }
            return MainModelChatResult.ok(outcome.result.getContent(), metrics);
        }
        return MainModelChatResult.failure(statusOf(outcome.failure.getKind()),
                "main-model call failed: " + outcome.failure, baseMetrics(budget).build());
    }

    private ModelCallMetrics.Builder baseMetrics(int budget) {
        return ModelCallMetrics.builder()
                .role(roleId)
                .slot(slot.getName())
                .contextTokens(effectiveContextTokens())
                .answerBudget(budget);
    }

    ModelCallMetrics metricsOf(ChatResult result, int budget) {
        return baseMetrics(budget)
                .modelLabel(result.getModelLabel())
                .usage(result.getUsage().getPromptTokens(), result.getUsage().getOutputTokens())
                .doneReason(doneReasonOf(result.getFinishReason()))
                .thinkingChars(result.getThinking().length())
                .durations(result.getDuration().toMillis(), result.getLoadDuration().toMillis(),
                        result.getPromptEvalDuration().toMillis())
                .build();
    }

    /** The context the call runs with: the bound model's; the role's minimum when no binding is known. */
    private int effectiveContextTokens() {
        try {
            ResourceSnapshot snapshot = port.resources();
            SlotBinding binding = snapshot == null ? null : snapshot.bindingOf(slot).orElse(null);
            return binding == null ? contextTokens : binding.getContextTokens();
        } catch (RuntimeException unavailable) {
            return contextTokens;
        }
    }

    static String doneReasonOf(ChatResult.FinishReason reason) {
        switch (reason) {
            case STOP:
                return "stop";
            case LENGTH:
                return "length";
            case TOOL_CALLS:
                return "tool_calls";
            default:
                return "other";
        }
    }

    static ModelMessage toModelMessage(ChatMessage message) {
        switch (message.getRole()) {
            case SYSTEM:
                return ModelMessage.system(message.getContent());
            case ASSISTANT:
                return ModelMessage.assistant(message.getContent());
            default:
                return ModelMessage.user(message.getContent());
        }
    }

    static MainModelChatResult.Status statusOf(ModelFailure.Kind kind) {
        switch (kind) {
            case TIMEOUT:
                return MainModelChatResult.Status.TIMEOUT;
            case INVALID_RESPONSE:
                return MainModelChatResult.Status.INVALID_RESPONSE;
            default:
                return MainModelChatResult.Status.PROVIDER_FAILURE;
        }
    }
}
