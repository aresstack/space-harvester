package com.aresstack.spaceharvester.application.extraction;

import java.util.Objects;

/**
 * Ein bestimmter Stand eines Dokuments, dem ein Analyseergebnis gehört.
 *
 * @param origin  woher das Dokument kommt, etwa {@code eakte}; trennt gleiche Kennungen verschiedener Ablagen
 * @param id      stabile Kennung in dieser Ablage, bei der E-Akte das WebDAV-{@code href}; nie aus dem Namen gebaut
 * @param version Versionskennung, bei WebDAV das ETag; leer, wenn unbekannt. Ein anderes ETag ist ein anderes
 *                Dokument: Ergebnisse eines alten Stands erscheinen nie beim neuen
 */
public record DocumentRef(String origin, String id, String version) {

    public DocumentRef {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id ist leer");
        }
        version = version == null ? "" : version;
    }
}
