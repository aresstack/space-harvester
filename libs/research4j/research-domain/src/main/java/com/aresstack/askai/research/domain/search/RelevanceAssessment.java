package com.aresstack.askai.research.domain.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How relevant each candidate is — the answer to "how good is this hit for this query", and nothing else.
 * <p>
 * Deliberately separate from selection: relevance is a property of a candidate against a query, while
 * selection is a decision about WHICH of them to look at under a given policy. Merging the two would make
 * "the reranker said 0.8" and "we chose it" the same statement, and then a second selection over the same
 * run (a user picking different hits, a later deepening) could not exist without re-running the reranker.
 * <p>
 * When the assessment is {@link #isAvailable() unavailable} — reranker down, timed out, misconfigured — that
 * is a fact to act on, not a value to fake: no automatic selection may happen, while the candidates
 * themselves remain exactly as discovered.
 */
public final class RelevanceAssessment {

    /** One candidate's relevance; the score's scale belongs to the model that produced it. */
    public static final class Score {
        private final String candidateId;
        private final double relevance;

        public Score(String candidateId, double relevance) {
            if (candidateId == null || candidateId.trim().isEmpty()) {
                throw new IllegalArgumentException("candidateId must not be empty");
            }
            this.candidateId = candidateId.trim();
            this.relevance = relevance;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public double getRelevance() {
            return relevance;
        }
    }

    private final String model;
    private final boolean available;
    private final String unavailableReason;
    private final List<Score> scores;
    private final Map<String, Double> byCandidateId;

    private RelevanceAssessment(String model, boolean available, String unavailableReason,
                                List<Score> scores) {
        this.model = model == null ? "" : model.trim();
        this.available = available;
        this.unavailableReason = unavailableReason == null ? "" : unavailableReason.trim();
        this.scores = scores == null || scores.isEmpty()
                ? Collections.<Score>emptyList()
                : Collections.unmodifiableList(new ArrayList<Score>(scores));
        Map<String, Double> index = new LinkedHashMap<String, Double>();
        for (Score score : this.scores) {
            index.put(score.getCandidateId(), score.getRelevance());
        }
        this.byCandidateId = Collections.unmodifiableMap(index);
    }

    /** Scores in the order the model ranked them (best first). */
    public static RelevanceAssessment of(String model, List<Score> rankedScores) {
        return new RelevanceAssessment(model, true, "", rankedScores);
    }

    /** No relevance could be established — the reason is kept so a caller can report it honestly. */
    public static RelevanceAssessment unavailable(String reason) {
        return new RelevanceAssessment("", false, reason, Collections.<Score>emptyList());
    }

    public String getModel() {
        return model;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    /** Best first — the model's own order, not a selection. */
    public List<Score> getScores() {
        return scores;
    }

    /** This candidate's relevance, or {@code null} when the model did not score it. */
    public Double relevanceOf(String candidateId) {
        return candidateId == null ? null : byCandidateId.get(candidateId.trim());
    }

    /** The candidate ids in relevance order. */
    public List<String> rankedCandidateIds() {
        List<String> ids = new ArrayList<String>();
        for (Score score : scores) {
            ids.add(score.getCandidateId());
        }
        return Collections.unmodifiableList(ids);
    }

    public boolean isEmpty() {
        return scores.isEmpty();
    }
}
