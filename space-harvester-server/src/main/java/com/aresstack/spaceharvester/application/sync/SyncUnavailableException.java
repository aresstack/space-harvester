package com.aresstack.spaceharvester.application.sync;

/** Synchronisierung ist nicht möglich, weil Cache-Speicher oder Inhaltsabruf (noch) nicht angebunden sind. */
public class SyncUnavailableException extends RuntimeException {

    public SyncUnavailableException(String message) {
        super(message);
    }
}
