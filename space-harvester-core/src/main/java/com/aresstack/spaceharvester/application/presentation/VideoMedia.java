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
     * @param cancelled ob der Auftrag abgebrochen oder angehalten wurde; dann endet die Transkription bald
     * @return das ganze Transkript im Format der Ablage ({@link TranscriptSections})
     * @throws Exception wenn das Video oder das Sprachmodell nicht lesbar ist
     */
    String transcribe(String existing, Consumer<String> partial, BooleanSupplier cancelled) throws Exception;

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
        public String transcribe(String existing, Consumer<String> partial, BooleanSupplier cancelled) {
            return existing;
        }

        @Override
        public void frames(List<Long> millis, BiConsumer<Long, byte[]> found, BooleanSupplier cancelled) {
            // nichts
        }
    };
}
