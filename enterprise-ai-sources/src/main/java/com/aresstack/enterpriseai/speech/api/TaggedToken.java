package com.aresstack.enterpriseai.speech.api;

/** One token with its part-of-speech tag as the model gave it, the neutral word class, and the tag's probability. */
public final class TaggedToken {

    private final String text;
    private final String tag;
    private final WordClass wordClass;
    private final double probability;

    /**
     * @param tag         the model's own tag ({@code NN}, {@code VVFIN}, ...)
     * @param probability probability of the tag in [0, 1], or {@code -1} when the model gives none
     */
    public TaggedToken(String text, String tag, WordClass wordClass, double probability) {
        this.text = text == null ? "" : text;
        this.tag = tag == null ? "" : tag;
        this.wordClass = wordClass == null ? WordClass.OTHER : wordClass;
        this.probability = probability;
    }

    public String text() {
        return text;
    }

    public String tag() {
        return tag;
    }

    public WordClass wordClass() {
        return wordClass;
    }

    /** Probability of the tag in [0, 1], or {@code -1} when unknown. */
    public double probability() {
        return probability;
    }

    @Override
    public String toString() {
        return text + "/" + tag;
    }
}
