package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/** An ACCEPTED topic with a stable id — created only from an accepted {@link TopicProposal}. */
public final class Topic {

    private final String topicId;
    private final String acceptedFromProposalId;
    private final String title;
    private final List<String> passageIds;

    public Topic(String topicId, String acceptedFromProposalId, String title, List<String> passageIds) {
        this.topicId = topicId == null ? "" : topicId;
        this.acceptedFromProposalId = acceptedFromProposalId == null ? "" : acceptedFromProposalId;
        this.title = title == null ? "" : title;
        this.passageIds = passageIds == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(passageIds));
    }

    public String getTopicId() {
        return topicId;
    }

    public String getAcceptedFromProposalId() {
        return acceptedFromProposalId;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getPassageIds() {
        return passageIds;
    }
}
