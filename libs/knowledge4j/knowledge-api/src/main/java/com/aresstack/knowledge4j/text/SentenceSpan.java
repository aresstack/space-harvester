package com.aresstack.knowledge4j.text;

/** One sentence as a character range [start, end) of the segmented text. */
public record SentenceSpan(int start, int end) {

    public SentenceSpan {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid sentence range " + start + ".." + end);
        }
    }

    public String of(String text) {
        return text.substring(start, end);
    }
}
