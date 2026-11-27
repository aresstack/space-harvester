package com.aresstack.writing4j.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Component 7: the two text rules of the research memory, both plain program checks.
 * <ul>
 * <li>{@link #key}: when two passages are the same text (a text counts once as evidence, whatever source carries
 * it);</li>
 * <li>{@link #holdsQuote}: whether a quote stands verbatim in a passage. What only comes from layout, quoting or
 * punctuation does not count as a difference: line breaks and runs of blanks, a hyphen at a line end (with or without
 * the hyphen in the quote), a hyphen inside a word, soft hyphens, list marks at the start of a line, upper or lower
 * case, quotation marks, dashes, brackets, commas and the other signs between words. The words, the numbers (a sign
 * inside a number splits it, so "2,5" and "25" differ), the word breaks and the order within a part do: a quote glued
 * together from places of the passage without an ellipsis does not hold, except for short gaps in order that leave
 * out no number or negation. A quote shortened with an ellipsis holds
 * when every part stands in the passage at a place of its own, in any order: the model often quotes the decisive
 * sentence first and an earlier one after it.</li>
 * <li>{@link #numbersHeld}: whether every number a statement names stands in the quote (build plan, second review:
 * for numbers and efficacy claims the number must stand in the quote).</li>
 * </ul>
 */
public final class PassageText {

    /** Characters of the normalised text that make the key. */
    public static final int KEY_CHARS = 700;

    /** A shorter quote proves nothing: it stands in almost every passage. */
    public static final int MIN_QUOTE_CHARS = 20;

    /** Each part of a quote shortened with an ellipsis needs this many letters or digits. */
    public static final int MIN_PART_LETTERS = 6;

    private static final Pattern LINE_END_HYPHEN = Pattern.compile("(?<=\\p{L})-[ \\t]*\\r?\\n\\s*(?=\\p{Ll})");
    /** For the quote check: a hyphen at a line end between letters, whatever case follows. */
    private static final Pattern BROKEN_WORD = Pattern.compile("(?<=\\p{L})-[ \\t]*\\r?\\n\\s*(?=\\p{L})");
    /** For the quote check: a hyphen inside a word ("Funk-Sende-Empfänger" reads as "FunkSendeEmpfänger"). */
    private static final Pattern WORD_HYPHEN = Pattern.compile("(?<=\\p{L})-(?=\\p{L})");
    /** For the quote check: a list mark at the start of a line is layout. */
    private static final Pattern LIST_MARK = Pattern.compile("(?m)^[ \\t]*[-•*·▪●◦‣⁃][ \\t]+");
    private static final Pattern ELISION = Pattern.compile(
            "\\[\\s*(?:\\.\\.\\.|…)\\s*\\]|\\(\\s*(?:\\.\\.\\.|…)\\s*\\)|\\.\\.\\.|…");
    /** For the quote check: every run of signs that are no letter or digit is one word break. */
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** The places tried before the quote check gives up: a bound for quotes of many short, frequent parts. */
    private static final int MAX_PLACES_TRIED = 10000;
    /** A part of a quote with unmarked gaps stands in runs of at least this many words ({@link #gapped}). */
    static final int MIN_RUN_WORDS = 4;
    /** At most this many words left out in one unmarked gap. */
    static final int MAX_GAP_WORDS = 8;
    /** At most this many unmarked gaps in one part. */
    static final int MAX_GAPS = 3;
    /** Words a quote must not leave out unmarked: they turn or narrow what a sentence says. */
    private static final java.util.Set<String> MEANING_WORDS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals", "nirgends", "ohne",
            "nur", "lediglich", "ausschließlich", "ausser", "außer", "weder", "noch", "kaum", "selten",
            "not", "no", "never", "none", "without", "only", "except", "neither", "nor", "hardly", "rarely"));

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,\\u00A0\\u202F]\\d+)*");

    private PassageText() {
    }

    /**
     * The first {@value #KEY_CHARS} characters, lower case, everything but letters and digits folded to a blank, with
     * no blank at either end (a verdict stores its key trimmed, so a key cut on a blank must not keep it).
     */
    public static String key(String passageText) {
        if (passageText == null) {
            return "";
        }
        String folded = unhyphenated(passageText).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return folded.length() > KEY_CHARS ? folded.substring(0, KEY_CHARS).trim() : folded;
    }

    /**
     * Whether {@code quote} stands verbatim in {@code passageText}, compared in the form {@link #comparable} gives with
     * only letters, digits and word breaks left ({@link #words}). A quote the model shortened with an ellipsis holds
     * when each part has at least {@value #MIN_PART_LETTERS} letters or digits and every part stands in the passage at
     * a place of its own (no two parts on the same words), in any order. Every part starts and ends at a word break,
     * so a quote cut from inside a word or a number ("wirksam" from "unwirksam", "25" from "125") does not hold. A
     * quote of fewer than {@value #MIN_QUOTE_CHARS} characters never holds. Within a part, a few words may be left out
     * without a mark ({@link #gapped}): runs of at least {@value #MIN_RUN_WORDS} words, gaps of at most
     * {@value #MAX_GAP_WORDS} words, no left-out number or negation.
     * <p>
     * Stock test of component 11 on 22.11.2026: four of the seven quotes of gemma4:12b that this check turned down
     * were right; three gave the decisive sentence first and an earlier one after the ellipsis, one differed only in
     * commas and brackets. A changed, added or reordered word still does not hold.
     */
    public static boolean holdsQuote(String passageText, String quote) {
        String text = words(comparable(passageText));
        String wanted = comparable(stripOuterQuotes(quote));
        if (text.isEmpty() || wanted.replace("...", "").replace("…", "").trim().length() < MIN_QUOTE_CHARS) {
            return false;
        }
        List<String[]> pieces = new ArrayList<String[]>();
        for (String part : ELISION.split(wanted)) {
            String piece = words(part);
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.replace(" ", "").length() < MIN_PART_LETTERS) {
                return false;
            }
            pieces.add(piece.split(" "));
        }
        return !pieces.isEmpty() && placed(text.split(" "), pieces, 0, new ArrayList<int[]>(),
                new int[]{MAX_PLACES_TRIED});
    }

    /** Letters and digits of a comparable text, every run of other signs one blank. */
    static String words(String comparable) {
        return NOT_A_WORD.matcher(comparable).replaceAll(" ").trim();
    }

    /** Whether the parts from {@code index} on find places of their own beside the places {@code taken}. */
    private static boolean placed(String[] text, List<String[]> pieces, int index, List<int[]> taken, int[] budget) {
        if (index == pieces.size()) {
            return true;
        }
        for (int[] span : spans(text, pieces.get(index), budget)) {
            boolean free = true;
            for (int[] place : taken) {
                free &= span[1] <= place[0] || span[0] >= place[1];
            }
            if (free) {
                taken.add(span);
                if (placed(text, pieces, index + 1, taken, budget)) {
                    return true;
                }
                taken.remove(taken.size() - 1);
            }
        }
        return false;
    }

    /**
     * The places (first word, word after the last) where {@code piece} stands in {@code text}: word for word first,
     * then with unmarked gaps ({@link #gapped}). Words are whole words, so "wirksam" does not stand in "unwirksam".
     */
    private static List<int[]> spans(String[] text, String[] piece, int[] budget) {
        List<int[]> exact = new ArrayList<int[]>();
        List<int[]> gapped = new ArrayList<int[]>();
        for (int at = 0; at + piece.length <= text.length && budget[0] > 0; at++) {
            if (!text[at].equals(piece[0])) {
                continue;
            }
            budget[0]--;
            int n = 0;
            while (n < piece.length && text[at + n].equals(piece[n])) {
                n++;
            }
            if (n == piece.length) {
                exact.add(new int[]{at, at + n});
            } else {
                int end = gapped(text, piece, at, 0, 0, budget);
                if (end > 0) {
                    gapped.add(new int[]{at, end});
                }
            }
        }
        exact.addAll(gapped);
        return exact;
    }

    /**
     * Where the words {@code piece[from..]} end when they stand in {@code text} from {@code at} on in runs of at least
     * {@value #MIN_RUN_WORDS} words, with up to {@value #MAX_GAPS} gaps of 1 to {@value #MAX_GAP_WORDS} left-out
     * words between runs, in order; {@code -1} when they do not. A left-out word must not change what the quote says:
     * no number and no word of negation or restriction ({@link #MEANING_WORDS}). The model often leaves a few words
     * out without marking it (stock test of component 6 on 23.11.2026: five of seven quotes the check turned down);
     * a changed or added word still does not hold.
     */
    private static int gapped(String[] text, String[] piece, int at, int from, int gaps, int[] budget) {
        int n = 0;
        while (from + n < piece.length && at + n < text.length && text[at + n].equals(piece[from + n])) {
            n++;
        }
        if (n < MIN_RUN_WORDS && n < piece.length - from) {
            return -1;
        }
        if (from + n == piece.length) {
            return n >= Math.min(MIN_RUN_WORDS, piece.length) ? at + n : -1;
        }
        if (gaps == MAX_GAPS) {
            return -1;
        }
        // The run may also end earlier, so the next run can start on a word the passage repeats.
        for (int run = n; run >= MIN_RUN_WORDS; run--) {
            for (int gap = 1; gap <= MAX_GAP_WORDS && at + run + gap < text.length; gap++) {
                String skipped = text[at + run + gap - 1];
                if (MEANING_WORDS.contains(skipped) || skipped.matches(".*\\d.*")) {
                    break;
                }
                if (--budget[0] < 0) {
                    return -1;
                }
                if (text[at + run + gap].equals(piece[from + run])) {
                    int end = gapped(text, piece, at + run + gap, from + run, gaps + 1, budget);
                    if (end > 0) {
                        return end;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Whether every number {@code statement} names stands in {@code quote} too; a statement without a number always
     * holds. Thousands separators do not count ("1.500" holds "1500"), and the decimal sign may be a point or a comma
     * ("2,5" holds "2.5"). Only the digits count: a number written as a word is no number here.
     */
    public static boolean numbersHeld(String statement, String quote) {
        Set<String> wanted = numbers(statement);
        return wanted.isEmpty() || numbers(stripOuterQuotes(quote)).containsAll(wanted);
    }

    /** The numbers of a text in one form: thousands separators dropped, any other separator a point. */
    static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<String>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String[] groups = m.group().split("[.,\\u00A0\\u202F]");
            boolean thousands = groups.length > 1;
            for (int i = 1; i < groups.length; i++) {
                thousands &= groups[i].length() == 3;
            }
            out.add(String.join(thousands ? "" : ".", groups));
        }
        return out;
    }

    /**
     * The form in which quote and passage are compared: soft hyphens out, every dash a hyphen, list marks at a line
     * start out, a hyphen at a line end or inside a word out (the word reads the same with and without it),
     * quotation marks of one shape, blanks single, lower case. Every other sign stays, so "2,5" and "25" differ.
     */
    static String comparable(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("­", "").replaceAll("[‐‑‒–—−]", "-");
        text = LIST_MARK.matcher(text).replaceAll("");
        text = BROKEN_WORD.matcher(text).replaceAll("");
        text = WORD_HYPHEN.matcher(text).replaceAll("");
        text = text.replaceAll("[“”„‟«»‹›]", "\"").replaceAll("[‘’‚‛]", "'");
        return text.replaceAll("[\\s   ]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String unhyphenated(String value) {
        return LINE_END_HYPHEN.matcher(value.replace("­", "")).replaceAll("");
    }

    private static String stripOuterQuotes(String quote) {
        if (quote == null) {
            return "";
        }
        String text = quote.trim();
        while (text.length() >= 2 && isQuoteMark(text.charAt(0)) && isQuoteMark(text.charAt(text.length() - 1))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static boolean isQuoteMark(char c) {
        return "\"'“”„‟«»‘’‚".indexOf(c) >= 0;
    }
}
