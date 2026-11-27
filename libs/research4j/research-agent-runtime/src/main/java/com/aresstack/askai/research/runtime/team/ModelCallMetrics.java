package com.aresstack.askai.research.runtime.team;

/**
 * What one model call cost and why it stopped (plan PR 01): the token usage, the finish reason, the length
 * of the thinking text, the model that answered and the provider's durations, plus the role, the slot and
 * the effective context size of the call. Immutable; unknown numbers are -1, unknown labels "".
 */
public final class ModelCallMetrics {

    private static final ModelCallMetrics UNKNOWN = builder().build();

    private final String role;
    private final String slot;
    private final String modelLabel;
    private final int promptEvalCount;
    private final int evalCount;
    private final String doneReason;
    private final int thinkingChars;
    private final long durationMillis;
    private final long loadMillis;
    private final long promptEvalMillis;
    private final int contextTokens;
    private final int answerBudget;

    private ModelCallMetrics(Builder b) {
        this.role = b.role;
        this.slot = b.slot;
        this.modelLabel = b.modelLabel;
        this.promptEvalCount = b.promptEvalCount;
        this.evalCount = b.evalCount;
        this.doneReason = b.doneReason;
        this.thinkingChars = b.thinkingChars;
        this.durationMillis = b.durationMillis;
        this.loadMillis = b.loadMillis;
        this.promptEvalMillis = b.promptEvalMillis;
        this.contextTokens = b.contextTokens;
        this.answerBudget = b.answerBudget;
    }

    public static ModelCallMetrics unknown() {
        return UNKNOWN;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getRole() {
        return role;
    }

    public String getSlot() {
        return slot;
    }

    public String getModelLabel() {
        return modelLabel;
    }

    /** Prompt tokens the provider evaluated, -1 when not reported. */
    public int getPromptEvalCount() {
        return promptEvalCount;
    }

    /** Answer tokens the provider produced, -1 when not reported. */
    public int getEvalCount() {
        return evalCount;
    }

    /** The provider's finish reason in lower case ({@code stop}, {@code length}, {@code tool_calls}), or "". */
    public String getDoneReason() {
        return doneReason;
    }

    public boolean isCutAtLength() {
        return "length".equals(doneReason);
    }

    public int getThinkingChars() {
        return thinkingChars;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public long getLoadMillis() {
        return loadMillis;
    }

    public long getPromptEvalMillis() {
        return promptEvalMillis;
    }

    /** The effective {@code num_ctx} of the call, 0 when unknown (the model's own default). */
    public int getContextTokens() {
        return contextTokens;
    }

    /** The answer budget the call was sent with, 0 when none. */
    public int getAnswerBudget() {
        return answerBudget;
    }

    public static final class Builder {
        private String role = "";
        private String slot = "";
        private String modelLabel = "";
        private int promptEvalCount = -1;
        private int evalCount = -1;
        private String doneReason = "";
        private int thinkingChars;
        private long durationMillis;
        private long loadMillis;
        private long promptEvalMillis;
        private int contextTokens;
        private int answerBudget;

        private Builder() {
        }

        public Builder role(String value) {
            this.role = value == null ? "" : value;
            return this;
        }

        public Builder slot(String value) {
            this.slot = value == null ? "" : value;
            return this;
        }

        public Builder modelLabel(String value) {
            this.modelLabel = value == null ? "" : value;
            return this;
        }

        public Builder usage(int promptEvalCount, int evalCount) {
            this.promptEvalCount = promptEvalCount < 0 ? -1 : promptEvalCount;
            this.evalCount = evalCount < 0 ? -1 : evalCount;
            return this;
        }

        public Builder doneReason(String value) {
            this.doneReason = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            return this;
        }

        public Builder thinkingChars(int value) {
            this.thinkingChars = Math.max(0, value);
            return this;
        }

        public Builder durations(long totalMillis, long loadMillis, long promptEvalMillis) {
            this.durationMillis = Math.max(0L, totalMillis);
            this.loadMillis = Math.max(0L, loadMillis);
            this.promptEvalMillis = Math.max(0L, promptEvalMillis);
            return this;
        }

        public Builder contextTokens(int value) {
            this.contextTokens = Math.max(0, value);
            return this;
        }

        public Builder answerBudget(int value) {
            this.answerBudget = Math.max(0, value);
            return this;
        }

        public ModelCallMetrics build() {
            return new ModelCallMetrics(this);
        }
    }
}
