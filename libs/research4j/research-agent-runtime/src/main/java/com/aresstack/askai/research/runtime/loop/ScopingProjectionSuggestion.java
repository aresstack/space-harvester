package com.aresstack.askai.research.runtime.loop;

/**
 * ONE search suggestion as it travels over the wire — a transport-only value (no team/model types) so the
 * {@code loop} transport stays decoupled from the scoping model. Mirrors the UI-side decoded suggestion.
 */
public final class ScopingProjectionSuggestion {

    private final String query;
    private final String purpose;
    private final int priority;

    public ScopingProjectionSuggestion(String query, String purpose, int priority) {
        this.query = query == null ? "" : query;
        this.purpose = purpose == null ? "" : purpose;
        this.priority = priority;
    }

    public String getQuery() {
        return query;
    }

    public String getPurpose() {
        return purpose;
    }

    public int getPriority() {
        return priority;
    }
}
