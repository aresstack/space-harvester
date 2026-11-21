package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * HTTP-Naht des Jira-Adapters: ein einzelner GET. Produktiv {@link UrlConnectionJiraTransport}; in Tests ein Fake.
 *
 * <p>Proxy, TLS-Client-Zertifikat (mTLS) und Timeouts sind Sache der Implementierung und werden in der Composition
 * Root festgelegt; der Adapter kennt nur diese Schnittstelle. Header können ein {@code Authorization} enthalten;
 * Implementierungen loggen Header nie. Der Adapter schreibt nie (kein POST, PUT oder DELETE).
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
}
