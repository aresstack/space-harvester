package com.aresstack.askai.research.runtime.team;

/**
 * ONE search-engine query the scoping assistant proposes — deliberately separate from the research question.
 * The research question is a human, natural-language brief; a {@code SearchSuggestion} is a short, focused,
 * operational query derived from it. RA-P6 §20/§21/§25: this is only a SUGGESTION; nothing here runs a search
 * (that is a later, user-triggered in-phase action).
 */
public final class SearchSuggestion {

    private final String query;
    private final String purpose;
    private final int priority;

    public SearchSuggestion(String query, String purpose, int priority) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive");
        }
        this.query = query.trim();
        this.purpose = purpose == null ? "" : purpose.trim();
        this.priority = priority;
    }

    /** The engine-facing query (never blank). */
    public String getQuery() {
        return query;
    }

    /** What this query is meant to find (may be empty). */
    public String getPurpose() {
        return purpose;
    }

    /** A positive priority (1 = highest); lower-priority queries come later. */
    public int getPriority() {
        return priority;
    }
}
