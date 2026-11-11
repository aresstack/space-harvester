package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Optional;

/**
 * Bilder und Anhänge, die ein Import ({@link ExportPackageImport}) zu einer Seite mitgebracht hat: die Einträge ihres
 * Bildmanifests zur importierten Version, die Dateien selbst liegen in der Asset-Ablage. Ist die Quelle nicht
 * erreichbar, nimmt ein neuer Export Bilder und Anhänge daraus ({@link ExportAssetCollection}), statt anzuhalten.
 */
public interface ImportedAssets {

    /** Legt die Einträge der Seite zu dieser Version ab und ersetzt frühere. */
    void save(PageKey page, String version, ExportAssetManifest assets);

    /** Die Einträge der Seite, nur wenn sie zu genau dieser Version importiert wurden. */
    Optional<ExportAssetManifest> find(PageKey page, String version);
}
