package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SpaceFingerprint;
import java.util.Objects;

/**
 * Stand einer Seite in einer bestätigten Generation: das, womit die nächste Inventur verglichen wird.
 *
 * @param pageId         ID der Seite in der Quelle
 * @param spaceKey       Space der Seite
 * @param parentPageId   ID der Elternseite oder {@code null} für eine Wurzelseite
 * @param version        Versionskennung der Quelle oder {@code null}, wenn unbekannt
 * @param metadataHash   {@code page-metadata/v1}
 * @param attributesHash {@code page-attributes/v1}
 */
public record BaselineEntry(String pageId, String spaceKey, String parentPageId, String version, String metadataHash,
                            String attributesHash) {

    public BaselineEntry {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(metadataHash, "metadataHash");
        Objects.requireNonNull(attributesHash, "attributesHash");
    }

    public static BaselineEntry of(PageMetadataListing.Entry entry) {
        return new BaselineEntry(entry.page().pageId(), entry.page().partitionKey(), entry.page().parentPageId(),
                entry.page().version(), entry.metadataHash(), entry.attributesHash());
    }

    SpaceFingerprint.Page fingerprint() {
        return new SpaceFingerprint.Page(pageId, metadataHash, attributesHash);
    }
}
