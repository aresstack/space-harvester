package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Antwort eines {@link JiraHttpTransport}: Status, Content-Type und Körper. */
public final class JiraHttpResponse {

    private final int status;
    private final String contentType;
    private final byte[] body;

    public JiraHttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType == null ? "" : contentType;
        this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    public int length() {
        return body.length;
    }

    /** Körper als UTF-8 (Jira liefert JSON immer in UTF-8). */
    public String bodyAsUtf8() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /** Ohne Körper: Antworten können Vorgangsinhalte enthalten. */
    @Override
    public String toString() {
        return "JiraHttpResponse[" + status + ", " + contentType + ", " + body.length + " Bytes]";
    }
}
