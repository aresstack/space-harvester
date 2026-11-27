package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * The EVIDENCE-BASED document outline proposal (post-orientation): built from the confirmed brief, the
 * topic clusters and their representative passages — never straight from a model prompt. Approval turns
 * the section proposals into an {@link OutlineRevision} with STABLE section ids.
 */
public final class OutlineProposal {

    public static final class SectionProposal {
        private final String proposedSectionId;
        private final String title;
        private final String parentId;
        private final List<String> topicIds;
        private final List<String> researchQuestions;
        private final List<String> evidenceRequirements;
        private final List<String> coveredPassageIds;
        private final List<String> identifiedGaps;

        public SectionProposal(String proposedSectionId, String title, String parentId,
                               List<String> topicIds, List<String> researchQuestions,
                               List<String> evidenceRequirements, List<String> coveredPassageIds,
                               List<String> identifiedGaps) {
            this.proposedSectionId = proposedSectionId == null ? "" : proposedSectionId;
            this.title = title == null ? "" : title;
            this.parentId = parentId == null ? "" : parentId;
            this.topicIds = copy(topicIds);
            this.researchQuestions = copy(researchQuestions);
            this.evidenceRequirements = copy(evidenceRequirements);
            this.coveredPassageIds = copy(coveredPassageIds);
            this.identifiedGaps = copy(identifiedGaps);
        }

        public String getProposedSectionId() {
            return proposedSectionId;
        }

        public String getTitle() {
            return title;
        }

        public String getParentId() {
            return parentId;
        }

        public List<String> getTopicIds() {
            return topicIds;
        }

        public List<String> getResearchQuestions() {
            return researchQuestions;
        }

        public List<String> getEvidenceRequirements() {
            return evidenceRequirements;
        }

        public List<String> getCoveredPassageIds() {
            return coveredPassageIds;
        }

        public List<String> getIdentifiedGaps() {
            return identifiedGaps;
        }
    }

    private final String proposalId;
    private final long basedOnBriefRevision;
    private final List<SectionProposal> sections;
    private final Lifecycle status;

    public OutlineProposal(String proposalId, long basedOnBriefRevision,
                           List<SectionProposal> sections, Lifecycle status) {
        this.proposalId = proposalId == null ? "" : proposalId;
        this.basedOnBriefRevision = basedOnBriefRevision;
        this.sections = sections == null ? Collections.<SectionProposal>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<SectionProposal>(sections));
        this.status = status == null ? Lifecycle.PROPOSED : status;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public OutlineProposal withStatus(Lifecycle newStatus) {
        return new OutlineProposal(proposalId, basedOnBriefRevision, sections, newStatus);
    }

    public String getProposalId() {
        return proposalId;
    }

    public long getBasedOnBriefRevision() {
        return basedOnBriefRevision;
    }

    public List<SectionProposal> getSections() {
        return sections;
    }

    public Lifecycle getStatus() {
        return status;
    }
}
