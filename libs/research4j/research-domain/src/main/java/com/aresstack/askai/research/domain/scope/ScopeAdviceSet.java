package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The reason-aware advisory summary of ONE sweep — the layer between "interesting semantic
 * region" (Z3) and "best next conversational question" (Z4b/user): question candidates grouped by
 * their conversational reason plus the drift guards, BOUND to the snapshot they were computed on.
 * The candidate order is PRESENTATION order (PENDING, BOUNDARY, IN-EXTENSION, UNEXPLORED sections,
 * reading order within) — deliberately NOT a priority: which question wins is the chooser's and
 * ultimately the user's call, never a baked-in ranking.
 * <p>
 * Optimistic concurrency: a consumer MUST call {@link #appliesTo(long)} against the CURRENT draft
 * revision before using this advice — a set computed on revision R describes a fence that may no
 * longer exist.
 */
public final class ScopeAdviceSet {

    private final long scopeRevision;
    private final String embeddingFingerprint;
    private final List<ScopeAdviceCandidate> questionCandidates;
    private final List<ScopeDriftGuard> driftGuards;

    public ScopeAdviceSet(long scopeRevision, String embeddingFingerprint,
                          List<ScopeAdviceCandidate> questionCandidates,
                          List<ScopeDriftGuard> driftGuards) {
        if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("advice must stay bound to its embedding snapshot");
        }
        this.scopeRevision = scopeRevision;
        this.embeddingFingerprint = embeddingFingerprint.trim();
        this.questionCandidates = Collections.unmodifiableList(new ArrayList<ScopeAdviceCandidate>(
                questionCandidates == null
                        ? Collections.<ScopeAdviceCandidate>emptyList() : questionCandidates));
        this.driftGuards = Collections.unmodifiableList(new ArrayList<ScopeDriftGuard>(
                driftGuards == null ? Collections.<ScopeDriftGuard>emptyList() : driftGuards));
    }

    public long getScopeRevision() {
        return scopeRevision;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public List<ScopeAdviceCandidate> getQuestionCandidates() {
        return questionCandidates;
    }

    public List<ScopeDriftGuard> getDriftGuards() {
        return driftGuards;
    }

    /** Advice computed on another revision describes a fence that no longer exists — do not use. */
    public boolean appliesTo(long currentRevision) {
        return scopeRevision == currentRevision;
    }

    public ScopeAdviceCandidate candidateById(String candidateId) {
        for (ScopeAdviceCandidate candidate : questionCandidates) {
            if (candidate.getCandidateId().equals(candidateId)) {
                return candidate;
            }
        }
        return null;
    }
}
