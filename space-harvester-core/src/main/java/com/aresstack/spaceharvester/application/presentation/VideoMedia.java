package com.aresstack.spaceharvester.application.presentation;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Was nur die App mit dem Video selbst kann, für „In PPTX umwandeln“: die Tonspur lokal transkribieren (Whisper) und
 * Standbilder der Marken ziehen (libVLC). Der Core rechnet beides nie selbst; die App reicht diese Schnittstelle beim
 * Start der Aufträge herein. Alle Aufrufe laufen im Auftrag, nie auf dem EDT.
 */
public interface VideoMedia {

    /**
     * Vervollständigt das Transkript: setzt hinter dem letzten Abschnitt von {@code existing} fort, oder beginnt
     * vorn, wenn es keines gibt. Ein schon vollständiges Transkript kommt unverändert zurück.
     *
     * @param existing  das abgelegte Transkript oder {@code null}
     * @param partial   bekommt nach jedem neuen Abschnitt den ganzen bisherigen Text, damit er sofort abgelegt wird
     * @param progress  bekommt die Länge des Videos und die Zeit, bis zu der das Transkript reicht
     * @param cancelled ob der Auftrag abgebrochen oder angehalten wurde; dann endet die Transkription bald
     * @return das ganze Transkript im Format der Ablage ({@link TranscriptSections})
     * @throws Exception wenn das Video oder das Sprachmodell nicht lesbar ist
     */
    String transcribe(String existing, Consumer<String> partial, Progress progress, BooleanSupplier cancelled)
            throws Exception;

    /**
     * Das Whisper-Modell, mit dem die App ein neues Transkript erzeugt, etwa {@code tiny}; leer, wenn sie keines
     * nennt. Gibt es schon ein Original, setzt der Core dessen Modell über {@link #withWhisper} fort.
     */
    default String whisper() {
        return "";
    }

    /** Dieselben Medien, aber mit dem Whisper-Modell {@code whisper} (Kennung wie {@link #whisper()}). */
    default VideoMedia withWhisper(String whisper) {
        return this;
    }

    /**
     * Die Folienwechsel des Videos in Millisekunden, aus seinem Index (Hintergrundart „Folienwechsel erkennen“); leer,
     * wenn das Video keinen lesbaren Index oder keine Folien hat.
     *
     * @throws Exception wenn das Video nicht lesbar ist
     */
    default List<Long> slideChanges() throws Exception {
        return List.of();
    }

    /** Wie weit die Transkription ist, gemessen an der Zeit im Video. */
    interface Progress {

        /** Die Länge des Videos; einmal, sobald sie bekannt ist. */
        void duration(long millis);

        /** Bis hierhin reicht das Transkript jetzt, auch das schon vorhandene. */
        void reached(long millis);

        /** Meldet nichts. */
        Progress NONE = new Progress() {
            @Override
            public void duration(long millis) {
            }

            @Override
            public void reached(long millis) {
            }
        };
    }

    /**
     * Zieht die Standbilder dieser Marken als PNG.
     *
     * @param found bekommt je gezogenem Standbild seine Position und die PNG-Bytes
     * @throws Exception wenn das Video nicht lesbar ist
     */
    void frames(List<Long> millis, BiConsumer<Long, byte[]> found, BooleanSupplier cancelled) throws Exception;

    /** Ohne Video: kein Transkript und keine Standbilder über das Abgelegte hinaus. */
    VideoMedia NONE = new VideoMedia() {
        @Override
        public String transcribe(String existing, Consumer<String> partial, Progress progress,
                                 BooleanSupplier cancelled) {
            return existing;
        }

        @Override
        public void frames(List<Long> millis, BiConsumer<Long, byte[]> found, BooleanSupplier cancelled) {
            // nichts
        }
    };
}
