package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * An APPROVED outline revision: sections carry STABLE ids that never change because a chapter is later
 * renamed or moved. A new approval SUPERSEDES the previous revision — which stays, fully traceable.
 */
public final class OutlineRevision {

    public static final class Section {
        private final String sectionId;
        private final String title;
        private final String parentId;
        private final List<String> topicIds;
        private final List<String> researchQuestions;
        private final List<String> evidenceRequirements;
        private final Lifecycle status;

        public Section(String sectionId, String title, String parentId, List<String> topicIds,
                       List<String> researchQuestions, List<String> evidenceRequirements,
                       Lifecycle status) {
            this.sectionId = sectionId == null ? "" : sectionId;
            this.title = title == null ? "" : title;
            this.parentId = parentId == null ? "" : parentId;
            this.topicIds = copy(topicIds);
            this.researchQuestions = copy(researchQuestions);
            this.evidenceRequirements = copy(evidenceRequirements);
            this.status = status == null ? Lifecycle.ACCEPTED : status;
        }

        public Section withStatus(Lifecycle newStatus) {
            return new Section(sectionId, title, parentId, topicIds, researchQuestions,
                    evidenceRequirements, newStatus);
        }

        public String getSectionId() {
            return sectionId;
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

        public Lifecycle getStatus() {
            return status;
        }
    }

    private final String outlineId;
    private final long revision;
    private final String approvedFromProposalId;
    private final List<Section> sections;
    private final Lifecycle status;

    public OutlineRevision(String outlineId, long revision, String approvedFromProposalId,
                           List<Section> sections, Lifecycle status) {
        this.outlineId = outlineId == null ? "" : outlineId;
        this.revision = revision;
        this.approvedFromProposalId = approvedFromProposalId == null ? "" : approvedFromProposalId;
        this.sections = sections == null ? Collections.<Section>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<Section>(sections));
        this.status = status == null ? Lifecycle.ACCEPTED : status;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public OutlineRevision withStatus(Lifecycle newStatus) {
        return new OutlineRevision(outlineId, revision, approvedFromProposalId, sections, newStatus);
    }

    public OutlineRevision withSections(List<Section> newSections) {
        return new OutlineRevision(outlineId, revision, approvedFromProposalId, newSections, status);
    }

    public String getOutlineId() {
        return outlineId;
    }

    public long getRevision() {
        return revision;
    }

    public String getApprovedFromProposalId() {
        return approvedFromProposalId;
    }

    public List<Section> getSections() {
        return sections;
    }

    public Lifecycle getStatus() {
        return status;
    }
}
