# ADR 0001 – Ausgehender HTTP-Transport (Proxy/PAC, mTLS, asynchrone I/O)

- **Status:** vorgeschlagen (Teil 1 umgesetzt, Entscheidung zu WebClient offen)
- **Tickets:** [#34](https://github.com/aresstack/space-harvester/issues/34), [#33](https://github.com/aresstack/space-harvester/issues/33), [#2](https://github.com/aresstack/space-harvester/issues/2)

## Kontext

Im Unternehmensnetz braucht jede Confluence-Verbindung die vom PAC-Skript gewählte Route (#34) und ein
TLS-Clientzertifikat (#33). Der übernommene Confluence-Adapter (`enterprise-ai-sources`, unveränderte Kopie) hat
dafür bereits Ports: `HttpRoutePort` wird **je Ziel-URL** gefragt und in `URL.openConnection(Proxy)` übersetzt;
UNAVAILABLE führt zu einer `IOException`. TLS kommt als `SSLSocketFactory` von außen. Der Transport
(`UrlConnectionConfluenceTransport` hinter `ConfluenceHttpTransport`) ist synchron und liefert die rohen
Antwort-Bytes mit Größengrenze, Redirect-Host-Bindung und Fehlerzuordnung.

#34 wünscht zusätzlich einen Spring-`WebClient` mit echter nichtblockierender I/O.

## Entscheidung (Teil 1)

1. Die Proxy-Route kommt aus `source.network.ProxyRoutes` (Spring-konfiguriert, `win-proxy-java`) und wird dem
   **bestehenden** Transport über `HttpRoutePort` übergeben. Kein zweiter Confluence-Client, keine Änderung der Kopie.
2. mTLS (#33) bleibt unabhängig davon über die vorhandene `SSLSocketFactory`/`ClientCertificates`-Schnittstelle.
   Damit funktionieren Route und Zertifikat zusammen, auch über `CONNECT`.
3. Ein `WebClient`-Transport wird **nicht** in diesem Schritt gebaut.

## Offene Entscheidung: WebClient / asynchrone I/O

Ein `WebClient`-Adapter hinter dem synchronen `ConfluenceHttpTransport` müsste `block()` aufrufen und wäre damit
nicht asynchron. Echte nichtblockierende I/O braucht eine **asynchrone Transport-SPI upstream** (z. B.
`CompletionStage<ConfluenceHttpResponse> getAsync(...)`) und einen Adapter, der vom Reactor-Netty-Event-Loop aus
weder PAC-Auflösung noch PowerShell blockierend aufruft (Route vorher auf eigenem Scheduler auflösen).

Kriterien für die Entscheidung, nachzuweisen mit Integrationstests gegen den bisherigen Transport:

- Verhaltensparität: Header, Basic/PAT, Redirect-Host-Bindung (keine Credentials/Zertifikate an fremde Hosts),
  Cookies/SSO, Byte-Limit, Fehlerzuordnung, bytegenaue Rohpayload.
- Route je Ziel-URL: Reactor-Netty-`ProxyProvider` je Verbindung bzw. getrennte Connection-Pools je Route, ohne dass
  ein Pool die Route einer anderen Quelle/URL wiederverwendet.
- mTLS mit Windows-MY (eigener `KeyManager`) über Netty-`SslContext`, auch durch `CONNECT`.
- Messbarer Nutzen: Der Harvester läuft auf virtuellen Threads (`spring.threads.virtual.enabled`); blockierende
  `HttpURLConnection`-Aufrufe binden dort keine Plattform-Threads. Ein asynchroner Transport lohnt sich erst, wenn
  Lasttests mit parallelen Space-Ernten (#9/#16) einen Engpass zeigen.
- Alternative mit kleinerem Eingriff: `java.net.http.HttpClient.sendAsync` (JDK, kein Netty), Proxy über
  `ProxySelector` je Client und `SSLContext` je Quelle.

## Folgen

- Produktiver PAC-Pfad ist ohne Transportumbau nutzbar; das gemeinsame Gate mTLS + PAC/CONNECT (#33/#34) hängt
  nicht am WebClient.
- Der asynchrone Transport ist eine Upstream-Änderung in `aresstack/enterprise-ai-client` mit anschließender
  Synchronisierung der Kopie (#2).
