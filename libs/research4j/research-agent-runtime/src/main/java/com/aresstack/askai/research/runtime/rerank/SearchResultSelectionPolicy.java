package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Turns raw cross-encoder scores into the ordered set of candidates the loop is allowed to open,
 * strictly by RAW_LOGIT relevance. There is NO fixed 0.5 threshold: the best hit for a query may be a
 * small or negative logit and still be #1. Ordering is by score descending (ties broken by the
 * candidate's original engine rank so the result is deterministic).
 *
 * <p>The required rule is Top-N. Three OPTIONAL cut-offs refine it when the host configured them; all
 * are absent by default because a global cut-off on unbounded, model-specific logits would be a guess:
 * <ul>
 *   <li>{@code absoluteMinimumScore} — drop any candidate scoring below this floor.</li>
 *   <li>{@code maximumScoreDropFromBest} — drop any candidate whose score is more than this far below
 *       the best score (a relative floor that follows the query's difficulty).</li>
 *   <li>{@code minimumTopScoreMargin} — a tail-trust gate: if the best candidate does not beat the
 *       second by at least this margin, the model shows no clear leader, so only the single top
 *       candidate is admitted rather than a whole page of near-indistinguishable hits.</li>
 * </ul>
 */
public final class SearchResultSelectionPolicy {

    private final RerankerSelectionConfiguration configuration;

    public SearchResultSelectionPolicy(RerankerSelectionConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Rank {@code scored} by relevance and select the survivors. The returned selection carries the full
     * reranked order, the admitted Top-N-bounded selection, and a typed outcome distinguishing an empty
     * input ({@code NO_CANDIDATES}) from a scored set where nothing passed the cut-offs
     * ({@code NO_SEMANTIC_MATCHES}).
     */
    public SearchResultSelection select(List<RerankedSearchResultCandidate> scored) {
        List<RerankedSearchResultCandidate> ordered =
                new ArrayList<RerankedSearchResultCandidate>(scored);
        Collections.sort(ordered, BY_RELEVANCE);

        // Re-stamp ranks to the sorted order so rerankRank is authoritative regardless of input order.
        List<RerankedSearchResultCandidate> reranked =
                new ArrayList<RerankedSearchResultCandidate>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            RerankedSearchResultCandidate c = ordered.get(i);
            reranked.add(new RerankedSearchResultCandidate(c.candidate, c.score, i + 1));
        }
        if (reranked.isEmpty()) {
            return new SearchResultSelection(SearchResultRerankingOutcome.NO_CANDIDATES, reranked,
                    reranked);
        }

        double best = reranked.get(0).score;
        List<RerankedSearchResultCandidate> surviving =
                new ArrayList<RerankedSearchResultCandidate>();
        for (RerankedSearchResultCandidate c : reranked) {
            if (configuration.absoluteMinimumScore.isPresent()
                    && c.score < configuration.absoluteMinimumScore.getAsDouble()) {
                continue;
            }
            if (configuration.maximumScoreDropFromBest.isPresent()
                    && (best - c.score) > configuration.maximumScoreDropFromBest.getAsDouble()) {
                continue;
            }
            surviving.add(c);
        }

        // Tail-trust gate: no clear leader -> open only the top candidate.
        if (configuration.minimumTopScoreMargin.isPresent() && surviving.size() >= 2) {
            double margin = surviving.get(0).score - surviving.get(1).score;
            if (margin < configuration.minimumTopScoreMargin.getAsDouble()) {
                surviving = new ArrayList<RerankedSearchResultCandidate>(
                        surviving.subList(0, 1));
            }
        }

        int limit = Math.min(configuration.maximumSelectedCandidates, surviving.size());
        List<RerankedSearchResultCandidate> selected =
                new ArrayList<RerankedSearchResultCandidate>(surviving.subList(0, limit));
        SearchResultRerankingOutcome outcome = selected.isEmpty()
                ? SearchResultRerankingOutcome.NO_SEMANTIC_MATCHES
                : SearchResultRerankingOutcome.SUCCESS;
        return new SearchResultSelection(outcome, reranked, selected);
    }

    /** Score descending; ties broken by lower original engine rank for a stable, deterministic order. */
    private static final Comparator<RerankedSearchResultCandidate> BY_RELEVANCE =
            new Comparator<RerankedSearchResultCandidate>() {
                public int compare(RerankedSearchResultCandidate a, RerankedSearchResultCandidate b) {
                    int byScore = Double.compare(b.score, a.score);
                    if (byScore != 0) {
                        return byScore;
                    }
                    return Integer.compare(a.candidate.originalRank, b.candidate.originalRank);
                }
            };
}
