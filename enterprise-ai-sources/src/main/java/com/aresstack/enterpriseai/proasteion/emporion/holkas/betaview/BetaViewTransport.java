package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Transport-Naht: ein HTTP-Austausch ohne automatische Weiterleitung. Cookies, Weiterleitungen und CSRF-Token
 * verwaltet die {@link BetaViewSession}; Tests ersetzen den Transport durch einen Fake-Server.
 */
interface BetaViewTransport {

    /**
     * @param formBody URL-kodierter Formularrumpf für POST, {@code null} für GET
     */
    Response exchange(URI uri, String cookieHeader, String formBody, long maxBytes) throws IOException;

    /** Status, Header und Rumpf einer Antwort. */
    final class Response {

        private final int status;
        private final Map<String, List<String>> headers;
        private final byte[] body;

        Response(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status;
            this.headers = headers == null ? Collections.<String, List<String>>emptyMap() : headers;
            this.body = body == null ? new byte[0] : body;
        }

        int status() {
            return status;
        }

        /** Header wie {@link java.net.URLConnection#getHeaderFields()} (für den Cookie-Speicher). */
        Map<String, List<String>> headers() {
            return headers;
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        byte[] body() {
            return body;
        }
    }
}
