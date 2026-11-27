package com.aresstack.askai.research.runtime.writer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the markers of a writer answer (plan D8, stage 1, PR 28). It strips markers that name no passage of the
 * pack and reports them, splits the text into paragraphs and sentences, keeps the cited numbers per sentence,
 * flags uncited sentences and paragraphs and turns {@code GAP:} lines into open points. Headings and list bullets,
 * which the writer must not produce, are reduced to plain text.
 *
 * <p>What is no prose leaves the text (live run 4, books 2 and 3): a heading or list line without a sentence end and
 * without a marker, and a paragraph made only of such words, is a label the writer echoed (its key points, run
 * together into one line); a sentence the answer, an earlier part of the same section or another written section of
 * the book already contains, word for word or nearly ({@link RepeatCheck}, live run 4, book 6), is a repeat; a
 * paragraph of uncited questions holds the writer's open questions, which become open points like
 * {@code GAP:} lines. A {@code GAP:} line that repeats a sentence of the text (or of an earlier part) or another
 * {@code GAP:} line is no open point (live run 4, book 5: 12 of 23 gap lines were sentences of the section's own
 * text, and each became a search query); it is dropped and reported. A finding about a claim is still only a flag:
 * no claim is removed. Stateless and deterministic.</p>
 */
public final class CitationValidator {

