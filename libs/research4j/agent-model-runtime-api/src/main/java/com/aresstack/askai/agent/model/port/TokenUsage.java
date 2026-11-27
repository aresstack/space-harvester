package com.aresstack.askai.agent.model.port;

/**
 * Token accounting of one completed call, as far as the provider reports it ({@code -1} = not reported).
 * Feeds the token budgets of tasks (ADR 0001, 4.4) and the benchmark metrics.
 */
public final class TokenUsage {

    private static final TokenUsage UNKNOWN = new TokenUsage(-1, -1);

    private final int promptTokens;
    private final int outputTokens;

    public TokenUsage(int promptTokens, int outputTokens) {
        this.promptTokens = promptTokens < 0 ? -1 : promptTokens;
        this.outputTokens = outputTokens < 0 ? -1 : outputTokens;
    }

    public static TokenUsage unknown() {
        return UNKNOWN;
    }

    /** Tokens of the evaluated prompt, or -1 when not reported. */
    public int getPromptTokens() {
        return promptTokens;
    }

    /** Generated tokens (reasoning included), or -1 when not reported. */
    public int getOutputTokens() {
        return outputTokens;
    }
}
