package com.aresstack.askai.research.domain;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The domain invariants: no delete, revisions supersede, approvals gate confirmed-state changes. */
public class ResearchProjectInvariantsTest {

    private static final Approval USER = new Approval("user", 1000L, "");

    private ResearchProject project() {
        return new ResearchProject("p1", IdSequence.counting());
    }

    private ResearchBrief brief(String question) {
        return new ResearchBrief("", 0L, question, "goal", "audience",
                Arrays.asList("scope"), null, "report", null, null,
                Arrays.asList("What is X?"));
    }

    private ResearchProject withOutline() {
        ResearchProject p = project();
        p.confirmResearchBrief(brief("Q"), USER);
        p.recordSourceCapture(new SourceCapture("cap-1", "src-1", "https://a", 1L, "h", "T", "",
                Collections.<SourceCapture.StructuralBlock>emptyList()));
        p.recordPassages(Arrays.asList(new Passage("pass-1", "cap-1",
                Arrays.asList("s1"), "", "text", "fp")));
        p.proposeOutline(new OutlineProposal("op-1", 1L, Arrays.asList(
                new OutlineProposal.SectionProposal("sp-1", "Chapter", "", null,
                        Arrays.asList("What is X?"), null, null, null)), Lifecycle.PROPOSED));
        p.approveOutline("op-1", USER);
        return p;
    }

    @Test
    public void confirmedStateChangesRequireAnApproval() {
        ResearchProject p = project();
        try {
            p.confirmResearchBrief(brief("Q"), null);
            fail("approval required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("REQUIRES an explicit approval"));
        }
    }

    @Test
    public void aNewOutlineApprovalSupersedesButNeverDeletesTheOldRevision() {
        ResearchProject p = withOutline();
        p.proposeOutline(new OutlineProposal("op-2", 1L, Arrays.asList(
                new OutlineProposal.SectionProposal("sp-2", "Reworked", "", null, null, null,
                        null, null)), Lifecycle.PROPOSED));
        p.approveOutline("op-2", USER);
        assertEquals("both revisions exist", 2, p.outlineRevisions().size());
        assertEquals(Lifecycle.SUPERSEDED, p.outlineRevisions().get(0).getStatus());
        assertEquals(Lifecycle.ACCEPTED, p.activeOutline().getStatus());
        // Stable section ids: the new revision has NEW ids, the old ones remain readable.
        assertTrue(!p.outlineRevisions().get(0).getSections().get(0).getSectionId()
                .equals(p.activeOutline().getSections().get(0).getSectionId()));
    }

    @Test
    public void excludingEvidenceIsALifecycleTransitionNeverADelete() {
        ResearchProject p = withOutline();
        String sectionId = p.activeOutline().getSections().get(0).getSectionId();
        Claim claim = p.recordClaim("X is true", null, Arrays.asList(sectionId));
        EvidenceLink link = p.linkEvidence(claim.getClaimId(), "pass-1",
                EvidenceRelation.SUPPORTS, 1, 1, 1);
        p.excludeEvidence(link.getLinkId(), USER);
        assertEquals("the link still exists", 1, p.evidenceLinks().size());
        assertEquals(Lifecycle.EXCLUDED,
                p.evidenceLinks().get(link.getLinkId()).getStatus());
    }

    @Test
    public void evidenceMustReferenceAPersistedPassageNeverAnObservation() {
        ResearchProject p = withOutline();
        String sectionId = p.activeOutline().getSections().get(0).getSectionId();
        Claim claim = p.recordClaim("X", null, Arrays.asList(sectionId));
        p.recordSearchObservation(new SearchObservation("obs-1", "q", "t", "snippet",
                "https://x", "BRAVE", 1, 1L));
        try {
            p.linkEvidence(claim.getClaimId(), "obs-1", EvidenceRelation.SUPPORTS, 1, 1, 1);
            fail("observations are discovery data, never evidence");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("never SERP observations"));
        }
    }

