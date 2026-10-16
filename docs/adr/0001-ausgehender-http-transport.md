# ADR 0001 – Ausgehender HTTP-Transport (Proxy/PAC, mTLS, asynchrone I/O)

- **Status:** Teil 1 umgesetzt (#36); Teil 2 (asynchroner Transport, #47) umgesetzt, einschließlich asynchroner
  Seiten-Snapshots im Adapter und Konfiguration `space-harvester.network.async-transport`
- **Tickets:** [#34](https://github.com/aresstack/space-harvester/issues/34), [#33](https://github.com/aresstack/space-harvester/issues/33), [#47](https://github.com/aresstack/space-harvester/issues/47), [#2](https://github.com/aresstack/space-harvester/issues/2)

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

## Entscheidung (Teil 2, #47): asynchroner Transport

### Upstream-Port (`aresstack/enterprise-ai-client#173`)

- **Port:** `ConfluenceAsyncHttpTransport` neben dem unveränderten `ConfluenceHttpTransport`, mit
  `CompletionStage<ConfluenceHttpResponse> getAsync(URI, Map<String, String> headers, int maxBytes)` und
  `getAnonymousAsync(...)`. Java 8, kein Reactor-Typ im Port. Request-Typ bleibt wie synchron: absolute URI,
  unveränderliche Header, Obergrenze; die Antwort ist dieselbe `ConfluenceHttpResponse` (Status, Content-Type,
  Bytes, Location, Cookies, Ziel-URI, Weiterleitungen). Ein eigener Request-Typ hätte nichts getragen, was der
  synchrone Weg nicht schon hat.
- **Header:** kommen vollständig vom Aufrufer (`Authorization` für Basic/PAT, `Accept`, Sitzungs-`Cookie`);
  der Transport ergänzt nur den User-Agent, den der Aufrufer überschreiben kann. Header werden nie geloggt.
- **Anonym:** `getAnonymousAsync` hat bewusst **keine** Standardumsetzung (anders als synchron), damit kein
  mTLS-Transport fremden Hosts sein Zertifikat zeigt.
- **Fehler:** 4xx/5xx sind Antworten. Alles andere beendet die Stufe mit `IOException`: Verbindung, Timeout
  (`SocketTimeoutException`), nicht verfügbare Route, Schema, Überlast; zu groß als
  `ConfluenceResponseTooLargeException`. Die Methoden selbst werfen dafür nicht. Damit bleibt die Abbildung im
  Adapter (`UNAVAILABLE`, `ACCESS_DENIED`, `NOT_FOUND`, `INVALID_RESPONSE`) dieselbe.
- **Abbruch:** `toCompletableFuture().cancel(true)` bricht die Anfrage ab und gibt die Verbindung frei; nichts wird
  weiter gepuffert.
- **Gemeinsame Regeln:** Weiterleitung nur auf demselben Host (höchstens 5, nie https→http) und Cookie-Sammlung
  stehen in `ConfluenceRedirects` und gelten für beide Transporte; der asynchrone Transport baut sie nicht nach.
- **TLS:** `ClientCertificates.contextFromKeyStore`/`windowsMyContext` liefern denselben Kontext mit erzwungenem
  Alias als `SSLContext`, weil Engine-basierte Clients keine `SSLSocketFactory` nehmen. Dabei fiel auf, dass der
  erzwungene Alias über eine `SSLEngine` nie angeboten wurde (reiner `X509KeyManager`); das ist upstream behoben.
- **Vertragstest:** `ConfluenceAsyncHttpTransportContract` (Testfixtures, JUnit 4) prüft jede Umsetzung gegen
  einen lokalen Server, unter anderem dass der Aufruf vor der Serverantwort zurückkehrt.

### Umsetzung im Space Harvester

`source.confluence.WebClientConfluenceTransport`: Spring `WebClient` auf Reactor Netty, nur als Client (der Dienst
bleibt Spring MVC auf Tomcat). Rückgabe per `Mono#toFuture()`, ohne `block()`/`join()` im Anfragepfad.

- **Route:** `HttpRoutePort#routeFor` je Ziel-URL und je Weiterleitungsschritt auf einem begrenzten eigenen
  Scheduler (Standard 4 Threads, 64 wartend), nie auf einem Netty-Event-Loop. UNAVAILABLE endet als
  `IOException` ohne Verbindungsversuch.
- **Isolation:** je Schlüssel aus effektiver Route (Art, Proxy), Ziel (Schema, Host, Port) und TLS-Identität (mit
  oder ohne Client-Zertifikat) ein eigener `WebClient` mit eigenem `ConnectionProvider`. Höchstens 64 Schlüssel; der
  älteste wird freigegeben.
- **Proxy:** Reactor-Netty-`ProxyProvider` vom Typ HTTP mit unaufgelöster Proxy-Adresse. Abweichung vom
  synchronen Weg: Netty tunnelt auch `http`-Ziele per `CONNECT` statt Absolute-Form. Confluence mit Anmeldung ist
  ohnehin nur über https zulässig.
- **TLS:** `JdkSslContext` aus dem `SSLContext` von `ConfiguredClientCertificates.sslContext` (dieselben
  Prüfungen wie #33); Hostnamenprüfung wie `HttpsURLConnection`.
- **Größe:** `Content-Length` über der Grenze wird sofort abgelehnt; sonst wird beim Empfang gezählt und beim
  Überschreiten abgebrochen, auch chunked. Kompression wird nicht angefordert, der Körper bleibt byte-genau.
- **Parallelität:** je Pool 8 Verbindungen und 64 wartende Anfragen (konfigurierbar); darüber endet eine
  Anfrage sofort mit `IOException`.
- **Namensauflösung:** über die JVM (`DefaultAddressResolverGroup`), wie `HttpURLConnection` inklusive Hosts-Datei
  und Windows-DNS; Nettys eigener DNS-Resolver liest unter Windows nicht dieselbe Konfiguration.
- **Lebenszyklus:** `close()` gibt alle Pools und den Routen-Scheduler frei.
- **Nachweis:** Vertragstest aus der Kopie; Parität mit `UrlConnectionConfluenceTransport` für 200/401/403/404/
  429/500/503, Weiterleitungen und Cookies; echtes PAC mit zwei Zielen und `CONNECT`, PAC-Ausfall ohne DIRECT;
  PAT + PKCS12 direkt und durch den Tunnel; Abbruch schließt die Verbindung; Lese-Timeout; Backpressure;
  `bootJarPacCheck` ruft aus dem ausführbaren JAR asynchron ab (`ProxyDiagnosticsMain --fetch=async`).

### Delegierte Aufrufe mit Nutzertoken (#24)

`DelegatedConfluenceHttp` (je Quelle über `ConfiguredSourceCatalog#delegatedHttp`) schickt einen GET mit dem Token
des Nutzers statt der Quell-Zugangsdaten, über dieselbe Route, dasselbe Server-Vertrauen und dasselbe
Client-Zertifikat wie das Ernten. Nur Pfade unter der Basis-URL der Quelle (sonst `IllegalArgumentException`, das
Token geht nie an einen anderen Host); gesendet werden nur `Authorization: Bearer …` und `Accept`; keine Cookies,
keine Weiterleitungen (3xx ist eine Antwort); 10 s Zeitgrenze einschließlich Routenauswahl, dann Abbruch und
`SocketTimeoutException`; 1 MiB Körpergrenze. Das Token ist kein Pool-Schlüssel und steht nie in Logs oder
Meldungen. Die Abbildung der Antworten (401, 403, 404) macht der Zugriffsschutz (Strang F).

### Verdrahtung in den Adapter

- **Upstream (enterprise-ai-client#173):** `AsyncStoredContentSource` (source-api, optional) liefert den nativen
  Seiten-Snapshot als `CompletionStage<StoredContent>`. Der Confluence-Adapter nutzt dafür seine bestehende
  Fachlogik: dieselbe Anfrage (`SNAPSHOT_EXPAND`), dieselbe Anmeldung, dieselben Wiederholungen (Wartezeit per
  Zeitgeber), dieselbe Status-, Fehler- und JSON-Abbildung; kein zweiter REST-Client und kein synchroner Aufruf auf
  einem Executor. Die JSON-Abbildung läuft auf einem eigenen Executor, nie auf einem Netty-Event-Loop; ein Abbruch
  der Stufe bricht die Anfrage oder die Wartezeit ab. `ConfluenceSourceProvider.withAsyncTransports` baut solche
  Quellen; Quellen mit Zertifikat aus `MM_CertAlias` (KeePass) bleiben synchron.
- **Harvester:** `space-harvester.network.async-transport: web-client` lässt `ConfiguredSourceCatalog` je Quelle
  einen `WebClientConfluenceTransport` mit derselben Route, demselben Vertrauen und demselben Zertifikat
  (`ConfiguredClientCertificates`) bauen (`AsyncSnapshotTransports`, schließt alle Transporte und den
  Abbildungs-Executor beim Herunterfahren). `HarvestSource.storedPageAsync` liefert dann die Seite nicht blockierend,
  mit denselben `SourceAccessException`-Gründen wie `storedPage`.
- **Rückfallpfad:** Standard ist `disabled`: alles läuft wie bisher über den synchronen Transport, und
  `storedPageAsync` endet sofort mit `UNSUPPORTED`. Nichts schaltet still um; Listen, Metadaten und alle übrigen
  Abrufe bleiben synchron, bis ihre Fachlogik upstream ebenfalls eine asynchrone Fähigkeit hat.
- **Nachweis:** `AsyncSnapshotsTest` (PAC wählt den Proxy, `CONNECT`, PAT und PKCS12, Antwort byte-gleich mit dem
  synchronen Abruf, Abbildung nicht auf dem Aufrufer-Thread, Fehlerabbildung, Rückfall), upstream
  `ConfluenceAsyncStoredContentTest`.

### Noch offen

- Windows-MY unter dem Dienstkonto und der Live-Test gegen die produktive Instanz bleiben das gemeinsame Gate von
  #33/#34 (siehe `docs/MTLS.md`).

## Folgen

- Produktiver PAC-Pfad ist ohne Transportumbau nutzbar; das gemeinsame Gate mTLS + PAC/CONNECT (#33/#34) hängt
  nicht am WebClient.
- Der asynchrone Transport beginnt als Upstream-Änderung in `aresstack/enterprise-ai-client` mit anschließender
  Synchronisierung der Kopie (#2).
- `spring-webflux` und `reactor-netty-http` sind Laufzeitabhängigkeiten; der Dienst bleibt ein Servlet-Dienst.
