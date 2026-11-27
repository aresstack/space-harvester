package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The own-phase history window of one prompt (plan PR 17, D7, table 4.2): the newest {@code USER} and
 * {@code FINAL} entries of the live phase that fit into the window budget, in chronological order. Entries of
 * other phases, tool rounds, repairs and nudges never enter it. The whole history is never sent.
 *
 * <p>Stateless and deterministic: the same history, phase and budget always give the same window.</p>
 */
public final class HistoryWindow {

    /** Window budget of a Concept chat turn (table 4.2). */
    public static final int CONCEPT_WINDOW_TOKENS = 4000;
    /** Window budget of a chat turn in Sources, Outline and Document (table 4.2). */
    public static final int PHASE_WINDOW_TOKENS = 3000;
    /** Newest own-phase entries of the post-search review (table 4.2). */
    public static final int REVIEW_WINDOW_TOKENS = 1500;

    private HistoryWindow() {
    }

    /** The window budget of a chat turn in {@code phaseId}. */
    public static int chatBudget(String phaseId) {
        return PhaseKeys.isConcept(phaseId) ? CONCEPT_WINDOW_TOKENS : PHASE_WINDOW_TOKENS;
    }

    /**
     * The newest window entries of {@code phaseId} whose estimated tokens together stay within
     * {@code budgetTokens}, oldest first. Selection stops at the first entry that no longer fits, so the window
     * is always a contiguous tail of the phase's conversation.
     */
    public static List<ChatMessage> select(List<ChatMessage> history, String phaseId, int budgetTokens) {
        if (history == null || history.isEmpty() || budgetTokens <= 0) {
            return Collections.emptyList();
        }
        String phase = PhaseKeys.normalize(phaseId);
        List<ChatMessage> newestFirst = new ArrayList<ChatMessage>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage entry = history.get(i);
            if (!entry.isWindowEntryOf(phase)) {
                continue;
            }
            int tokens = tokensOf(entry);
            if (used + tokens > budgetTokens) {
                break;
            }
            used += tokens;
            newestFirst.add(entry);
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** The pre-call token estimate of one entry, with the meter's ratio. */
    static int tokensOf(ChatMessage entry) {
        int chars = entry.getContent().length();
        return (chars + PromptBlockMeter.CHARS_PER_TOKEN - 1) / PromptBlockMeter.CHARS_PER_TOKEN;
    }
}
