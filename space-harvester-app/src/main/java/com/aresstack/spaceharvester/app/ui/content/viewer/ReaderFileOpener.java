package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

/**
 * Öffnet eine Datei mit dem Programm, das das System dafür vorsieht (Anhang-Overlay „Öffnen“).
 *
 * <p>Kopie von {@code ReaderFileOpener} aus dem Enterprise AI Client (exedra ui.reader); unverändert bis auf das Paket.</p>
 */
public interface ReaderFileOpener {

    void open(File file) throws IOException;

    /** {@link Desktop#open}; ohne Desktop-Unterstützung ein Fehler mit Grund. */
    static ReaderFileOpener system() {
        return file -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Das System kann Dateien hier nicht öffnen");
            }
            Desktop.getDesktop().open(file);
        };
    }
}
