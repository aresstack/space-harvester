package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.sources.PageAttachment;
import java.time.Instant;
import java.util.List;

/**
 * Die Anhänge einer Seite, live gelesen, mit ihrem abgelegten Stand (#19).
 *
 * @param listComplete {@code false}, wenn die Anhangsliste der Quelle Einträge auslassen musste (#9)
 */
public record PageAssets(String sourceId, String pageId, String spaceKey, boolean listComplete, List<Entry> entries,
                         Instant retrievedAt) {

    public PageAssets {
        entries = List.copyOf(entries);
    }

    /**
     * @param previewOf {@code attachmentId} des draw.io-Diagramms, dessen Vorschaubild dieser Anhang ist, sonst
     *                  {@code null}; Verbraucher nehmen dann die Diagrammdaten statt des Bildes
     * @param stored    die abgelegte Datei in der aktuellen Version oder {@code null}
     */
    public record Entry(PageAttachment attachment, AssetKind kind, String previewOf, Asset stored) {
    }
}
