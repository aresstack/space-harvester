package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import java.io.IOException;
import java.net.URI;

/** Transport-Naht: ein GET mit optionalem Authorization-Header. Tests ersetzen ihn durch eine Fake-Site. */
interface SharePointTransport {

    Response get(URI uri, String accept, String authorization, long maxBytes) throws IOException;

    /** Execute a read-only change-log request. */
    default Response post(URI uri, String authorization, String digest, byte[] body, long maxBytes) throws IOException {
        throw new IOException("Change log transport is unavailable");
    }

    /** Stream successful responses to a temporary file; do not retain the body in memory. */
    Response download(URI uri, String authorization, java.nio.file.Path target, long maxBytes) throws IOException;

    /** Status, Inhaltstyp und Rumpf einer Antwort. */
    final class Response {

        private final int status;
        private final String contentType;
        private final byte[] body;
        private final long serverDateMillis;

        Response(int status, String contentType, byte[] body) {
            this(status, contentType, body, 0);
        }

        Response(int status, String contentType, byte[] body, long serverDateMillis) {
            this.status = status;
            this.serverDateMillis = serverDateMillis;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body;
        }

        int status() {
            return status;
        }

        String contentType() {
            return contentType;
        }

        long serverDateMillis() {
            return serverDateMillis;
        }

        byte[] body() {
            return body;
        }
    }
}
