package com.aresstack.enterpriseai.model.api;

import java.util.List;

/**
 * Port of a cross-encoder (model category RERANK): scores each document on its own against a query. Neutral: no URL,
 * no provider DTOs; which backend answers is decided by the adapter behind it.
 */
public interface RerankPort {

    /**
     * Score {@code documents} against {@code query} with the model {@code modelId}.
     *
     * @return one score per document, in the order of {@code documents}; higher means more relevant
     * @throws RerankException when the reranker is not reachable, not configured or answers unreadably
     */
    double[] rerank(String modelId, String query, List<String> documents) throws RerankException;
}
