package com.aresstack.enterpriseai.proasteion.platform.network;

import java.net.URI;

/**
 * Entscheidet je Ziel-URL, wie eine HTTP-Verbindung aufgebaut wird.
 *
 * <p>Implementierungen dürfen blockieren (PAC-Skript laden und auswerten, PowerShell fragen) und werden deshalb
 * nie auf dem Swing-EDT, sondern nur im Verbindungsaufbau der Adapter aufgerufen. Sie cachen typischerweise je
 * Host. Der Aufrufer darf das Ergebnis nicht "reparieren": {@link HttpRoute.Kind#UNAVAILABLE} bedeutet, dass die
 * Verbindung nicht aufgebaut werden darf.
 */
public interface HttpRoutePort {

    /**
     * @param target die vollständige Ziel-URL (Schema, Host, Port, Pfad); nie {@code null}
     * @return die Route, nie {@code null}
     */
    HttpRoute routeFor(URI target);

    /** Immer direkt, ohne Proxy. Für Tests und Umgebungen ohne Proxy. */
    static HttpRoutePort direct() {
        return new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(URI target) {
                return HttpRoute.direct("direct");
            }

            @Override
            public String toString() {
                return "HttpRoutePort[direct]";
            }
        };
    }
}
