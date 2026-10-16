package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import java.io.IOException;

/**
 * Ein {@link ConfluenceHttpTransport} hat den Antwortkörper verworfen, weil er größer als die erlaubte Obergrenze
 * ist. Eigene Art, damit der Adapter „zu groß“ von „nicht erreichbar“ unterscheiden kann.
 */
public final class ConfluenceResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int maxBytes;

    public ConfluenceResponseTooLargeException(int maxBytes) {
        super("Antwort größer als " + maxBytes + " Bytes");
        this.maxBytes = maxBytes;
    }

    /** Die überschrittene Obergrenze in Bytes. */
    public int maxBytes() {
        return maxBytes;
    }
}
