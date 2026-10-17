package com.aresstack.spaceharvester.application.assets;

/** Ein Anhang ist größer als die konfigurierte Grenze; er wird weder gekürzt noch abgelegt. */
public class AssetTooLargeException extends RuntimeException {

    private final String sourceId;
    private final long maxBytes;

    public AssetTooLargeException(String sourceId, long maxBytes) {
        super("Anhang über der Grenze von " + maxBytes + " Bytes");
        this.sourceId = sourceId;
        this.maxBytes = maxBytes;
    }

    public String sourceId() {
        return sourceId;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
