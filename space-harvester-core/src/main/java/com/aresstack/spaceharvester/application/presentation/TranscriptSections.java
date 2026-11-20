package com.aresstack.spaceharvester.application.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Abschnitte eines Video-Transkripts, wie die App es ablegt: je Whisper-Fenster eine Zeile
 * {@code [00:01:13 – 00:01:40] Text} (ältere Transkripte nur mit Anfang {@code [00:01:13] Text}), getrennt durch
 * Leerzeilen. Text vor dem ersten Abschnitt gehört zu einem Abschnitt ab 0.
 */
public final class TranscriptSections {

    /** Zwischen Anfang und Ende eines Abschnitts. */
    public static final String RANGE_SEPARATOR = " – ";

    private static final Pattern SECTION = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})(?:\\s*[–-]\\s*"
            + "(\\d{2}):(\\d{2}):(\\d{2}))?\\]\\s?", Pattern.MULTILINE);

    /**
     * Ein Abschnitt.
     *
     * @param startMillis Anfang im Video
     * @param endMillis   Ende im Video; ohne bekanntes Ende der Anfang des nächsten Abschnitts (beim letzten sein
     *                    Anfang)
     * @param text        erkannter Text ohne Zeitangabe
     */
    public record Section(long startMillis, long endMillis, String text) {

        public Section {
            Objects.requireNonNull(text, "text");
            if (startMillis < 0 || endMillis < startMillis) {
                throw new IllegalArgumentException("Ungültiger Abschnitt");
            }
        }

        /** Die Zeile wie im abgelegten Transkript. */
        public String line() {
            String range = endMillis > startMillis ? clock(startMillis) + RANGE_SEPARATOR + clock(endMillis)
                    : clock(startMillis);
            return "[" + range + "] " + text;
        }
    }

    private TranscriptSections() {
    }

    /** Die Abschnitte des Texts in ihrer Reihenfolge; leere fallen weg. */
    public static List<Section> parse(String transcript) {
        List<Section> sections = new ArrayList<>();
        if (transcript == null || transcript.isBlank()) {
            return sections;
        }
        Matcher matcher = SECTION.matcher(transcript);
        List<long[]> heads = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        while (matcher.find()) {
            long start = millis(matcher, 1);
            long end = matcher.group(4) == null ? -1 : millis(matcher, 4);
            heads.add(new long[] {start, end});
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        if (heads.isEmpty()) {
            sections.add(new Section(0, 0, transcript.strip()));
            return sections;
        }
        String before = transcript.substring(0, spans.get(0)[0]).strip();
        if (!before.isEmpty()) {
            sections.add(new Section(0, heads.get(0)[0], before));
        }
        for (int i = 0; i < heads.size(); i++) {
            int from = spans.get(i)[1];
            int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : transcript.length();
            String text = transcript.substring(from, to).strip();
            long start = heads.get(i)[0];
            long end = heads.get(i)[1];
            if (end < start) {
                end = i + 1 < heads.size() ? Math.max(start, heads.get(i + 1)[0]) : start;
            }
            if (!text.isEmpty()) {
                sections.add(new Section(start, end, text));
            }
        }
        return sections;
    }

    /** Das Transkript aus Abschnitten im Format der Ablage. */
    public static String render(List<Section> sections) {
        StringBuilder text = new StringBuilder();
        for (Section section : sections) {
            text.append(section.line()).append("\n\n");
        }
        return text.toString();
    }

    /**
     * Die Abschnitte, die in die Zeit {@code [from, to)} fallen: jeder, der sich mit ihr überschneidet; ohne bekanntes
     * Ende zählt sein Anfang. {@code to} {@link Long#MAX_VALUE} bis zum Ende.
     */
    public static List<Section> between(List<Section> sections, long from, long to) {
        List<Section> found = new ArrayList<>();
        for (Section section : sections) {
            boolean overlaps = section.endMillis() > section.startMillis()
                    ? section.startMillis() < to && section.endMillis() > from
                    : section.startMillis() >= from && section.startMillis() < to;
            if (overlaps) {
                found.add(section);
            }
        }
        return found;
    }

    /** Teilt die Abschnitte in Pakete von höchstens etwa {@code maxChars} Zeichen; ein langer Abschnitt bleibt allein. */
    public static List<List<Section>> chunks(List<Section> sections, int maxChars) {
        List<List<Section>> chunks = new ArrayList<>();
        List<Section> current = new ArrayList<>();
        int length = 0;
        for (Section section : sections) {
            int size = section.line().length() + 2;
            if (!current.isEmpty() && length + size > maxChars) {
                chunks.add(current);
                current = new ArrayList<>();
                length = 0;
            }
            current.add(section);
            length += size;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    /** {@code hh:mm:ss}. */
    public static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private static long millis(Matcher matcher, int first) {
        return (Long.parseLong(matcher.group(first)) * 3600 + Long.parseLong(matcher.group(first + 1)) * 60
                + Long.parseLong(matcher.group(first + 2))) * 1000;
    }
}
