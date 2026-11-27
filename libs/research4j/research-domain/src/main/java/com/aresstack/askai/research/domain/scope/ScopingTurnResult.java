package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What ONE scoping turn of the assistant produces: what it says, what it wants to change about the scope,
 * what it still does not know, and which short lookups would help.
 * <p>
 * What it deliberately CANNOT contain: a complete scope object, a readiness verdict, or any workflow
 * command. The user owns the state machine — the assistant conducts the conversation and proposes changes,
 * it never advances, approves or submits anything.
 */
public final class ScopingTurnResult {

    private final String assistantMessage;
    private final ScopePatch patch;
    private final List<UnresolvedScopeIssue> unresolvedIssues;
    private final List<OrientationSuggestion> orientationSuggestions;

    public ScopingTurnResult(String assistantMessage, ScopePatch patch,
                             List<UnresolvedScopeIssue> unresolvedIssues,
                             List<OrientationSuggestion> orientationSuggestions) {
        if (assistantMessage == null || assistantMessage.trim().isEmpty()) {
            throw new IllegalArgumentException("assistantMessage must not be blank");
        }
        this.assistantMessage = assistantMessage.trim();
        this.patch = patch == null ? ScopePatch.empty() : patch;
        this.unresolvedIssues = copy(unresolvedIssues);
        this.orientationSuggestions = copy(orientationSuggestions);
    }

    /** The visible reply. */
    public String getAssistantMessage() {
        return assistantMessage;
    }

    /** The proposed scope changes; may be empty for a purely conversational turn. */
    public ScopePatch getPatch() {
        return patch;
    }

    /**
     * What the assistant considers still open AFTER this turn. Reporting an uncertainty is a valid result —
     * it is the honest alternative to guessing or to asking an ever narrower question.
     */
    public List<UnresolvedScopeIssue> getUnresolvedIssues() {
        return unresolvedIssues;
    }

    /** Short lookups the assistant proposes to reduce its own uncertainty; nothing runs by itself. */
    public List<OrientationSuggestion> getOrientationSuggestions() {
        return orientationSuggestions;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty()
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
