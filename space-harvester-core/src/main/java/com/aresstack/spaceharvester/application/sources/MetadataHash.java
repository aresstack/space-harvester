package com.aresstack.spaceharvester.application.sources;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministischer Fingerprint der Verwaltungsmetadaten einer Seite ({@code metadataHash}), Schema
 * {@value #SCHEMA}.
 *
 * <p>Eingabe ist ein kanonisches JSON-Objekt mit genau diesen Feldern in dieser Reihenfolge: {@code schema},
 * {@code sourceId}, {@code pageId}, {@code spaceKey}, {@code title}, {@code parentPageId}, {@code version},
 * {@code modifiedAt}. Unbekannte Werte sind {@code null}; Zeiten stehen in UTC als ISO-8601 ({@link
 * java.time.Instant#toString()}). Zeichenketten werden unverändert übernommen (keine Unicode-Normalisierung) und nur
 * {@code "}, {@code \} und Steuerzeichen unter U+0020 escaped; das Ergebnis wird als UTF-8 mit SHA-256 gehasht und als
 * {@code sha256:<hex>} ausgegeben. Abrufzeit, URL und weitere Attribute gehören nicht dazu.
 *
 * <p>Ein neuer Hash zeigt eine Änderung dieser Metadaten an (Version, Titel, Elternseite, Space), beweist aber keine
 * Änderung des Inhalts; dafür gibt es später {@code rawSha256} über die gespeicherten Rohdaten.
 */
public final class MetadataHash {

    public static final String SCHEMA = "page-metadata/v1";

    private MetadataHash() {
    }

    public static String of(String sourceId, PageMetadata page) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schema", SCHEMA);
        fields.put("sourceId", sourceId);
        fields.put("pageId", page.pageId());
        fields.put("spaceKey", page.partitionKey());
        fields.put("title", page.title());
        fields.put("parentPageId", page.parentPageId());
        fields.put("version", page.version());
        fields.put("modifiedAt", page.modifiedAt() == null ? null : page.modifiedAt().toString());
        return "sha256:" + HexFormat.of().formatHex(sha256(canonicalJson(fields)));
    }

    static String canonicalJson(Map<String, String> fields) {
        StringBuilder json = new StringBuilder("{");
        fields.forEach((key, value) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            string(json, key);
            json.append(':');
            if (value == null) {
                json.append("null");
            } else {
                string(json, value);
            }
        });
        return json.append('}').toString();
    }

    static void string(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < 0x20) {
                json.append(String.format("\\u%04x", (int) c));
            } else {
                json.append(c);
            }
        }
        json.append('"');
    }

    static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
