package com.aresstack.enterpriseai.astu.acropolis.textmining;

/**
 * Thresholds of the acronym extractor. Read freshly for every document, so changes apply without restart.
 */
public final class AcronymSettings {

    /** Longest acronym in letters; longer capitalized words count as shouting, not as acronyms. */
    public static final int DEFAULT_MAX_LETTERS = 10;
    /**
     * A line where at least this share (percent) of its words is written in capitals is shouting (e.g. a heading in
     * capitals); its capitalized words are no acronyms.
     */
    public static final int DEFAULT_SHOUTING_PERCENT = 60;
    /** Fewest words a line needs before it can count as shouting. */
    public static final int DEFAULT_SHOUTING_MIN_WORDS = 3;

    private final int maxLetters;
    private final int shoutingPercent;
    private final int shoutingMinWords;

    public AcronymSettings(int maxLetters, int shoutingPercent, int shoutingMinWords) {
        if (maxLetters < 2) {
            throw new IllegalArgumentException("maxLetters must be at least 2: " + maxLetters);
        }
        if (shoutingPercent < 1 || shoutingPercent > 100) {
            throw new IllegalArgumentException("shoutingPercent must be 1-100: " + shoutingPercent);
        }
        if (shoutingMinWords < 1) {
            throw new IllegalArgumentException("shoutingMinWords must be at least 1: " + shoutingMinWords);
        }
        this.maxLetters = maxLetters;
        this.shoutingPercent = shoutingPercent;
        this.shoutingMinWords = shoutingMinWords;
    }

    public static AcronymSettings defaults() {
        return new AcronymSettings(DEFAULT_MAX_LETTERS, DEFAULT_SHOUTING_PERCENT, DEFAULT_SHOUTING_MIN_WORDS);
    }

    public int maxLetters() {
        return maxLetters;
    }

    public int shoutingPercent() {
        return shoutingPercent;
    }

    public int shoutingMinWords() {
        return shoutingMinWords;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AcronymSettings)) {
            return false;
        }
        AcronymSettings that = (AcronymSettings) other;
        return maxLetters == that.maxLetters && shoutingPercent == that.shoutingPercent
                && shoutingMinWords == that.shoutingMinWords;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * maxLetters + shoutingPercent) + shoutingMinWords;
    }

    @Override
    public String toString() {
        return "AcronymSettings[maxLetters=" + maxLetters + ", shoutingPercent=" + shoutingPercent
                + ", shoutingMinWords=" + shoutingMinWords + "]";
    }
}
