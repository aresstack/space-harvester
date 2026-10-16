# ADR 0001 – Ausgehender HTTP-Transport (Proxy/PAC, mTLS, asynchrone I/O)

- **Status:** Teil 1 umgesetzt; asynchroner Client ist gesetzt (Angelo, 2026-10-16), offen ist nur die Umsetzung
- **Tickets:** [#34](https://github.com/aresstack/space-harvester/issues/34), [#33](https://github.com/aresstack/space-harvester/issues/33), [#2](https://github.com/aresstack/space-harvester/issues/2)

## Kontext

Im Unternehmensnetz braucht jede Confluence-Verbindung die vom PAC-Skript gewählte Route (#34) und ein
TLS-Clientzertifikat (#33). Der übernommene Confluence-Adapter (`enterprise-ai-sources`, unveränderte Kopie) hat
dafür bereits Ports: `HttpRoutePort` wird **je Ziel-URL** gefragt und in `URL.openConnection(Proxy)` übersetzt;
UNAVAILABLE führt zu einer `IOException`. TLS kommt als `SSLSocketFactory` von außen. Der Transport
(`UrlConnectionConfluenceTransport` hinter `ConfluenceHttpTransport`) ist synchron und liefert die rohen
Antwort-Bytes mit Größengrenze, Redirect-Host-Bindung und Fehlerzuordnung.

Vorgabe (Angelo, 2026-10-16): ausgehende Quellabrufe laufen über einen **asynchronen** HTTP-Client mit echter
nichtblockierender I/O. Ein synchroner Client (auch `RestClient` mit virtuellen Threads) erfüllt das nicht.

## Entscheidung (Teil 1)

1. Die Proxy-Route kommt aus `source.network.ProxyRoutes` (Spring-konfiguriert, `win-proxy-java`) und wird dem
   **bestehenden** Transport über `HttpRoutePort` übergeben. Kein zweiter Confluence-Client, keine Änderung der Kopie.
2. mTLS (#33) bleibt unabhängig davon über die vorhandene `SSLSocketFactory`/`ClientCertificates`-Schnittstelle.
   Damit funktionieren Route und Zertifikat zusammen, auch über `CONNECT`.
3. Der asynchrone Client wird **nicht** in diesem Schritt gebaut, damit PAC-Gate und mTLS (#33) nicht daran hängen.

## Offen: Umsetzung des asynchronen Clients

Gesetzt ist *dass* asynchron übertragen wird, offen ist nur *wie*:

1. **Asynchrone Transport-SPI upstream zuerst** (`aresstack/enterprise-ai-client`), z. B.
   `CompletionStage<ConfluenceHttpResponse> getAsync(URI, Map<String, String>, int maxBytes)` neben dem synchronen
   `ConfluenceHttpTransport`. Ein asynchroner Client hinter dem heutigen synchronen Port müsste `block()` bzw.
   `join()` aufrufen und wäre damit nicht asynchron. Danach Kopie synchronisieren (#2).
2. **Client im Space Harvester** hinter dieser SPI. Kandidaten: Spring `WebClient` (Reactor Netty) oder JDK
   `java.net.http.HttpClient.sendAsync` (ohne Netty). Auswahl nach den Kriterien unten.

Kriterien, nachzuweisen mit Integrationstests gegen den bisherigen Transport:

- Verhaltensparität: Header, Basic/PAT, Redirect-Host-Bindung (keine Credentials/Zertifikate an fremde Hosts),
  Cookies/SSO, Byte-Limit, Fehlerzuordnung, bytegenaue Rohpayload.
- Route je Ziel-URL aus `ProxyRoutes`, vorher auf eigenem Pool aufgelöst (nie auf einem Event-Loop); kein
  Connection-Pool verwendet die Route einer anderen Quelle/URL wieder.
- mTLS mit Windows-MY (eigener `KeyManager`) und PKCS12, auch durch `CONNECT`.
- Begrenzte Parallelität je Quelle und Fail-Closed wie heute.

## Folgen

- Produktiver PAC-Pfad ist ohne Transportumbau nutzbar; das gemeinsame Gate mTLS + PAC/CONNECT (#33/#34) hängt
  nicht am WebClient.
- Der asynchrone Transport beginnt als Upstream-Änderung in `aresstack/enterprise-ai-client` mit anschließender
  Synchronisierung der Kopie (#2).
