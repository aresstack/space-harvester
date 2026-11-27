package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * A clustering RESULT offered for acceptance: member passages, representatives, a suggested title and a
 * confidence. AI may phrase title/summary from the representative passages — cluster MEMBERSHIP never
 * depends on that phrasing, and the proposal never contains unevidenced content.
 */
public final class TopicProposal {

    private final String proposalId;
    private final List<String> memberPassageIds;
    private final List<String> representativePassageIds;
    private final String suggestedTitle;
    private final String suggestedSummary;
    private final double confidence;
    private final Lifecycle status;

    public TopicProposal(String proposalId, List<String> memberPassageIds,
                         List<String> representativePassageIds, String suggestedTitle,
                         String suggestedSummary, double confidence, Lifecycle status) {
        this.proposalId = proposalId == null ? "" : proposalId;
        this.memberPassageIds = copy(memberPassageIds);
        this.representativePassageIds = copy(representativePassageIds);
        this.suggestedTitle = suggestedTitle == null ? "" : suggestedTitle;
        this.suggestedSummary = suggestedSummary == null ? "" : suggestedSummary;
        this.confidence = confidence;
        this.status = status == null ? Lifecycle.PROPOSED : status;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public TopicProposal withStatus(Lifecycle newStatus) {
        return new TopicProposal(proposalId, memberPassageIds, representativePassageIds,
                suggestedTitle, suggestedSummary, confidence, newStatus);
    }

    public String getProposalId() {
        return proposalId;
    }

    public List<String> getMemberPassageIds() {
        return memberPassageIds;
    }

    public List<String> getRepresentativePassageIds() {
        return representativePassageIds;
    }

    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    public String getSuggestedSummary() {
        return suggestedSummary;
    }

    public double getConfidence() {
        return confidence;
    }

    public Lifecycle getStatus() {
        return status;
    }
}
