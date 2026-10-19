package com.aresstack.enterpriseai.astu.acropolis.textmining;

import com.aresstack.enterpriseai.astu.acropolis.passage.SentenceSegmenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds acronyms, technical identifiers and explicitly stated long forms in the canonical text of one document
 * (issue #107). Rules only: the shape of a token ({@link AcronymForms}), the context it stands in (code, addresses,
 * lines in capitals are left out) and, for long forms, the letter alignment of {@link LongFormMatcher} inside one
 * sentence. Sentences come from a {@link SentenceSegmenter} (the OpenNLP model of the language if installed, else the
 * rule-based splitter); without models the extractor still works.
 *
 * <p>Stateless and thread-safe as long as the segmenter is.</p>
 */
public final class AcronymExtractor {

    /** Identity of this extractor in stored facts. */
    public static final String ID = "text-acronyms";
    /** Raise when the rules change, so stored facts are re-evaluated. */
    public static final String VERSION = "1";

    private final SentenceSegmenter sentences;

    public AcronymExtractor(SentenceSegmenter sentences) {
        if (sentences == null) {
            throw new IllegalArgumentException("sentences must not be null");
        }
        this.sentences = sentences;
    }

    /** The findings of {@code text}; an empty analysis for empty text. */
    public AcronymAnalysis analyze(String text, AcronymSettings settings) {
        if (text == null || text.trim().isEmpty()) {
            return AcronymAnalysis.empty();
        }
        AcronymSettings rules = settings == null ? AcronymSettings.defaults() : settings;
        Map<String, Group> groups = new LinkedHashMap<String, Group>();
        List<TextSpan> shouting = shoutingLines(text, rules);
        for (TermScanner.Token token : TermScanner.tokens(text)) {
            collect(token, rules, shouting, groups);
        }
        for (TextSpan sentence : sentenceSpans(text)) {
            for (AcronymDefinition definition : definitions(text, sentence, rules)) {
                String key = TermKind.ACRONYM + ":" + AcronymForms.normalize(definition.shortForm());
                Group group = groups.get(key);
                if (group == null) {
                    group = new Group(AcronymForms.normalize(definition.shortForm()), TermKind.ACRONYM);
                    group.add(definition.shortForm(), definition.shortSpan());
                    groups.put(key, group);
                }
                group.definitions.add(definition);
            }
        }
        List<AcronymFinding> findings = new ArrayList<AcronymFinding>();
        for (Group group : groups.values()) {
            findings.add(group.finding());
        }
        Collections.sort(findings, new Comparator<AcronymFinding>() {
            @Override
            public int compare(AcronymFinding a, AcronymFinding b) {
                return a.occurrences().get(0).compareTo(b.occurrences().get(0));
            }
        });
        return new AcronymAnalysis(findings, sentences.id());
    }

    private static void collect(TermScanner.Token token, AcronymSettings rules, List<TextSpan> shouting,
                                Map<String, Group> groups) {
        TermKind whole = AcronymForms.classify(token.text(), rules.maxLetters());
        if (whole == TermKind.TECHNICAL_IDENTIFIER) {
            add(groups, token.text(), TermKind.TECHNICAL_IDENTIFIER, token.span());
            return;
        }
        List<TermScanner.Token> parts = token.text().indexOf('-') >= 0 || token.text().indexOf('/') >= 0
                ? TermScanner.parts(token) : Collections.singletonList(token);
        for (TermScanner.Token part : parts) {
            TermKind kind = parts.size() == 1 ? whole : AcronymForms.classify(part.text(), rules.maxLetters());
            if (kind == TermKind.ACRONYM && !inside(part.span(), shouting)) {
                add(groups, part.text(), TermKind.ACRONYM, part.span());
            } else if (kind == TermKind.TECHNICAL_IDENTIFIER) {
                add(groups, part.text(), TermKind.TECHNICAL_IDENTIFIER, part.span());
            }
        }
    }

    private static void add(Map<String, Group> groups, String surface, TermKind kind, TextSpan span) {
        String normalized = kind == TermKind.ACRONYM ? AcronymForms.normalize(surface) : surface;
        String key = kind + ":" + normalized;
        Group group = groups.get(key);
        if (group == null) {
            group = new Group(normalized, kind);
            groups.put(key, group);
        }
        group.add(surface, span);
    }

