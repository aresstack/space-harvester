package com.aresstack.spaceharvester.application.convert;

import java.util.List;
import java.util.Optional;

/**
 * Die Bilder eines Exportdatenbestands für Converter: Einträge je Seite aus {@code assets.json} und ihre Bytes aus dem
 * Asset-Speicher. Ein Converter fordert nie bei der Quelle an; was nicht {@link ExportAssetManifest.Entry#stored()}
 * ist, meldet er als fehlend.
 */
public interface ExportAssets {

    /** Ohne beschaffte Bilder: keine Einträge. */
    ExportAssets NONE = new ExportAssets() {
        @Override
        public Optional<ExportAssetManifest> manifest() {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> content(ExportAssetManifest.Entry entry) {
            return Optional.empty();
        }
    };

    /** Das Manifest oder leer, solange keine Bilder beschafft wurden. */
    Optional<ExportAssetManifest> manifest();

    /** Die Einträge einer Seite; leer ohne Manifest. */
    default List<ExportAssetManifest.Entry> forPage(String pageId) {
        return manifest().map(manifest -> manifest.forPage(pageId)).orElse(List.of());
    }

    /**
     * Die abgelegten Bytes eines Eintrags, gegen seine {@code sha256} geprüft.
     *
     * @return leer, wenn er nicht abgelegt ist oder die Ablage ihn nicht (mehr) hat
     */
    Optional<byte[]> content(ExportAssetManifest.Entry entry);
}
