package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Span;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;

/** Store contiguous source positions as runs rather than allocating a position per character. */
final class MappedCobolText {
    private record Run(int start, int end, Position origin, boolean advancing) {
        Position position(int index) {
            return advancing ? new Position(origin.source(), origin.line(),
                    origin.column() + index - start, origin.debuggingLine()) : origin;
        }
    }

    private final String text;
    private final List<Run> runs;
    private final int[] codePointOffsets;

    private MappedCobolText(String text, List<Run> runs) {
        this.text = text;
        this.runs = List.copyOf(runs);
        int count = text.codePointCount(0, text.length());
        if (count == text.length()) {
            codePointOffsets = null;
        } else {
            codePointOffsets = new int[count + 1];
            int offset = 0;
            for (int i = 0; i < count; i++) {
                codePointOffsets[i] = offset;
                offset += Character.charCount(text.codePointAt(offset));
            }
            codePointOffsets[count] = text.length();
        }
    }

    String text() {
        return text;
    }

    private int charOffset(int index) {
        if (index < 0) {
            return -1;
        }
        if (codePointOffsets == null) {
            return index <= text.length() ? index : -1;
        }
        return index < codePointOffsets.length ? codePointOffsets[index] : -1;
    }

    private int runIndex(int offset) {
        int lo = 0;
        int hi = runs.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (runs.get(mid).end() <= offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    Position at(Token token) {
        return token == null ? Position.UNKNOWN : atCodePoint(token.getStartIndex());
    }

    Position atCodePoint(int index) {
        int offset = charOffset(index);
        if (offset < 0 || offset >= text.length()) {
            return Position.UNKNOWN;
        }
        return runs.get(runIndex(offset)).position(offset);
    }

    Span span(Token first, Token last) {
        Position start = at(first);
        Position end = last == null ? start : atCodePoint(last.getStopIndex());
        return new Span(start, end.line() == 0 ? start : end);
    }

    void appendTokenTo(Builder target, Token token) {
        int start = charOffset(token.getStartIndex());
        int end = charOffset(token.getStopIndex() + 1);
        if (start >= 0 && end >= start) {
            target.appendSlice(this, start, end);
        }
    }

    record Replacement(int start, int end, String text) { }

    MappedCobolText replaceAll(List<Replacement> replacements) {
        Builder result = new Builder();
        int offset = 0;
        for (Replacement replacement : replacements) {
            result.appendSlice(this, offset, replacement.start());
            Position origin = runs.get(runIndex(replacement.start())).position(replacement.start());
            // Anchor generated characters to the original replaced range's first character.
            result.append(replacement.text(), origin);
            offset = replacement.end();
        }
        result.appendSlice(this, offset, text.length());
        return result.build();
    }

    static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final List<Run> runs = new ArrayList<>();

        void append(String value, Position origin) {
            append(value, origin, false);
        }

        void appendLineContent(String value, Position origin) {
            append(value, origin, origin.column() > 0);
        }

        private void append(String value, Position origin, boolean advancing) {
            int start = text.length();
            text.append(value);
            addRun(start, text.length(), origin, advancing);
        }

        private void addRun(int start, int end, Position origin, boolean advancing) {
            if (start == end) {
                return;
            }
            if (!runs.isEmpty()) {
                Run previous = runs.getLast();
                if (previous.end() == start && previous.advancing() == advancing
                        && previous.position(advancing ? start : previous.start()).equals(origin)) {
                    runs.set(runs.size() - 1, new Run(previous.start(), end, previous.origin(), advancing));
                    return;
                }
            }
            runs.add(new Run(start, end, origin, advancing));
        }

        void append(MappedCobolText value) {
            appendSlice(value, 0, value.text.length());
        }

        void appendSlice(MappedCobolText value, int start, int end) {
            if (start == end) {
                return;
            }
            int targetStart = text.length();
            text.append(value.text, start, end);
            for (int i = value.runIndex(start); i < value.runs.size(); i++) {
                Run run = value.runs.get(i);
                if (run.start() >= end) {
                    break;
                }
                int from = Math.max(start, run.start());
                int to = Math.min(end, run.end());
                addRun(targetStart + from - start, targetStart + to - start,
                        run.position(from), run.advancing());
            }
        }

        MappedCobolText build() {
            return new MappedCobolText(text.toString(), runs);
        }
    }
}