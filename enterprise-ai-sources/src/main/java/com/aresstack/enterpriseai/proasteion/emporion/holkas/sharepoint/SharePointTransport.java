package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import java.io.IOException;
import java.net.URI;

/** Transport-Naht: ein GET mit optionalem Authorization-Header. Tests ersetzen ihn durch eine Fake-Site. */
interface SharePointTransport {

    Response get(URI uri, String accept, String authorization, long maxBytes) throws IOException;

    /** Status, Inhaltstyp und Rumpf einer Antwort. */
    final class Response {

        private final int status;
        private final String contentType;
        private final byte[] body;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body;
        }

        int status() {
            return status;
        }

        String contentType() {
            return contentType;
        }

        byte[] body() {
            return body;
        }
    }
}
