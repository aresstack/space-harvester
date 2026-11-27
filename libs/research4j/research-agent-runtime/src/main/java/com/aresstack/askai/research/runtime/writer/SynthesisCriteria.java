package com.aresstack.askai.research.runtime.writer;

import java.util.Locale;

/**
 * Synthesis instead of retelling (live run 4, book 6): almost every paragraph retold exactly one passage, often word
 * for word, and section 1.1 had no paragraph that drew on two sources. The writer prompt asks each key-point part for
 * {@link #getParagraphsPerPart() paragraphs per part} paragraphs that each combine passages of at least
 * {@link #getMinSources() min sources} different sources; the book's quality report notes (not blocking) a section in
 * which more than {@link #getMaxSingleSourceShare() max single-source share} of its paragraphs with at least
 * {@link #getMinSentences() min sentences} sentences cite one source only. Immutable; every bound is a setting.
 */
public final class SynthesisCriteria {

    /** Default: a paragraph that combines two sources is a synthesis. */
    public static final int DEFAULT_MIN_SOURCES = 2;
    /** Default: each part of a section writes at least one such paragraph (the report's fix 2). */
    public static final int DEFAULT_PARAGRAPHS_PER_PART = 1;
    /** Default: a paragraph of one sentence is a transition, not a retelling; two sentences count. */
    public static final int DEFAULT_MIN_SENTENCES = 2;
    /**
     * Default share of single-source paragraphs above which a section is noted. Book 6: 1.1 (21 of 21), 1.2 (3 of 5),
     * 1.3 (6 of 10) and 2.1 (7 of 12) lie above it; 3.1 (5 of 12) and 4.1 (5 of 11), which combine sources in most
     * paragraphs, below.
     */
    public static final double DEFAULT_MAX_SINGLE_SOURCE_SHARE = 0.5;

    private final int minSources;
    private final int paragraphsPerPart;
    private final int minSentences;
    private final double maxSingleSourceShare;

    public SynthesisCriteria(int minSources, int paragraphsPerPart, int minSentences, double maxSingleSourceShare) {
        if (minSources < 2 || paragraphsPerPart < 0 || minSentences < 1 || maxSingleSourceShare < 0
                || maxSingleSourceShare > 1) {
            throw new IllegalArgumentException("min sources >= 2, paragraphs per part >= 0, min sentences >= 1 and "
                    + "the share within 0..1");
        }
        this.minSources = minSources;
        this.paragraphsPerPart = paragraphsPerPart;
        this.minSentences = minSentences;
        this.maxSingleSourceShare = maxSingleSourceShare;
    }

    public static SynthesisCriteria defaults() {
        return new SynthesisCriteria(DEFAULT_MIN_SOURCES, DEFAULT_PARAGRAPHS_PER_PART, DEFAULT_MIN_SENTENCES,
                DEFAULT_MAX_SINGLE_SOURCE_SHARE);
    }

    /** Different sources one synthesis paragraph combines. */
    public int getMinSources() {
        return minSources;
    }

    /** Synthesis paragraphs the prompt asks of each key-point part; 0 leaves the instruction out. */
    public int getParagraphsPerPart() {
        return paragraphsPerPart;
    }

    /** Sentences a paragraph needs before the report counts it as single-source or not. */
    public int getMinSentences() {
        return minSentences;
    }

    /** Share of counted paragraphs that may cite one source only before the report notes the section. */
    public double getMaxSingleSourceShare() {
        return maxSingleSourceShare;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SynthesisCriteria)) {
            return false;
        }
        SynthesisCriteria c = (SynthesisCriteria) o;
        return c.minSources == minSources && c.paragraphsPerPart == paragraphsPerPart
                && c.minSentences == minSentences
                && Double.compare(c.maxSingleSourceShare, maxSingleSourceShare) == 0;
    }

    @Override
    public int hashCode() {
        return ((minSources * 31 + paragraphsPerPart) * 31 + minSentences) * 31
                + Double.valueOf(maxSingleSourceShare).hashCode();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "SynthesisCriteria[minSources=%d, paragraphsPerPart=%d, minSentences=%d, "
                + "maxSingleSourceShare=%.2f]", minSources, paragraphsPerPart, minSentences, maxSingleSourceShare);
    }
}
