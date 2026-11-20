package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.util.function.Consumer;

/**
 * Holt für das Anhang-Overlay die Originaldatei und, für Typen ohne eigene Darstellung (Office), eine Vorschau der
 * Quelle. Die Rückrufe kommen auf dem EDT; blockierende Arbeit erledigt der Controller abseits davon. Ein
 * Fehlschlag meldet seinen Grund an {@code failed}.
 *
 * <p>Kopie von {@code ReaderAttachmentLoader} aus dem Enterprise AI Client (exedra ui.reader); ohne die Varianten mit
 * Einmalcode-Abfrage ({@code ReaderSignIn}): Die App meldet Anmeldungen der Quelle über ihre eigene Anmeldeleiste.</p>
 */
public interface ReaderAttachmentLoader {

    void load(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed);

    /**
     * Ob {@link #downloadTo} die Datei direkt auf die Platte lädt, ohne sie ganz im Speicher zu halten: für Dateien,
     * die zu groß für Cache und Anzeige sind. Standard: nein, dann laden Speichern und Öffnen über {@link #load}.
     */
    default boolean downloadsToFile() {
        return false;
    }

    /**
     * Lädt die Datei nach {@code target} (abseits des EDT); {@code done} bzw. {@code failed} kommen auf dem EDT.
     * Standard: über {@link #load} und dann geschrieben.
     */
    default void downloadTo(ReaderAttachment attachment, java.nio.file.Path target, Runnable done,
                            Consumer<String> failed) {
        load(attachment, bytes -> {
            try {
                java.nio.file.Files.write(target, bytes);
                done.run();
            } catch (java.io.IOException e) {
                failed.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }, failed);
    }

    /** Eine Vorschau der Quelle (PDF oder Bild); ohne Vorschauweg ein Hinweis. */
    default void preview(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
        failed.accept("Die Quelle bietet keine Vorschau an.");
    }
}
