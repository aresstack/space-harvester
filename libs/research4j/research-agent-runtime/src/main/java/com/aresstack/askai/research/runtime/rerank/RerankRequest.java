package com.aresstack.askai.research.runtime.rerank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The typed request to {@code POST <baseUrl>/api/rerank}: the served model name, the query and the
 * ordered documents to score. {@code top_n} is always the full document count — this client does its
 * OWN {@code RAW_LOGIT} selection locally, so it asks the endpoint to score every document and never
 * lets the endpoint pre-truncate the ranking.
 */
public final class RerankRequest {

    public final String modelName;
    public final String query;
    public final List<String> documents;

    public RerankRequest(String modelName, String query, List<String> documents) {
        this.modelName = modelName;
        this.query = query;
        this.documents = Collections.unmodifiableList(new ArrayList<String>(documents));
    }

    String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"model\":");
        RerankJson.appendString(sb, modelName);
        sb.append(",\"query\":");
        RerankJson.appendString(sb, query);
        sb.append(",\"documents\":[");
        for (int i = 0; i < documents.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            RerankJson.appendString(sb, documents.get(i));
        }
        sb.append("],\"top_n\":").append(documents.size());
        sb.append('}');
        return sb.toString();
    }
}
