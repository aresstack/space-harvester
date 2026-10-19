# Space Harvester

![Space Harvester – Vom Dokument zum Wissensnetz](docs/images/space-harvester-banner.png)

**Space Harvester** erschließt Wissensquellen: Seiten und Dokumente finden, sicher abrufen, lokal indexieren,
lesbar darstellen, aktuell halten, analysieren und in wiederverwendbare Formate exportieren.

Das Projekt ist inzwischen **mehr als ein Confluence-Exportdienst**. Ein gemeinsamer, Spring-unabhängiger Core
trägt sowohl die **Swing-Desktop-Anwendung** als auch einen separat startbaren **Spring-Boot-Dienst mit REST-API und
Weboberfläche**. Die Quelladapter stammen aus bzw. orientieren sich am
[Enterprise AI Client](https://github.com/aresstack/enterprise-ai-client).

## Was Space Harvester kann

| Bereich | Funktionsumfang |
|---|---|
| **Quellen** | Confluence Data Center und lokale Dateien; Erweiterung über Quellenadapter |
| **Suche** | Lucene-Volltextindex, Live-Suche über Quellen, schrittweises Nachladen, optional semantische Suche |
| **Lesen** | Seitenbaum, Breadcrumbs, Lesetext, Eigenschaften, Anhänge, Querverweise und gerenderte Diagramme |
| **Synchronisieren** | Versions- und Bestandsabgleich, Aktualitätsprüfung, gesteuerte Hintergrundbefüllung und nachvollziehbare Aufträge |
| **Exportieren** | Reproduzierbarer JSON-Datenbestand; daraus JSON, Markdown, Word und PDF ohne erneuten Quellenabruf |
| **Anreichern** | Seitenbezogene Verarbeitung und Analyse, unter anderem Akronymerkennung; optionale KI- und Embedding-Funktionen |
| **Betreiben** | Desktop-App ohne lokalen Server oder headless REST-Dienst; Rechteprüfung, Proxy/PAC, TLS/mTLS und Diagnose |

**Noch in Entwicklung:** Die globale Begriffsverwaltung mit **Akronymen, Bezeichnern, Beziehungen und Fachglossar**
ist als eigener Desktop-Hauptbereich und seitenbezogene Ansicht geplant
([#339](https://github.com/aresstack/space-harvester/issues/339)).
Dafür werden der Begriffsindex ([#119](https://github.com/aresstack/space-harvester/issues/119)),
kuratierbare Beziehungen ([#120](https://github.com/aresstack/space-harvester/issues/120)),
die Akronymauflösung ([#122](https://github.com/aresstack/space-harvester/issues/122)) und
automatische Synonymvorschläge ([#336](https://github.com/aresstack/space-harvester/issues/336)) weiterentwickelt.
Das KI-gestützte Fachglossar ist noch keine fertige Funktion.

## Schnellstart

**Voraussetzung:** JDK 21. Der Gradle Wrapper ist enthalten, eine separate Gradle-Installation ist nicht nötig.

### Desktop-App

Die Desktop-Anwendung läuft eigenständig mit direktem Core-Zugriff – **ohne REST-Server und ohne Spring-Kontext**.

1. Im Bereich [Releases](https://github.com/aresstack/space-harvester/releases) das Desktop-JAR
   `space-harvester-app-<version>.jar` und bei Bedarf die `SHA256SUMS` herunterladen.
2. Mit Java 21 starten:

```bash
java -jar space-harvester-app-<version>.jar
```

Beim ersten Start führt der Quellen-Assistent durch die Einrichtung. Die App bietet Suche, Explorer, Leseansicht,
Quellenverwaltung, Synchronisierung, Downloads, Aufträge, Status und Einstellungen. Einzelheiten einschließlich
Windows-Start und Konfiguration: [Desktop-Dokumentation](docs/DESKTOP_APP.md).

### REST-Dienst und Weboberfläche

```bash
./gradlew :space-harvester-server:bootRun
```

Der Dienst stellt seine API unter `http://localhost:8080/api/v1/` und seine Weboberfläche unter
`http://localhost:8080/ui/` bereit. Für reguläre API-Zugriffe ist ein Bearer-Token vorgesehen
([Sicherheit](docs/SECURITY.md)); für eine lokale Entwicklungsinstanz ohne Anmeldung:

```bash
SPRING_PROFILES_ACTIVE=local SPACE_HARVESTER_SECURITY_MODE=development ./gradlew :space-harvester-server:bootRun
```

Der Dienst kann ohne eingerichtete Confluence-Quelle starten. Mit dem Profil `demo` lässt sich der Ablauf anhand
einer Fake-Confluence ausprobieren ([End-to-End-Beispiele](docs/E2E.md)).
Port, Quellen, Authentifizierung und Netzwerkeinstellungen sind konfigurierbar
([Konfiguration](docs/CONFIGURATION.md)).

### Bauen und testen

```bash
./gradlew build
```

Der Build prüft Core, Server, Desktop und Vergleichstests. Unter Windows den Gradle Wrapper über `gradlew.bat`
aufrufen. Für ein ausführbares Server-JAR siehe die Ausgabe von `:space-harvester-server:bootJar`.
Strukturierte JSON-Logs (Elastic Common Schema) unterstützt das Profil `json-logs`.

## Dokumentation

- [Desktop-App](docs/DESKTOP_APP.md) – Bedienung, Einrichtung und Bereiche
- [Module und Architektur](docs/MODULES.md) – Core, Server und Desktop
- [Konfiguration](docs/CONFIGURATION.md) und [Quellenadapter](docs/SOURCE_ADAPTERS.md)
- [Sicherheit](docs/SECURITY.md), [TLS/mTLS](docs/MTLS.md) und [Geheimnisse](docs/SECRETS.md)
- [Funktionsparität mit dem Enterprise AI Client](docs/PARITY.md) – umgesetzte und noch offene Fähigkeiten
- [Formatkonvertierung](docs/CONVERTERS.md), [Diagnose](docs/DIAGNOSTICS.md) und [Architekturentscheidungen](docs/adr/)

Die folgenden Abschnitte dokumentieren die technischen Details und Schnittstellen.

## Module und Nutzung als Bibliothek

Der Build umfasst drei produktive Module ([Modulübersicht](docs/MODULES.md), [ADR 0006](docs/adr/0006-core-bibliothek-und-server.md)):

- `space-harvester-core`: Bibliothek ohne Spring Boot und ohne REST. Fachlogik, Quellenadapter (Confluence, Dateien),
  Lucene-Cache, Abgleich, Suche, Export und Embeddings.
- `space-harvester-server`: der Spring-Boot-Dienst mit REST-API, Confluence-Fassade, Weboberfläche und Sicherheit; er
  verwendet den Core; starten lässt er sich mit `./gradlew :space-harvester-server:bootRun`.
- `space-harvester-app`: Desktop-App (Swing, Comic-Design des Enterprise AI Client) direkt auf dem Core, ohne REST und
  ohne Spring-Kontext: `java -jar space-harvester-app/build/libs/space-harvester-app-<version>.jar`. Start unter
  Windows, Konfiguration und Funktionsumfang: [docs/DESKTOP_APP.md](docs/DESKTOP_APP.md).

Der Core ist als gemeinsame Wissensquellen- und Dokumentverarbeitungsbibliothek für Space Harvester und den
Enterprise AI Client ausgelegt ([ADR 0012](docs/adr/0012-core-als-gemeinsame-bibliothek.md)). Welche Fähigkeiten
bereits gleichwertig sind und wo noch Lücken bestehen, dokumentiert [die Paritätsübersicht](docs/PARITY.md).

Ohne Dienst nutzt ein Java-21-Programm den Core über `com.aresstack.spaceharvester.core.Harvester`. Er baut dieselben
Anwendungsfälle wie der Dienst und gibt sie heraus (`sources()`, `pages()`, `search()`, `relevance()`, `syncs()`,
`exports()`, `assets()`, `enrichments()`, `jobs()`); Abgleich und Export laufen als Aufträge mit Fortschritt und
Abbruch:

```java
try (Harvester harvester = Harvester.builder()
        .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", URI.create("https://wiki.example.org"),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, token), Map.of()))
        .dataDirectory(Path.of("data"))          // cache/, exports/, assets/
        .build()) {
    JobStatus sync = harvester.await(harvester.sync("wiki", "DOC").id(), Duration.ofMinutes(30));
    PageListing pages = harvester.pages().pages(harvester.caller(), "wiki", "DOC", null, 100);
    JobStatus export = harvester.await(harvester.export("wiki", "DOC", List.of("markdown")).id(),
            Duration.ofMinutes(30));
    ExportArtifact artifact = harvester.exportArtifact(export.id()).orElseThrow();
}
```

Proxy/PAC (`proxy(...)`), Vertrauensquellen (`trustBundles(...)`), Rechte (`accessPolicy(...)`, Standard: der
einbettende Aufrufer darf alles lesen), Auftragsgrenzen und ein lokales Embedding-Modell setzt der Builder; mit
`configuration(ConfigurationManager)` folgen Modelle, Zeitplan und Regeln je Quelle zur Laufzeit jeder aktivierten
Konfiguration ([CONFIGURATION.md](docs/CONFIGURATION.md)). Die
Veröffentlichung als `com.aresstack:space-harvester-core` ist vorbereitet (`./gradlew
:space-harvester-core:publishMavenPublicationToLocalRepository` schreibt nach `space-harvester-core/build/repo`),
aber noch nicht eingerichtet.

## Quellen konfigurieren

Quellen stehen unter `space-harvester.sources` in der `application.yml` oder in einer zusätzlichen Datei, etwa
`config/application-local.yml` (von Git ignoriert, wird mit `--spring.profiles.active=local` geladen).
Zugangsdaten stehen nie in Dateien im Repository: Dort verweisen Platzhalter wie `${CONFLUENCE_TOKEN}` auf
Umgebungsvariablen. In der lokalen, nicht eingecheckten `application-local.yml` dürfen `auth.password` und
`auth.token` bewusst auch direkt als Wert stehen.

```yaml
space-harvester:
  sources:
    - id: confluence                  # Quellen-ID in der API, getrennt von den Space-Keys
      type: confluence
      title: Confluence
      base-url: https://confluence.example.org   # ohne /rest
      auth:
        type: pat                     # none | basic | pat
        token: ${CONFLUENCE_TOKEN}
      options:                        # weitere Einstellungen des Confluence-Adapters
        pageSize: 100
        maxRetries: 2                 # Standard 2: Netzfehler, 5xx und 429 wiederholen, 401/403/404 nie
        retryBackoffMillis: 1000      # erste Wartezeit, verdoppelt je Versuch, gestreut, höchstens maxRetryBackoffMillis
        foreignFileRedirects: false   # immer false: Datei-Weiterleitungen auf fremde Hosts werden nie verfolgt
    - id: team-wiki
      type: confluence
      base-url: https://wiki.example.org
      auth:
        type: basic
        username: ${TEAM_WIKI_USER}
        password: ${TEAM_WIKI_PASSWORD}
```

In `config/application-local.yml` geht dasselbe auch ohne Umgebungsvariablen:

```yaml
space-harvester:
  sources:
    - id: confluence
      type: confluence
      base-url: https://confluence.example.org
      auth:
        type: pat
        token: "mein-persönlicher-token"   # nur in der lokalen, von Git ignorierten Datei
```

- **Anmeldearten:** `none` (anonym, Standard), `basic` (Benutzername und Passwort als HTTP Basic) und `pat`
  (Personal Access Token als `Authorization: Bearer`). Die Zugangsdaten gehen über einen `SecretProvider` an den
  Confluence-Adapter. Platzhalter können optional aus Azure Key Vault kommen (Profil `azure-keyvault`); ein
  Platzhalter ohne Wert verhindert den Start. Varianten, Anmeldung und Live-Test: [docs/SECRETS.md](docs/SECRETS.md).
- **Prüfung beim Start:** Fehlerhafte Quellen verhindern den Start; die Meldung nennt jede Quelle und jedes Feld.
  Eine nicht erreichbare Quelle verhindert den Start nicht, Verbindungen entstehen erst bei Anfragen.
- **Keine Geheimnisse nach außen:** Passwort, Token und Zertifikatspasswort erscheinen weder in API-Antworten noch in Logs.
- **Client-Zertifikate (mTLS):** je Quelle unter `client-certificate`, zusätzlich zur Anmeldung (`auth` bleibt
  unverändert). PKCS#12-Datei oder unter Windows der Zertifikatsspeicher `Windows-MY` des Dienstkontos; eigene
  Server-/Proxy-CAs über `trust-bundle` (ein Spring-SSL-Bundle). Details, Windows-Dienst und Live-Test:
  [docs/MTLS.md](docs/MTLS.md).

  ```yaml
  space-harvester:
    sources:
      - id: confluence
        type: confluence
        base-url: https://confluence.example.org
        auth:
          type: pat
          token: ${CONFLUENCE_PAT}
        client-certificate:
          enabled: true
          type: pkcs12                # pkcs12 | windows-my
          path: ${CONFLUENCE_CLIENT_KEYSTORE}
          alias: confluence-client
          password: ${CONFLUENCE_CLIENT_KEYSTORE_PASSWORD}
  ```
- **Spring Cloud Config Server:** optional über das Profil `config-server` (`SPRING_CLOUD_CONFIG_URI`); ohne
  erreichbaren Server startet der Dienst mit der lokalen Konfiguration. Reihenfolge der Quellen und Fallstricke:
  [docs/CONFIGURATION.md](docs/CONFIGURATION.md). Proxy und PAC: siehe nächsten Abschnitt.

### Weitere Quellentypen (#18)

Jeder Quellentyp ist ein `SourceAdapter` (Paket `source`): er prüft seine Einstellungen und öffnet die Quelle über
einen Provider aus `enterprise-ai-sources`; Fachlogik wie Partitionierung, Cursor und Größengrenzen liegt upstream im
Enterprise AI Client. Mitgeliefert sind `confluence` und `files`. Ein weiterer Typ ist eine Spring-Bean vom Typ
`SourceAdapter`; Routen, Freigaben, Sync und Export bleiben unverändert, weil sie nur die Partitionen, Seiten-IDs und
Fähigkeiten der Quelle sehen. `GET /api/v1/sources` zeigt je Quelle `capabilities`, etwa `stored-page` (Original
abrufbar) oder `async-stored-page`.

`type: files` liest ein lokales Verzeichnis, ohne Netz, Anmeldung und Zertifikat:

```yaml
space-harvester:
  sources:
    - id: handbuch
      type: files
      title: Betriebshandbuch
      options:
        directory: /srv/handbuch       # Pflicht
        include: "*.md, *.txt, *.pdf"  # optional; außerdem exclude, maxDepth (20), maxFileMegabytes (20, höchstens 64)
```

Das Verzeichnis ist eine Partition mit der Quellen-ID als Schlüssel (`/spaces/handbuch/...`). Die Seiten-ID ist der
relative Pfad als Base64url ohne Auffüllung (`start.md` → `c3RhcnQubWQ`), das Original die Datei Byte für Byte
(`nativeFormat` `file`). Versteckte Dateien und Ordner (`.git`, `.ssh`) bleiben außen vor; Metadaten nennen nur den
relativen Pfad, nie den Serverpfad. Eine Datei, die sich während des Lesens ändert, gilt als nicht verfügbar, eine zu große als
fehlgeschlagen; gekürzt wird nie. Lesetext für Volltextindex und Anreicherungen haben Textdateien und Dokumente: PDF,
Word, Excel, PowerPoint, OpenDocument und RTF liest Apache Tika wie im Enterprise AI Client (#183, Grenzen in
[docs/SOURCE_ADAPTERS.md](docs/SOURCE_ADAPTERS.md#dokumente-typerkennung-und-text-183)).

## Proxy und PAC/WPAD

Ausgehende Verbindungen aller Quellen nehmen die Route, die `space-harvester.network.proxy` für die jeweilige
Ziel-URL bestimmt ([#34](https://github.com/aresstack/space-harvester/issues/34)). Die Auflösung übernimmt die
Bibliothek [`win-proxy-java`](https://github.com/aresstack/win-proxy-java) (PAC-Adresse finden, PAC laden,
`FindProxyForURL` mit GraalJS auswerten); der Confluence-Adapter wendet die Route je Verbindung an. Es gibt keine
globalen `https.proxyHost`-Einstellungen und keinen prozessweiten `ProxySelector`.

```yaml
space-harvester:
  network:
    proxy:
      mode: pac-url-powershell      # Windows: PAC-Adresse aus AutoConfigURL (HKCU) per Inline-PowerShell
      non-proxy-hosts:              # optional, immer direkt: exakter Name oder *.suffix
        - intranet.example.org
      resolve-timeout: 20s          # Frist je Auflösung (PowerShell, PAC-Download, Auswertung)
```

| `mode` | Bedeutung | Plattform |
| --- | --- | --- |
| `disabled` | Standard: alle Verbindungen direkt | alle |
| `manual-proxy` | fester Proxy aus `proxy-host` und `proxy-port` | alle |
| `pac-url-manual` | PAC-Skript von `pac-url` (`http`, `https` oder `file`) | alle, z. B. Container |
| `pac-url-powershell` | PAC-Adresse aus `AutoConfigURL` per PowerShell `-Command` (kein Skript auf der Platte) | Windows |
| `pac-url-windows-settings` | PAC-Adresse aus den Interneteinstellungen per `reg.exe` | Windows |
| `pac-url-wscript` | PAC-Adresse per VBScript (`cscript`) | Windows |
| `windows-static-proxy` | fester Proxy aus den Interneteinstellungen (`ProxyServer`, `ProxyOverride`) | Windows |

- **Fail-Closed:** Lässt sich eine Route nicht bestimmen (keine PAC-Adresse, PAC nicht ladbar, Skriptfehler,
  Zeitüberschreitung), scheitert die Anfrage mit dem Grund. Der Dienst verbindet dann **nie** stillschweigend direkt.
  Gescheiterte Auflösungen werden 1 Minute gemerkt (`failure-ttl`), gefundene Routen 10 Minuten (`cache-ttl`).
- **Ohne Konfiguration** bleibt alles direkt wie bisher. Loopback-Ziele gehen immer direkt. Falsche Einstellungen
  (fehlende `pac-url`, Windows-Modus auf Linux, nicht umgesetzte `windows-native-*`-Modi) verhindern den Start.
- **Je Ziel-URL:** Das PAC-Skript bekommt bei HTTPS wie im Browser nur `https://host[:port]/`, bei HTTP die ganze
  URL ohne Benutzerinfo. Unter genau diesem Schlüssel wird die Route gemerkt. Protokoll, Diagnose und
  Fehlermeldungen zeigen Ziel- und PAC-URLs nur ohne Query (`?…`), auch in Meldungen der Bibliothek. Die
  Auflösung läuft auf einem eigenen Pool (höchstens 4 gleichzeitig) und erst bei der ersten Anfrage.
- **HTTPS über Proxy** läuft per `CONNECT`-Tunnel; `Authorization`, Zugangsdaten und das Client-Zertifikat (mTLS)
  gehen nur durch den Tunnel zum Ziel, nicht an den Proxy.
- **Anmeldung am Proxy** (BASIC, [#189](https://github.com/aresstack/space-harvester/issues/189)): `auth.username`
  und `auth.password` (Passwort über einen Platzhalter, z. B. `${PROXY_PASSWORD}`). Der WebClient-Transport meldet
  sich je Verbindung an, auch im `CONNECT`-Tunnel. Für `HttpURLConnection` (synchrone Confluence-Aufrufe,
  KI-Dienst) setzt der Core wie der Enterprise AI Client einen prozessweiten `Authenticator`, der nur dem Proxy
  antwortet, und gibt BASIC für den Tunnel frei (`jdk.http.auth.tunneling.disabledSchemes`, sofern nicht gesetzt).
  Einbettende Programme können das Passwort auch über `Harvester.builder().secretProvider(…)` unter
  `network:proxy` liefern; dann reicht `auth.username` oder ein leeres `auth`. Weder Benutzer noch Passwort
  erscheinen im Protokoll.

  ```yaml
  space-harvester:
    network:
      proxy:
        mode: pac-url-powershell
        auth:
          username: proxy-nutzer
          password: ${PROXY_PASSWORD}
  ```
- **Eigenes Skript zur PAC-Ermittlung** (#189): `pac-discovery-script` ersetzt bei `pac-url-powershell` das
  eingebaute PowerShell-Skript und bei `pac-url-wscript` das VBScript; es gibt die PAC-Adresse aus, wie
  `network.proxy.pacDiscoveryScript` im Enterprise AI Client.
- **TLS-Vertrauen** ([#189](https://github.com/aresstack/space-harvester/issues/189)) für Quellen und KI-Dienst
  ohne eigenes `trust-bundle`, wie `network.tls` im Enterprise AI Client: JVM-Truststore (`use-jvm-default`,
  Standard an), Windows-Stammzertifikate (`use-windows-root`), Windows-Speicher Root und CA samt
  Zwischenzertifikaten (`use-windows-ca-stores`) und eine PEM-/DER-Datei mit weiteren CAs
  (`ca-certificates-file`, z. B. die CA eines TLS-prüfenden Firmen-Proxys). Ein Zertifikat gilt, sobald eine Quelle
  es akzeptiert; Kette, Gültigkeit und Hostname prüft die Java-Laufzeit. Die Windows-Speicher liest der Core erst
  bei der ersten TLS-Verbindung (außerhalb von Windows bleiben sie mit Hinweis im Protokoll aus); prozessweit wird
  nichts umgestellt. Bibliothek: `Harvester.builder().trust(new TrustSettings(…))`.

  ```yaml
  space-harvester:
    network:
      tls:
        use-windows-root: true
        use-windows-ca-stores: true
        ca-certificates-file: C:/firma/proxy-ca.pem
  ```
- **Windows-Dienst:** `pac-url-powershell`, `pac-url-windows-settings`, `pac-url-wscript` und `windows-static-proxy`
  lesen `HKCU` des **ausführenden Dienstkontos**, nicht die Einstellungen des angemeldeten Benutzers. Hat das
  Dienstkonto keine `AutoConfigURL`, `pac-url-manual` mit der PAC-Adresse des Unternehmens verwenden.
- **Linux/Container:** `pac-url-manual` oder `manual-proxy`.
- **GraalJS:** `win-proxy-java` 0.2.0 bringt GraalJS 21.2 (`js` und `regex`) mit; JDK 21 hat keinen eingebauten
  JavaScript-Interpreter. GraalJS läuft im Interpretermodus, die erste Auswertung dauert etwa 1 s. Das Skript läuft
  ohne Zugriff auf Java-Klassen, Dateien oder Prozesse. `./gradlew check` prüft mit `bootJarPacCheck` die
  Auswertung im ausführbaren JAR (verschachtelte JARs, keine zusammengeführten `META-INF/services`).

Diagnose ohne Start des Dienstes, mit denselben Optionen und allen Schritten der Bibliothek (Exit-Code 2, wenn eine
Route nicht bestimmbar ist):

```bash
java -Dloader.main=com.aresstack.spaceharvester.source.network.ProxyDiagnosticsMain \
     -cp space-harvester-server/build/libs/space-harvester-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher \
     --mode=pac-url-powershell https://confluence.example.org/
```

Native Seiten-Snapshots laden standardmäßig nicht blockierend über den asynchronen `WebClientConfluenceTransport`
(Spring WebClient auf Reactor Netty, #47) mit derselben Route je Ziel-URL, demselben Client-Zertifikat und denselben
Grenzen wie der synchrone `HttpURLConnection`-Transport des übernommenen Confluence-Adapters; alle anderen Abrufe
bleiben vorerst synchron. Rückfall, falls der asynchrone Weg im Unternehmensnetz scheitert:

```yaml
space-harvester:
  network:
    async-transport: disabled   # Standard: web-client
``` Entscheidungen und Nachweise stehen in [ADR 0001](docs/adr/0001-ausgehender-http-transport.md).
`--fetch=async` ruft jede Ziel-URL der Diagnose zusätzlich ohne Anmeldung über diesen Transport ab und zeigt nur
Status und Größe.

## Seiten-Cache (Lucene)

Native Seiten-Snapshots und Kopfdaten liegen in einem eingebetteten Apache-Lucene-Index im lokalen Dateisystem
(#26); es gibt keinen Datenbankserver. Ohne Verzeichnis läuft der Dienst ohne Cache.

```yaml
space-harvester:
  cache:
    directory: /var/lib/space-harvester/cache   # Wurzel: Seiten-Index in pages/, Aufträge in jobs/
    max-payload-size: 20MB                      # Standard; größere Seiten werden abgewiesen, nie gekürzt
    search-timeout: 5s                          # Standard; längere Suchen (#28) brechen mit 503 ab
```

- **Inhalt:** je Seite (`sourceId` + `pageId`) die aktuellen Kopfdaten, der zuletzt gespeicherte Snapshot mit den
  Originalbytes der Antwort, ein Verlauf der Revisionen (ohne Payload) und je Space die zuletzt bestätigte Generation
  der Synchronisierung.
  Feldschema und Version (`page-cache/v3`): `PageCacheSchema`; Entscheidung und Aufbau: [ADR 0003](docs/adr/0003-lucene-cache.md). Zugangsdaten, Header oder Cookies stehen nie im Index.
- **Aufträge:** Mit gesetztem Verzeichnis überdauern Auftragszustände (#12) einen Neustart (Index `jobs/`, Schema
  `job-store/v1`); ohne bleiben sie nur im Speicher.
- **Schutzbedarf:** Die Payloads enthalten Seiteninhalte samt Makroparametern und Benutzerkennungen. Das
  Verzeichnis gehört allein dem Dienstkonto (unter Linux z. B. `chmod 700`) und liegt auf einem verschlüsselten
  Datenträger, wenn die Seiten das verlangen.
- **Ein Prozess je Verzeichnis:** Lucene sperrt den Index (`write.lock`); ein zweiter Dienst auf demselben
  Verzeichnis startet nicht.
- **Konsistenz:** Änderungen werden erst mit einem Commit sichtbar und dauerhaft. Ein Synchronisierungslauf schreibt
  in einem Schreiblauf und wird erst als vollständig bestätigt, wenn die Synchronisierung das meldet; ein Abbruch
  oder Absturz vorher lässt den zuletzt bestätigten Stand stehen. Beim Lesen wird jede Payload gegen ihren
  `rawSha256` geprüft.
- **Sicherung:** Dienst anhalten und das Verzeichnis kopieren. Im laufenden Betrieb ist eine Kopie nur dann
  konsistent, wenn währenddessen kein Commit geschieht.
- **Löschen und Wiederaufbau:** Dienst anhalten, Verzeichnis löschen, neu starten und neu ernten. Ein beschädigter
  Index oder einer mit anderem Schema verhindert den Start mit einer Meldung; er wird nie stillschweigend ersetzt.

## API-Übersicht

| Pfad | Zweck |
|---|---|
| `GET /api/v1/info` | Name, Version und API-Version des Dienstes |
| `GET /api/v1/sources` | Konfigurierte Quellen (ID, Typ, Titel; ohne Verbindungsdaten) |
| `GET /api/v1/sources/{sourceId}/spaces` | Alle sichtbaren Spaces einer Quelle (Key, Name, Startseite), ungekürzt |
| `GET /api/v1/sources/{sourceId}/diagnosis` | Verbindungsdiagnose in Stufen `proxy-route`, `dns`, `tcp`, `tls`, `authentication`, `api` mit Ergebnis, Ursache und Hinweis; live, legt nichts ab (#189, [DIAGNOSTICS.md](docs/DIAGNOSTICS.md#verbindungsdiagnose)) |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/pages/metadata` | Metadaten aller Seiten eines Space ohne Inhalte, live und stapelweise (`cursor`, `limit` 1–100) mit `metadataHash` und `attributesHash` |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/metadata` | Metadaten einer einzelnen Seite ohne Inhalt, live; kanonische Route ohne Space-Key |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/pages` | Flache Seitenliste aus dem Cache, ohne Inhalte, stapelweise (`cursor`, `limit` 1–100), mit Cache-Stand |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/tree` | Seitenbaum aus dem Cache als `roots[]` mit `id` und `children[]`; `include=metadata` ergänzt Titel, Version, Änderungszeit, URL |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/attachments` | Alle Anhänge einer Seite als Metadaten, live, auch nicht eingebettete, ohne Dateiinhalte; `complete=false` mit `warnings`, wenn die Quelle Einträge auslassen musste (#9) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/assets` | Anhänge einer Seite mit Art (`DIAGRAM` vor `VECTOR` vor `IMAGE` vor `OTHER`), Bezug Vorschaubild → draw.io-Diagramm (`previewOf`) und abgelegtem Stand; lädt nichts (#19) |
| `POST /api/v1/sources/{sourceId}/pages/{pageId}/assets/harvest` | Alle Anhänge der Seite über die Anmeldung der Quelle herunterladen und versioniert ablegen; gleiche Versionen werden nicht erneut übertragen; verlangt `sync` (#19) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/assets/{attachmentId}/content` | Ein Anhang in seiner aktuellen Version Byte für Byte, aus der Ablage oder bei Bedarf nachgeladen; immer als Download, `ETag` = SHA-256; verlangt `content` (#19) |
| `GET`/`POST /api/v1/sources/{sourceId}/pages/{pageId}/assets/{attachmentId}/description` | Maschinelle Beschreibung eines Bildes (`provenance=machine-generated`), je Bildinhalt und Modellversion gecacht; `POST` erzeugt sie bei Bedarf und verlangt zusätzlich `sync`; ohne konfigurierten Beschreiber `image-description-unavailable` (503) (#19) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}` | Native Payload einer Seite aus dem Cache, Byte für Byte; Cache-Stand in Headern, `ETag` = `rawSha256`. `view=extended`: Container mit `data`, `metadata`, `enrichments`; `refresh=true` prüft gegen die Quelle |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/reading-view[?refresh=true]` | Lese-Ansicht wie im Enterprise AI Client: gerendertes HTML (`body.export_view`, Makros ausgeführt), Lesetext (`body.view`) und Bilder als Verweise, nur zur geprüften Version, danach neben dem Raw-Snapshot abgelegt (`stored`); bindet die Seite Inhalte anderer Seiten oder Systeme ein, nur mit Freigabe der ganzen Quelle, sonst `status=restricted`; verlangt `content` (#176) |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/children[?cursor=…]` | Wurzelseiten eines Space live in Confluence-Reihenfolge, stapelweise mit `nextCursor`, je Seite `hasChildren`; verlangt `metadata`, Seiten ohne Freigabe fehlen (#177) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/children[?cursor=…]` | Direkte Kindseiten einer Seite live, wie oben (#177) |
| `GET /api/v1/sources/{sourceId}/pages?title=…[&spaceKey=…][&limit=…]` | Seiten live über ihren Titel finden, auch nie abgeglichene (Groß-/Kleinschreibung egal; Confluence über die CQL-Wortsuche, Dateien über den Dateinamen), mit `spaceKey` je Seite und `truncated`; `spaceKey` wiederholbar, `limit` 1–100 (Standard 25); verlangt `metadata`, Seiten ohne Freigabe fehlen auch in `truncated` |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/ancestors` | Breadcrumbs: Space mit Namen, Vorfahren von oben nach unten, die Seite selbst; live (#177) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/links` | Querverweise auf andere Seiten der Quelle (ausgehend; eingehend nur, wenn die Quelle sie vollständig kennt, `incomingSupported`); verlangt `content`, Ziele ohne Freigabe fehlen (#177) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/related[?chunk=…&kind=…]` | Verwandte Abschnitte eines eingebetteten Stücks wie im Enterprise AI Client: ähnliche Stücke (`similar-to`, ab `minSimilarity` 0,80), Elternseite, Nachbarstücke, gleicher Abschnitt; aus den gespeicherten Vektoren, nichts geht an den KI-Dienst; verlangt `content`, Ziele ohne Freigabe fehlen (#181, [EMBEDDINGS.md](docs/EMBEDDINGS.md#verwandte-abschnitte)) |
| `GET /api/v1/sources/{sourceId}/pages?url=…` | Dieselbe Seite über ihre Adresse: URL mit Seiten-ID, `/display/KEY/Titel`, Space-Startseite, `confluence://…`, Ressourcen-ID oder Lesezeichen `space-harvester://…` (#178) |
| `GET /api/v1/addresses?address=…` | Eine Adresse über alle Quellen auflösen; je Treffer Seite und Lesezeichen (#178) |
| `POST /api/v1/sources/{sourceId}/pages/{pageId}/enrichments[?processor=…]` | Anreicherungen einer Seite aus dem Cache neu berechnen (alle oder ein Prozessor); verlangt `content` |
| `POST /api/v1/sources/{sourceId}/spaces/{spaceKey}/syncs` | Abgleich eines Space mit der Quelle als Hintergrundauftrag starten (202, `Location` auf den Status); der erste Abgleich auf leerem Cache ist die Ernte des Space (#10). Vor der Annahme: ungültiger Space-Key 400 (`invalid-request`), Space, den die Quelle nicht kennt, 404 (`source-object-not-found`) |
| `GET /api/v1/syncs/{syncId}`, `POST /api/v1/syncs/{syncId}/cancel` | Status samt Delta eines eigenen Abgleichs, Abbruch |
| `GET /api/v1/search[?sourceId=…\|sourceIds=…]` | Mit `text` Schlagwortsuche über Titel und Inhalt in der Suche der Quellen, ohne Nachladen, mit Textauszügen; sonst Titel- und Metadatensuche über den Index (`title`, `spaceKey`, `pageId`, `parentPageId`, `version`, `modifiedFrom`, `modifiedBefore`), stapelweise; ohne `sourceId` global über alle freigegebenen Quellen oder nur die in `sourceIds` (#114) |
| `POST /api/v1/search/progressive`, `GET`/`DELETE /api/v1/search/progressive/{id}` | Progressive Suche ([ADR 0017](docs/adr/0017-progressive-suche.md)): `q` sofort, dann Varianten in Wellen, bewertet gegen `q`; ohne `wait` 202 mit Stand und `Location`, mit `wait: true` 200 mit der fertigen Liste und `reason`; Abfrage und Abbruch nur für den eigenen Aufrufer |
| `GET /api/compat/confluence/{sourceId}/rest/api/content/{id}`, `…/content?spaceKey=…`, `…/content/{id}/child/page` | Confluence-kompatible Einzelseite und Seitenlisten aus dem Cache, Payloads Byte für Byte, nur lesend; eine fehlende Einzelseite wird wie bei der Seiten-API nachgeladen; Umfang und Abweichungen: [docs/CONFLUENCE_COMPAT.md](docs/CONFLUENCE_COMPAT.md) |
| `GET /v3/api-docs` | OpenAPI-Dokument der REST-API (`/api/**`) |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /actuator/health` | Health; Probes unter `/actuator/health/liveness` und `/actuator/health/readiness` |
| `GET /actuator/info` | Build-Informationen |

Fehler werden als Problem Details (RFC 9457, `application/problem+json`) beantwortet, außer unter `/api/compat/confluence`, wo sie den Fehlerkörper von Confluence haben. Bei Quellen unterscheidet
`type` die Fälle: `access-denied` (403, keine Freigabe oder unbekannte Quellen-ID), `source-access-denied` (502, Anmeldung
abgelehnt), `source-unavailable` (503, nicht erreichbar), `source-rate-limited` (429, die Quelle drosselt auch nach den
Wiederholungen), `source-object-not-found` (404, auch für Seiten, die das Konto nicht sehen darf) und
`source-invalid-response` (502). `detail` ist ein fester
Text je Fall; Meldungen der Quelle oder des Netzwerks gehen nicht an Clients und stehen nur im DEBUG-Log.

**Zugriffsschutz:** Alle Pfade unter `/api/**` verlangen `Authorization: Bearer <token>` eines konfigurierten
technischen Clients (sonst 401 `unauthenticated`). Jeder Client sieht nur die Quellen und Spaces seiner
Korpusfreigabe; anderes ergibt 403 `access-denied`, eine einzelne Seite außerhalb der Freigabe 404 wie eine unbekannte.
Ohne konfigurierte Clients verweigert die API jeden Datenzugriff. Frei erreichbar sind nur Health, Info und die
API-Beschreibung. Für lokale Entwicklung ohne Anmeldung: `space-harvester.security.mode=development` zusammen mit dem
Profil `local`. Konfiguration und Regeln: [docs/SECURITY.md](docs/SECURITY.md).

**Seitenmetadaten:** Die Liste kommt live aus der Quelle (`origin: live`) und enthält alle Seiten des Space, auch
außerhalb des Baums der Startseite, ohne Gesamtgrenze. `nextCursor` wird an den nächsten Aufruf übergeben, bis er
`null` ist (`complete: true`). Ein Fehler beim Nachladen ist ein Fehler (z. B. 503), nie eine leere oder vollständige
Liste; ein Cursor gilt nur für seinen Space. Je Seite: `pageId`, `title`, `spaceKey`, `parentPageId` (`null` für
Wurzelseiten), `version`, `modifiedAt`, `url`, weitere Attribute der Quelle (etwa `confluence.labels`) und
`metadataHash` sowie `attributesHash`.

`metadataHash` (Schema `page-metadata/v1`, in `metadataHashSchema` mitgeliefert) ist `sha256:<hex>` über ein
kanonisches JSON-Objekt aus `schema`, `sourceId`, `pageId`, `spaceKey`, `title`, `parentPageId`, `version` und
`modifiedAt` in dieser Reihenfolge; unbekannte Werte sind `null`, Zeiten UTC nach ISO-8601, Zeichenketten unverändert
(keine Unicode-Normalisierung), UTF-8. Abrufzeit, URL und Attribute zählen nicht mit. Der Hash zeigt Änderungen dieser
Metadaten an, beweist aber keine Inhaltsänderung; ein Inhalts-Hash (`rawSha256`) kommt mit den Rohdaten (#8).

`attributesHash` (Schema `page-attributes/v1`, in `attributesHashSchema`) ist getrennt davon `sha256:<hex>` über
`{"schema":…,"sourceId":…,"pageId":…,"attributes":{…}}` mit allen `attributes` der Seite, nach Schlüssel sortiert;
fehlende Attribute fehlen auch im Objekt, Labels stehen sortiert und ohne Duplikate kommagetrennt. Eine reine
Labeländerung ändert nur `attributesHash`, nicht `metadataHash`. Confluence liefert als Attribute außerdem
`confluence.createdAt` und `confluence.createdBy` (aus `history`).

**Einzelne Seite:** `GET /api/v1/sources/{sourceId}/pages/{pageId}/metadata` liefert dieselben Felder und Hashes wie
die Liste (unter `page`, dazu `origin`, `retrievedAt` und die beiden Schemata), live und ohne Inhalt. Die Route kommt
ohne Space-Key aus: eine in einen anderen Space verschobene Seite behält ihre `pageId`, `spaceKey` zeigt den
aktuellen Space. Eine unbekannte oder nicht sichtbare Seite ist `source-object-not-found` (404), eine ID in fremder
Form `source-unsupported` (400).

**Seiten aus dem Cache (#27):** Seitenliste, Baum und native Einzelseite lesen nur aus dem Cache des Harvesters
(`origin: cache`), nie live. Jede Antwort nennt den Cache-Stand: `cache.generation`, `cache.state` (`confirmed` aus
einem vollständigen Abgleich, `incomplete`, `stale`), `syncedAt` und `lastCheckedAt`; ein Treffer im Cache gilt nie
als frisch. Liste und Baum derselben Generation enthalten dieselben Seiten. Die Liste ist aufsteigend nach `pageId`
sortiert; ein Stapel kann kürzer als `limit` oder leer sein, solange `nextCursor` nicht `null` ist. Ersetzt ein neuer
Abgleich die Generation, endet die Abfrage mit `cache-generation-changed` (409) und beginnt ohne `cursor` neu. Im Baum
erscheint jede Seite genau einmal; Seiten, deren Elternseite fehlt oder nicht sichtbar ist, und Elternzyklen werden
zu Wurzeln und stehen in `anomalies` (`missing-parent`, `cycle`). Bäume über `space-harvester.query.max-tree-nodes`
(Standard 50000) Seiten oder 200 Ebenen ergeben `tree-too-large` (422); sie liefert der Export. Die Einzelseite gibt die
gespeicherten Bytes unverändert zurück, Cache-Stand und Revision stehen in den Headern `X-Cache-Generation`,
`X-Cache-State`, `X-Cache-Synced-At`, `X-Cache-Last-Checked-At`, `X-Page-Revision` und `X-Native-Format`.
`view=extended` liefert stattdessen einen JSON-Container: `data` ist die gespeicherte Payload als JSON (dieselbe
Struktur, Zahlen ohne Stellenverlust, aber nicht bytegenau), `metadata` die aktuellen Kopfdaten wie in der Seitenliste
samt Hash-Schemas sowie `payloadRevision`, `payloadRetrievedAt`, `headerObservedAt` und `nativeFormat` (Kopfdaten
können neuer sein als die Payload) und `enrichments` die Anreicherungen (siehe unten). Harvester-Angaben stehen nie
in `data`. Ist die
Payload kein JSON, antwortet `view=extended` mit `payload-not-json` (422).
Jeder Abruf einer Einzelseite prüft zuerst live bei der Quelle Recht und Version, für jede Anmeldeart gleich
([ADR 0010](docs/adr/0010-export-ohne-vorbereiteten-bestand.md), Punkt 8): Ist sie unverändert, kommt der Cache-Stand (`X-Page-Refresh: current`). Bei neuen Kopfdaten und gleicher
Revision kommt die gespeicherte Payload mit den Kopfdaten der Quelle (`header-changed`), bei neuer Revision die live
geladene Payload (`content-changed`), die dabei im Cache abgelegt wird. Konnte sie nicht abgelegt werden (ein Abgleich
schreibt gerade), tragen Abweichungen vom Cache `X-Page-Origin: live` (bzw. `origin: live`) und den Cache-Zustand
`stale`. Mit Zeitfenster ([ADR 0013](docs/adr/0013-suche-ueber-den-cache.md), Punkt 4) entfällt die Rückfrage:
`space-harvester.cache.max-age` (Standard `0s`, im Core `Harvester.builder().cacheFreshness(…)`, höchstens 365 Tage)
lässt eine Seite ab ihrem Abruf (`retrievedAt`, mit dem Cache gespeichert, übersteht also einen Neustart) ohne
Rückfrage gelten; Beispiel: abgerufen um 10:00, `max-age: 6h`, bis 16:00 kommt die Seite ohne Frage an die Quelle aus
dem Cache. Danach prüft der Abruf die Version (`space-harvester.cache.version-check: true`, Standard), und eine
bestätigte Version öffnet ein neues Fenster; mit `false` wird die Seite stattdessen neu abgerufen. Die Rechte gelten
auch im Fenster, bei delegierter Anmeldung mit der Identität des Benutzers; `refresh=true` fragt immer. Ist die Quelle
für die Prüfung nicht erreichbar, kommt ohne `refresh` der Cache-Stand ungeprüft (ohne
`X-Page-Refresh`), aber nur, wenn die Zugriffspolicy die Seite freigibt; `refresh=true` liefert nie ungeprüft. Die
Rechte gelten im Space, den die Quelle meldet. Kennt die Quelle
die Seite nicht mehr, ist die Antwort `cache-miss` (404); antwortet sie nicht oder nicht in der Frist (30 s), kommt
`source-unavailable` (503) bzw. `source-access-denied`/`source-invalid-response` (502). Ohne angebundenen Inhaltsabruf
antwortet `refresh=true` mit `refresh-unavailable` (501). Seiten lassen sich auch über ihre URL abrufen
(`…/pages?url=…`): mit Seiten-ID, über Space und Titel (`/display/KEY/Titel`, live bei der Quelle), als Space-Startseite
(`/display/KEY`), als Bookmark `confluence://…`, als Ressourcen-ID der Quelle oder als Lesezeichen
`space-harvester://<Quelle>/page/<ID>[?version=…]` (#178). Eine Adresse, die auf keine lesbare Seite der Quelle zeigt
(anderer Server, unbekannter Titel, keine Freigabe), ist `cache-miss`; keine Adresse ist 400. Der Cache ist transparent
([ADR 0010](docs/adr/0010-export-ohne-vorbereiteten-bestand.md)): Liegt eine Seite nicht im Cache, wird sie auch ohne
`refresh` von der Quelle geladen, sofern der Aufrufer sie lesen darf (geprüft an den live gelesenen Kopfdaten, bevor
der Inhalt geladen wird), im gemeinsamen Cache abgelegt, angereichert und ausgeliefert. `cache-miss` (404) heißt
dann: auch die Quelle kennt sie nicht oder zeigt sie dem Aufrufer nicht; ist die Quelle nicht erreichbar, kommt
`source-unavailable` (503); ohne eingerichteten Cache (#26) antworten die Routen mit `cache-unavailable` (503). Rechte (siehe
[docs/SECURITY.md](docs/SECURITY.md)): Liste und Baum verlangen `metadata` für den Space, sonst 403; die
Einzelseite verlangt in beiden Ansichten `content`. Liegt die Seite in einem Space ohne `content`-Freigabe, ist die Antwort `cache-miss`
wie bei einer unbekannten Seite.

**Anreicherungen (#29).** Deterministische Prozessoren ohne KI leiten aus einer konservativen Textprojektion des
Storage-Formats (`confluence-storage-text/v1`: Makros, Bilder und Verweise entfallen, nichts wird ausgeführt oder
aufgelöst) Hinweise ab: `acronyms` (Akronyme, Ausschreibung nur bei passenden Anfangsbuchstaben in Klammern; seit
Version 2 mit allen Fundstellen `occurrences`, lesbar über `PageAcronyms`) und
`outline` (Gliederung mit Wortzahlen), für deutschen Text außerdem `entities` (Begriffe aus Nominalgruppen mit
Häufigkeit) und `properties` (Eigenschaften, attributiv wie „die zuständige Stelle“ oder prädikativ wie „der Dienst ist
nicht erreichbar“) sowie `activities` (Handlungen je Teilsatz mit vorgeschlagener Grundform, Aktiv oder Passiv,
Anweisung oder Aussage und Beteiligten in den Rollen `ACTOR`, `AFFECTED_OBJECT`, `RECIPIENT`, `INSTRUMENT`) und
`relations` (Subjekt–Prädikat–Objekt aus den Handlungen, Begriffsrelationen `is_a`, `part_of`, `has_part`, `synonym` und
zeitliche Folge `before` nur bei Signalwörtern wie „anschließend“ oder „nachdem“). Die Sprachprozessoren arbeiten mit
festen Regeln und einem Lexikon der Funktionswörter, ohne Sprachmodell; Grundformen sind ungeprüfte Vorschläge, Pronomen
werden nicht aufgelöst. Jedes Ergebnis trägt Prozessor, Version, `inputSha256`, Revision, Zeit, Status und Warnungen und
liegt in einem eigenen Lucene-Index (`enrichments/` neben `pages/`), getrennt von der unveränderten Payload. Nach jedem
bestätigten Abgleich werden fehlende oder veraltete Ergebnisse berechnet und die entfernter Seiten gelöscht; scheitert
ein Prozessor, steht nur sein Ergebnis auf `failed`, Abgleich und Export laufen weiter. In `view=extended` steht je
Ergebnis `freshness` (`current` oder `stale`, wenn Text, Gliederung oder Prozessorversion sich geändert haben).
Anreicherungen sind aus dem Inhalt abgeleitet und verlangen darum wie die Payload `content`. Embeddings sind optional
und kommen nur von einem externen, OpenAI-kompatiblen KI-Dienst (`space-harvester.ai.*`): Er berechnet die Anreicherung
`embedding` im begrenzten Hintergrund, und nur für Quellen aus `space-harvester.ai.external-processing-sources`; ohne
diese Einstellungen ändert sich nichts ([docs/EMBEDDINGS.md](docs/EMBEDDINGS.md),
[ADR 0011](docs/adr/0011-embeddings-ueber-externen-ki-dienst.md)).

**Schlagwortsuche über den Cache ([ADR 0013](docs/adr/0013-suche-ueber-den-cache.md)):** Mit `text` sucht der
Harvester in drei Schritten. Erst findet jede Quelle, die selbst suchen kann (Confluence: CQL `siteSearch` über
`/rest/api/search` wie die Websuche, also Titel und Inhalt), Kandidaten; der Harvester liest ihre Kopfdaten live und
prüft an ihnen `metadata` für genau diese Seite. Dann lädt er die sichtbaren Kandidaten, deren Version nicht im Cache
liegt, mit den Rechten des Aufrufers in den Cache (alle des Stapels, gleiche Versionen nicht erneut; ohne vorherigen
Abgleich). Zuletzt sucht der Volltextindex über den Cache (BM25 über Titel und Lesetext, Auszug als Textfenster,
Verzeichnis `text/` neben dem Cache), auch für Quellen ohne eigene Suche wie lokale Dateien (`indexed` in `sources`,
ohne Cache `not-supported`). Nur von dort kommen Treffer (`origin: cache`); Ergebnislisten werden nicht
zusammengeführt. Jeder Treffer verlangt `metadata` und `content`, weil Fundstelle und Auszug aus dem Inhalt stammen.
Die Suche wartet nicht auf das Laden (Fortschreibung 18.10.): Alle Funde der Quellen kommen sofort als vorläufige
Treffer mit Titel und Auszug der Quelle in `found`, nach Titel und Auszug bewertet, wenn ein Reranker eingerichtet ist,
sonst in der Reihenfolge der Quelle; geladen wird in dieser Rangfolge, so viele, wie
`space-harvester.search.candidate-selection` auswählt (`adaptive`, Standard; `top-n`; `order`; ADR 0016) und
`space-harvester.search.loads` zulässt (Standard ohne Grenze).
Vorläufige Treffer brauchen die quellweite Freigabe für `metadata`, sonst werden sie wie bisher einzeln live geprüft;
den Auszug zeigen sie nur mit quellweitem `content`. `progress` (`id`, `total`, `loaded`, `failed`, `cancelled`,
`done`) zählt das Laden; `GET /api/v1/search/progress/{id}` fragt nach, `DELETE` bricht ab. Wer dieselbe Suche erneut
stellt, bekommt geladene Seiten als Treffer aus dem Cache in `hits`, der Rest bleibt in `found`: Kein Treffer
verschwindet, er rückt nur an seinen Platz (gleiche Identität aus Quelle und Seiten-ID). `done` heißt „Die Suche ist
abgeschlossen“. Was dann noch lädt, zählt `loading` und erscheint über `nextCursor`. Delegierte Benutzer warten
höchstens 5 s und laden nur während ihrer Anfrage. Jeder Treffer trägt Titel, Textauszug (`snippet`), `url`,
Version, Quelle und Space. `nextCursor` findet weiter (höchstens 1000 je Quelle) und sucht erneut im Cache, ohne
ausgelieferte Seiten zu wiederholen. `sources` nennt je Quelle `searched`, `indexed`, `not-supported` oder
`unavailable`; eine nicht erreichbare Quelle verwirft die anderen nicht, und `complete` ist dann `false`. Nie
Embeddings, keine semantische Suche. `spaceKey` (nur diese Spaces) geht als `space in (…)` und `excludeSpaceKey`
(ohne diese, wiederholbar) wie im Enterprise AI Client als `space not in (…)` in die CQL (#179), ohne Obergrenze der
Spaces, und gilt ebenso im Volltextindex. Im Core lädt `Harvester.builder().prefetchSearchHits(false)` nichts; dann
findet die Suche nur, was schon im Cache liegt.

**Progressive Suche ([ADR 0017](docs/adr/0017-progressive-suche.md), Core):** `Harvester.progressiveSearch()` sucht
die ursprüngliche Anfrage sofort und erzeugt gleichzeitig Varianten (zuerst mit allen Begriffen, dann schrittweise
weniger). Die Varianten laufen in Wellen, und jeder Fund wird gegen die ursprüngliche Anfrage bewertet. Die Suche
endet am Qualitätsziel des `SearchProfile` oder an Zeit- und Abfragebudget. `searchAsync` liefert die sortierte
Liste mit Abschlussgrund, ein `ProgressiveSearchListener` meldet unterwegs Varianten und die neu geordnete Liste.
Die Auswahl der Beschaffung und das Laden in Rangfolge beschreibt
[ADR 0016](docs/adr/0016-auswahl-beschaffung-hintergrund.md).

Über REST startet `POST /api/v1/search/progressive` dieselbe Suche, mit einem JSON-Körper: `q` (Pflicht, höchstens
200 Zeichen), `sourceId` oder `sourceIds`, `spaceKeys`, `excludeSpaceKeys` wie bei der Schlagwortsuche, dazu
`mode` (`quality`, Standard, oder `all-variants` für einen Agenten, der alle Varianten will), `maxVariants` (0 bis 32,
Standard 8), `minRelevantHits` (Standard 5), `minScore` (Standard 0,5), `timeBudgetMs` (bis 120000, Standard 30000),
`searchBudget` (Standard 12), `limitPerSearch` (1 bis 100, Standard 20), `expansion` (`ai`, Standard, ohne
`ai.chat-model` wie `rules`; oder `rules`) und `wait`. Ohne `wait` antwortet der Dienst sofort mit 202, `Location` und
dem Stand; `GET /api/v1/search/progressive/{id}` fragt nach, bis `done` wahr ist, `DELETE` bricht ab (204, danach
`reason: cancelled`). Mit `wait: true` wartet die Anfrage bis zum Abschluss (höchstens Zeitbudget plus 5 s) und
antwortet mit 200; delegierte Benutzer warten immer. Der Stand nennt `id`, `status` (`running`, `done`), `done`,
`reason` (`null`, solange die Suche läuft; dann `quality-reached`, `all-variants-done`, `budget-exhausted`,
`cancelled` oder `failed`), `mode`, `expansion`, `hits` (je Treffer `sourceId`, `pageId`, `spaceKey`, `title`,
`snippet`, `url`, `version`, `modifiedAt`, `parentPageId`, `score` oder `null` ohne Bewertung, `foundBy` als Priorität
der findenden Suchanfrage, `confirmed`), `variants` (`text`, `priority`, `status`, `newHits`), `loads` (nach dem
Abschluss die Kennungen für `/api/v1/search/progress/{id}`) und `elapsedMs`. Jede Suche gehört ihrem Aufrufer; ein
anderer bekommt 404. Bekannt sind die letzten 256 Suchen, beendete 5 Minuten lang; laufen 256, antwortet der Dienst
mit 429 (`too-many-searches`). Rechte prüft die Schlagwortsuche je Suchanfrage: Nicht sichtbare Seiten erscheinen
weder als Treffer noch in einer Zahl.

```sh
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"q":"Wie läuft der nächtliche Betrieb?","mode":"all-variants","wait":true}' \
  "http://localhost:8080/api/v1/search/progressive"
```

Eine längere Frage (mehr als 6 Wörter) geht nicht als Satz an die Quelle: Wie im Enterprise AI Client werden daraus
zuerst ihre stärksten wörtlichen Begriffe (Kürzel wie `HaVdB` oder `BS-PIMA` in ihrer Schreibung), dann ihre
Schlüsselwörter einzeln und die ersten beiden zusammen, ohne Füll- und Fragewörter und ohne Dopplungen. Höchstens
`space-harvester.search.discovery.max-queries` Anfragen (Standard `4`, 1 bis 8; `1` schickt den Suchtext unverändert)
laufen je Quelle parallel; ihre Funde werden reihum zusammengeführt, jede Seite einmal. Ein knapper Suchausdruck geht
unverändert hinaus. Im Cache sucht weiter der Text des Aufrufers. Mit `space-harvester.ai.rerank-model` bewertet der
Cross-Encoder Titel und Auszug der Funde und bestimmt so, was zuerst in den Cache geladen wird
(`space-harvester.search.excerpt-ranking`, Standard `true`); er verwirft nichts, an ihn gehen nur Texte aus Quellen in
`external-processing-sources`, und die Rangfolge der Treffer bleibt die des Volltextindex. Scheitert er, gilt die
Reihenfolge der Quellen. Im Core: `Harvester.builder().discoveryQueries(int)` und `excerptRanking(boolean)`.

**Suche im Index (#28):** Ohne `text` sucht `GET /api/v1/search?sourceId=…`
in den gespeicherten Kopfdaten einer Quelle. Alle Bedingungen gelten zugleich: `title` (jedes Wort muss als ganzes Wort im Titel
vorkommen, ohne Beachtung der Groß-/Kleinschreibung; Wörter trennt jedes Zeichen, das weder Buchstabe noch Ziffer ist;
kein Präfix, keine Abfragesprache; höchstens 8 Wörter und 200 Zeichen), `spaceKey` (wiederholbar, höchstens 20),
`pageId`, `parentPageId` (direkte Kinder), `version`, `modifiedFrom` (einschließlich) und `modifiedBefore`
(ausschließlich). `sort` ist `title` (Standard), `-modifiedAt` oder `pageId`, jeweils danach `pageId` und `sourceId`;
`limit` 1 bis 100. Treffer haben `sourceId`, dieselben Felder wie die Seitenliste und den Cache-Stand ihres Space;
`index.generation` nennt den Indexstand. Wie bei der Seitenliste kann ein Stapel kürzer als `limit` sein; `nextCursor` gilt nur für dieselbe Anfrage
(sonst 400), ein neuer Indexstand ergibt `cache-generation-changed` (409). Rechte: mit `spaceKey` `metadata` für jeden
genannten Space, sonst für die Quelle; Treffer ohne Freigabe fallen weg. Ohne Index antwortet die Suche mit
`cache-miss` (404) bzw. ohne eingerichteten Index mit `cache-unavailable` (503).

**Globale Suche (#114, [ADR 0009](docs/adr/0009-globale-mehrquellensuche.md)):** Ohne `sourceId` durchsucht
`GET /api/v1/search` alle Quellen, für die der Aufrufer `metadata` hat und die im Index liegen (`scope: global`,
`sourceId: null`): eine Abfrage über den gemeinsamen Index, global sortiert und mit einem Cursor über die ganze
Treffermenge. Filter gelten in jeder Quelle (`spaceKey=DEV` heißt DEV in jeder freigegebenen Quelle); nicht
freigegebene Quellen fallen ohne 403 und ohne Spur weg. Jeder Treffer nennt seine `sourceId`. Ändert sich eine der
gelesenen Quellen, endet das Weiterblättern mit 409; ohne lesbare indexierte Quelle ist die Antwort leer
(`index: null`), nie 404. `sourceIds` (wiederholbar oder durch Komma getrennt, höchstens 50) beschränkt die globale
Suche auf diese Quellen; unbekannte oder nicht freigegebene IDs darin fallen ebenso still weg, zusammen mit `sourceId`
ist es 400. Die Desktop-App nutzt denselben Core-Anwendungsfall.

```sh
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/search?title=Architektur"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/search?title=Architektur&sourceIds=confluence,wiki2"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/search?sort=relevance&q=Softwarearchitektur"
```

**Suche nach Relevanz (#89), experimentell:** die semantische Suche; die Standardsuche ist die Schlagwortsuche oben.
`sort=relevance` mit `q` (Frage in natürlicher Sprache, höchstens 500 Zeichen; ohne `q`
die Wörter aus `title`) liefert genau einen Stapel (kein `cursor`; `complete=false` nur, wenn ein Kandidat bei der Quelle nicht prüfbar war) aus höchstens 100 Kandidaten der
Vektorsuche (global ein gemeinsames Fenster über alle Quellen, keine Top-K je Quelle), geordnet nach der
Kosinusähnlichkeit zu den gespeicherten Embeddings. Ergebnislisten werden nicht zusammengeführt, Volltext- und
Titeltreffer kommen nicht hinzu ([ADR 0013](docs/adr/0013-suche-ueber-den-cache.md)). Alle Filter gelten
weiter. Kandidaten der Vektorsuche brauchen `content` für ihre Seite; `relevance` (Ähnlichkeit, Abschnitt, `freshness`)
steht nur an Treffern mit `content`-Freigabe. Jeder Treffer wird vor der Auslieferung live bei der Quelle auf Recht und Version geprüft (ADR 0011,
Punkt 7). Ohne eingerichteten KI-Dienst, oder wenn er die Anfrage nicht
einbetten kann, antwortet die Suche immer mit `semantic-search-unavailable` (503), auch mit `title`; es gibt keinen
stillen Rückfall auf die Titelsuche (ADR 0011). Einzelheiten:
[docs/EMBEDDINGS.md](docs/EMBEDDINGS.md#suchen).

**Space-Fingerprint:** `spaceFingerprint` (Schema `space-inventory/v1`) ist `sha256:<hex>` über
`{"schema":…,"sourceId":…,"spaceKey":…,"pages":[{"pageId":…,"metadataHash":…,"attributesHash":…},…]}` mit den Seiten
nach `pageId` sortiert (Zeichenkettenordnung), Escaping und UTF-8 wie oben. Er entsteht nur aus einer vollständig und
widerspruchsfrei gelisteten Inventur und spart den Seitenabgleich, nicht das Listen bei der Quelle. Die
Synchronisierung dazu (Paket `application.sync`, [#16](https://github.com/aresstack/space-harvester/issues/16)):
abgebrochene oder widersprüchliche Inventuren bestätigen keine Generation und entfernen nichts; eine fehlende Seite
wird erst entfernt, wenn die Quelle sie einzeln als nicht vorhanden oder in einen anderen Space verschoben meldet.

**Abgleich als Auftrag (#16):** `POST …/spaces/{spaceKey}/syncs` verlangt die Freigabe `sync` für den Space und
antwortet sofort mit 202. Der Lauf lädt nur neue und geänderte Seiten, übernimmt reine Metadaten- und Labeländerungen
ohne Inhaltsabruf und entfernt fehlende Seiten erst nach Einzelprüfung: Bestätigt die Quelle die Löschung (lokale
Dateien), fällt die Seite samt Indizes weg (`removed`); meldet sie dem technischen Konto nur „nicht gefunden“
(Confluence), ist die Seite sofort für jede Ausgabe gesperrt und aus den Indizes genommen (`withheld`), ihre Rohdaten
entfernt erst ein Lauf nach `space-harvester.sync.withheld-retention` (Standard `30d`, `purged`); ein verweigerter
Zugriff ändert nichts ([ADR 0014](docs/adr/0014-abgleich-berechtigung-suche-getrennt.md)). Leser sehen seine Änderungen erst zusammen mit
der neuen Generation; ein abgebrochener, unvollständiger oder überholter Lauf hinterlässt nichts. `state` ist
`completed` nur mit neu bestätigter Generation, in der jede Seite abgeglichen ist; sonst `failed` mit `error.code`
`SYNC_INCOMPLETE` (nichts übernommen), `SYNC_CONFLICT` (nichts übernommen) oder `SYNC_PARTIAL` (Generation bestätigt,
aber Seiten nicht abgeglichen: neue ohne Inhalt, `summary.complete: false`, Cache-Stand `incomplete`; oder bekannte mit
altem Stand, Cache-Stand `stale`; nie `confirmed`, der nächste Lauf gleicht sie erneut ab), oder `cancelled`.
`summary` nennt das Delta (Zähler je Änderungsart, `failed`, `missing`, `generation`, `complete`, `spaceFingerprint`,
`notConfirmed` als fester Grund; `failedPageIds` nur mit `metadata`-Freigabe). Je Space läuft höchstens ein Abgleich (`sync-already-running`, 409); nur der
auslösende Client sieht ihn, andere bekommen `sync-not-found` (404). Solange Cache-Speicher (#26) oder Inhaltsabruf
(#8) nicht angebunden sind, antwortet der Start mit `sync-unavailable` (503). Bekannt ist beim Abgleich, was die
Generation führt und was der Cache schon kennt, etwa durch Suche oder Abruf; solche Seiten werden nur bei neuer Version
erneut geladen. Mit `loadUnknown=false` gleicht der Lauf ohne Erstbefüllung ab: Er prüft, aktualisiert und bereinigt nur
Bekanntes und lädt keine unbekannten Seiten (`summary.notLoaded`, Teil von `missing`, `complete: false`)
([ADR 0014](docs/adr/0014-abgleich-berechtigung-suche-getrennt.md)). Einen regelmäßigen Abgleich plant
`space-harvester.sync.interval` (mindestens `PT1M`, etwa `PT30M`) für die Spaces in `space-harvester.sync.spaces` (je
`<sourceId>/<spaceKey>`), mit `space-harvester.sync.load-unknown` (Standard `false`: unbekannte Seiten nur in den Katalog; `true` hält den Space vollständig); ein noch
laufender Abgleich wird nicht doppelt gestartet. `GET /api/v1/reconciliations` zeigt je eingeplantem Space (nur mit
`sync`-Freigabe) Abstand, letzten Start samt Ergebnis und nächsten Start; ohne Einplanung behauptet der Dienst keine
lückenlose Aktualität. Verpasste Änderungen holt `?listing=changes` nach: eine Änderungssuche seit dem letzten
Lauf (oder seit `since`, ISO 8601) und eine Papierkorbabfrage je Space statt einer Inventur. Bekannte Seiten mit neuer
Version werden neu geladen, unbekannte nur in den Katalog übernommen, Seiten im Papierkorb entfernt
(`summary.inventory: changes`). Kann die Quelle das nicht, läuft ein Abgleich ohne Erstbefüllung. Auf Wunsch holt der
Dienst das beim Start für jeden Space mit bestätigter Generation nach (`space-harvester.sync.catch-up-on-start`,
Standard `false`); ein Zeitplan holt ebenfalls nur nach. Alle Änderungsquellen sind optional: Ohne sie beschafft und
aktualisiert die Suche die Seiten, die sie findet ([ADR 0014](docs/adr/0014-abgleich-berechtigung-suche-getrennt.md)). Den Stand
eines Space zeigt `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/catalog` ohne Quellenzugriff (`metadata`-Freigabe):
Katalogstatus `unknown`, `partial` oder `cataloged`, bekannte Seiten, Seiten mit Inhalt, veraltete und zurückgehaltene
sowie die bestätigte Generation. `GET …/sync-preview?mode=…` (`sync`-Freigabe) zeigt, was ein Abgleich ändern würde,
ohne etwas zu schreiben; die Seiten-IDs nur mit `metadata`-Freigabe.

**Exporte (#11, #12):** `POST /api/v1/sources/{sourceId}/spaces/{spaceKey}/exports` startet einen Export und
antwortet sofort mit 202 und `Location: /api/v1/exports/{exportId}`. Ein vorheriger Abgleich ist nicht nötig: Liegt
der Space nicht oder nur unbestätigt im Cache, liest der Exportauftrag ihn zuerst selbst ein; mit `refresh=true` gleicht
er immer zuerst gegen die Quelle ab ([ADR 0010](docs/adr/0010-export-ohne-vorbereiteten-bestand.md)). Dort stehen Zustand (`queued`,
`running`, `completed`, `failed`, `cancelled`), Fortschritt und Fehlercode; `POST …/{exportId}/cancel` bricht
kooperativ ab. Nach Abschluss nennt `artifact` die Dateien `pages.ndjson` (flaches Seiten-Array, eine Seite je Zeile, im Kopf `pageId` und `url`),
`tree.json` (Baum aus Verweisen: `roots`, je Knoten `id` und `url` der Seite) und `manifest.json` mit Größe, SHA-256 und Download-Adresse
`…/{exportId}/artifact/{datei}`; die Rohdaten je Seite liegen unter `raw/` und werden Byte für Byte ausgeliefert.
`artifact.complete` ist nur `true` bei bestätigter Cache-Generation ohne fehlende, beim Einlesen gescheiterte oder für
den Aufrufer gesperrte Seite. Start, Status, Abbruch und
Download verlangen `export` und `content` für den Space; einen Export sieht nur der Client, der ihn gestartet hat,
alle anderen erhalten `job-not-found` (404) wie bei einer unbekannten ID. Zu viele offene Aufträge ergeben
`too-many-jobs` (429). `GET …/{exportId}/events` liefert die Diagnoseereignisse des Exports mit Phase, Fehlercode,
HTTP-Status, Proxy-Route, TLS-Befund und maskierter Ausnahmekette ([Auftragsdiagnose](docs/DIAGNOSTICS.md)).
Artefakte liegen unter `space-harvester.export.directory` (Standard `data/exports`) und
leben so lange wie ihr Auftrag (`space-harvester.jobs.retention`, Standard 7 Tage): beim Start des Dienstes und vor
jedem neuen Export werden Artefakte ohne Auftrag und liegen gebliebene Zwischenstände abgebrochener Läufe entfernt.

**Einmal beschaffen, beliebig oft konvertieren:** Aus dem gespeicherten Datenbestand eines abgeschlossenen Exports
(`pages.ndjson`, `raw/`, `tree.json`) entsteht jedes Format später erneut, ohne die Quelle zu lesen und ohne neuen
Export. `GET /api/v1/exports/{exportId}/dataset` nennt Seitenzahl, Space, Vollständigkeit, Stand der Bilder und die
Formate; `GET /api/v1/exports/{exportId}/conversions/{format}` liefert die Datei (`json`, `markdown`, `docx`, `pdf`) mit
denselben Rechten wie der Download. In der Bibliothek: `Harvester.conversions().convert(exportId, owner, format,
ordner)` schreibt die Datei in den Ordner des Aufrufers und überschreibt nie. Der Datenbestand selbst bleibt im
Datenverzeichnis; jede Payload wird vor dem Konvertieren gegen ihre SHA-256 geprüft (`export-dataset-damaged`, 409).

**Ausgabeformate (#30):** Standardformat ist JSON: ohne `format` erzeugt ein Export neben dem Raw-First-Artefakt
`pages.json`. `?format=…` (wiederholbar, z. B. `format=markdown&format=pdf`) wählt die Formate selbst; sie entstehen
am Ende des Exports als abgeleitete Dateien aus denselben Seiten; `json` schreibt `pages.json`, ein JSON-Array mit einem Objekt je Seite (`header`, `depth`, `revision`,
`rawSha256`, `content`), linear in Baumreihenfolge und ohne eingebetteten Baum; `markdown` schreibt `pages.md` aus dem
Storage-Format, `docx` `pages.docx` (Word-Exporter des Enterprise AI Client mit Inhaltsverzeichnis) und `pdf`
`pages.pdf`, Bilder und Anhänge jeweils nur als Verweise
([Formate und Formatverluste](docs/CONVERTERS.md)). Converter lesen nur die exportierten
Seiten aus dem Cache, nie Confluence, und ändern weder Cache noch Rohdaten. `manifest.json` nennt je Datei unter
`conversions` Format, Converter-Version und den Input-Hash `conversion-input/v1`; gleicher Hash und gleiche Version
ergeben dieselbe Datei. Ein unbekanntes Format ergibt `invalid-request` (400). Jede konvertierte Datei darf höchstens
`space-harvester.export.max-converted-bytes` groß werden (Standard 512 MiB), sonst scheitert der Export mit
`CONVERSION_TOO_LARGE`, statt abzuschneiden. Alle Artefakte zusammen belegen höchstens
`space-harvester.export.max-total-bytes` (Standard 20 GiB); ist die Ablage voll, lehnt der Start mit
`export-storage-full` (507) ab, ein laufender Export scheitert mit `EXPORT_STORAGE_FULL`.

**Assets (#19):** Anhänge und referenzierte Bilder werden getrennt vom Seiten-JSON und nur auf Anfrage geladen, über
dieselbe Anmeldung, dieselben Zertifikate und denselben Proxy wie die Seiten; fremde URLs (`ri:url`) werden nie
abgerufen. Jede Anfrage liest die Anhangsliste live und prüft den aktuellen Space der Seite; ein Anhang, der nicht zur
Seite gehört, ist `source-object-not-found` (404). Die Dateien liegen unter `space-harvester.assets.directory`
(Standard `data/assets`, nicht im Lucene-Index), je Inhalt einmal unter seinem SHA-256 und je Anhang und Version mit
Metadaten; ohne Versionsangabe der Quelle wird jedes Mal neu geladen. Dateien über `space-harvester.assets.max-size`
(Standard 50 MB) werden weder geladen noch gekürzt (`asset-too-large`, 502); der Download bricht schon im Transport an
dieser Grenze ab. Alle Inhalte zusammen belegen höchstens `space-harvester.assets.max-total-bytes` (Standard 20 GiB),
darüber `asset-store-full` (507), ohne etwas zu verdrängen. Leitet die Quelle einen Download auf einen anderen Host um,
wird dem nie gefolgt, der Anhang gilt dann als fehlgeschlagen; `options.foreignFileRedirects: true` ist ein
Konfigurationsfehler. Fehler je Anhang stehen mit festem Code im
Ernteergebnis und im Log; drosselt die Quelle, werden die übrigen Anhänge nicht mehr angefragt. Für Diagramme sind die
draw.io-Daten (`DIAGRAM`) und SVG (`VECTOR`) dem Vorschaubild vorzuziehen, das erst eine KI beschreiben müsste.
Bildbeschreibungen erzeugt ein optionaler `ImageDescriber` (z. B. ein Vision-Modell); sie hängen am Bild, nicht an der
Seite, gelten nur für genau diese Bildbytes und diese Modellversion und werden bei geändertem Bild oder Modell neu
erzeugt. Dasselbe Bild läuft für dasselbe Modell nie zweimal gleichzeitig; höchstens zwei Beschreibungen laufen
zugleich, eine weitere bekommt `image-description-busy` (429). Sie sind kein bestätigter Teil der Seite und so geschützt wie das Bild. Diagrammdaten und Dokumente werden nicht
beschrieben (`asset-not-an-image`).

Geplante Endpunkte: Sync-Stand und Space-Fingerprint im Cache
([#27](https://github.com/aresstack/space-harvester/issues/27)).

## Aufbau

Space Harvester ist ein Java-21-Gradle-Multimodulprojekt mit klar getrennten Schichten:

- **`space-harvester-core`** enthält Domäne, Anwendungsfälle und Ports sowie Quellenadapter, Lucene-Persistenz,
  Indexierung, Synchronisierung, Suche, Anreicherung und Exportlogik. Er benötigt keinen Spring-Kontext.
- **`space-harvester-app`** ist die eigenständige Swing-Oberfläche und nutzt die Ports und Anwendungsfälle des
  Core direkt. App-spezifische Dialoge und Präsentationslogik bleiben außerhalb des Core.
- **`space-harvester-server`** stellt dieselben fachlichen Fähigkeiten über Spring Boot, REST, Web-UI und
  Server-Sicherheitskonfiguration bereit. Die Server-Schicht enthält keine Desktop-Logik.
- **`parity-tests`** hält die Fähigkeiten im Vergleich zum Enterprise AI Client nachvollziehbar
  ([Testverfahren](docs/PARITY.md#vergleichstests)).

Abhängigkeiten verlaufen von den Oberflächen zu Anwendungsfällen und Ports; Infrastruktur implementiert Ports,
anstatt die Fachlogik an ein Framework zu binden. Die Architekturentscheidungen beschreiben die Grenzen und
bewussten Abweichungen im Detail ([ADRs](docs/adr/), [Module](docs/MODULES.md)).

Der Confluence-Adapter basiert auf den Bibliotheken des Enterprise AI Client. Die im Projekt geführte
`enterprise-ai-sources`-Kopie und ihre Upstream-Regeln sind unter
[UPSTREAM.md](enterprise-ai-sources/UPSTREAM.md) dokumentiert.
