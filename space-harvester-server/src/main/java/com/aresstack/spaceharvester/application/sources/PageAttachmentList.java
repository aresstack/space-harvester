package com.aresstack.spaceharvester.application.sources;

import java.time.Instant;
import java.util.Objects;

/**
 * Anhangsliste einer Seite für die API, live aus der Quelle, mit dem Space der Seite.
 *
 * @param sourceId    ID der Quelle
 * @param spaceKey    aktueller Space der Seite
 * @param attachments die Liste der Quelle
 * @param retrievedAt Zeitpunkt des Abrufs
 */
public record PageAttachmentList(String sourceId, String spaceKey, PageAttachments attachments, Instant retrievedAt) {

    public PageAttachmentList {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }
}
