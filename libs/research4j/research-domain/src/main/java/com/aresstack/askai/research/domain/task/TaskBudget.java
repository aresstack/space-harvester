package com.aresstack.askai.research.domain.task;

/**
 * The time and token budget of one task, with what has been spent against it. Limits come from settings
 * (the domain holds no defaults); a limit of {@code 0} means "not limited on this task". Spending ACCUMULATES
 * across retries and interruptions — a resumed run continues the account, it never resets it.
 */
public final class TaskBudget {

    private static final TaskBudget UNLIMITED = new TaskBudget(0L, 0L, 0L, 0L);

    private final long timeLimitMillis;
    private final long tokenLimit;
    private final long timeUsedMillis;
    private final long tokensUsed;

    public TaskBudget(long timeLimitMillis, long tokenLimit, long timeUsedMillis, long tokensUsed) {
        if (timeLimitMillis < 0 || tokenLimit < 0 || timeUsedMillis < 0 || tokensUsed < 0) {
            throw new IllegalArgumentException("budget values must not be negative");
        }
        this.timeLimitMillis = timeLimitMillis;
        this.tokenLimit = tokenLimit;
        this.timeUsedMillis = timeUsedMillis;
        this.tokensUsed = tokensUsed;
    }

    /** A fresh budget with nothing spent yet. */
    public static TaskBudget of(long timeLimitMillis, long tokenLimit) {
        return new TaskBudget(timeLimitMillis, tokenLimit, 0L, 0L);
    }

    public static TaskBudget unlimited() {
        return UNLIMITED;
    }

    public long getTimeLimitMillis() {
        return timeLimitMillis;
    }

    public long getTokenLimit() {
        return tokenLimit;
    }

    public long getTimeUsedMillis() {
        return timeUsedMillis;
    }

    public long getTokensUsed() {
        return tokensUsed;
    }

    /** The budget with more spending added; the limits stay as they were. */
    public TaskBudget spend(long millis, long tokens) {
        if (millis < 0 || tokens < 0) {
            throw new IllegalArgumentException("spending must not be negative");
        }
        return new TaskBudget(timeLimitMillis, tokenLimit, timeUsedMillis + millis, tokensUsed + tokens);
    }

    public boolean isTimeExhausted() {
        return timeLimitMillis > 0 && timeUsedMillis >= timeLimitMillis;
    }

    public boolean isTokensExhausted() {
        return tokenLimit > 0 && tokensUsed >= tokenLimit;
    }

    /**
     * Whether either limit is reached. The domain only REPORTS this; whether the executor fails the task or
     * asks the user for more budget is the executor's decision under the approval envelope.
     */
    public boolean isExhausted() {
        return isTimeExhausted() || isTokensExhausted();
    }
}
