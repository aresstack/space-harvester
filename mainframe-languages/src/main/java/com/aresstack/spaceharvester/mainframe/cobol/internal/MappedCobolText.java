package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Span;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep origin positions attached to text across preprocessing transformations. */
final class MappedCobolText {
    private final String text;
    private final Position[] origins;
    private final int[] codePointOffsets;

    private MappedCobolText(String text, Position[] origins) {
        this.text = text;
        this.origins = origins;
        this.codePointOffsets = new int[text.codePointCount(0, text.length()) + 1];
        for (int i = 0, offset = 0; i < codePointOffsets.length - 1; i++) {
            codePointOffsets[i] = offset;
            offset += Character.charCount(text.codePointAt(offset));
        }
        codePointOffsets[codePointOffsets.length - 1] = text.length();
    }

    String text() {
        return text;
    }

    Position at(Token token) {
        return token == null ? Position.UNKNOWN : atCodePoint(token.getStartIndex());
    }

    Position atCodePoint(int index) {
        return index < 0 || index >= codePointOffsets.length - 1
                ? Position.UNKNOWN : origins[codePointOffsets[index]];
    }

    Span span(Token first, Token last) {
        Position start = at(first);
        Position end = last == null ? start : atCodePoint(last.getStopIndex());
        return new Span(start, end.line() == 0 ? start : end);
    }

    MappedCobolText token(Token token) {
        int start = token.getStartIndex();
        int end = token.getStopIndex() + 1;
        if (start < 0 || end < start || end >= codePointOffsets.length) {
            return new Builder().build();
        }
        return slice(codePointOffsets[start], codePointOffsets[end]);
    }

    private MappedCobolText slice(int start, int end) {
        return new MappedCobolText(text.substring(start, end), Arrays.copyOfRange(origins, start, end));
    }

    MappedCobolText replace(Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(text);
        Builder result = new Builder();
        int offset = 0;
        while (matcher.find()) {
            result.append(slice(offset, matcher.start()));
            Position origin = matcher.start() < origins.length ? origins[matcher.start()] : Position.UNKNOWN;
            // Anchor generated replacement text to the original replaced text, not to the COPY statement.
            result.append(replacement, new Position(origin.source(), origin.line(), 0, origin.debuggingLine()));
            offset = matcher.end();
        }
        result.append(slice(offset, text.length()));
        return result.build();
    }

    static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final List<Position> origins = new ArrayList<>();

        void append(String value, Position origin) {
            text.append(value);
            for (int i = 0; i < value.length(); i++) {
                origins.add(origin);
            }
        }

        void append(MappedCobolText value) {
            text.append(value.text);
            origins.addAll(Arrays.asList(value.origins));
        }

        MappedCobolText build() {
            return new MappedCobolText(text.toString(), origins.toArray(Position[]::new));
        }
    }
}
