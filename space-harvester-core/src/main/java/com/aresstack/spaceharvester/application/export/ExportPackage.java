package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein geöffnetes JSON-Paket {@code export.zip} ab {@code json-package/3} (Port für den Import): Space, Seiten mit ihren
 * Rohdaten Byte für Byte, Lese-Ansichten und die mitgebrachten Bilder und Anhänge. Die Umsetzung liest einzeln aus dem
 * Paket und hält keine Seiteninhalte im Speicher; jede Payload ist gegen ihren {@code rawSha256} geprüft.
 */
public interface ExportPackage extends AutoCloseable {

    /** Quelle, aus der das Paket stammt. */
    String sourceId();

    String spaceKey();

    /** Name des Space laut Paket, sonst der Schlüssel. */
    String spaceName();

    /** Startseite eines Teilbaum- oder Einzelseiten-Exports, {@code null} für den ganzen Space. */
    String rootPageId();

    /** Die Seiten in Baumreihenfolge, ohne Payload. */
    List<Page> pages();

    /**
     * Der Snapshot einer Seite, wie das Paket ihn mitbringt: Kopfdaten aus {@code pages.json}, Payload aus
     * {@code raw/}, geprüft gegen {@code rawSha256}.
     *
     * @throws ExportPackageException wenn die Seite fehlt oder ihre Rohdaten nicht passen
     */
    PageSnapshot snapshot(String pageId);

    /** Die mitgebrachte Lese-Ansicht einer Seite, leer ohne. */
    Optional<ExportReadingView> readingView(String pageId);

    /** Bildmanifest des Datenbestands und die Dateien dazu aus dem Paket; ohne {@link ExportAssets#NONE}. */
    ExportAssets assets();

    @Override
    void close();

    /**
     * Eine Seite des Pakets ohne Payload.
     *
     * @param metadata       Kopfdaten wie beim Export
     * @param metadataHash   Hash der Kopfdaten für die Quelle des Pakets
     * @param attributesHash Hash der Attribute für die Quelle des Pakets
     * @param depth          Tiefe im Baum des Exports
     * @param retrievedAt    wann die Quelle die Payload geliefert hat
     */
    record Page(PageMetadata metadata, String metadataHash, String attributesHash, int depth, Instant retrievedAt) {

        public Page {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(metadataHash, "metadataHash");
            Objects.requireNonNull(attributesHash, "attributesHash");
            Objects.requireNonNull(retrievedAt, "retrievedAt");
        }

        public String pageId() {
            return metadata.pageId();
        }
    }

    /** Öffnet ein Paket aus einer Datei. */
    @FunctionalInterface
    interface Reader {

        /**
         * @throws ExportPackageException wenn die Datei kein lesbares Paket ab {@code json-package/3} ist
         */
        ExportPackage open(Path file);
    }
}
