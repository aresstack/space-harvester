package com.aresstack.enterpriseai.astu.acropolis.textmining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts text into word tokens with their character spans and leaves out what is no prose: fenced code blocks,
 * inline code in backticks, web and mail addresses, link targets of light Markdown, paths and file names. Works on
 * the canonical text of a document ({@code KnowledgeDocument.text()}) as well as on the text of a rendered view, so
 * both sides cut the same words.
 *
 * <p>A token starts with a letter or digit and continues over letters, digits and the joiners {@code - . / _} as long
 * as a letter or digit follows the joiner ({@code U.S.A.} becomes {@code U.S.A}, {@code IAR-Schnittstelle} stays one
 * token and is split into its parts by the caller).</p>
 */
public final class TermScanner {

    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)\\b(?:https?|ftp|file|mailto):[^\\s)\\]>\"']+|\\bwww\\.[^\\s)\\]>\"']+"
                    + "|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern LINK_TARGET = Pattern.compile("\\]\\([^)\\s]*\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");
    /** A Windows path or a token with a backslash. */
    private static final Pattern BACKSLASH_PATH = Pattern.compile("\\S*\\\\\\S*");

    /** One word of the text. */
    public static final class Token {
        private final String text;
        private final TextSpan span;

        Token(String text, TextSpan span) {
            this.text = text;
            this.span = span;
        }

        public String text() {
            return text;
        }

        public TextSpan span() {
            return span;
        }

        @Override
        public String toString() {
            return text + "@" + span;
        }
    }

    private TermScanner() {
    }

    /** The prose tokens of {@code text} in order. */
    public static List<Token> tokens(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<TextSpan> masked = maskedRanges(text);
        List<Token> tokens = new ArrayList<Token>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!Character.isLetterOrDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            i++;
            while (i < n) {
                char c = text.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    i++;
                } else if (isJoiner(c) && i + 1 < n && Character.isLetterOrDigit(text.charAt(i + 1))) {
                    i += 2;
                } else {
                    break;
                }
            }
            TextSpan span = new TextSpan(start, i);
            if (!isMasked(span, masked) && !isFileName(text, span)) {
                tokens.add(new Token(text.substring(start, i), span));
            }
        }
        return tokens;
    }

    /** The parts of a token between hyphens and slashes, each with its own span. */
    public static List<Token> parts(Token token) {
        List<Token> parts = new ArrayList<Token>();
        String text = token.text();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '-' || text.charAt(i) == '/') {
                if (i > start) {
                    parts.add(new Token(text.substring(start, i),
                            new TextSpan(token.span().start() + start, token.span().start() + i)));
                }
                start = i + 1;
            }
        }
        return parts;
    }

    /** Fenced code blocks, inline code, addresses, link targets and backslash paths. */
    static List<TextSpan> maskedRanges(String text) {
        List<TextSpan> ranges = new ArrayList<TextSpan>();
        int fenceStart = -1;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd).trim();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                if (fenceStart < 0) {
                    fenceStart = lineStart;
                } else {
                    ranges.add(new TextSpan(fenceStart, lineEnd));
                    fenceStart = -1;
                }
            }
            lineStart = lineEnd + 1;
        }
        if (fenceStart >= 0) {
            ranges.add(new TextSpan(fenceStart, text.length())); // unclosed fence: code to the end
        }
        for (Pattern pattern : new Pattern[] {ADDRESS, LINK_TARGET, INLINE_CODE, BACKSLASH_PATH}) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                ranges.add(new TextSpan(matcher.start(), matcher.end()));
            }
        }
        return ranges;
    }

    private static boolean isMasked(TextSpan span, List<TextSpan> masked) {
        for (TextSpan range : masked) {
            if (range.overlaps(span)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A file name or path: the token has a slash between words, or ends in a dot and a short lower-case extension
     * ({@code report.pdf}, {@code IAR.xml}).
     */
    static boolean isFileName(String text, TextSpan span) {
        String token = span.of(text);
        int dot = token.lastIndexOf('.');
        if (dot > 0 && dot < token.length() - 1) {
            String extension = token.substring(dot + 1);
            boolean lower = extension.length() <= 5;
            for (int i = 0; i < extension.length() && lower; i++) {
                char c = extension.charAt(i);
                lower = Character.isLowerCase(c) || Character.isDigit(c);
            }
            if (lower && Character.isLetter(extension.charAt(0))) {
                return true;
            }
        }
        int slash = token.indexOf('/');
        return slash >= 0 && token.indexOf('/', slash + 1) >= 0; // a/b/c: a path, not an alternative
    }

    private static boolean isJoiner(char c) {
        return c == '-' || c == '.' || c == '/' || c == '_';
    }
}
