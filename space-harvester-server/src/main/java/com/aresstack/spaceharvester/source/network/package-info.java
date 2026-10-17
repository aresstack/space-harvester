/**
 * Proxy-Route je Ziel-URL für alle Quellenadapter (#34): {@link com.aresstack.spaceharvester.source.network.ProxyRoutes}
 * setzt den Upstream-Port {@code HttpRoutePort} mit der Bibliothek {@code com.aresstack:win-proxy-java} um (PAC-Adresse
 * per PowerShell, Windows-Einstellungen oder VBScript, feste PAC-Adresse, fester Proxy, Windows-Proxyeinstellungen,
 * keiner). Die Adapter geben die Route jeder Verbindung einzeln mit; es gibt keinen prozessweiten
 * {@code ProxySelector} und keine {@code https.proxyHost}-Systemeigenschaften. Eine nicht bestimmbare Route führt zu
 * einem sichtbaren Verbindungsfehler, nie still zu einer Direktverbindung.
 */
package com.aresstack.spaceharvester.source.network;
