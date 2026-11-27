package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a book length from free text ("20–30 Seiten", "20 bis 30 Seiten", "zwischen 20 und 30 Seiten", "ca. 25
 * Seiten", "8.000 Wörter", "20-30 pages") and writes it back in the one form later phases read ("20-30 pages", "8000
 * words").
 * <p>
 * Repair plan step 1 (2026-11-21): the scoping grammar cannot carry a length, so in four stored workspaces the
 * deliverable stayed empty although the mission named "20 bis 30 Seiten"; and the outline's length reader knew only
 * the ASCII hyphen, so "20–30 Seiten" read as 30 pages. This reader is the one place both now use. It understands
 * the hyphen, en and em dash, "bis", "to", "zwischen … und …", approximation words in front ("ca.", "etwa", "rund",
 * "about") and thousands separators ("8.000", "8,000", "8 000"). A decimal number ("2,5 Seiten") is no length, nor
 * is a page reference ("Seite 3"), an abbreviation ("30 s.") or a length per part ("je 5 Seiten", "2 Seiten pro
 * Kapitel").
 * <p>
 * When the text names several different lengths, the reader does not guess: it takes the one introduced by "Umfang",
 * "insgesamt", "Länge", "length" or "total", and none when no such word picks exactly one. {@link #candidates} lists
 * them all so the concept tab can show them. Stateless.
 */
public final class DeliverableLengthText {

    private static final String NUMBER = "(\\d{1,3}(?:[.,\\u00a0\\u202f ]\\d{3})+|\\d+)(?![.,]\\d)";
    private static final String RANGE = "\\s*(-|\\u2013|\\u2014|bis(?:\\s+zu)?|to|until|und|and)\\s*";
    private static final String UNIT = "(normseiten|druckseiten|seiten|pages?|w(?:\\u00f6|oe|o)rter|worte|words?)";
    private static final Pattern LENGTH = Pattern.compile(
            "(?<![\\w.,])(?:(zwischen|between)\\s+)?" + NUMBER + "(?:" + RANGE + NUMBER + ")?"
                    + "\\s*(?:din[- ]?a4[- ]?)?" + UNIT + "(?!\\w)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A length for one part, not for the book: "je 5 Seiten", "jeweils 3 Seiten". */
    private static final Pattern PER_PART_BEFORE = Pattern.compile(
            "(?:\\bje|\\bjeweils|\\bpro|\\beach|\\bper)\\s+(?:\\p{L}+\\s+)?"
                    + "(?:(?:ca\\.|etwa|rund|about|around)\\s+)?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** "2 Seiten je Kapitel", "3 pages per chapter". */
    private static final Pattern PER_PART_AFTER = Pattern.compile("^\\s*(?:je(?!\\s+nach)|pro|jeweils|per|each)\\s+\\p{L}",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** The word that says a length is the whole book's, in the same clause before it. */
    private static final Pattern WHOLE_BEFORE = Pattern.compile(
            "(?:umfang|insgesamt|gesamt\\w*|l(?:\\u00e4|ae)nge|length|total|overall)\\b[^.;,\\n]{0,30}$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DeliverableLengthText() {
    }

    /**
     * The book length {@code text} names, as a deliverable with the standard synthesis policy; {@code null} when it
     * names none, or several different ones of which no "Umfang"/"insgesamt"/"Länge" picks exactly one. A reversed
     * range is kept as the user's intent ({@link ResearchDeliverable} orders it).
     */
    public static ResearchDeliverable read(String text) {
        List<Found> found = find(text);
        Set<String> distinct = new LinkedHashSet<>();
        for (Found f : found) {
            distinct.add(describe(f.deliverable));
        }
        if (distinct.isEmpty()) {
            return null;
        }
        if (distinct.size() == 1) {
            return found.get(0).deliverable;
        }
        Found whole = null;
        Set<String> wholeDistinct = new LinkedHashSet<>();
        for (Found f : found) {
            if (f.whole) {
                wholeDistinct.add(describe(f.deliverable));
                whole = whole == null ? f : whole;
            }
        }
        return wholeDistinct.size() == 1 ? whole.deliverable : null;
    }

    /**
     * Every different book length {@code text} names, in the order it names them, in the form of
     * {@link #describe}; per-part lengths are left out. Empty when it names none.
     */
    public static List<String> candidates(String text) {
        Set<String> distinct = new LinkedHashSet<>();
        for (Found f : find(text)) {
            distinct.add(describe(f.deliverable));
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }

    /** {@code "20-30 pages"}, {@code "25 pages"} or {@code "8000 words"}; {@code ""} without a length. */
    public static String describe(ResearchDeliverable deliverable) {
        if (deliverable == null || !deliverable.hasTargetLength()) {
            return "";
        }
        String unit = deliverable.getLengthUnit().name().toLowerCase(Locale.ROOT);
        int min = deliverable.getTargetLengthMin();
        int max = deliverable.getTargetLengthMax();
        if (min > 0 && max > 0 && min != max) {
            return min + "-" + max + " " + unit;
        }
        return Math.max(min, max) + " " + unit;
    }

    /**
     * The deliverable later phases get: the stated one when it has a length, else the length the mission names
     * (when {@code fromMission} is on), else {@code ""}.
     */
    public static String effective(ResearchDeliverable stated, String mission, boolean fromMission) {
        String described = describe(stated);
        if (!described.isEmpty() || !fromMission) {
            return described;
        }
        return describe(read(mission));
    }

    private static List<Found> find(String text) {
        List<Found> found = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        Matcher matcher = LENGTH.matcher(text);
        while (matcher.find()) {
            boolean between = matcher.group(1) != null;
            String separator = matcher.group(3);
            boolean and = separator != null && separator.trim().matches("(?i)und|and");
            int min;
            int max;
            if (and && !between) {
                // "3 und 30 Seiten" without "zwischen" is no range; only the number before the unit is a length.
                min = number(matcher.group(4));
                max = min;
            } else {
                min = number(matcher.group(2));
                max = matcher.group(4) == null ? min : number(matcher.group(4));
            }
            if (min <= 0 || max <= 0) {
                continue;
            }
            String before = text.substring(Math.max(0, matcher.start() - 60), matcher.start());
            String after = text.substring(matcher.end(), Math.min(text.length(), matcher.end() + 20));
            // The per-part check reads "ca." with its dot ("je Kapitel ca. 5 Seiten"), before the dot goes.
            if (PER_PART_BEFORE.matcher(before).find() || PER_PART_AFTER.matcher(after).find()) {
                continue;
            }
            // "ca." and "z. B." end no clause: their dot must not cut "Umfang" off its length.
            before = before.replaceAll("(?i)\\b(ca|bzw|z\\.\\s?b|approx)\\.", "$1");
            String unit = matcher.group(5).toLowerCase(Locale.ROOT);
            ResearchDeliverable.LengthUnit lengthUnit = unit.startsWith("w")
                    ? ResearchDeliverable.LengthUnit.WORDS : ResearchDeliverable.LengthUnit.PAGES;
            found.add(new Found(new ResearchDeliverable(min, max, lengthUnit, SynthesisPolicy.defaults()),
                    WHOLE_BEFORE.matcher(before).find()));
        }
        return found;
    }

    private static int number(String digits) {
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c >= '0' && c <= '9') {
                plain.append(c);
            }
        }
        if (plain.length() == 0 || plain.length() > 9) {
            return 0;
        }
        return Integer.parseInt(plain.toString());
    }

    private static final class Found {
        final ResearchDeliverable deliverable;
        final boolean whole;

        Found(ResearchDeliverable deliverable, boolean whole) {
            this.deliverable = deliverable;
            this.whole = whole;
        }
    }
}
