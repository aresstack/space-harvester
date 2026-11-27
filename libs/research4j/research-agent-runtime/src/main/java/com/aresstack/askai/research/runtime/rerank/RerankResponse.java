package com.aresstack.askai.research.runtime.rerank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A validated {@code /api/rerank} response: the served model name, the scored rows (in response order,
 * unsorted) and the runtime's self-reported durations in nanoseconds. Produced only after the strict
 * client has checked the model, completeness and finiteness of every row.
 */
public final class RerankResponse {

    public final String model;
    public final List<RerankScore> scores;
    public final long totalDurationNanos;
    public final long loadDurationNanos;

    public RerankResponse(String model, List<RerankScore> scores, long totalDurationNanos,
                          long loadDurationNanos) {
        this.model = model == null ? "" : model;
        this.scores = Collections.unmodifiableList(new ArrayList<RerankScore>(scores));
        this.totalDurationNanos = totalDurationNanos;
        this.loadDurationNanos = loadDurationNanos;
    }
}
