package com.aresstack.writing4j.engine;

/**
 * Whether the key-point parts of a section know their section (stage 1 of the writer proposal, option A). Without
 * it every part call reads "You write one section" and "Write the section now", knows neither its place nor the
 * other parts' key points, and gets only the last three sentences of the part before it as "PREVIOUS SECTION": the
 * A/B of 20 November 2026 counted a paragraph that starts the subject again in 3 of 6 sections. With it, every part
 * call after the first carries its place ("part 2 of 3"), the plan of all key points of the section (already
 * written, this part, a later part) and the text of the parts before it without citation markers, and is told to
 * continue that text instead of starting anew (prototype: 0 of 12 sections). The text so far is cut to its end at
 * {@link #getSoFarChars()} characters, because it costs context (about 20 % more tokens per chapter). Off by
 * default until the acceptance; every bound is a setting. Immutable.
 */
public final class PartContextCriteria {

    /** Characters of the section text so far a part call carries at most (the prototype's value). */
    public static final int DEFAULT_SO_FAR_CHARS = 6000;

    private final boolean enabled;
    private final int soFarChars;

    /**
     * @param enabled    whether the parts get their section context
     * @param soFarChars characters of the text so far at most; 0 = the whole text
     */
    public PartContextCriteria(boolean enabled, int soFarChars) {
        if (soFarChars < 0) {
            throw new IllegalArgumentException("the characters of the text so far must not be negative");
        }
        this.enabled = enabled;
        this.soFarChars = soFarChars;
    }

    /** The parts know nothing of each other (the behaviour before stage 1). */
    public static PartContextCriteria off() {
        return new PartContextCriteria(false, DEFAULT_SO_FAR_CHARS);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSoFarChars() {
        return soFarChars;
    }
}
