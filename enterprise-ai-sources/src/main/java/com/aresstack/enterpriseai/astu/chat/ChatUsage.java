package com.aresstack.enterpriseai.astu.chat;

/**
 * Token-Verbrauch einer Antwort, soweit der Provider ihn meldet. Melden Server keinen oder einen
 * offensichtlich leeren Verbrauch (alles 0), liefert der Adapter {@link #notReported()}.
 */
public final class ChatUsage {

    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    private ChatUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public static ChatUsage of(int promptTokens, int completionTokens, int totalTokens) {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        return new ChatUsage(promptTokens, completionTokens, totalTokens);
    }

    public static ChatUsage notReported() {
        return new ChatUsage(-1, -1, -1);
    }

    public boolean isReported() {
        return totalTokens >= 0;
    }

    /** @return Prompt-Tokens oder -1, wenn nicht gemeldet */
    public int promptTokens() {
        return promptTokens;
    }

    /** @return Completion-Tokens oder -1, wenn nicht gemeldet */
    public int completionTokens() {
        return completionTokens;
    }

    /** @return Gesamt-Tokens oder -1, wenn nicht gemeldet */
    public int totalTokens() {
        return totalTokens;
    }

    @Override
    public String toString() {
        return isReported()
                ? "ChatUsage[prompt=" + promptTokens + ", completion=" + completionTokens + ", total=" + totalTokens + "]"
                : "ChatUsage[not reported]";
    }
}
