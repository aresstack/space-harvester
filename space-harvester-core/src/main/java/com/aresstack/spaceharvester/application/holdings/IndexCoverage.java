package com.aresstack.spaceharvester.application.holdings;

import java.util.EnumMap;
import java.util.Map;

/** Count files, not structural folders, without changing the existing cache counts. */
public record IndexCoverage(Map<IndexState, Integer> counts) {
    public static final IndexCoverage ZERO = new IndexCoverage(Map.of());

    public IndexCoverage {
        counts = Map.copyOf(counts);
    }

    public static IndexCoverage of(IndexState state) {
        return state == IndexState.FOLDER ? ZERO : new IndexCoverage(Map.of(state, 1));
    }

    public int count(IndexState state) {
        return counts.getOrDefault(state, 0);
    }

    public int files() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public IndexCoverage plus(IndexCoverage other) {
        Map<IndexState, Integer> result = new EnumMap<>(IndexState.class);
        result.putAll(counts);
        other.counts.forEach((state, count) -> result.merge(state, count, Integer::sum));
        return new IndexCoverage(result);
    }
}
