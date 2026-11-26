package com.aresstack.spaceharvester.app.speech;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Live mitschreiben ohne das 30-Sekunden-Problem (Angelo 26.11.2026): Whisper sieht immer 30 Sekunden; ein wachsendes
 * Fenster darüber hinaus wird abgeschnitten, und naiv aneinandergehängte, überlappende Fenster wiederholen Text. Daher:
 *
 * <ol>
 *   <li>Gleitendes Fenster: erkannt wird nur der noch nicht übernommene Ton ab {@link #start()}, höchstens
 *       {@link LiveDictationSettings.Tuning#windowSeconds()} (immer unter 30 Sekunden).</li>
 *   <li>Übereinstimmung (wie „LocalAgreement“): Ein Segment wird erst übernommen, wenn seine Wörter in zwei
 *       aufeinanderfolgenden Erkennungen gleich lauten. Danach beginnt das Fenster am Ende dieses Segments; der
 *       übernommene Ton wird nie wieder erkannt.</li>
 *   <li>Deduplizierung: Wiederholt der Anfang einer neuen Erkennung das Ende des übernommenen Texts (Zeitstempel etwas
 *       zu früh), fällt die Überlappung weg.</li>
 *   <li>Ist das Fenster voll, ohne dass zwei Erkennungen übereinstimmen, werden alle Segmente bis auf das letzte
 *       übernommen, damit das Fenster nie 30 Sekunden erreicht.</li>
 * </ol>
 *
 * Alle Positionen sind Abtastwerte der Aufnahme (16 kHz). Reine Logik, ohne Modell: die Erkennung liefert der Aufrufer.
 */
final class LiveTranscript {

    static final int RATE = 16000;

    /** Ein erkanntes Segment mit Anfang und Ende in Abtastwerten der Aufnahme. */
    record Segment(long start, long end, String text) {
    }

    /** Der nächste zu erkennende Ausschnitt; {@code full}, wenn er die eingestellte Fensterlänge erreicht. */
    record Window(long start, long end, boolean full) {
    }

    private final LiveDictationSettings.Tuning tuning;
    private final List<String> committed = new ArrayList<>();
    private List<String> previous = List.of();
    private long start;
    private long decodedEnd = -1;

    LiveTranscript(long start, LiveDictationSettings.Tuning tuning) {
        this.start = Math.max(0, start);
        this.tuning = tuning;
    }

    /** Ab hier ist der Ton noch nicht übernommen. */
    long start() {
        return start;
    }

    /** Der nächste Ausschnitt bei {@code available} aufgenommenen Abtastwerten, oder {@code null}, wenn es nichts Neues gibt. */
    Window next(long available) {
        long end = Math.min(available, start + windowSamples());
        if (end - start < firstSamples() || end <= decodedEnd) {
            return null;
        }
        return new Window(start, end, end - start >= windowSamples());
    }

    /** Übernimmt die Erkennung des Ausschnitts {@code window}. */
    void accept(Window window, List<Segment> hypothesis) {
        decodedEnd = window.end();
        List<Segment> segments = withoutOverlap(hypothesis);
        List<String> words = words(segments);
        int agreed = commonPrefix(previous, words);
        int commit = 0;
        int counted = 0;
        for (Segment segment : segments) {
            int count = split(segment.text()).size();
            if (counted + count > agreed) {
                break;
            }
            counted += count;
            commit++;
        }
        if (commit == 0 && window.full()) {
            commit = segments.size() > 1 ? segments.size() - 1 : segments.size();
        }
        if (commit > 0) {
            for (Segment segment : segments.subList(0, commit)) {
                committed.addAll(split(segment.text()));
            }
            start = Math.max(start, Math.min(window.end(), segments.get(commit - 1).end()));
        } else if (window.full() && segments.isEmpty()) {
            start = Math.max(start, window.end() - firstSamples()); // nur Stille: das Ende bleibt für ein Wort im Anlauf
        }
        previous = words(segments.subList(commit, segments.size()));
    }

    /** Nach dem Stopp: die Erkennung des Rests ab {@link #start()} übernehmen; liefert den ganzen Text. */
    String finish(List<Segment> tail) {
        committed.addAll(words(withoutOverlap(tail)));
        previous = List.of();
        return committed();
    }

    /** Der übernommene Text; er ändert sich nicht mehr. */
    String committed() {
        return String.join(" ", committed);
    }

    /** Der vorläufige Rest der letzten Erkennung; die nächste Erkennung kann ihn noch ändern. */
    String provisional() {
        return String.join(" ", previous);
    }

    /** Lässt am Anfang weg, was das Ende des übernommenen Texts schon enthält. */
    private List<Segment> withoutOverlap(List<Segment> hypothesis) {
        List<String> words = words(hypothesis);
        int overlap = 0;
        for (int k = Math.min(committed.size(), words.size()); k > 0; k--) {
            if (commonPrefix(committed.subList(committed.size() - k, committed.size()), words.subList(0, k)) == k) {
                overlap = k;
                break;
            }
        }
        List<Segment> result = new ArrayList<>();
        int skip = overlap;
        for (Segment segment : hypothesis) {
            List<String> segmentWords = split(segment.text());
            if (skip >= segmentWords.size()) {
                skip -= segmentWords.size();
                if (!segmentWords.isEmpty()) {
                    continue;
                }
            }
            List<String> kept = segmentWords.subList(Math.min(skip, segmentWords.size()), segmentWords.size());
            skip = 0;
            if (!kept.isEmpty()) {
                result.add(new Segment(segment.start(), segment.end(), String.join(" ", kept)));
            }
        }
        return result;
    }

    private long windowSamples() {
        return (long) tuning.windowSeconds() * RATE;
    }

    private long firstSamples() {
        return (long) tuning.firstMillis() * RATE / 1000;
    }

    private static List<String> words(List<Segment> segments) {
        List<String> result = new ArrayList<>();
        for (Segment segment : segments) {
            result.addAll(split(segment.text()));
        }
        return result;
    }

    private static List<String> split(String text) {
        String value = text == null ? "" : text.strip();
        return value.isEmpty() ? List.of() : Arrays.asList(value.split("\\s+"));
    }

    /** Wie viele Wörter am Anfang gleich lauten, ohne Groß-/Kleinschreibung und Satzzeichen. */
    private static int commonPrefix(List<String> left, List<String> right) {
        int length = Math.min(left.size(), right.size());
        int index = 0;
        while (index < length && normalized(left.get(index)).equals(normalized(right.get(index)))) {
            index++;
        }
        return index;
    }

    private static String normalized(String word) {
        return word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
