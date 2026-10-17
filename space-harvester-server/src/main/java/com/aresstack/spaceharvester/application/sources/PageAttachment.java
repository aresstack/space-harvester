package com.aresstack.spaceharvester.application.sources;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Metadaten eines Anhangs einer Seite, ohne Dateiinhalt. Fehlende Angaben bleiben {@code null}, nichts wird erfunden.
 *
 * @param attachmentId ID des Anhangs in der Quelle (bei Confluence {@code att<Ziffern>})
 * @param resourceId   quellübergreifend eindeutige Ressourcen-ID
 * @param title        Dateiname bzw. Titel
 * @param mediaType    Medientyp laut Quelle
 * @param fileSize     Größe in Bytes oder {@code null}, wenn die Quelle sie nicht nennt
 * @param version      Versionskennung oder {@code null}
 * @param modifiedAt   letzte Änderung oder {@code null}
 * @param url          Adresse in der Quelle oder {@code null}; keine Download-Freigabe (#14, #19)
 */
public record PageAttachment(String attachmentId, String resourceId, String title, String mediaType, Long fileSize,
                             String version, Instant modifiedAt, URI url) {

    public PageAttachment {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(resourceId, "resourceId");
        title = title == null ? "" : title;
    }
}
