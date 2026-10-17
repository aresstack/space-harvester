package com.aresstack.spaceharvester.application.assets;

/** Die Asset-Ablage kann nicht lesen oder schreiben, oder ein abgelegter Inhalt passt nicht mehr zu seinem Hash. */
public class AssetStoreException extends RuntimeException {

    public AssetStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
