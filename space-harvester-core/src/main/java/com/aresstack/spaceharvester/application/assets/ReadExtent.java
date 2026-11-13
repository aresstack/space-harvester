package com.aresstack.spaceharvester.application.assets;

/**
 * Wie weit gelesen wurde (ADR 0026, Punkt 7): gelesene Bytes des Originals und erzeugte Zeichen.
 *
 * @param bytes gelesene Bytes, ab 0
 * @param chars erzeugte Zeichen, ab 0
 */
public record ReadExtent(long bytes, long chars) {

    public ReadExtent {
        if (bytes < 0 || chars < 0) {
            throw new IllegalArgumentException("Umfang darf nicht negativ sein");
        }
    }
}
