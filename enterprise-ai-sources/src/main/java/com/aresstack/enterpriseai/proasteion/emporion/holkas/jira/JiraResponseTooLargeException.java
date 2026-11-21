package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import java.io.IOException;

/** Der Antwortkörper überschritt die Grenze des Aufrufers ({@link JiraHttpTransport#get}). */
public final class JiraResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int maxBytes;

    public JiraResponseTooLargeException(int maxBytes) {
        super("Antwort größer als " + maxBytes + " Bytes");
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }
}
