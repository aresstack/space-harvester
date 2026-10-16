package com.aresstack.spaceharvester.application.sources;

import java.time.Instant;
import java.util.Objects;

/**
 * Die native Antwort der Quelle für eine Seite, Byte für Byte, mit den Kopfdaten aus genau dieser Antwort. Die Bytes
 * sind so schutzbedürftig wie die Seite selbst; {@link #toString()} zeigt sie nie.
 */
public final class StoredPage {

    private final PageMetadata metadata;
    private final byte[] payload;
    private final String mediaType;
    private final String nativeFormat;
    private final Instant retrievedAt;

    /**
     * @param metadata     Kopfdaten, gelesen aus {@code payload}
     * @param payload      HTTP-Body nach Transportdekodierung, ohne Header, Cookies oder Zugangsdaten
     * @param mediaType    Medientyp ohne Parameter, z. B. {@code application/json}
     * @param nativeFormat Format und Abrufform der Quelle, z. B. {@code confluence-rest-content+json;expand=…}
     * @param retrievedAt  Zeitpunkt der Antwort
     */
    public StoredPage(PageMetadata metadata, byte[] payload, String mediaType, String nativeFormat,
                      Instant retrievedAt) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.nativeFormat = Objects.requireNonNull(nativeFormat, "nativeFormat");
        this.retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
    }

    public PageMetadata metadata() {
        return metadata;
    }

    /** Eine Kopie der Bytes. */
    public byte[] payload() {
        return payload.clone();
    }

    public String mediaType() {
        return mediaType;
    }

    public String nativeFormat() {
        return nativeFormat;
    }

    public Instant retrievedAt() {
        return retrievedAt;
    }

    @Override
    public String toString() {
        return "StoredPage[pageId=" + metadata.pageId() + ", version=" + metadata.version() + ", " + payload.length
                + " bytes " + mediaType + "]";
    }
}
