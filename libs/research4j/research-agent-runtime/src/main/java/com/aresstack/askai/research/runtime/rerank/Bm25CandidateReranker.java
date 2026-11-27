package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The LEXICAL default reranker: Okapi BM25 over each candidate's title, snippet, displayed domain and
 * target address — no model, no sidecar, no GPU. This is what scores the search unless the user explicitly enables the AI reranker
 * ("KI-Reranker verwenden"): cheap and deterministic, at the price of pure word matching (no
 * synonyms, no semantics). Scores are unbounded and only comparable WITHIN one call — exactly the
 * {@link RerankerScoreSemantics#RAW_LOGIT} contract the selection policy already honours.
 */
public final class Bm25CandidateReranker implements CandidateReranker {

    /**
     * Okapi BM25 shape parameters, the literature-standard values (Robertson et al.). They shape the
     * term-frequency saturation curve of the algorithm itself — not a user-facing budget or limit.
     */
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    public static final String MODEL_NAME = "lexical/bm25";

    @Override
    public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                              CancellationSignal cancellation) {
        long started = System.nanoTime();
        if (candidates == null || candidates.isEmpty()) {
            return new SearchResultRerankingResult(SearchResultRerankingOutcome.NO_CANDIDATES,
                    new ArrayList<RerankedSearchResultCandidate>(),
                    new ArrayList<RerankedSearchResultCandidate>(),
                    MODEL_NAME, RerankerScoreSemantics.RAW_LOGIT, "no candidates", 0L, 0L);
        }
        LinkedHashMap<String, String> documents = new LinkedHashMap<String, String>();
        Map<String, SearchResultCandidate> byId = new HashMap<String, SearchResultCandidate>();
        for (int i = 0; i < candidates.size(); i++) {
            SearchResultCandidate candidate = candidates.get(i);
            String id = "bm25-" + i;
            documents.put(id, documentTextOf(candidate));
            byId.put(id, candidate);
        }
        List<com.aresstack.askai.research.domain.search.RelevanceAssessment.Score> scores =
                scoreAll(query, documents);
        List<RerankedSearchResultCandidate> ranked = new ArrayList<RerankedSearchResultCandidate>();
        List<RerankedSearchResultCandidate> selected = new ArrayList<RerankedSearchResultCandidate>();
        int rank = 1;
        for (com.aresstack.askai.research.domain.search.RelevanceAssessment.Score score : scores) {
            RerankedSearchResultCandidate entry = new RerankedSearchResultCandidate(
                    byId.get(score.getCandidateId()), score.getRelevance(), rank++);
            ranked.add(entry);
            if (score.getRelevance() > 0.0d) {
                selected.add(entry); // zero overlap with the query is an honest non-match
            }
        }
        SearchResultRerankingOutcome outcome = selected.isEmpty()
                ? SearchResultRerankingOutcome.NO_SEMANTIC_MATCHES
                : SearchResultRerankingOutcome.SUCCESS;
        return new SearchResultRerankingResult(outcome, ranked, selected, MODEL_NAME,
                RerankerScoreSemantics.RAW_LOGIT,
                "BM25 lexical scoring (no AI model; enable the AI reranker for semantic ranking)",
                System.nanoTime() - started, 0L);
    }

    @Override
    public com.aresstack.askai.research.domain.search.RelevanceAssessment assess(
            String query, LinkedHashMap<String, String> documentsById, CancellationSignal cancellation) {
        if (documentsById == null || documentsById.isEmpty()) {
            return com.aresstack.askai.research.domain.search.RelevanceAssessment.of(MODEL_NAME,
                    java.util.Collections.<com.aresstack.askai.research.domain.search
                            .RelevanceAssessment.Score>emptyList());
        }
        return com.aresstack.askai.research.domain.search.RelevanceAssessment.of(MODEL_NAME,
                scoreAll(query, documentsById));
    }

    // ------------------------------------------------------------------ Okapi BM25 over the call's corpus

    private static List<com.aresstack.askai.research.domain.search.RelevanceAssessment.Score> scoreAll(
            String query, LinkedHashMap<String, String> documentsById) {
        List<String> queryTerms = tokenize(query);
        Map<String, List<String>> tokensById = new LinkedHashMap<String, List<String>>();
        double totalLength = 0.0d;
        for (Map.Entry<String, String> document : documentsById.entrySet()) {
            List<String> tokens = tokenize(document.getValue());
            tokensById.put(document.getKey(), tokens);
            totalLength += tokens.size();
        }
        double averageLength = Math.max(1.0d, totalLength / tokensById.size());
        // Document frequency per query term, over THIS call's corpus.
        Map<String, Integer> documentFrequency = new HashMap<String, Integer>();
        for (String term : queryTerms) {
            int frequency = 0;
            for (List<String> tokens : tokensById.values()) {
                if (tokens.contains(term)) {
                    frequency++;
                }
            }
            documentFrequency.put(term, frequency);
        }
        int corpusSize = tokensById.size();
        List<com.aresstack.askai.research.domain.search.RelevanceAssessment.Score> scores =
                new ArrayList<com.aresstack.askai.research.domain.search.RelevanceAssessment.Score>();
        for (Map.Entry<String, List<String>> document : tokensById.entrySet()) {
            List<String> tokens = document.getValue();
            double score = 0.0d;
            for (String term : queryTerms) {
                int termFrequency = 0;
                for (String token : tokens) {
                    if (token.equals(term)) {
                        termFrequency++;
                    }
                }
                if (termFrequency == 0) {
                    continue;
                }
                int df = documentFrequency.get(term);
                double idf = Math.log(1.0d + (corpusSize - df + 0.5d) / (df + 0.5d));
                double normalized = termFrequency * (K1 + 1.0d)
                        / (termFrequency + K1 * (1.0d - B + B * tokens.size() / averageLength));
                score += idf * normalized;
            }
            scores.add(new com.aresstack.askai.research.domain.search.RelevanceAssessment.Score(
                    document.getKey(), score));
        }
        java.util.Collections.sort(scores,
                new java.util.Comparator<com.aresstack.askai.research.domain.search
                        .RelevanceAssessment.Score>() {
                    public int compare(
                            com.aresstack.askai.research.domain.search.RelevanceAssessment.Score a,
                            com.aresstack.askai.research.domain.search.RelevanceAssessment.Score b) {
                        return Double.compare(b.getRelevance(), a.getRelevance());
                    }
                });
        return scores;
    }

    private static String documentTextOf(SearchResultCandidate candidate) {
        StringBuilder sb = new StringBuilder();
        if (candidate.title != null) {
            sb.append(candidate.title).append(' ');
        }
        if (candidate.snippet != null) {
            sb.append(candidate.snippet).append(' ');
        }
        if (candidate.displayedDomain != null) {
            sb.append(candidate.displayedDomain).append(' ');
        }
        // The address the hit points to is part of what the engine matched, and often the only place
        // a version or a product name survives: the captured snippet is a short excerpt, and Bing's
        // breadcrumb "adoptium.net › temurin › releases" hides "?version=21". Without it the Temurin
        // JDK 21 page Bing ranked fourth for "Java 21 LTS release" scored zero and was dropped
        // before it ever reached the frontier.
        if (candidate.resolvedTargetUrl != null) {
            sb.append(addressWords(candidate.resolvedTargetUrl));
        }
        return sb.toString();
    }

    /**
     * The address as words: without its scheme and with percent escapes decoded, so "/h%C3%BChner/"
     * scores as "hühner". Scoring text only — the hit keeps its address. An address that does not
     * decode is scored as it stands.
     */
    static String addressWords(String url) {
        String address = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        try {
            return java.net.URLDecoder.decode(address, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return address;
        }
    }

    /** Unicode-aware lower-case word tokens — "Hühner" stays one term (see the query-mangling lesson). */
    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        for (String token : (text == null ? "" : text).toLowerCase(Locale.ROOT)
                .split("[^\\p{L}\\p{Nd}]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
