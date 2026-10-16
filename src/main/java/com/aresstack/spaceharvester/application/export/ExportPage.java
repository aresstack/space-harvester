package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * Eine Seite aus dem Cache, wie sie exportiert wird: Kopfdaten und native Payload aus derselben Antwort der Quelle.
 * Entspricht dem nativen Seiten-Snapshot aus Strang B; der Leser bildet ihn eins zu eins ab.
 *
 * @param sourceId       Quelle
 * @param metadata       Kopfdaten
 * @param metadataHash   {@code metadataHash} der Kopfdaten
 * @param attributesHash {@code attributesHash} der Kopfdaten
 * @param payload        Antwort der Quelle Byte für Byte; ohne Header oder Zugangsdaten
 * @param mediaType      Medientyp der Payload, z. B. {@code application/json}
 * @param nativeFormat   Format und Abrufform der Quelle
 * @param retrievedAt    Zeitpunkt der Antwort der Quelle
 */
public record ExportPage(String sourceId, PageMetadata metadata, String metadataHash, String attributesHash,
                         byte[] payload, String mediaType, String nativeFormat, Instant retrievedAt) {

    public ExportPage {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(metadataHash, "metadataHash");
        Objects.requireNonNull(attributesHash, "attributesHash");
        payload = Objects.requireNonNull(payload, "payload").clone();
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(nativeFormat, "nativeFormat");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }

    public String pageId() {
        return metadata.pageId();
    }

    /** Eine Kopie der Bytes. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Nie der Inhalt: die Payload ist schutzbedürftig wie die Seite selbst. */
    @Override
    public String toString() {
        return "ExportPage[" + sourceId + "/" + metadata.pageId() + ", " + payload.length + " bytes]";
    }
}
