# Space Harvester

Eigenständiger, headless Spring-Boot-Dienst (Java 21), der Wissensquellen erfasst und maschinenlesbar exportiert,
zunächst Confluence Data Center. Über eine REST-API sollen Spaces aufgelistet, Seitenmetadaten mit Versionsinformationen
und reproduzierbaren Hashwerten paginiert abgefragt und vollständige Spaces asynchron als JSON/NDJSON exportiert werden.

Die Umsetzung folgt der Roadmap in [#20](https://github.com/aresstack/space-harvester/issues/20).
Vorhandene Knowledge-Source-Adapter aus [enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client)
werden wiederverwendet (siehe [#2](https://github.com/aresstack/space-harvester/issues/2)).

## Voraussetzungen

- JDK 21
- Kein lokales Gradle nötig; der Gradle Wrapper (8.14.3) wird mitgeliefert.

## Bauen, testen, starten

```bash
./gradlew build        # kompiliert, testet und baut build/libs/space-harvester-<version>.jar
./gradlew bootRun      # startet den Dienst auf http://localhost:8080
```

Alternativ das gebaute Jar starten:

```bash
java -jar build/libs/space-harvester-0.1.0-SNAPSHOT.jar
```

Der Dienst startet ohne Confluence-Zugang. Der Port lässt sich mit `SPACE_HARVESTER_PORT` ändern.
Strukturierte JSON-Logs (Elastic Common Schema) liefert das Profil `json-logs`:

```bash
SPRING_PROFILES_ACTIVE=json-logs ./gradlew bootRun
```

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
  Confluence-Adapter; weitere Secret-Speicher wie Azure Key Vault (#22) kommen später daneben.
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
- **Noch nicht unterstützt:** OAuth und ein Spring Cloud Config Server. Proxy und PAC: siehe nächsten Abschnitt.

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
  gehen nur durch den Tunnel zum Ziel, nicht an den Proxy. Proxy-Authentifizierung wird noch nicht unterstützt.
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
     -cp build/libs/space-harvester-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher \
     --mode=pac-url-powershell https://confluence.example.org/
```

Der Transport bleibt vorerst der synchrone `HttpURLConnection`-Transport des übernommenen Confluence-Adapters.
Der vorgesehene asynchrone HTTP-Client und die dafür nötige Upstream-SPI beschreibt
[ADR 0001](docs/adr/0001-ausgehender-http-transport.md).

## Seiten-Cache (Lucene)

Native Seiten-Snapshots und Kopfdaten liegen in einem eingebetteten Apache-Lucene-Index im lokalen Dateisystem
(#26); es gibt keinen Datenbankserver. Ohne Verzeichnis läuft der Dienst ohne Cache.

```yaml
space-harvester:
  cache:
    directory: /var/lib/space-harvester/cache   # Indexverzeichnis
    max-payload-size: 20MB                      # Standard; größere Seiten werden abgewiesen, nie gekürzt
```

- **Inhalt:** je Seite (`sourceId` + `pageId`) die aktuellen Kopfdaten, der zuletzt gespeicherte Snapshot mit den
  Originalbytes der Antwort, ein Verlauf der Revisionen (ohne Payload) und je Space der Synchronisierungsstand.
  Feldschema und Version (`page-cache/v1`): `PageCacheSchema`. Zugangsdaten, Header oder Cookies stehen nie im Index.
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
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/pages/metadata` | Metadaten aller Seiten eines Space ohne Inhalte, live und stapelweise (`cursor`, `limit` 1–100) mit `metadataHash` und `attributesHash` |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/metadata` | Metadaten einer einzelnen Seite ohne Inhalt, live; kanonische Route ohne Space-Key |
| `GET /v3/api-docs` | OpenAPI-Dokument der REST-API (`/api/**`) |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /actuator/health` | Health; Probes unter `/actuator/health/liveness` und `/actuator/health/readiness` |
| `GET /actuator/info` | Build-Informationen |

Fehler werden als Problem Details (RFC 9457, `application/problem+json`) beantwortet. Bei Quellen unterscheidet
`type` die Fälle: `source-not-found` (404, unbekannte Quellen-ID), `source-access-denied` (502, Anmeldung
abgelehnt), `source-unavailable` (503, nicht erreichbar) und `source-invalid-response` (502). `detail` ist ein fester
Text je Fall; Meldungen der Quelle oder des Netzwerks gehen nicht an Clients und stehen nur im DEBUG-Log.

> **Nur für den isolierten Entwicklungsbetrieb.** Die REST-API hat noch keine Authentifizierung (folgt mit
> [#14](https://github.com/aresstack/space-harvester/issues/14)). Wer den Dienst erreicht, sieht über die
> konfigurierten Zugangsdaten alle Spaces der Quellen, auch deren Namen, die nicht automatisch für jeden freigegeben
> sind ([#24](https://github.com/aresstack/space-harvester/issues/24)). Den Dienst daher nicht offen im Netz betreiben.

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

**Space-Fingerprint:** `spaceFingerprint` (Schema `space-inventory/v1`) ist `sha256:<hex>` über
`{"schema":…,"sourceId":…,"spaceKey":…,"pages":[{"pageId":…,"metadataHash":…,"attributesHash":…},…]}` mit den Seiten
nach `pageId` sortiert (Zeichenkettenordnung), Escaping und UTF-8 wie oben. Er entsteht nur aus einer vollständig und
widerspruchsfrei gelisteten Inventur und spart den Seitenabgleich, nicht das Listen bei der Quelle. Die
Synchronisierung dazu (Paket `application.sync`, [#16](https://github.com/aresstack/space-harvester/issues/16)):
abgebrochene oder widersprüchliche Inventuren bestätigen keine Generation und entfernen nichts; eine fehlende Seite
wird erst entfernt, wenn die Quelle sie einzeln als nicht vorhanden oder in einen anderen Space verschoben meldet.

Geplante Endpunkte: Sync-Stand und Space-Fingerprint im Cache
([#16](https://github.com/aresstack/space-harvester/issues/16), [#27](https://github.com/aresstack/space-harvester/issues/27)), asynchrone Exporte
([#10](https://github.com/aresstack/space-harvester/issues/10)–[#12](https://github.com/aresstack/space-harvester/issues/12)).

## Aufbau

Ein Gradle-Projekt, geschichtet nach Paketen unter `com.aresstack.spaceharvester`:

- `api` – REST-Controller, Antwortmodelle, OpenAPI-Konfiguration
- `application` – Anwendungsfälle, frameworkneutral (kein Spring)
- `config` – Spring-Konfiguration, die Anwendungsfälle verdrahtet
- `source` – Quellenadapter; `source.confluence` baut den übernommenen Confluence-Adapter headless zusammen,
  `source.network` bestimmt die Proxy-Route je Ziel-URL
- `output` – Exportformate und Artefakte (folgt mit #11)
- `persistence` – Speicher hinter den Ports aus `application`; `persistence.lucene` ist der Seiten-Cache

`source` und `output` kennen weder `api` noch einander, `persistence` nur `application`, Lucene steht nur in
`persistence.lucene`; `LayeringTest` (ArchUnit) prüft diese Regeln. Desktop- oder Swing-Code aus dem Enterprise AI Client
wird nicht übernommen.

Der Confluence-Code stammt unverändert aus dem Enterprise AI Client und liegt als Kopie im Gradle-Subprojekt
[`enterprise-ai-sources`](enterprise-ai-sources/UPSTREAM.md); dort stehen Herkunft, Regeln und der Abgleich. Der Build prüft per Prüfsummen, dass die Kopie unverändert ist.

Versionen stehen zentral in `gradle/libs.versions.toml` (Spring Boot 4.1.1, springdoc-openapi 3.1.1);
die Spring-Starter erhalten ihre Versionen aus der Spring-Boot-BOM. Das Jar wird reproduzierbar gebaut
(feste Zeitstempel und Dateireihenfolge, keine Build-Zeit in `build-info.properties`); der Wrapper prüft
die SHA-256 der Gradle-Distribution.