    /** {@code [3]}, {@code [1, 4]}, {@code [2; 5]}. */
    static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\]");
    /**
     * A bracket group with a letter and no digit: the writer's own note in place of a citation ("[keine Passage]",
     * live run 4) when it stands {@linkplain #inMarkerPlace where a marker belongs}. Only {@code [n]} cites a
     * passage.
     */
    private static final Pattern NOTE = Pattern.compile("\\s*\\[(?=[^\\[\\]\\d]*\\p{L})[^\\[\\]\\d]+\\]");
    /** What follows a note before a sentence end: closing quotes or brackets, then the end. */
    private static final Pattern BEFORE_SENTENCE_END = Pattern.compile("^[\"'“”»’)]*(?:[.!?…]|$)");
    /** What precedes a note after a sentence end: a full stop as {@link #SENTENCE_END} reads it. */
    private static final Pattern AFTER_SENTENCE_END = Pattern.compile("[.!?][\"“”»’')]?$");
    private static final Pattern GAP = Pattern.compile("^\\s*(?:[-*]\\s*)?GAP\\s*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^\\s*#{1,6}\\s+");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•]|\\d{1,2}[.)])\\s+");
    /** A sentence ends at . ! ? (optionally a closing quote or bracket) followed by whitespace. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?][\"“”»’')]?)\\s+");
    /**
     * A part ending in a short abbreviation ("z.", "B.", "Dr.", "ca.", "vgl.", "Hrsg.") continues in the next part,
     * also after a bracket or a quote ("(vgl."). c10 split "§ 87 Abs. 1 Nr. 6 BetrVG" and "Art. 9 Abs. 2 lit. b DSGVO"
     * into pieces without a marker.
     */
    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?<![\\p{L}\\d])(?:\\p{L}|ca|dr|nr|vgl|bzw|usw|etc|prof|abb|kap|mio|mrd|jh|evtl|ggf|inkl|tab|lt"
                    + "|e\\.g|i\\.e|vs|hrsg|bsp|bspw|insb|zzgl|exkl|bgbl|verf|bzgl|abzgl|einschl|ausschl)\\.$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** "sog." continues in lowercase only: "Sog." is a noun that may end a sentence. */
    private static final Pattern LOWERCASE_ABBREVIATION = Pattern.compile("(?<![\\p{L}\\d])sog\\.$");
    /**
     * An abbreviation before a number ("Abs. 2", "Rn. 12", "3. Aufl. 2019"): it continues only when the next part
     * starts with a number, so "ABS." or "well lit." at a sentence's end still ends it ("lit. b" goes on in lowercase).
     */
    private static final Pattern NUMBER_ABBREVIATION = Pattern.compile(
            "(?<![\\p{L}\\d])(?:abs|lit|ziff|rn|rdnr|abschn|anh|anl|bd|aufl|nrn)\\.$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * An abbreviation that is also a word ("auf diese Art."): it continues only before a number of one to three digits
     * ("Art. 9 DSGVO", "Art. 229 EGBGB"), not before a year that opens the next sentence ("Art. 2023 kam …").
     */
    private static final Pattern SHORT_NUMBER_ABBREVIATION = Pattern.compile(
            "(?<![\\p{L}\\d])(?:(?iu:art|artikel|sec|para)|No)\\.$");
    private static final Pattern SHORT_NUMBER = Pattern.compile("^\\d{1,3}(?!\\d)");
    /**
     * "gem." continues before a paragraph sign, a number, a code in capitals ("gem. § 5", "gem. DIN EN 1838"), a word
     * in lowercase, or a word of a speaker's statement ("gem. Herstellerangaben", "gem. Datenblatt": the review of
     * 22.11.2026); before another word ("gem. Danach …", or "a real gem. Studies show …") it ends the sentence.
     */
    private static final Pattern GEM = Pattern.compile("(?<![\\p{L}\\d])gem\\.$");
    private static final Pattern AFTER_GEM = Pattern.compile("^(?:§|\\d|\\p{Lu}{2,}(?!\\p{Ll})|Art\\.|Abs\\.|\\p{Ll}"
            + "|(?:Hersteller|Anbieter|Händler|Verband|Datenbl|Angabe|Bericht|Auskunft)\\p{L}*"
            + "|Studie(?:n\\p{L}*)?(?!\\p{L}))");
    /** A day or a century before its month or its word: "am 1. Januar 2024", "im 19. Jahrhundert". */
    private static final Pattern ORDINAL = Pattern.compile("(?<![\\p{L}\\d.,])\\d{1,2}\\.$");
    private static final Pattern AFTER_ORDINAL = Pattern.compile("^(?:Januar|Februar|März|April|Mai|Juni|Juli"
            + "|August|September|Oktober|November|Dezember|Jahrhunderts?|Jh\\.|Auflage|Aufl\\.)(?![\\p{L}])");
    private static final Pattern LEADING_MARKERS = Pattern.compile("^((?:\\s*" + MARKER.pattern() + ")+)\\s*");
    /** A sentence end anywhere: prose has one, a label (a heading, a key point, a list item) has none. */
    private static final Pattern ANY_SENTENCE_END = Pattern.compile("[.!?]");
    /** Closing quotes and brackets after a question mark: {@code "Wer zahlt?“}. */
    private static final Pattern TRAILING_CLOSERS = Pattern.compile("[\"'“”»«’)]+$");
    /** The closing punctuation of a sentence, with its quotes and brackets: no part of what it says. */
    private static final Pattern SENTENCE_CLOSE = Pattern.compile("[\\s.!?…\"'“”»«’)]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** One sentence with the passage numbers it cites. */
    public static final class Sentence {
        private final String text;
        private final Set<Integer> cited;

        Sentence(String text, Set<Integer> cited) {
            this.text = text;
            this.cited = Collections.unmodifiableSet(new TreeSet<Integer>(cited));
        }

        public String getText() {
            return text;
        }

        public Set<Integer> getCited() {
            return cited;
        }

        /** The sentence without its markers, for the claim check. */
        public String plainText() {
            return MARKER.matcher(text).replaceAll("").replaceAll("\\s+([.,;:!?])", "$1").trim();
        }
    }

    /** One paragraph: its sentences and the union of their citations. */
    public static final class Paragraph {
        private final List<Sentence> sentences;
        private final Set<Integer> cited;

        Paragraph(List<Sentence> sentences) {
            this.sentences = Collections.unmodifiableList(new ArrayList<Sentence>(sentences));
            Set<Integer> all = new TreeSet<Integer>();
            for (Sentence s : sentences) {
                all.addAll(s.getCited());
            }
            this.cited = Collections.unmodifiableSet(all);
        }

        public List<Sentence> getSentences() {
            return sentences;
        }

        public Set<Integer> getCited() {
            return cited;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Sentence s : sentences) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s.getText());
            }
            return sb.toString();
        }
    }

    /** The checked answer. */
    public static final class Result {
        private final List<Paragraph> paragraphs;
        private final List<String> strippedMarkers;
        private final List<String> gaps;
        private final List<ClaimFlag> flags;
        private final List<String> labelLines;
        private final List<String> repeatedSentences;
        private final List<String> questions;
        private final List<String> droppedGaps;

        Result(List<Paragraph> paragraphs, List<String> strippedMarkers, List<String> gaps, List<ClaimFlag> flags) {
            this(paragraphs, strippedMarkers, gaps, flags, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        Result(List<Paragraph> paragraphs, List<String> strippedMarkers, List<String> gaps, List<ClaimFlag> flags,
               List<String> labelLines, List<String> repeatedSentences, List<String> questions) {
            this(paragraphs, strippedMarkers, gaps, flags, labelLines, repeatedSentences, questions,
                    Collections.<String>emptyList());
        }

        Result(List<Paragraph> paragraphs, List<String> strippedMarkers, List<String> gaps, List<ClaimFlag> flags,
               List<String> labelLines, List<String> repeatedSentences, List<String> questions,
               List<String> droppedGaps) {
            this.paragraphs = Collections.unmodifiableList(paragraphs);
            this.strippedMarkers = Collections.unmodifiableList(strippedMarkers);
            this.gaps = Collections.unmodifiableList(gaps);
            this.flags = Collections.unmodifiableList(flags);
            this.labelLines = Collections.unmodifiableList(new ArrayList<String>(labelLines));
            this.repeatedSentences = Collections.unmodifiableList(new ArrayList<String>(repeatedSentences));
            this.questions = Collections.unmodifiableList(new ArrayList<String>(questions));
            this.droppedGaps = Collections.unmodifiableList(new ArrayList<String>(droppedGaps));
        }

        /**
         * {@code GAP:} lines that are no open point: a sentence of the text, another {@code GAP:} line, or what
         * {@link #withoutGaps} took out (the writer's word target, a sentence the repair asked to leave out).
         */
        public List<String> getDroppedGaps() {
            return droppedGaps;
        }

        /** A copy without the open points that {@code drop} accepts; they join {@link #getDroppedGaps()}. */
        Result withoutGaps(java.util.function.Predicate<String> drop) {
            List<String> kept = new ArrayList<String>();
            List<String> dropped = new ArrayList<String>(droppedGaps);
            for (String point : gaps) {
                if (drop.test(point)) {
                    dropped.add(point);
                } else {
                    kept.add(point);
                }
            }
            if (dropped.size() == droppedGaps.size()) {
                return this;
            }
            return new Result(paragraphs, strippedMarkers, kept, flags, labelLines, repeatedSentences, questions,
                    dropped);
        }

        /** Headings, key points and list items without a sentence end or a marker; removed from the text. */
        public List<String> getLabelLines() {
            return labelLines;
        }

        /**
         * Sentences the answer, an earlier part of the section or another section of the book already contained, or
         * nearly contained ({@link RepeatCheck}); removed from the text.
         */
        public List<String> getRepeatedSentences() {
            return repeatedSentences;
        }

        /** The uncited questions of question-only paragraphs; removed from the text, kept as open points. */
        public List<String> getQuestions() {
            return questions;
        }

        public List<Paragraph> getParagraphs() {
            return paragraphs;
        }

        /** Markers that named no passage of the pack, as written (e.g. {@code [9]}); removed from the text. */
        public List<String> getStrippedMarkers() {
            return strippedMarkers;
        }

        /** The text of the {@code GAP:} lines: the section's open points. */
        public List<String> getGaps() {
            return gaps;
        }

        /** Uncited sentences and paragraphs. */
        public List<ClaimFlag> getFlags() {
            return flags;
        }

        /** Every passage number the text cites, ascending. */
        public Set<Integer> citedNumbers() {
            Set<Integer> all = new TreeSet<Integer>();
            for (Paragraph p : paragraphs) {
                all.addAll(p.getCited());
            }
            return all;
        }

        /** The section text: paragraphs separated by a blank line, markers kept, gap lines removed. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Paragraph p : paragraphs) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(p.text());
            }
            return sb.toString();
        }

        public int wordCount() {
            String plain = MARKER.matcher(text()).replaceAll(" ").trim();
            return plain.isEmpty() ? 0 : plain.split("\\s+").length;
        }
    }

    /**
     * @param answer      the writer's plain-text answer
     * @param passageCount k, the number of passages in the pack: valid markers are {@code [1]..[k]}
     */
    public Result validate(String answer, int passageCount) {
        return validate(answer, passageCount, Collections.<String>emptySet());
    }

    /**
     * @param answer       the writer's plain-text answer
     * @param passageCount k, the number of passages in the pack: valid markers are {@code [1]..[k]}
     * @param written      the {@linkplain #sentenceKeys(String) sentence keys} of the section's earlier parts: a
     *                     sentence of this answer that repeats one of them is removed like a repeat within it
     */
    public Result validate(String answer, int passageCount, Set<String> written) {
        return validate(answer, passageCount, written, RepeatCheck.none());
    }

    /**
     * @param answer       the writer's plain-text answer
     * @param passageCount k, the number of passages in the pack: valid markers are {@code [1]..[k]}
     * @param written      the {@linkplain #sentenceKeys(String) sentence keys} of the book's other sections and of the
     *                     section's earlier parts: a sentence of this answer that repeats one of them is removed like
     *                     a repeat within it
     * @param earlier      the same sentences for the near-repeat check ({@link RepeatCheck}); a sentence that nearly
     *                     repeats one of them, or an earlier sentence of this answer, is removed as well. Not changed:
     *                     the check works on a copy.
     */
    public Result validate(String answer, int passageCount, Set<String> written, RepeatCheck.Memory earlier) {
        RepeatCheck.Memory near = (earlier == null ? RepeatCheck.none() : earlier).copy();
        List<String> stripped = new ArrayList<String>();
        List<String> gapLines = new ArrayList<String>();
        List<String> questionPoints = new ArrayList<String>();
        List<String> labelLines = new ArrayList<String>();
        List<String> repeated = new ArrayList<String>();
        List<String> questions = new ArrayList<String>();
        List<Paragraph> paragraphs = new ArrayList<Paragraph>();
        List<StringBuilder> blocks = new ArrayList<StringBuilder>();
        StringBuilder current = null;
        for (String raw : (answer == null ? "" : answer).replace("\r\n", "\n").split("\n", -1)) {
            Matcher gap = GAP.matcher(raw);
            if (gap.matches()) {
                String point = gap.group(1).trim();
                if (!point.isEmpty()) {
                    gapLines.add(point);
                }
                current = null;
                continue;
            }
            boolean structured = HEADING.matcher(raw).find() || BULLET.matcher(raw).find();
            String line = BULLET.matcher(HEADING.matcher(raw).replaceFirst("")).replaceFirst("").trim();
            if (line.isEmpty()) {
                current = null;
                continue;
            }
            if (structured && isLabel(line)) {
                labelLines.add(line); // a heading or list item ends the paragraph it would have been glued to
                current = null;
                continue;
            }
            if (current == null) {
                current = new StringBuilder();
                blocks.add(current);
            } else {
                current.append(' ');
            }
            current.append(line);
        }
        Set<String> seen = new HashSet<String>(written == null ? Collections.<String>emptySet() : written);
        Set<String> prose = new HashSet<String>(seen); // what the text says: no open point
        Set<String> openPoints = new HashSet<String>();
        for (String point : gapLines) {
            openPoints.add(pointKey(point));
        }
        for (StringBuilder block : blocks) {
            String text = stripUnknown(block.toString(), passageCount, stripped);
            if (text.isEmpty()) {
                continue;
            }
            if (isLabel(text)) {
                labelLines.add(text); // key points written as plain lines: one line of words without a sentence
                continue;
            }
            List<Sentence> kept = new ArrayList<Sentence>();
            // the paragraph's sentences join the memory only when it stays prose (not an uncited question block)
            RepeatCheck.Memory paragraph = near.copy();
            for (Sentence sentence : sentences(text)) {
                String plain = sentence.plainText();
                String key = key(plain);
                if (!key.isEmpty() && (!seen.add(key) || paragraph.repeats(plain))) {
                    repeated.add(sentence.getText());
                    continue;
                }
                paragraph.remember(plain);
                kept.add(sentence);
            }
            if (kept.isEmpty()) {
                continue;
            }
            if (onlyUncitedQuestions(kept)) {
                for (Sentence question : kept) {
                    String point = question.plainText();
                    questions.add(point);
                    if (openPoints.add(key(point))) {
                        questionPoints.add(point);
                    }
                }
                continue;
            }
            for (Sentence sentence : kept) {
                prose.add(key(sentence.plainText()));
            }
            near = paragraph;
            paragraphs.add(new Paragraph(kept));
        }
        List<String> gaps = new ArrayList<String>();
        List<String> droppedGaps = new ArrayList<String>();
        Set<String> gapKeys = new HashSet<String>();
        for (String point : gapLines) {
            String key = pointKey(point);
            if (prose.contains(key) || !gapKeys.add(key)) {
                droppedGaps.add(point); // a sentence of the text, or the same gap again
            } else {
                gaps.add(point);
            }
        }
        gaps.addAll(questionPoints);
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        for (int p = 0; p < paragraphs.size(); p++) {
            Paragraph paragraph = paragraphs.get(p);
            if (paragraph.getCited().isEmpty()) {
                flags.add(new ClaimFlag(ClaimFlag.Kind.UNCITED_PARAGRAPH, p, paragraph.text(), ""));
            }
            for (Sentence s : paragraph.getSentences()) {
                if (s.getCited().isEmpty()) {
                    flags.add(new ClaimFlag(ClaimFlag.Kind.UNCITED_SENTENCE, p, s.getText(), ""));
                }
            }
        }
        return new Result(paragraphs, stripped, gaps, flags, labelLines, repeated, questions, droppedGaps);
    }

    /** The {@linkplain #key(String) key} of an open point or a listed sentence, its markers left out. */
    static String pointKey(String text) {
        return key(new Sentence(text == null ? "" : text, Collections.<Integer>emptySet()).plainText());
    }

    /**
     * The keys of a written text's sentences (case, spacing, closing punctuation and markers ignored), so a later
     * part of the same section can leave out what this text already says.
     */
    public static Set<String> sentenceKeys(String text) {
        Set<String> keys = new HashSet<String>();
        for (Sentence sentence : sentencesOf(text)) {
            String key = key(sentence.plainText());
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * The sentences of a written text as this check reads them: paragraphs parted by blank lines, each split into
     * sentences with the passage numbers they cite. The sentence check and the mission report read a section so.
     */
    public static List<Sentence> sentencesOf(String text) {
        List<Sentence> all = new ArrayList<Sentence>();
        for (String paragraph : (text == null ? "" : text).split("\\n\\s*\\n")) {
            all.addAll(sentences(paragraph.replace('\n', ' ').trim()));
        }
        return all;
    }

    /** A line or paragraph without any sentence end and without a marker: a label, not prose. */
    static boolean isLabel(String text) {
        return !ANY_SENTENCE_END.matcher(text).find() && !MARKER.matcher(text).find();
    }

    private static boolean onlyUncitedQuestions(List<Sentence> sentences) {
        for (Sentence sentence : sentences) {
            if (!sentence.getCited().isEmpty()
                    || !TRAILING_CLOSERS.matcher(sentence.plainText()).replaceAll("").endsWith("?")) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a repeat is found by: case, spacing and the closing punctuation are ignored, every other character counts,
     * so "-20 °C" and "20 °C", or "5 %" and "5 ‰", stay two claims.
     */
    private static String key(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return SENTENCE_CLOSE.matcher(WHITESPACE.matcher(n).replaceAll(" ").trim()).replaceAll("");
    }

    /**
     * Removes the numbers of a marker outside 1..k and every {@linkplain #NOTE note in a marker's place}; a marker
     * left empty disappears with its leading space.
     */
    static String stripUnknown(String text, int k, List<String> stripped) {
        Matcher note = NOTE.matcher(text);
        StringBuffer clean = new StringBuffer();
        while (note.find()) {
            if (inMarkerPlace(text, note.start(), note.end())) {
                stripped.add(note.group().trim());
                note.appendReplacement(clean, "");
            }
        }
        note.appendTail(clean);
        Matcher m = MARKER.matcher(clean.toString());
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            List<String> kept = new ArrayList<String>();
            for (String n : m.group(1).split("[,;]")) {
                int number = Integer.parseInt(n.trim());
                if (number >= 1 && number <= k) {
                    kept.add(String.valueOf(number));
                } else {
                    stripped.add("[" + number + "]");
                }
            }
            String replacement = kept.isEmpty() ? "" : "[" + String.join(", ", kept) + "]";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s+([.,;:!?])", "$1").replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * True when the bracket group from {@code start} to {@code end} stands where a marker belongs: before a sentence
     * end, or after one as {@link #sentences} reads it. A full stop after an abbreviation ("z. B.") or before a
     * lowercase word or a comma ends no sentence, so a bracket there is prose ("z. B. [Puls und Temperatur], …").
     */
    private static boolean inMarkerPlace(String text, int start, int end) {
        String after = text.substring(end);
        if (BEFORE_SENTENCE_END.matcher(after).find()) {
            return true;
        }
        String before = text.substring(0, start).trim();
        String next = after.trim();
        return AFTER_SENTENCE_END.matcher(before).find()
                && !continues(before, next)
                && (after.isEmpty() || Character.isWhitespace(after.charAt(0)))
                && (next.isEmpty() || !startsLowercase(next));
    }

    /** Whether a part ending {@code previous} goes on in {@code next}: an abbreviation, not a sentence end. */
    static boolean continues(String previous, String next) {
        if (ABBREVIATION.matcher(previous).find() || LOWERCASE_ABBREVIATION.matcher(previous).find()) {
            return true;
        }
        if (next == null || next.isEmpty()) {
            return false;
        }
        if (NUMBER_ABBREVIATION.matcher(previous).find() && Character.isDigit(next.codePointAt(0))) {
            return true;
        }
        if (SHORT_NUMBER_ABBREVIATION.matcher(previous).find() && SHORT_NUMBER.matcher(next).find()) {
            return true;
        }
        if (ORDINAL.matcher(previous).find() && AFTER_ORDINAL.matcher(next).find()) {
            return true;
        }
        return GEM.matcher(previous).find() && AFTER_GEM.matcher(next).find();
    }

    /** Splits a paragraph into sentences; markers written after the full stop belong to the sentence before. */
    static List<Sentence> sentences(String paragraph) {
        List<String> parts = new ArrayList<String>();
        for (String piece : SENTENCE_END.split(paragraph)) {
            String part = piece.trim();
            if (part.isEmpty()) {
                continue;
            }
            Matcher lead = LEADING_MARKERS.matcher(part);
            if (!parts.isEmpty() && lead.find()) {
                int last = parts.size() - 1;
                parts.set(last, parts.get(last) + " " + lead.group(1).trim());
                part = part.substring(lead.end()).trim();
                if (part.isEmpty()) {
                    continue;
                }
            }
            String previous = parts.isEmpty() ? "" : parts.get(parts.size() - 1);
            if (!parts.isEmpty() && (startsLowercase(part) || continues(previous, part))) {
                int last = parts.size() - 1; // "z. B. die" – an abbreviation, not a sentence end
                parts.set(last, parts.get(last) + " " + part);
                continue;
            }
            parts.add(part);
        }
        List<Sentence> sentences = new ArrayList<Sentence>();
        for (String part : parts) {
            Set<Integer> cited = new LinkedHashSet<Integer>();
            Matcher m = MARKER.matcher(part);
            while (m.find()) {
                for (String n : m.group(1).split("[,;]")) {
                    cited.add(Integer.parseInt(n.trim()));
                }
            }
            sentences.add(new Sentence(part, cited));
        }
        return sentences;
    }

    private static boolean startsLowercase(String part) {
        int cp = part.codePointAt(0);
        return Character.isLowerCase(cp);
    }
}
