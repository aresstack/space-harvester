package com.aresstack.askai.agent.model.port;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One chat call through the {@link ModelPort} (ADR 0001, 3.2). Immutable; built with {@link #builder}.
 *
 * <ul>
 *   <li>{@code slot}: the model slot of the calling role; the platform resolves it to a model on an
 *       endpoint. Required.</li>
 *   <li>{@code messages}: the full ordered context; at least one message.</li>
 *   <li>{@code tools}: the tool catalogue offered for THIS call (the role's tool subset); empty = no tool
 *       calling.</li>
 *   <li>{@code outputFormat}: free text, generic JSON or a JSON schema; default text.</li>
 *   <li>{@code thinking}: the thinking mode; default {@link ThinkingMode#DEFAULT}.</li>
 *   <li>{@code temperature}: null = the model's default.</li>
 *   <li>{@code maxOutputTokens}: 0 = the slot's answer budget (none unless configured).</li>
 *   <li>{@code contextTokens}: the MINIMUM context this call needs; 0 = none. The call always runs with the
 *       model's context; a minimum above it fails with {@code CONTEXT_TOO_LARGE} before the HTTP call.</li>
 *   <li>{@code truncate}: whether the runtime may cut a prompt that exceeds the context; {@code FALSE} makes
 *       it refuse instead (Ollama {@code truncate}); null = the runtime's default, nothing is sent.</li>
 *   <li>{@code timeout}: the caller's time limit for the whole call (a setting of the calling role, never a
 *       constant); null = the platform's configured default.</li>
 * </ul>
 *
 * <p>Deliberately absent: model names, endpoints, provider options such as Ollama's {@code keep_alive}.
 * Residency is the platform scheduler's business (ADR 0001, 4.5); a plugin never steers the GPU.</p>
 */
public final class ChatRequest {

    private final ModelSlot slot;
    private final List<ModelMessage> messages;
    private final List<ToolDefinition> tools;
    private final OutputFormat outputFormat;
    private final ThinkingMode thinking;
    private final Double temperature;
    private final int maxOutputTokens;
    private final int contextTokens;
    private final Boolean truncate;
    private final Duration timeout;

    private ChatRequest(Builder builder) {
        this.slot = builder.slot;
        this.messages = Collections.unmodifiableList(new ArrayList<ModelMessage>(builder.messages));
        this.tools = Collections.unmodifiableList(new ArrayList<ToolDefinition>(builder.tools));
        this.outputFormat = builder.outputFormat;
        this.thinking = builder.thinking;
        this.temperature = builder.temperature;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.contextTokens = builder.contextTokens;
        this.truncate = builder.truncate;
        this.timeout = builder.timeout;
    }

    public static Builder builder(ModelSlot slot) {
        return new Builder(slot);
    }

    public ModelSlot getSlot() {
        return slot;
    }

    public List<ModelMessage> getMessages() {
        return messages;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public ThinkingMode getThinking() {
        return thinking;
    }

    /** The sampling temperature, or null for the model's default. */
    public Double getTemperature() {
        return temperature;
    }

    /** Output token limit; 0 = none set by the caller. */
    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /** The minimum context in tokens; 0 = none. */
    public int getContextTokens() {
        return contextTokens;
    }

    /** Whether the runtime may truncate an oversized prompt; null = the runtime's default. */
    public Boolean getTruncate() {
        return truncate;
    }

    /** The call's time limit, or null for the platform default. */
    public Duration getTimeout() {
        return timeout;
    }

    public static final class Builder {
        private final ModelSlot slot;
        private final List<ModelMessage> messages = new ArrayList<ModelMessage>();
        private final List<ToolDefinition> tools = new ArrayList<ToolDefinition>();
        private OutputFormat outputFormat = OutputFormat.text();
        private ThinkingMode thinking = ThinkingMode.DEFAULT;
        private Double temperature;
        private int maxOutputTokens;
        private int contextTokens;
        private Boolean truncate;
        private Duration timeout;

        private Builder(ModelSlot slot) {
            if (slot == null) {
                throw new IllegalArgumentException("slot must not be null");
            }
            this.slot = slot;
        }

        public Builder message(ModelMessage message) {
            if (message == null) {
                throw new IllegalArgumentException("message must not be null");
            }
            messages.add(message);
            return this;
        }

        public Builder messages(List<ModelMessage> messages) {
            if (messages != null) {
                for (ModelMessage message : messages) {
                    message(message);
                }
            }
            return this;
        }

        public Builder tool(ToolDefinition tool) {
            if (tool == null) {
                throw new IllegalArgumentException("tool must not be null");
            }
            for (ToolDefinition existing : tools) {
                if (existing.getName().equals(tool.getName())) {
                    throw new IllegalArgumentException("duplicate tool name: " + tool.getName());
                }
            }
            tools.add(tool);
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            if (tools != null) {
                for (ToolDefinition tool : tools) {
                    tool(tool);
                }
            }
            return this;
        }

        public Builder outputFormat(OutputFormat outputFormat) {
            this.outputFormat = outputFormat == null ? OutputFormat.text() : outputFormat;
            return this;
        }

        public Builder thinking(ThinkingMode thinking) {
            this.thinking = thinking == null ? ThinkingMode.DEFAULT : thinking;
            return this;
        }

        /** @param temperature null for the model's default; otherwise finite and >= 0 */
        public Builder temperature(Double temperature) {
            if (temperature != null && (temperature.isNaN() || temperature.isInfinite() || temperature < 0)) {
                throw new IllegalArgumentException("temperature must be finite and >= 0");
            }
            this.temperature = temperature;
            return this;
        }

        /** @param maxOutputTokens 0 for no limit */
        public Builder maxOutputTokens(int maxOutputTokens) {
            if (maxOutputTokens < 0) {
                throw new IllegalArgumentException("max output tokens must be >= 0");
            }
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        /** @param contextTokens 0 for the reservation size or the slot default */
        public Builder contextTokens(int contextTokens) {
            if (contextTokens < 0) {
                throw new IllegalArgumentException("context tokens must be >= 0");
            }
            this.contextTokens = contextTokens;
            return this;
        }

        /** @param truncate {@code FALSE} = refuse an oversized prompt; null = the runtime's default */
        public Builder truncate(Boolean truncate) {
            this.truncate = truncate;
            return this;
        }

        /** @param timeout null for the platform default; otherwise positive */
        public Builder timeout(Duration timeout) {
            if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** @throws IllegalStateException when no message was added */
        public ChatRequest build() {
            if (messages.isEmpty()) {
                throw new IllegalStateException("a chat request needs at least one message");
            }
            return new ChatRequest(this);
        }
    }
}
