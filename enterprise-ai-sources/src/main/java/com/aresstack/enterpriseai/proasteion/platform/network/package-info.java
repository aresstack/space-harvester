/**
 * Port für die HTTP-Route je Ziel-URL: {@link com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort} entscheidet
 * für eine Ziel-URL, ob die Verbindung direkt, über einen Proxy oder gar nicht aufgebaut wird
 * ({@link com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute}). Die Adapter (Chat, Embeddings, MediaWiki, Confluence)
 * fragen den Port vor jeder {@code HttpURLConnection} und übergeben die Entscheidung explizit an
 * {@code URL.openConnection(Proxy)}; einen prozessweiten {@code ProxySelector} gibt es nicht.
 *
 * <p>Der Port ist neutral: keine {@code java.net.Proxy}-, Socket- oder TLS-Typen, damit er zum Kern zählt.
 * Die einzige Implementierung lebt in der Composition Root ({@code app.net.HttpRoutes}) und benutzt dort
 * {@code com.aresstack:win-proxy-java}; eine Route, die sich nicht bestimmen lässt (Fehler, nicht implementierter
 * Modus), ist {@link com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute.Kind#UNAVAILABLE} und führt beim Adapter zu
 * einem sichtbaren Verbindungsfehler, nie still zu einer Direktverbindung.
 */
package com.aresstack.enterpriseai.proasteion.platform.network;
