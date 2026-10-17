package com.aresstack.spaceharvester.application.sources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Deterministischer Fingerprint des Seitenbestands eines Space ({@code spaceFingerprint}), Schema {@value #SCHEMA};
 * fasst die Fingerprints aller Seiten zusammen, ohne {@link MetadataHash} oder {@link AttributesHash} umzudefinieren.
 *
 * <p>Eingabe ist ein kanonisches JSON-Objekt
 * {@code {"schema":…,"sourceId":…,"spaceKey":…,"pages":[{"pageId":…,"metadataHash":…,"attributesHash":…},…]}} mit
 * den Seiten nach {@code pageId} sortiert ({@link String#compareTo}); jede Seite höchstens einmal. Escaping, UTF-8 und
 * {@code sha256:<hex>} wie bei {@link MetadataHash}.
 *
 * <p>Der Fingerprint ist nur über einen vollständig und widerspruchsfrei gelisteten Bestand aussagekräftig; aus einem
 * abgebrochenen Lauf darf keiner entstehen (siehe {@code application.sync}). Gleiche Fingerprints sparen den
 * Seitenabgleich, nicht das Listen der Metadaten bei der Quelle.
 */
public final class SpaceFingerprint {

    public static final String SCHEMA = "space-inventory/v1";

    private SpaceFingerprint() {
    }

    /** Fingerprints einer Seite. */
    public record Page(String pageId, String metadataHash, String attributesHash) {

        public Page {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(metadataHash, "metadataHash");
            Objects.requireNonNull(attributesHash, "attributesHash");
        }
    }

    /** @throws IllegalArgumentException wenn eine {@code pageId} mehrfach vorkommt */
    public static String of(String sourceId, String spaceKey, Collection<Page> pages) {
        List<Page> sorted = new ArrayList<>(pages);
        sorted.sort(Comparator.comparing(Page::pageId));
        StringBuilder json = new StringBuilder("{");
        field(json, "schema", SCHEMA);
        json.append(',');
        field(json, "sourceId", sourceId);
        json.append(',');
        field(json, "spaceKey", spaceKey);
        json.append(",\"pages\":[");
        for (int i = 0; i < sorted.size(); i++) {
            Page page = sorted.get(i);
            if (i > 0) {
                if (sorted.get(i - 1).pageId().equals(page.pageId())) {
                    throw new IllegalArgumentException("Seite mehrfach im Bestand: " + page.pageId());
                }
                json.append(',');
            }
            json.append('{');
            field(json, "pageId", page.pageId());
            json.append(',');
            field(json, "metadataHash", page.metadataHash());
            json.append(',');
            field(json, "attributesHash", page.attributesHash());
            json.append('}');
        }
        json.append("]}");
        return "sha256:" + HexFormat.of().formatHex(MetadataHash.sha256(json.toString()));
    }

    private static void field(StringBuilder json, String key, String value) {
        MetadataHash.string(json, key);
        json.append(':');
        if (value == null) {
            json.append("null");
        } else {
            MetadataHash.string(json, value);
        }
    }
}
