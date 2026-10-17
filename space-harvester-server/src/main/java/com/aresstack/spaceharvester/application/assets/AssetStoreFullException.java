package com.aresstack.spaceharvester.application.assets;

/** Die Ablage hat ihre Gesamtgrenze erreicht; der Anhang wird nicht abgelegt und nichts wird verdrängt. */
public class AssetStoreFullException extends AssetStoreException {

    private final long maxTotalBytes;

    public AssetStoreFullException(long maxTotalBytes) {
        super("Asset-Ablage voll (Grenze " + maxTotalBytes + " Bytes)", null);
        this.maxTotalBytes = maxTotalBytes;
    }

    public long maxTotalBytes() {
        return maxTotalBytes;
    }
}
