package com.aresstack.spaceharvester.application.sources;

import java.util.HexFormat;
import java.util.Map;

/**
 * Deterministischer Fingerprint der weiteren Quellattribute einer Seite ({@code attributesHash}), Schema
 * {@value #SCHEMA}; getrennt von {@link MetadataHash}, dessen Schema {@code page-metadata/v1} unverändert bleibt.
 *
 * <p>Eingabe ist ein kanonisches JSON-Objekt {@code {"schema":…,"sourceId":…,"pageId":…,"attributes":{…}}}. Die
 * Attribute sind alle, die die Quelle beim Inventarisieren je Seite liefert (bei Confluence etwa
 * {@code confluence.labels}, {@code confluence.space}, {@code confluence.type}), nach Schlüssel sortiert
 * ({@link String#compareTo}); ein fehlendes Attribut fehlt auch im Objekt. Werte sind Zeichenketten; Listen wie die
 * Labels liefert der Quellenadapter bereits sortiert und ohne Duplikate, kommagetrennt. Escaping, UTF-8 und
 * {@code sha256:<hex>} wie bei {@link MetadataHash}.
 *
 * <p>Eine reine Label- oder Attributänderung ändert nur diesen Hash, nicht den {@code metadataHash}; sie verlangt
 * einen Abgleich der Metadaten, nicht automatisch einen erneuten Abruf des Inhalts.
 */
public final class AttributesHash {

    public static final String SCHEMA = "page-attributes/v1";

    private AttributesHash() {
    }

    public static String of(String sourceId, PageMetadata page) {
        StringBuilder json = new StringBuilder("{");
        field(json, "schema", SCHEMA);
        json.append(',');
        field(json, "sourceId", sourceId);
        json.append(',');
        field(json, "pageId", page.pageId());
        json.append(",\"attributes\":{");
        boolean first = true;
        for (Map.Entry<String, String> attribute : page.attributes().entrySet()) {
            if (!first) {
                json.append(',');
            }
            field(json, attribute.getKey(), attribute.getValue());
            first = false;
        }
        json.append("}}");
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
