package com.aresstack.enterpriseai.astu.acropolis.textmining;

/**
 * A long form the document states next to its short form, e.g. {@code Hypertext Transfer Protocol (HTTP)} or
 * {@code HTTP (Hypertext Transfer Protocol)}. The long form is a literal part of the text; nothing is completed or
 * guessed.
 */
public final class AcronymDefinition {

    /** Which of both stands in parentheses. */
    public enum Pattern {
        /** {@code long form (SHORT)} */
        LONG_FORM_FIRST,
        /** {@code SHORT (long form)} */
        SHORT_FORM_FIRST
    }

    private final String shortForm;
    private final String longForm;
    private final Pattern pattern;
    private final TextSpan shortSpan;
    private final TextSpan longSpan;
    private final TextSpan sentenceSpan;

    public AcronymDefinition(String shortForm, String longForm, Pattern pattern, TextSpan shortSpan,
                             TextSpan longSpan, TextSpan sentenceSpan) {
        if (shortForm == null || longForm == null || pattern == null || shortSpan == null || longSpan == null
                || sentenceSpan == null) {
            throw new IllegalArgumentException("all parts of a definition are required");
        }
        this.shortForm = shortForm;
        this.longForm = longForm;
        this.pattern = pattern;
        this.shortSpan = shortSpan;
        this.longSpan = longSpan;
        this.sentenceSpan = sentenceSpan;
    }

    /** The short form as written. */
    public String shortForm() {
        return shortForm;
    }

    /** The long form as written (inner whitespace collapsed). */
    public String longForm() {
        return longForm;
    }

    /** The long form for comparing: lower case, single spaces. Two definitions differ when this differs. */
    public String normalizedLongForm() {
        return AcronymForms.normalizeLongForm(longForm);
    }

    public Pattern pattern() {
        return pattern;
    }

    public TextSpan shortSpan() {
        return shortSpan;
    }

    public TextSpan longSpan() {
        return longSpan;
    }

    /** The sentence the definition stands in. */
    public TextSpan sentenceSpan() {
        return sentenceSpan;
    }

    /** From the start of the first to the end of the second form. */
    public TextSpan span() {
        return new TextSpan(Math.min(shortSpan.start(), longSpan.start()), Math.max(shortSpan.end(), longSpan.end()));
    }

    @Override
    public String toString() {
        return shortForm + " = " + longForm + " (" + pattern + " @" + span() + ")";
    }
}
