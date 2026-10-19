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

    /** Eine Vorschau der Quelle (PDF oder Bild); ohne Vorschauweg ein Hinweis. */
    default void preview(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
        failed.accept("Die Quelle bietet keine Vorschau an.");
    }
}
