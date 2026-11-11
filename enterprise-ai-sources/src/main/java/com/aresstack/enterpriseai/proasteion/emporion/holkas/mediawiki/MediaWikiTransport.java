package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import java.io.IOException;

/**
 * HTTP-Naht des MediaWiki-Adapters zur {@code api.php} einer Site. Standard ist der Transport über
 * {@code HttpURLConnection} (Proxy-Route und Vertrauensquellen der Composition Root); eine Anwendung kann über
 * {@link MediaWikiSourceProvider#withTransports} einen eigenen einsetzen, Tests einen Fake ohne Netz.
 *
 * <p>Vertrag für Implementierungen: Ein Transport gehört zu genau einer Site ({@link MediaWikiSiteConfig}) und hält
 * deren Sitzung (Cookies) selbst, nie prozessweit; Login und folgende Leseaufrufe müssen dieselben Cookies sehen.
 * Weiterleitungen folgt der Transport selbst, ohne die Cookies zu verlieren. Fehlerstatus (4xx/5xx) sind normale
 * Antworten, keine Ausnahme; der Adapter übersetzt sie (401/403 = Zugriff verweigert, dann genau ein neuer Login).
 * Die Aufrufe sind blockierend und kommen aus beliebigen Threads; ein asynchroner HTTP-Client wartet in der
 * Implementierung auf seine Antwort. Cookies, Tokens, Formularkörper und Antwortkörper werden nie geloggt.
 */
public interface MediaWikiTransport {

    /**
     * GET {@code api.php?query}.
     *
     * @param query fertig kodierter Query-String ohne {@code ?}
     * @throws IOException bei Verbindungs- oder Timeoutfehlern (der Adapter meldet dann {@code UNAVAILABLE})
     */
    Response get(String query) throws IOException;

    /**
     * POST {@code api.php} mit {@code application/x-www-form-urlencoded; charset=UTF-8} (Login).
     *
     * @param formBody fertig kodierter Formularkörper; enthält Zugangsdaten und wird nie geloggt
     * @throws IOException bei Verbindungs- oder Timeoutfehlern
     */
    Response postForm(String formBody) throws IOException;

    /** Verwirft die Sitzung (Cookies), z. B. vor einem neuen Login. */
    void resetSession();

    /** HTTP-Status und Antwortkörper (UTF-8). */
    final class Response {

        private final int status;
        private final String body;

        /**
         * @param status HTTP-Status
         * @param body   Antwortkörper als Text; {@code null} = leer
         */
        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }
}
