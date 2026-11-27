package com.aresstack.askai.browser.search.layout;

/**
 * A neutral projection of ONE mechanical signal-family score for a container candidate: the family
 * name (e.g. {@code GEOMETRY}) and its summed signed contribution. It carries no rationale text and
 * no page content — it is a number the model and the diagnostics may read.
 */
public final class SearchPageSignalScore {

    public final String family;
    public final double score;

    public SearchPageSignalScore(String family, double score) {
        this.family = family == null ? "" : family;
        this.score = score;
    }
}
