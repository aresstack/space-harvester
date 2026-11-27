package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Something that is genuinely UNCLEAR about the scope — modelled explicitly because "I don't know that" is a
 * valid state for the assistant. Without a place to put it, an assistant lacking the domain map has only two
 * bad options: guess, or ask an ever narrower question. With it, the uncertainty can be carried forward, be
 * answered by the user, or motivate a short orientation search.
 * <p>
 * An issue does NOT block anything: the user owns the state machine and may confirm the scope at any time.
 */
public final class UnresolvedScopeIssue {

    /** How much the answer would change the research mandate. */
    public enum Significance {
        /** Nice to know; the mandate stands either way. */
        MINOR,
        /** Shifts emphasis or wording of the mandate. */
        SIGNIFICANT,
        /** Could change WHAT is being researched at all. */
        CRITICAL
    }

    private final String issueId;
    private final String description;
    private final List<String> affectedFacetIds;
    private final Significance significance;

    public UnresolvedScopeIssue(String issueId, String description, List<String> affectedFacetIds,
                                Significance significance) {
        if (issueId == null || issueId.trim().isEmpty()) {
            throw new IllegalArgumentException("issueId must not be empty");
        }
        this.issueId = issueId.trim();
        this.description = description == null ? "" : description.trim();
        this.affectedFacetIds = affectedFacetIds == null || affectedFacetIds.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(affectedFacetIds));
        this.significance = significance == null ? Significance.SIGNIFICANT : significance;
    }

    public String getIssueId() {
        return issueId;
    }

    /** The open question in plain language, as the assistant would put it to the user. */
    public String getDescription() {
        return description;
    }

    /** The facets this uncertainty touches; empty when it concerns the whole scope. */
    public List<String> getAffectedFacetIds() {
        return affectedFacetIds;
    }

    public Significance getSignificance() {
        return significance;
    }

    public boolean isCritical() {
        return significance == Significance.CRITICAL;
    }
}