    @Test
    public void approvingEvidenceWithOpenGapsRequiresAcceptedLimitations() {
        ResearchProject p = withOutline();
        String sectionId = p.activeOutline().getSections().get(0).getSectionId();
        p.recordResearchGap(sectionId, "battery life data missing");
        EvidenceReview review = p.buildEvidenceReview();
        try {
            p.approveEvidenceBaseline(review, null, USER);
            fail("gaps require accepted limitations");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("ACCEPTED limitations"));
        }
        EvidenceBaseline baseline = p.approveEvidenceBaseline(review,
                Arrays.asList(new AcceptedLimitation("l1", "battery life data missing", USER)), USER);
        assertTrue(baseline.getKnownGapDescriptions().contains("battery life data missing"));
    }

    @Test
    public void draftingRequiresTheEvidenceBaselineFirst() {
        ResearchProject p = withOutline();
        try {
            p.recordDraftRevision(Collections.<Drafting.DraftParagraph>emptyList());
            fail("baseline first");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("evidence baseline"));
        }
    }

    @Test
    public void aChangedBriefMarksConfirmedSectionsStaleInsteadOfRollingBack() {
        ResearchProject p = withOutline();
        p.confirmResearchBrief(brief("Q, but sharper"), USER);
        assertEquals("old revisions stay", 2, p.briefRevisions().size());
        assertEquals(Lifecycle.STALE, p.activeOutline().getSections().get(0).getStatus());
    }

    @Test
    public void recapturingAPageIsANewCaptureNeverAnOverwrite() {
        ResearchProject p = project();
        p.recordSourceCapture(new SourceCapture("cap-1", "src-1", "https://a", 1L, "h1", "T", "",
                null));
        try {
            p.recordSourceCapture(new SourceCapture("cap-1", "src-1", "https://a", 2L, "h2", "T", "",
                    null));
            fail("captures are immutable");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("immutable"));
        }
    }

    @Test
    public void recordingTheIdenticalCaptureAgainIsIdempotent() {
        ResearchProject p = project();
        p.recordSourceCapture(new SourceCapture("cap-1", "src-1", "https://a", 1L, "h", "T", "", null));
        // Same id + same content (checksum) → no duplicate, no throw, returns the existing capture.
        SourceCapture again =
                p.recordSourceCapture(new SourceCapture("cap-1", "src-1", "https://a", 1L, "h", "T", "", null));
        assertEquals(1, p.captures().size());
        assertEquals("cap-1", again.getCaptureId());
    }

    @Test
    public void recordingTheSameSentencesAndPassagesAgainAddsNoDuplicates() {
        ResearchProject p = project();
        p.recordSourceCapture(new SourceCapture("cap-1", "src-1", "https://a", 1L, "h", "T", "", null));
        p.recordSentences(Arrays.asList(new Sentence("cap-1#s0", "cap-1", "b1", 0, "Text.")));
        p.recordSentences(Arrays.asList(new Sentence("cap-1#s0", "cap-1", "b1", 0, "Text.")));
        assertEquals("idempotent by deterministic sentence id", 1, p.sentences().size());

        p.recordPassages(Arrays.asList(
                new Passage("cap-1#p0@seg-v1-fp", "cap-1", Arrays.asList("cap-1#s0"), "", "Text.", "fp")));
        p.recordPassages(Arrays.asList(
                new Passage("cap-1#p0@seg-v1-fp", "cap-1", Arrays.asList("cap-1#s0"), "", "Text.", "fp")));
        assertEquals("idempotent by deterministic passage id", 1, p.passages().size());
    }

    @Test
    public void eventsArePublishedInOperationOrder() {
        ResearchProject p = withOutline();
        assertEquals("ResearchBriefConfirmed", p.events().get(0).getName());
        assertTrue(p.events().get(p.events().size() - 1).getName().equals("OutlineApproved"));
    }
}
