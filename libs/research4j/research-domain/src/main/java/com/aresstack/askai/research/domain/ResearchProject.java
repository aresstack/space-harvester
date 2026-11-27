package com.aresstack.askai.research.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The research project AGGREGATE: every fachliche operation of the methodology chain runs through this
 * class, which enforces the invariants — most importantly NO DELETE: there is no {@code deleteSection()},
 * {@code deleteClaim()} or any other physically removing operation. Confirmed objects change only by a
 * NEW revision (the old one becomes {@link Lifecycle#SUPERSEDED}), exclusions are lifecycle transitions,
 * and dependents of a changed confirmation become {@link Lifecycle#STALE}, never rolled back.
 *
 * <p>Automatic analysis may ADD freely (captures, sentences, passages, proposals, claims, links, gaps);
 * changing the ACTIVE CONFIRMED project state requires an {@link Approval}. Domain events are appended
 * synchronously and in order AFTER each successful operation.</p>
 *
 * <p>Pure Java 8. The aggregate owns no clock and no randomness: timestamps arrive in {@link Approval}s
 * and captures, ids come from the injected {@link IdSequence}.</p>
 */
public final class ResearchProject {

    private final String projectId;
    private final IdSequence ids;
    private final List<DomainEvent> events = new ArrayList<DomainEvent>();

    private final List<ResearchBrief> briefRevisions = new ArrayList<ResearchBrief>();
    private boolean briefConfirmed;
    private final List<OrientationResearchRun> orientationRuns = new ArrayList<OrientationResearchRun>();
    private final List<SearchObservation> observations = new ArrayList<SearchObservation>();
    private final Map<String, SourceCapture> captures = new LinkedHashMap<String, SourceCapture>();
    private final Map<String, Sentence> sentences = new LinkedHashMap<String, Sentence>();
    private final Map<String, Passage> passages = new LinkedHashMap<String, Passage>();
    private final Map<String, TopicProposal> topicProposals = new LinkedHashMap<String, TopicProposal>();
    private final Map<String, Topic> topics = new LinkedHashMap<String, Topic>();
    private final List<ConceptPaper> conceptRevisions = new ArrayList<ConceptPaper>();
    private final Map<String, OutlineProposal> outlineProposals =
            new LinkedHashMap<String, OutlineProposal>();
    private final List<OutlineRevision> outlineRevisions = new ArrayList<OutlineRevision>();
    private final Map<String, Claim> claims = new LinkedHashMap<String, Claim>();
    private final Map<String, EvidenceLink> evidenceLinks = new LinkedHashMap<String, EvidenceLink>();
    private final Map<String, ResearchGap> gaps = new LinkedHashMap<String, ResearchGap>();
    private final List<DetailedResearchPlan> detailPlans = new ArrayList<DetailedResearchPlan>();
    private final List<EvidenceBaseline> evidenceBaselines = new ArrayList<EvidenceBaseline>();
    private final List<Drafting.DraftRevision> draftRevisions = new ArrayList<Drafting.DraftRevision>();
    private final List<Drafting.DraftBaseline> draftBaselines = new ArrayList<Drafting.DraftBaseline>();
    private final List<Drafting.FinalRevision> finalRevisions = new ArrayList<Drafting.FinalRevision>();
    private final List<ChangeRequest> changeRequests = new ArrayList<ChangeRequest>();

    public ResearchProject(String projectId, IdSequence ids) {
        this.projectId = projectId == null ? "" : projectId;
        this.ids = ids == null ? IdSequence.counting() : ids;
    }

    // ------------------------------------------------------------------ brief + orientation

    /** A new brief revision (first or changed); confirming requires an approval. */
    public ResearchBrief confirmResearchBrief(ResearchBrief candidate, Approval approval) {
        requireApproval(approval, "confirmResearchBrief");
        long revision = briefRevisions.size() + 1L;
        ResearchBrief confirmed = new ResearchBrief(
                candidate.getBriefId().isEmpty() ? ids.next("brief") : candidate.getBriefId(),
                revision, candidate.getResearchQuestion(), candidate.getGoal(), candidate.getAudience(),
                candidate.getScope(), candidate.getOutOfScope(), candidate.getExpectedResult(),
                candidate.getQualityRequirements(), candidate.getSourceGuidelines(),
                candidate.getInitialQuestions());
        briefRevisions.add(confirmed);
        briefConfirmed = true;
        if (revision > 1) {
            // A changed confirmed brief makes dependent confirmations STALE — nothing is rolled back.
            markActiveOutlineSectionsStale();
        }
        publish("ResearchBriefConfirmed", confirmed.getBriefId());
        return confirmed;
    }

    /** Starting orientation research is approval-gated (budget!); results later are not. */
    public OrientationResearchRun startOrientationResearch(Approval approval) {
        requireBrief("startOrientationResearch");
        requireApproval(approval, "startOrientationResearch");
        OrientationResearchRun run = new OrientationResearchRun(
                ids.next("orientation-run"), activeBrief().getRevision(), approval);
        orientationRuns.add(run);
        publish("OrientationResearchStarted", run.getRunId());
        return run;
    }

    // ------------------------------------------------------------------ facts (no approval needed)

    /** Discovery data only — never evidence. */
    public SearchObservation recordSearchObservation(SearchObservation observation) {
        observations.add(observation);
        publish("SearchObservationRecorded", observation.getObservationId());
        return observation;
    }

    /**
     * Immutable capture; a re-captured page is a NEW capture (new id), the old one stays. Reprocess-idempotent:
     * recording the SAME capture id with the SAME content (checksum) again is a no-op that returns the existing
     * capture (no duplicate, no event) — only a SAME id with DIFFERENT content violates immutability and throws.
     */
    public SourceCapture recordSourceCapture(SourceCapture capture) {
        SourceCapture existing = captures.get(capture.getCaptureId());
        if (existing != null) {
            if (existing.getChecksum().equals(capture.getChecksum())) {
                return existing; // idempotent: identical capture already recorded
            }
            throw new IllegalArgumentException("capture " + capture.getCaptureId()
                    + " already exists with different content (captures are immutable)");
        }
        captures.put(capture.getCaptureId(), capture);
        publish("SourceCaptureRecorded", capture.getCaptureId());
        return capture;
    }

    /** Idempotent by (deterministic) sentence id: re-recording the same sentences adds nothing and no event. */
    public void recordSentences(List<Sentence> segmented) {
        int added = 0;
        for (Sentence sentence : segmented) {
            requireKnownCapture(sentence.getCaptureId(), "recordSentences");
            if (!sentences.containsKey(sentence.getSentenceId())) {
                sentences.put(sentence.getSentenceId(), sentence);
                added++;
            }
        }
        if (added > 0) {
            publish("SentencesRecorded", String.valueOf(added));
        }
    }

    /** Idempotent by (deterministic) passage id: re-recording the same passages adds nothing and no event. */
    public void recordPassages(List<Passage> formed) {
        int added = 0;
        for (Passage passage : formed) {
            requireKnownCapture(passage.getCaptureId(), "recordPassages");
            if (!passages.containsKey(passage.getPassageId())) {
                passages.put(passage.getPassageId(), passage);
                added++;
            }
        }
        if (added > 0) {
            publish("PassagesRecorded", String.valueOf(added));
        }
    }

    public TopicProposal proposeTopic(TopicProposal proposal) {
        for (String passageId : proposal.getMemberPassageIds()) {
            requireKnownPassage(passageId, "proposeTopic");
        }
        topicProposals.put(proposal.getProposalId(), proposal);
        publish("TopicProposed", proposal.getProposalId());
        return proposal;
    }

    /** Accepting a proposal creates the stable Topic; rejecting keeps the proposal (REJECTED). */
    public Topic acceptTopicProposal(String proposalId) {
        TopicProposal proposal = requireTopicProposal(proposalId);
        topicProposals.put(proposalId, proposal.withStatus(Lifecycle.ACCEPTED));
        Topic topic = new Topic(ids.next("topic"), proposalId, proposal.getSuggestedTitle(),
                proposal.getMemberPassageIds());
        topics.put(topic.getTopicId(), topic);
        publish("TopicAccepted", topic.getTopicId());
        return topic;
    }

    public void rejectTopicProposal(String proposalId) {
        TopicProposal proposal = requireTopicProposal(proposalId);
        topicProposals.put(proposalId, proposal.withStatus(Lifecycle.REJECTED));
        publish("TopicRejected", proposalId);
    }

    // ------------------------------------------------------------------ concept + outline

    public ConceptPaper proposeConcept(ConceptPaper proposal) {
        long revision = conceptRevisions.size() + 1L;
        ConceptPaper stored = new ConceptPaper(
                proposal.getConceptId().isEmpty() ? ids.next("concept") : proposal.getConceptId(),
                revision, proposal.getStartingSituation(), proposal.getGoal(),
                proposal.getKeyQuestions(), proposal.getRecognizedTopicIds(),
                proposal.getArgumentationLine(), proposal.getEvidenceRequirements(),
                proposal.getOpenQuestions(), proposal.getKnownLimitations(), Lifecycle.PROPOSED);
        conceptRevisions.add(stored);
        publish("ConceptProposed", stored.getConceptId());
        return stored;
    }

    public ConceptPaper approveConcept(Approval approval) {
        requireApproval(approval, "approveConcept");
        if (conceptRevisions.isEmpty()) {
            throw new IllegalStateException("no concept proposal to approve");
        }
        int last = conceptRevisions.size() - 1;
        ConceptPaper approved = conceptRevisions.get(last).withStatus(Lifecycle.ACCEPTED);
        conceptRevisions.set(last, approved);
        publish("ConceptApproved", approved.getConceptId());
        return approved;
    }

    public OutlineProposal proposeOutline(OutlineProposal proposal) {
        outlineProposals.put(proposal.getProposalId(), proposal);
        publish("OutlineProposed", proposal.getProposalId());
        return proposal;
    }

    /**
     * Approval turns the proposal into the ACTIVE {@link OutlineRevision} with STABLE section ids. A
     * previously active revision becomes SUPERSEDED and stays — removing or merging confirmed chapters
     * is only possible through exactly this approved path.
     */
    public OutlineRevision approveOutline(String proposalId, Approval approval) {
        requireApproval(approval, "approveOutline");
        OutlineProposal proposal = outlineProposals.get(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("unknown outline proposal " + proposalId);
        }
        outlineProposals.put(proposalId, proposal.withStatus(Lifecycle.ACCEPTED));
        if (!outlineRevisions.isEmpty()) {
            int last = outlineRevisions.size() - 1;
            outlineRevisions.set(last, outlineRevisions.get(last).withStatus(Lifecycle.SUPERSEDED));
        }
        List<OutlineRevision.Section> sections = new ArrayList<OutlineRevision.Section>();
        for (OutlineProposal.SectionProposal section : proposal.getSections()) {
            sections.add(new OutlineRevision.Section(ids.next("section"), section.getTitle(),
                    section.getParentId(), section.getTopicIds(), section.getResearchQuestions(),
                    section.getEvidenceRequirements(), Lifecycle.ACCEPTED));
        }
        OutlineRevision revision = new OutlineRevision(ids.next("outline"),
                outlineRevisions.size() + 1L, proposalId, sections, Lifecycle.ACCEPTED);
        outlineRevisions.add(revision);
        publish("OutlineApproved", revision.getOutlineId());
        return revision;
    }

    public void markSectionStale(String sectionId) {
        OutlineRevision active = requireActiveOutline("markSectionStale");
        List<OutlineRevision.Section> updated = new ArrayList<OutlineRevision.Section>();
        boolean found = false;
        for (OutlineRevision.Section section : active.getSections()) {
            if (section.getSectionId().equals(sectionId)) {
                updated.add(section.withStatus(Lifecycle.STALE));
                found = true;
            } else {
                updated.add(section);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("unknown section " + sectionId);
        }
        outlineRevisions.set(outlineRevisions.size() - 1, active.withSections(updated));
        publish("SectionMarkedStale", sectionId);
    }

    // ------------------------------------------------------------------ claims + evidence

    public Claim recordClaim(String normalizedStatement, List<String> topicIds,
                             List<String> sectionIds) {
        for (String sectionId : sectionIds == null ? Collections.<String>emptyList() : sectionIds) {
            requireKnownSection(sectionId, "recordClaim");
        }
        Claim claim = new Claim(ids.next("claim"), 1L, normalizedStatement, topicIds, sectionIds,
                Lifecycle.PROPOSED);
        claims.put(claim.getClaimId(), claim);
        publish("ClaimRecorded", claim.getClaimId());
        return claim;
    }

    /** Evidence must reference a PERSISTED passage — SERP observations can never be linked. */
    public EvidenceLink linkEvidence(String claimId, String passageId, EvidenceRelation relation,
                                     double relevance, double reliability, double confidence) {
        if (!claims.containsKey(claimId)) {
            throw new IllegalArgumentException("unknown claim " + claimId);
        }
        requireKnownPassage(passageId, "linkEvidence");
        EvidenceLink link = new EvidenceLink(ids.next("evidence"), claimId, passageId, relation,
                relevance, reliability, confidence, Lifecycle.PROPOSED);
        evidenceLinks.put(link.getLinkId(), link);
        publish("EvidenceLinked", link.getLinkId());
        return link;
    }

    /** Excluding accepted evidence changes the ACTIVE confirmed state → approval required; link stays. */
    public EvidenceLink excludeEvidence(String linkId, Approval approval) {
        requireApproval(approval, "excludeEvidence");
        EvidenceLink link = evidenceLinks.get(linkId);
        if (link == null) {
            throw new IllegalArgumentException("unknown evidence link " + linkId);
        }
        EvidenceLink excluded = link.withStatus(Lifecycle.EXCLUDED);
        evidenceLinks.put(linkId, excluded);
        publish("EvidenceExcluded", linkId);
        return excluded;
    }

    public ResearchGap recordResearchGap(String sectionId, String description) {
        requireKnownSection(sectionId, "recordResearchGap");
        ResearchGap gap = new ResearchGap(ids.next("gap"), sectionId, description, Lifecycle.PROPOSED);
        gaps.put(gap.getGapId(), gap);
        publish("ResearchGapRecorded", gap.getGapId());
        return gap;
    }

    public DetailedResearchPlan recordDetailedResearchPlan(DetailedResearchPlan plan) {
        requireKnownSection(plan.getSectionId(), "recordDetailedResearchPlan");
        detailPlans.add(plan);
        publish("DetailedResearchPlanned", plan.getPlanId());
        return plan;
    }

    // ------------------------------------------------------------------ evidence review + baseline

    /**
     * BuildEvidenceReview: the real operation behind "Belege prüfen" — per active section its claims with
     * supporting/contradicting/qualifying evidence (EXCLUDED links do not count), the questions without
     * any supported claim, and a coverage ratio (supported claims / all claims of the section).
     */
    public EvidenceReview buildEvidenceReview() {
        OutlineRevision active = requireActiveOutline("buildEvidenceReview");
        List<EvidenceReview.SectionReview> sections = new ArrayList<EvidenceReview.SectionReview>();
        for (OutlineRevision.Section section : active.getSections()) {
            List<EvidenceReview.ClaimEvidence> sectionClaims =
                    new ArrayList<EvidenceReview.ClaimEvidence>();
            int supported = 0;
            for (Claim claim : claims.values()) {
                if (!claim.getSectionIds().contains(section.getSectionId())) {
                    continue;
                }
                List<String> supports = new ArrayList<String>();
                List<String> contradicts = new ArrayList<String>();
                List<String> qualifies = new ArrayList<String>();
                List<String> context = new ArrayList<String>();
                for (EvidenceLink link : evidenceLinks.values()) {
                    if (!link.getClaimId().equals(claim.getClaimId())
                            || link.getStatus() == Lifecycle.EXCLUDED) {
                        continue;
                    }
                    switch (link.getRelation()) {
                        case SUPPORTS: supports.add(link.getPassageId()); break;
                        case CONTRADICTS: contradicts.add(link.getPassageId()); break;
                        case QUALIFIES: qualifies.add(link.getPassageId()); break;
                        default: context.add(link.getPassageId()); break;
                    }
                }
                EvidenceReview.ClaimEvidence evidence = new EvidenceReview.ClaimEvidence(
                        claim.getClaimId(), supports, contradicts, qualifies, context);
                if (evidence.isSupported()) {
                    supported++;
                }
                sectionClaims.add(evidence);
            }
            List<String> uncovered = new ArrayList<String>();
            if (sectionClaims.isEmpty()) {
                uncovered.addAll(section.getResearchQuestions());
            }
            double coverage = sectionClaims.isEmpty() ? 0.0
                    : supported / (double) sectionClaims.size();
            sections.add(new EvidenceReview.SectionReview(section.getSectionId(), sectionClaims,
                    uncovered, coverage));
        }
        publish("EvidenceReviewBuilt", active.getOutlineId());
        return new EvidenceReview(active.getRevision(), sections);
    }

    /**
     * The baseline FIRST, the state transition afterwards: approving evidence persists an immutable
     * {@link EvidenceBaseline} built from a review of the ACTIVE outline revision. Known contradictions
     * and gaps are recorded in the baseline — approving despite them requires accepted limitations.
     */
    public EvidenceBaseline approveEvidenceBaseline(EvidenceReview review,
                                                    List<AcceptedLimitation> limitations,
                                                    Approval approval) {
        requireApproval(approval, "approveEvidenceBaseline");
        OutlineRevision active = requireActiveOutline("approveEvidenceBaseline");
        if (review.getOutlineRevision() != active.getRevision()) {
            throw new IllegalStateException("the evidence review is stale: it reviews outline revision "
                    + review.getOutlineRevision() + ", active is " + active.getRevision());
        }
        List<String> claimIds = new ArrayList<String>();
        List<String> contradictions = new ArrayList<String>();
        List<String> gapDescriptions = new ArrayList<String>();
        for (EvidenceReview.SectionReview section : review.getSections()) {
            for (EvidenceReview.ClaimEvidence claim : section.getClaims()) {
                claimIds.add(claim.getClaimId());
                if (claim.hasContradiction()) {
                    contradictions.add(claim.getClaimId());
                }
            }
            gapDescriptions.addAll(section.getUncoveredQuestions());
        }
        for (ResearchGap gap : gaps.values()) {
            if (gap.getStatus() != Lifecycle.TOMBSTONED) {
                gapDescriptions.add(gap.getDescription());
            }
        }
        boolean hasOpenGaps = !gapDescriptions.isEmpty();
        if (hasOpenGaps && (limitations == null || limitations.isEmpty())) {
            throw new IllegalStateException("open evidence gaps require ACCEPTED limitations "
                    + "(never a silent equation of incomplete with sufficient evidence): "
                    + gapDescriptions);
        }
        List<String> linkIds = new ArrayList<String>();
        for (EvidenceLink link : evidenceLinks.values()) {
            if (link.getStatus() != Lifecycle.EXCLUDED) {
                linkIds.add(link.getLinkId());
            }
        }
        EvidenceBaseline baseline = new EvidenceBaseline(ids.next("evidence-baseline"),
                active.getRevision(), claimIds, linkIds, contradictions, gapDescriptions,
                limitations, approval);
        evidenceBaselines.add(baseline);
        publish("EvidenceBaselineApproved", baseline.getBaselineId());
        return baseline;
    }

    // ------------------------------------------------------------------ drafting + final

    public Drafting.DraftRevision recordDraftRevision(List<Drafting.DraftParagraph> paragraphs) {
        if (evidenceBaselines.isEmpty()) {
            throw new IllegalStateException("drafting requires an approved evidence baseline");
        }
        for (Drafting.DraftParagraph paragraph : paragraphs) {
            requireKnownSection(paragraph.getSectionId(), "recordDraftRevision");
            for (String claimId : paragraph.getClaimIds()) {
                if (!claims.containsKey(claimId)) {
                    throw new IllegalArgumentException("unknown claim " + claimId);
                }
            }
            for (String passageId : paragraph.getCitationPassageIds()) {
                requireKnownPassage(passageId, "recordDraftRevision");
            }
        }
        EvidenceBaseline latest = evidenceBaselines.get(evidenceBaselines.size() - 1);
        Drafting.DraftRevision revision = new Drafting.DraftRevision(ids.next("draft"),
                draftRevisions.size() + 1L, latest.getBaselineId(), paragraphs);
        draftRevisions.add(revision);
        publish("DraftRevisionRecorded", revision.getDraftId());
        return revision;
    }

    public Drafting.DraftBaseline approveDraftBaseline(Approval approval) {
        requireApproval(approval, "approveDraftBaseline");
        if (draftRevisions.isEmpty()) {
            throw new IllegalStateException("no draft revision to approve");
        }
        Drafting.DraftRevision latest = draftRevisions.get(draftRevisions.size() - 1);
        Drafting.DraftBaseline baseline = new Drafting.DraftBaseline(ids.next("draft-baseline"),
                latest.getDraftId(), latest.getRevision(), approval);
        draftBaselines.add(baseline);
        publish("DraftBaselineApproved", baseline.getBaselineId());
        return baseline;
    }

    public Drafting.FinalRevision approveFinalRevision(Approval approval) {
        requireApproval(approval, "approveFinalRevision");
        if (draftBaselines.isEmpty()) {
            throw new IllegalStateException("a final revision requires an approved draft baseline");
        }
        Drafting.DraftBaseline latest = draftBaselines.get(draftBaselines.size() - 1);
        Drafting.FinalRevision finalRevision = new Drafting.FinalRevision(ids.next("final"),
                finalRevisions.size() + 1L, latest.getBaselineId(), approval);
        finalRevisions.add(finalRevision);
        publish("FinalRevisionApproved", finalRevision.getFinalId());
        return finalRevision;
    }

    public ChangeRequest recordChangeRequest(String targetId, String reason, long requestedAtMillis) {
        ChangeRequest request = new ChangeRequest(ids.next("change-request"), targetId, reason,
                requestedAtMillis);
        changeRequests.add(request);
        publish("ChangeRequested", request.getRequestId());
        return request;
    }

    // ------------------------------------------------------------------ queries (read-only views)

    public String getProjectId() {
        return projectId;
    }

    public ResearchBrief activeBrief() {
        requireBrief("activeBrief");
        return briefRevisions.get(briefRevisions.size() - 1);
    }

    public List<ResearchBrief> briefRevisions() {
        return Collections.unmodifiableList(briefRevisions);
    }

    public OutlineRevision activeOutline() {
        return requireActiveOutline("activeOutline");
    }

    public List<OutlineRevision> outlineRevisions() {
        return Collections.unmodifiableList(outlineRevisions);
    }

    public Map<String, SourceCapture> captures() {
        return Collections.unmodifiableMap(captures);
    }

    public Map<String, Sentence> sentences() {
        return Collections.unmodifiableMap(sentences);
    }

    public Map<String, Passage> passages() {
        return Collections.unmodifiableMap(passages);
    }

    public Map<String, TopicProposal> topicProposals() {
        return Collections.unmodifiableMap(topicProposals);
    }

    public Map<String, Topic> topics() {
        return Collections.unmodifiableMap(topics);
    }

    public Map<String, Claim> claims() {
        return Collections.unmodifiableMap(claims);
    }

    public Map<String, EvidenceLink> evidenceLinks() {
        return Collections.unmodifiableMap(evidenceLinks);
    }

    public Map<String, ResearchGap> gaps() {
        return Collections.unmodifiableMap(gaps);
    }

    public List<DetailedResearchPlan> detailPlans() {
        return Collections.unmodifiableList(detailPlans);
    }

    public List<EvidenceBaseline> evidenceBaselines() {
        return Collections.unmodifiableList(evidenceBaselines);
    }

    public List<ConceptPaper> conceptRevisions() {
        return Collections.unmodifiableList(conceptRevisions);
    }

    public List<Drafting.DraftRevision> draftRevisions() {
        return Collections.unmodifiableList(draftRevisions);
    }

    public List<DomainEvent> events() {
        return Collections.unmodifiableList(events);
    }

    // ------------------------------------------------------------------ invariants

    private void publish(String name, String subjectId) {
        events.add(new DomainEvent(name, subjectId));
    }

    private void requireApproval(Approval approval, String operation) {
        if (approval == null) {
            throw new IllegalArgumentException(operation + " changes the confirmed project state "
                    + "and therefore REQUIRES an explicit approval");
        }
    }

    private void requireBrief(String operation) {
        if (!briefConfirmed || briefRevisions.isEmpty()) {
            throw new IllegalStateException(operation + " requires a confirmed research brief");
        }
    }

    private OutlineRevision requireActiveOutline(String operation) {
        if (outlineRevisions.isEmpty()) {
            throw new IllegalStateException(operation + " requires an approved outline revision");
        }
        return outlineRevisions.get(outlineRevisions.size() - 1);
    }

    private TopicProposal requireTopicProposal(String proposalId) {
        TopicProposal proposal = topicProposals.get(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("unknown topic proposal " + proposalId);
        }
        return proposal;
    }

    private void requireKnownCapture(String captureId, String operation) {
        if (!captures.containsKey(captureId)) {
            throw new IllegalArgumentException(operation + ": unknown capture " + captureId);
        }
    }

    private void requireKnownPassage(String passageId, String operation) {
        if (!passages.containsKey(passageId)) {
            throw new IllegalArgumentException(operation + ": unknown passage " + passageId
                    + " (only persisted passages are citable — never SERP observations)");
        }
    }

    private void requireKnownSection(String sectionId, String operation) {
        for (OutlineRevision.Section section : requireActiveOutline(operation).getSections()) {
            if (section.getSectionId().equals(sectionId)) {
                return;
            }
        }
        throw new IllegalArgumentException(operation + ": unknown section " + sectionId);
    }

    private void markActiveOutlineSectionsStale() {
        if (outlineRevisions.isEmpty()) {
            return;
        }
        OutlineRevision active = outlineRevisions.get(outlineRevisions.size() - 1);
        List<OutlineRevision.Section> updated = new ArrayList<OutlineRevision.Section>();
        for (OutlineRevision.Section section : active.getSections()) {
            updated.add(section.withStatus(Lifecycle.STALE));
        }
        outlineRevisions.set(outlineRevisions.size() - 1, active.withSections(updated));
    }
}
