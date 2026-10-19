package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import java.io.IOException;

/**
 * Paketinterne HTTP-Grenze zur {@code api.php}. Produktiv {@link UrlConnectionMediaWikiTransport}; Tests
 * ersetzen sie durch einen Fake, um den Adapter ohne Netz zu prüfen.
 */
interface MediaWikiTransport {

    /** GET {@code api.php?query}. */
    Response get(String query) throws IOException;

    /** POST {@code api.php} mit {@code application/x-www-form-urlencoded; charset=UTF-8}. */
    Response postForm(String formBody) throws IOException;

    /** Verwirft die Sitzung (Cookies), z. B. vor einem neuen Login. */
    void resetSession();

    /** HTTP-Status und Antwortkörper (UTF-8). */
    final class Response {

        private final int status;
        private final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        int status() {
            return status;
        }

        String body() {
            return body;
        }
    }
}
