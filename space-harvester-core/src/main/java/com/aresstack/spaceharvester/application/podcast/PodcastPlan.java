package com.aresstack.spaceharvester.application.podcast;

import java.util.List;
import java.util.Objects;

/**
 * Der Plan eines Podcasts, in Stufen wie im Audio-Studio (aresstack/audio, Plan vor den Abschnitten): erst die
 * Grobübersicht mit den Themen und ihrer Energie, dann je Thema die Abschnitte mit den geplanten Sprecherwechseln, zuletzt
 * je Abschnitt der gesprochene Text. Jede Stufe wird abgelegt, sobald sie da ist; ein neuer Lauf plant nur, was fehlt.
 *
 * @param title   Titel der Folge
 * @param topics  die Themen in Sendereihenfolge, das erste ist der Einstieg, das letzte der Abschluss
 * @param repairs was an der Antwort des Modells repariert oder ersetzt wurde, eine Zeile je Reparatur
 */
public record PodcastPlan(String title, List<Topic> topics, List<String> repairs) {

    /** Höchstens so viele Themen; ein längerer Plan wird gekürzt (Reparatur). */
    public static final int MAX_TOPICS = 8;
    /** Höchstens so viele Abschnitte je Thema. */
    public static final int MAX_SEGMENTS = 4;
    /** Höchstens so viele Sprecherwechsel je Abschnitt. */
    public static final int MAX_BEATS = 8;

    public PodcastPlan {
        title = title == null || title.isBlank() ? "Was sich geändert hat" : title.strip();
        topics = List.copyOf(topics);
        repairs = List.copyOf(repairs);
    }

    /** Wer spricht: Gastgeber (führt durch die Folge) oder Co-Host (fragt nach, ordnet ein). */
    public enum Speaker {
        HOST, COHOST;

        /** Liest „host“/„cohost“ (auch A/B); Unbekanntes ergibt {@code fallback}. */
        static Speaker parse(String value, Speaker fallback) {
            if (value == null) {
                return fallback;
            }
            String text = value.strip().toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
            return switch (text) {
                case "host", "a", "gastgeber", "gastgeberin" -> HOST;
                case "cohost", "b", "cohostin" -> COHOST;
                default -> fallback;
            };
        }

        Speaker other() {
            return this == HOST ? COHOST : HOST;
        }
    }

    /**
     * Ein Thema der Grobübersicht.
     *
     * @param title  wie die Sendung das Thema nennt
     * @param angle  worum es geht, in einem Satz
     * @param energy Energie 0 (ruhig, einordnend) bis 1 (lebhaft), wie die Intensität im Audio-Studio
     * @param points die Kernpunkte aus der Zusammenfassung, die das Thema tragen
     */
    public record Topic(String title, String angle, double energy, List<String> points) {
        public Topic {
            title = title == null || title.isBlank() ? "Thema" : title.strip();
            angle = angle == null ? "" : angle.strip();
            energy = Double.isFinite(energy) ? Math.max(0, Math.min(1, energy)) : 0.5;
            points = List.copyOf(points);
        }
    }

    /**
     * Ein Abschnitt eines Themas mit seinen Sprecherwechseln.
     *
     * @param title Thema des Abschnitts
     * @param beats die geplanten Wortbeiträge in Reihenfolge
     */
    public record Segment(String title, List<Beat> beats) {
        public Segment {
            title = title == null || title.isBlank() ? "Abschnitt" : title.strip();
            beats = List.copyOf(beats);
        }
    }

    /** Ein geplanter Wortbeitrag: wer spricht und mit welcher Absicht (etwa „fragt, was das für Teams bedeutet“). */
    public record Beat(Speaker speaker, String intent) {
        public Beat {
            Objects.requireNonNull(speaker, "speaker");
            intent = intent == null ? "" : intent.strip();
        }
    }

    /** Ein gesprochener Beitrag des fertigen Textes. */
    public record Turn(Speaker speaker, String text) {
        public Turn {
            Objects.requireNonNull(speaker, "speaker");
            text = text == null ? "" : text.strip();
        }
    }
}
