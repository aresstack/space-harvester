package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * HTTP-Naht des Confluence-Adapters: ein einzelner GET. Produktiv {@link UrlConnectionConfluenceTransport};
 * in Tests ein Fake oder ein lokaler HTTP-Server.
 *
 * <p>Proxy, TLS-Client-Zertifikat (mTLS) und Timeouts sind Sache der Implementierung und werden in der
 * Composition Root festgelegt; der Adapter selbst kennt nur diese Schnittstelle. Header können ein
 * {@code Authorization} enthalten; Implementierungen loggen Header nie.
 */
public interface ConfluenceHttpTransport {

    /**
     * @param uri      absolute URI
     * @param headers  Request-Header (unveränderlich)
     * @param maxBytes Obergrenze für den Antwortkörper; längere Antworten werden mit
     *                 {@link ConfluenceResponseTooLargeException} abgelehnt
     * @return die Antwort; Fehlerstatus (4xx/5xx) sind normale Antworten, keine Ausnahme
     * @throws IOException bei Verbindungs-, Timeout- oder Größenfehlern
     */
    ConfluenceHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) throws IOException;

    /**
     * Wie {@link #get}, aber ohne Client-Zertifikat: für Ziele außerhalb der eingestellten Confluence (Weiterleitung
     * auf einen anderen Host). Fremde Hosts bekommen weder {@code Authorization} noch Cookie noch Zertifikat.
     * Implementierungen mit mTLS müssen diese Methode überschreiben; der Standard ist nur für Transporte ohne
     * Zertifikat (Tests).
     */
    /**
     * Sendet ein Formular ({@code application/x-www-form-urlencoded}) wie ein Browser: Weiterleitungen auf demselben
     * Host werden verfolgt (301/302/303 als GET, 307/308 erneut als POST), Cookies, die unterwegs gesetzt werden,
     * gehen bei den folgenden Schritten mit. Der Körper enthält Formularwerte (etwa einen Einmalcode) und wird nie
     * geloggt. Standard: nicht unterstützt (Test-Transporte ohne Formulare).
     */
    default ConfluenceHttpResponse postForm(URI uri, Map<String, String> headers, byte[] form, int maxBytes)
            throws IOException {
        throw new IOException("Formular senden wird von diesem Transport nicht unterstützt");
    }

    default ConfluenceHttpResponse getAnonymous(URI uri, Map<String, String> headers, int maxBytes)
            throws IOException {
        return get(uri, headers, maxBytes);
    }
}