    /** The lines written in capitals. */
    private static List<TextSpan> shoutingLines(String text, AcronymSettings rules) {
        List<TextSpan> lines = new ArrayList<TextSpan>();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            if (AcronymForms.isShouting(text.substring(start, end), rules.shoutingPercent(), rules.shoutingMinWords())) {
                lines.add(new TextSpan(start, end));
            }
            start = end + 1;
        }
        return lines;
    }

    private static boolean inside(TextSpan span, List<TextSpan> ranges) {
        for (TextSpan range : ranges) {
            if (span.start() >= range.start() && span.end() <= range.end()) {
                return true;
            }
        }
        return false;
    }

    /** Sentences per line outside fenced code; a sentence never spans two lines (headings, list items, cells). */
    List<TextSpan> sentenceSpans(String text) {
        List<TextSpan> spans = new ArrayList<TextSpan>();
        boolean code = false;
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(start, end);
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                code = !code;
            } else if (!code && !trimmed.isEmpty()) {
                locate(line, start, sentences.sentences(trimmed), spans);
            }
            start = end + 1;
        }
        return spans;
    }

    /** Find each sentence in its line; if one cannot be found literally, the rest of the line is one sentence. */
    private static void locate(String line, int lineStart, List<String> parts, List<TextSpan> into) {
        int cursor = 0;
        for (String part : parts) {
            String sentence = part == null ? "" : part.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            int at = line.indexOf(sentence, cursor);
            if (at < 0) {
                break;
            }
            into.add(new TextSpan(lineStart + at, lineStart + at + sentence.length()));
            cursor = at + sentence.length();
        }
        String rest = line.substring(cursor).trim();
        if (!rest.isEmpty()) {
            int at = line.indexOf(rest, cursor);
            into.add(new TextSpan(lineStart + at, lineStart + at + rest.length()));
        }
    }

    /** The definitions in one sentence: every pair of parentheses is checked in both directions. */
    List<AcronymDefinition> definitions(String text, TextSpan sentence, AcronymSettings rules) {
        List<AcronymDefinition> found = new ArrayList<AcronymDefinition>();
        int from = sentence.start();
        while (true) {
            int open = text.indexOf('(', from);
            if (open < 0 || open >= sentence.end()) {
                break;
            }
            int close = text.indexOf(')', open + 1);
            int nested = text.indexOf('(', open + 1);
            if (close < 0 || close > sentence.end()) {
                break;
            }
            from = close + 1;
            if (nested >= 0 && nested < close) {
                continue; // nested parentheses: no reliable boundaries
            }
            AcronymDefinition definition = longFormFirst(text, sentence, open, close, rules);
            if (definition == null) {
                definition = shortFormFirst(text, sentence, open, close, rules);
            }
            if (definition != null) {
                found.add(definition);
            }
        }
        return found;
    }

    /** {@code long form (SHORT)}: the parentheses hold only the short form, maybe followed by a comma. */
    private static AcronymDefinition longFormFirst(String text, TextSpan sentence, int open, int close,
                                                   AcronymSettings rules) {
        TextSpan inner = firstChunk(text, open + 1, close);
        if (inner == null) {
            return null;
        }
        List<TermScanner.Token> tokens = TermScanner.tokens(inner.of(text));
        if (tokens.size() != 1 || tokens.get(0).span().length() != inner.length()) {
            return null;
        }
        String shortForm = tokens.get(0).text();
        if (AcronymForms.classify(shortForm, rules.maxLetters()) != TermKind.ACRONYM) {
            return null;
        }
        String base = AcronymForms.withoutPlural(shortForm);
        int candidateEnd = open;
        while (candidateEnd > sentence.start() && Character.isWhitespace(text.charAt(candidateEnd - 1))) {
            candidateEnd--;
        }
        int candidateStart = wordsBack(text, sentence.start(), candidateEnd,
                LongFormMatcher.maxWords(LongFormMatcher.characters(base)));
        String candidate = text.substring(candidateStart, candidateEnd);
        int at = LongFormMatcher.longFormStart(base, candidate);
        if (at < 0) {
            return null;
        }
        TextSpan longSpan = new TextSpan(candidateStart + at, candidateEnd);
        if (!isLongForm(longSpan.of(text), base)) {
            return null;
        }
        return new AcronymDefinition(shortForm, collapse(longSpan.of(text)), AcronymDefinition.Pattern.LONG_FORM_FIRST,
                inner, longSpan, sentence);
    }

    /** {@code SHORT (long form)}: the word right before the parentheses is the short form. */
    private static AcronymDefinition shortFormFirst(String text, TextSpan sentence, int open, int close,
                                                    AcronymSettings rules) {
        int end = open;
        while (end > sentence.start() && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > sentence.start() && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return null;
        }
        List<TermScanner.Token> tokens = TermScanner.tokens(text.substring(start, end));
        if (tokens.size() != 1) {
            return null;
        }
        TermScanner.Token word = tokens.get(0);
        if (AcronymForms.classify(word.text(), rules.maxLetters()) != TermKind.ACRONYM) {
            return null;
        }
        TextSpan inner = firstChunk(text, open + 1, close);
        if (inner == null) {
            return null;
        }
        String base = AcronymForms.withoutPlural(word.text());
        String candidate = inner.of(text);
        if (candidate.trim().split("\\s+").length > LongFormMatcher.maxWords(LongFormMatcher.characters(base))) {
            return null;
        }
        // The whole inner text must be the long form: the alignment has to reach its first word.
        if (LongFormMatcher.longFormStart(base, candidate) != 0 || !isLongForm(candidate, base)) {
            return null;
        }
        TextSpan shortSpan = new TextSpan(start + word.span().start(), start + word.span().end());
        return new AcronymDefinition(word.text(), collapse(candidate), AcronymDefinition.Pattern.SHORT_FORM_FIRST,
                shortSpan, inner, sentence);
    }

    /** The text inside the parentheses up to the first comma or semicolon, trimmed; {@code null} if empty. */
    private static TextSpan firstChunk(String text, int start, int end) {
        int stop = end;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == ',' || c == ';') {
                stop = i;
                break;
            }
        }
        while (start < stop && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (stop > start && Character.isWhitespace(text.charAt(stop - 1))) {
            stop--;
        }
        return stop > start ? new TextSpan(start, stop) : null;
    }

    /** The start of the last {@code words} words before {@code end}, not before {@code limit}. */
    private static int wordsBack(String text, int limit, int end, int words) {
        int i = end;
        int count = 0;
        while (i > limit && count < words) {
            while (i > limit && Character.isWhitespace(text.charAt(i - 1))) {
                i--;
            }
            while (i > limit && !Character.isWhitespace(text.charAt(i - 1))) {
                i--;
            }
            count++;
        }
        return i;
    }

    /** A long form is longer than its short form, carries lower-case letters and does not repeat it. */
    private static boolean isLongForm(String longForm, String shortForm) {
        String value = longForm.trim();
        if (value.length() <= shortForm.length() || value.indexOf('(') >= 0 || value.indexOf(')') >= 0) {
            return false;
        }
        boolean lower = false;
        for (int i = 0; i < value.length() && !lower; i++) {
            lower = Character.isLowerCase(value.charAt(i));
        }
        return lower && !value.contains(shortForm);
    }

    private static String collapse(String value) {
        return value.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    /** Occurrences and definitions of one normalized form while collecting. */
    private static final class Group {
        final String normalized;
        final TermKind kind;
        final Map<String, Integer> surfaces = new HashMap<String, Integer>();
        final List<String> order = new ArrayList<String>();
        final List<TextSpan> occurrences = new ArrayList<TextSpan>();
        final java.util.Set<TextSpan> seen = new java.util.HashSet<TextSpan>();
        final List<AcronymDefinition> definitions = new ArrayList<AcronymDefinition>();

        Group(String normalized, TermKind kind) {
            this.normalized = normalized;
            this.kind = kind;
        }

        void add(String surface, TextSpan span) {
            Integer count = surfaces.get(surface);
            if (count == null) {
                order.add(surface);
                count = 0;
            }
            surfaces.put(surface, count + 1);
            if (seen.add(span)) {
                occurrences.add(span);
            }
        }

        AcronymFinding finding() {
            List<String> ranked = new ArrayList<String>(order);
            Collections.sort(ranked, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return Integer.compare(surfaces.get(b), surfaces.get(a));
                }
            });
            return new AcronymFinding(normalized, kind, ranked, occurrences, definitions);
        }
    }
}
