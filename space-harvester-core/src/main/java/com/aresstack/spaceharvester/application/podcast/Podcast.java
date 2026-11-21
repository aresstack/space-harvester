package com.aresstack.spaceharvester.application.podcast;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Der Stand eines Podcasts, wie er gerade auf der Platte liegt: der Titel und je Abschnitt in Sendereihenfolge, ob sein
 * Audio fertig ist. Themen, deren Abschnitte noch nicht geplant sind, stehen mit einem Platzhalter (Abschnitt
 * {@code -1}) in der Liste, damit die Reihenfolge von Anfang an stimmt.
 *
 * @param key     der Schlüssel aus der Zusammenfassung
 * @param title   Titel der Folge; {@code null}, solange die Grobübersicht fehlt
 * @param parts   die Abschnitte in Sendereihenfolge; leer, solange die Grobübersicht fehlt
 * @param running ob gerade ein Auftrag daran arbeitet
 */
public record Podcast(String key, String title, List<Part> parts, boolean running) {

    public Podcast {
        Objects.requireNonNull(key, "key");
        parts = List.copyOf(parts);
    }

    /** Ob alle Abschnitte ihr Audio haben. */
    public boolean complete() {
        return !parts.isEmpty() && parts.stream().allMatch(part -> part.audio() != null);
    }

    /** Ob mindestens ein Abschnitt hörbar ist. */
    public boolean playable() {
        return parts.stream().anyMatch(part -> part.audio() != null);
    }

    /**
     * Ein Abschnitt.
     *
     * @param topic        Index des Themas
     * @param segment      Index des Abschnitts im Thema, {@code -1} als Platzhalter eines noch nicht geplanten Themas
     * @param topicTitle   Titel des Themas (für die gelben Marken der Zeitleiste)
     * @param segmentTitle Titel des Abschnitts, {@code null} beim Platzhalter
     * @param audio        die WAV-Datei, {@code null}, solange sie fehlt
     */
    public record Part(int topic, int segment, String topicTitle, String segmentTitle, Path audio) {

        /** Ob der Abschnitt ein Thema beginnt (dort steht eine Marke). */
        public boolean startsTopic() {
            return segment <= 0;
        }
    }
}
