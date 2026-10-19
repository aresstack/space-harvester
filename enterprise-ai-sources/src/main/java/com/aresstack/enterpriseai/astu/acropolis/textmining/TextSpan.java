package com.aresstack.enterpriseai.astu.acropolis.textmining;

/** A character range {@code [start, end)} in the canonical text of one document version. */
public final class TextSpan implements Comparable<TextSpan> {

    private final int start;
    private final int end;

    public TextSpan(int start, int end) {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span " + start + "-" + end);
        }
        this.start = start;
        this.end = end;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public int length() {
        return end - start;
    }

    /** Whether both ranges share at least one character. */
    public boolean overlaps(TextSpan other) {
        return start < other.end && other.start < end;
    }

    /** The covered text of {@code text}. */
    public String of(String text) {
        return text.substring(start, end);
    }

    @Override
    public int compareTo(TextSpan other) {
        return start != other.start ? Integer.compare(start, other.start) : Integer.compare(end, other.end);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TextSpan && start == ((TextSpan) other).start
                && end == ((TextSpan) other).end;
    }

    @Override
    public int hashCode() {
        return 31 * start + end;
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
