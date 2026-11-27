package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * A normalized statement the document will make. Claims connect passages to sections: a claim can be
 * supported, contradicted or qualified by SEVERAL passages, and a draft paragraph later references its
 * claims explicitly — that is what makes the citation apparatus reliable.
 */
public final class Claim {

    private final String claimId;
    private final long revision;
    private final String normalizedStatement;
    private final List<String> topicIds;
    private final List<String> sectionIds;
    private final Lifecycle status;

    public Claim(String claimId, long revision, String normalizedStatement, List<String> topicIds,
                 List<String> sectionIds, Lifecycle status) {
        this.claimId = claimId == null ? "" : claimId;
        this.revision = revision;
        this.normalizedStatement = normalizedStatement == null ? "" : normalizedStatement;
        this.topicIds = copy(topicIds);
        this.sectionIds = copy(sectionIds);
        this.status = status == null ? Lifecycle.PROPOSED : status;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public Claim withStatus(Lifecycle newStatus) {
        return new Claim(claimId, revision, normalizedStatement, topicIds, sectionIds, newStatus);
    }

    public String getClaimId() {
        return claimId;
    }

    public long getRevision() {
        return revision;
    }

    public String getNormalizedStatement() {
        return normalizedStatement;
    }

    public List<String> getTopicIds() {
        return topicIds;
    }

    public List<String> getSectionIds() {
        return sectionIds;
    }

    public Lifecycle getStatus() {
        return status;
    }
}
