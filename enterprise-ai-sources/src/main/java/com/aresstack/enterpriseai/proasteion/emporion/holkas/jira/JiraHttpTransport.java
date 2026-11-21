package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * HTTP-Naht des Jira-Adapters: ein einzelner GET. Produktiv {@link UrlConnectionJiraTransport}; in Tests ein Fake.
 *
 * <p>Proxy, TLS-Client-Zertifikat (mTLS) und Timeouts sind Sache der Implementierung und werden in der Composition
 * Root festgelegt; der Adapter kennt nur diese Schnittstelle. Header können ein {@code Authorization} enthalten;
 * Implementierungen loggen Header nie. Als Wissensquelle schreibt der Adapter nie; nur {@link JiraIssueOperations}
 * (Jira-Werkzeuge, jeder Aufruf nach ausdrücklicher Freigabe des Benutzers) schickt {@link #post}.
 */
public interface JiraHttpTransport {

    /**
     * @param uri      absolute URI
     * @param headers  Request-Header (unveränderlich)
     * @param maxBytes Obergrenze für den Antwortkörper; längere Antworten werden mit
     *                 {@link JiraResponseTooLargeException} abgelehnt
     * @return die Antwort; Fehlerstatus (4xx/5xx) sind normale Antworten, keine Ausnahme
     * @throws IOException bei Verbindungs-, Timeout- oder Größenfehlern
     */
    JiraHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) throws IOException;

    /**
     * Ein POST mit JSON-Körper, nur für die schreibenden Jira-Werkzeuge ({@link JiraIssueOperations}); ohne
     * Weiterleitung und ohne Wiederholung. Vorgabe: kein Schreiben.
     *
     * @param body JSON in UTF-8
     * @throws IOException bei Verbindungs-, Timeout- oder Größenfehlern, und wenn der Transport nicht schreibt
     */
    default JiraHttpResponse post(URI uri, Map<String, String> headers, byte[] body, int maxBytes) throws IOException {
        throw new IOException("dieser Transport schreibt nicht");
    }
}
