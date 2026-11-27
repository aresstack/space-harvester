package com.aresstack.askai.research.domain;

import java.util.Collections;
import java.util.List;

/**
 * The RESULT of {@code buildEvidenceReview}: per section its claims with supporting/contradicting/weak
 * evidence, the questions still uncovered and a coverage ratio. This is what "Belege prüfen" shows —
 * and the precondition for an {@link EvidenceBaseline}.
 */
public final class EvidenceReview {

    public static final class ClaimEvidence {
        private final String claimId;
        private final List<String> supportingPassageIds;
        private final List<String> contradictingPassageIds;
        private final List<String> qualifyingPassageIds;
        private final List<String> contextPassageIds;

        public ClaimEvidence(String claimId, List<String> supportingPassageIds,
                             List<String> contradictingPassageIds, List<String> qualifyingPassageIds,
                             List<String> contextPassageIds) {
            this.claimId = claimId == null ? "" : claimId;
            this.supportingPassageIds = copy(supportingPassageIds);
            this.contradictingPassageIds = copy(contradictingPassageIds);
            this.qualifyingPassageIds = copy(qualifyingPassageIds);
            this.contextPassageIds = copy(contextPassageIds);
        }

        public String getClaimId() {
            return claimId;
        }

        public List<String> getSupportingPassageIds() {
            return supportingPassageIds;
        }

        public List<String> getContradictingPassageIds() {
            return contradictingPassageIds;
        }

        public List<String> getQualifyingPassageIds() {
            return qualifyingPassageIds;
        }

        public List<String> getContextPassageIds() {
            return contextPassageIds;
        }

        public boolean isSupported() {
            return !supportingPassageIds.isEmpty();
        }

        public boolean hasContradiction() {
            return !contradictingPassageIds.isEmpty();
        }
    }

    public static final class SectionReview {
        private final String sectionId;
        private final List<ClaimEvidence> claims;
        private final List<String> uncoveredQuestions;
        private final double coverage;

        public SectionReview(String sectionId, List<ClaimEvidence> claims,
                             List<String> uncoveredQuestions, double coverage) {
            this.sectionId = sectionId == null ? "" : sectionId;
            this.claims = claims == null ? Collections.<ClaimEvidence>emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<ClaimEvidence>(claims));
            this.uncoveredQuestions = copy(uncoveredQuestions);
            this.coverage = coverage;
        }

        public String getSectionId() {
            return sectionId;
        }

        public List<ClaimEvidence> getClaims() {
            return claims;
        }

        public List<String> getUncoveredQuestions() {
            return uncoveredQuestions;
        }

        public double getCoverage() {
            return coverage;
        }
    }

    private final long outlineRevision;
    private final List<SectionReview> sections;

    public EvidenceReview(long outlineRevision, List<SectionReview> sections) {
        this.outlineRevision = outlineRevision;
        this.sections = sections == null ? Collections.<SectionReview>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<SectionReview>(sections));
    }

    private static List<String> copy(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    public long getOutlineRevision() {
        return outlineRevision;
    }

    public List<SectionReview> getSections() {
        return sections;
    }
}
