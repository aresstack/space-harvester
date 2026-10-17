package com.aresstack.spaceharvester.nlp.syntax;

import java.util.Objects;

/**
 * Woher ein Text stammt: Quelle, Dokument (Seiten-ID) und Version. Teil jeder Erwähnungs- und Aussage-ID, damit
 * Fundstellen über Versionen hinweg unterscheidbar bleiben.
 *
 * @param sourceId   Quelle, z. B. {@code confluence-prod}
 * @param documentId Dokument, z. B. die Seiten-ID
 * @param version    Version des Dokuments oder {@code null}
 */
public record DocumentRef(String sourceId, String documentId, String version) {

    public DocumentRef {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(documentId, "documentId");
    }

    /** {@code quelle/dokument@version}, ohne Version {@code quelle/dokument}. */
    public String key() {
        return sourceId + "/" + documentId + (version == null ? "" : "@" + version);
    }
}
