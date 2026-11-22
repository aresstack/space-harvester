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
 * @param picturesPending ob noch ein Bild aussteht, das ein Lauf zeichnen kann (dann gilt der Podcast als unfertig)
 */
public record Podcast(String key, String title, List<Part> parts, boolean running, boolean picturesPending) {

    public Podcast {
        Objects.requireNonNull(key, "key");
        parts = List.copyOf(parts);
    }

    public Podcast(String key, String title, List<Part> parts, boolean running) {
        this(key, title, parts, running, false);
    }

    /** Ob alle Abschnitte ihr Audio haben und kein Bild mehr aussteht (sonst setzt ein Lauf die Bilder fort). */
    public boolean complete() {
        return !parts.isEmpty() && parts.stream().allMatch(part -> part.audio() != null) && !picturesPending;
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
     * @param picture      das Bild des Themas, {@code null}, solange keins da ist (oder keins passt)
     * @param lines        die Beiträge des Abschnitts in Sprechreihenfolge (für Untertitel); leer, solange er nicht
     *                     geschrieben ist
     */
    public record Part(int topic, int segment, String topicTitle, String segmentTitle, Path audio, Picture picture,
                       List<Line> lines) {

        public Part {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public Part(int topic, int segment, String topicTitle, String segmentTitle, Path audio, Picture picture) {
            this(topic, segment, topicTitle, segmentTitle, audio, picture, List.of());
        }

        public Part(int topic, int segment, String topicTitle, String segmentTitle, Path audio) {
            this(topic, segment, topicTitle, segmentTitle, audio, null, List.of());
        }

        /** Ob der Abschnitt ein Thema beginnt (dort steht eine Marke). */
        public boolean startsTopic() {
            return segment <= 0;
        }
    }

    /**
     * Das Bild eines Themas, das der Inhaltsbereich zeigt, während das Thema läuft.
     *
     * @param file      PNG, JPEG, GIF oder SVG
     * @param mediaType etwa {@code image/png} oder {@code image/svg+xml}
     * @param caption   Bildunterschrift, kann leer sein
     */
    public record Picture(Path file, String mediaType, String caption) {
        public Picture {
            Objects.requireNonNull(file, "file");
            caption = caption == null ? "" : caption;
        }

        /** Ob es ein gezeichnetes Schaubild ist (SVG). */
        public boolean vector() {
            return "image/svg+xml".equals(mediaType);
        }
    }

    /**
     * Ein Beitrag eines Abschnitts, wie er gesprochen wird.
     *
     * @param host        ob der Gastgeber spricht (sonst der Co-Host)
     * @param text        der gesprochene Text
     * @param startMillis wo der Beitrag im Audio des Abschnitts beginnt, gemessen beim Vertonen; {@code -1}, wenn
     *                    unbekannt (Audio aus der Zeit davor oder noch nicht gesprochen)
     */
    public record Line(boolean host, String text, long startMillis) {
        public Line {
            text = text == null ? "" : text.strip();
            startMillis = startMillis < 0 ? -1 : startMillis;
        }

        public Line(boolean host, String text) {
            this(host, text, -1);
        }
    }
}
