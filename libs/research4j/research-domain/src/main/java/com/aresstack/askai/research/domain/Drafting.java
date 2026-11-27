package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * The drafting objects: a paragraph carries its claims and citation passages EXPLICITLY — not "source 17
 * belongs to chapter 3" but "paragraph 3.2.4 asserts claims 41+43, claim 41 is supported by passage 88".
 * Grouped in one file on purpose: they only ever appear together.
 */
public final class Drafting {

    private Drafting() {
    }

    public static final class DraftParagraph {
        private final String paragraphId;
        private final String sectionId;
        private final String paragraphIntent;
        private final List<String> claimIds;
        private final List<String> citationPassageIds;
        private final long textRevision;
        private final String text;
        private final Lifecycle reviewStatus;

        public DraftParagraph(String paragraphId, String sectionId, String paragraphIntent,
                              List<String> claimIds, List<String> citationPassageIds,
                              long textRevision, String text, Lifecycle reviewStatus) {
            this.paragraphId = paragraphId == null ? "" : paragraphId;
            this.sectionId = sectionId == null ? "" : sectionId;
            this.paragraphIntent = paragraphIntent == null ? "" : paragraphIntent;
            this.claimIds = copy(claimIds);
            this.citationPassageIds = copy(citationPassageIds);
            this.textRevision = textRevision;
            this.text = text == null ? "" : text;
            this.reviewStatus = reviewStatus == null ? Lifecycle.PROPOSED : reviewStatus;
        }

        public String getParagraphId() {
            return paragraphId;
        }

        public String getSectionId() {
            return sectionId;
        }

        public String getParagraphIntent() {
            return paragraphIntent;
        }

        public List<String> getClaimIds() {
            return claimIds;
        }

        public List<String> getCitationPassageIds() {
            return citationPassageIds;
        }

        public long getTextRevision() {
            return textRevision;
        }

        public String getText() {
            return text;
        }

        public Lifecycle getReviewStatus() {
            return reviewStatus;
        }
    }

    public static final class DraftRevision {
        private final String draftId;
        private final long revision;
        private final String baselineId;
        private final List<DraftParagraph> paragraphs;

        public DraftRevision(String draftId, long revision, String baselineId,
                             List<DraftParagraph> paragraphs) {
            this.draftId = draftId == null ? "" : draftId;
            this.revision = revision;
            this.baselineId = baselineId == null ? "" : baselineId;
            this.paragraphs = paragraphs == null ? Collections.<DraftParagraph>emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<DraftParagraph>(paragraphs));
        }

        public String getDraftId() {
            return draftId;
        }

        public long getRevision() {
            return revision;
        }

        public String getBaselineId() {
            return baselineId;
        }

        public List<DraftParagraph> getParagraphs() {
            return paragraphs;
        }
    }

    public static final class DraftBaseline {
        private final String baselineId;
        private final String draftId;
        private final long draftRevision;
        private final Approval approval;

        public DraftBaseline(String baselineId, String draftId, long draftRevision, Approval approval) {
            this.baselineId = baselineId == null ? "" : baselineId;
            this.draftId = draftId == null ? "" : draftId;
            this.draftRevision = draftRevision;
            this.approval = approval;
        }

        public String getBaselineId() {
            return baselineId;
        }

        public String getDraftId() {
            return draftId;
        }

        public long getDraftRevision() {
            return draftRevision;
        }

        public Approval getApproval() {
            return approval;
        }
    }

    public static final class FinalRevision {
        private final String finalId;
        private final long revision;
        private final String draftBaselineId;
        private final Approval approval;

        public FinalRevision(String finalId, long revision, String draftBaselineId, Approval approval) {
            this.finalId = finalId == null ? "" : finalId;
            this.revision = revision;
            this.draftBaselineId = draftBaselineId == null ? "" : draftBaselineId;
            this.approval = approval;
        }

        public String getFinalId() {
            return finalId;
        }

        public long getRevision() {
            return revision;
        }

        public String getDraftBaselineId() {
            return draftBaselineId;
        }

        public Approval getApproval() {
            return approval;
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }
}
