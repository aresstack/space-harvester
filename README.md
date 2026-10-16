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

Der Dienst startet ohne Confluence-Zugang. Der Port lässt sich mit `SPACE_HARVESTER_PORT` ändern. Die API verlangt
ein Bearer-Token eines technischen Clients ([docs/SECURITY.md](docs/SECURITY.md)); lokal ohne Anmeldung:

```bash
SPRING_PROFILES_ACTIVE=local SPACE_HARVESTER_SECURITY_MODE=development ./gradlew bootRun
```
Ohne echte Confluence lässt sich der Dienst mit einer Fake-Confluence und dem Profil `demo` ausprobieren; die
geprüften Curl-Beispiele dazu stehen in [docs/E2E.md](docs/E2E.md).

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
    directory: /var/lib/space-harvester/cache   # Wurzel: Seiten-Index in pages/, Aufträge in jobs/
    max-payload-size: 20MB                      # Standard; größere Seiten werden abgewiesen, nie gekürzt
    search-timeout: 5s                          # Standard; längere Suchen (#28) brechen mit 503 ab
```

- **Inhalt:** je Seite (`sourceId` + `pageId`) die aktuellen Kopfdaten, der zuletzt gespeicherte Snapshot mit den
  Originalbytes der Antwort, ein Verlauf der Revisionen (ohne Payload) und je Space die zuletzt bestätigte Generation
  der Synchronisierung.
  Feldschema und Version (`page-cache/v3`): `PageCacheSchema`. Zugangsdaten, Header oder Cookies stehen nie im Index.
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
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/pages/metadata` | Metadaten aller Seiten eines Space ohne Inhalte, live und stapelweise (`cursor`, `limit` 1–100) mit `metadataHash` und `attributesHash` |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/metadata` | Metadaten einer einzelnen Seite ohne Inhalt, live; kanonische Route ohne Space-Key |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/pages` | Flache Seitenliste aus dem Cache, ohne Inhalte, stapelweise (`cursor`, `limit` 1–100), mit Cache-Stand |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/tree` | Seitenbaum aus dem Cache als `roots[]` mit `id` und `children[]`; `include=metadata` ergänzt Titel, Version, Änderungszeit |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}` | Native Payload einer Seite aus dem Cache, Byte für Byte; Cache-Stand in Headern, `ETag` = `rawSha256`. `view=extended`: Container mit `data`, `metadata`, `enrichments` |
| `POST /api/v1/sources/{sourceId}/spaces/{spaceKey}/syncs` | Abgleich eines Space mit der Quelle als Hintergrundauftrag starten (202, `Location` auf den Status) |
| `GET /api/v1/syncs/{syncId}`, `POST /api/v1/syncs/{syncId}/cancel` | Status samt Delta eines eigenen Abgleichs, Abbruch |
| `GET /api/v1/search?sourceId=…` | Titel- und Metadatensuche über den Index (`title`, `spaceKey`, `pageId`, `parentPageId`, `version`, `modifiedFrom`, `modifiedBefore`), stapelweise |
| `GET /v3/api-docs` | OpenAPI-Dokument der REST-API (`/api/**`) |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /actuator/health` | Health; Probes unter `/actuator/health/liveness` und `/actuator/health/readiness` |
| `GET /actuator/info` | Build-Informationen |

Fehler werden als Problem Details (RFC 9457, `application/problem+json`) beantwortet. Bei Quellen unterscheidet
`type` die Fälle: `access-denied` (403, keine Freigabe oder unbekannte Quellen-ID), `source-access-denied` (502, Anmeldung
abgelehnt), `source-unavailable` (503, nicht erreichbar) und `source-invalid-response` (502). `detail` ist ein fester
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
können neuer sein als die Payload), `enrichments` bleibt bis #29 leer. Harvester-Angaben stehen nie in `data`. Ist die
Payload kein JSON, antwortet `view=extended` mit `payload-not-json` (422).
`refresh=true` liefert nie stattdessen den Cache-Stand; bis die Prüfung gegen die Quelle angebunden ist (#16, #8),
antwortet es mit `refresh-unavailable` (501). Was nicht im Cache liegt, ist `cache-miss`
(404); ohne eingerichteten Cache (#26) antworten die Routen mit `cache-unavailable` (503). Rechte (siehe
[docs/SECURITY.md](docs/SECURITY.md)): Liste und Baum verlangen `metadata` für den Space, sonst 403; die
Einzelseite verlangt in beiden Ansichten `content`. Liegt die Seite in einem Space ohne `content`-Freigabe, ist die Antwort `cache-miss`
wie bei einer unbekannten Seite.

**Suche (#28, erste Ausbaustufe):** `GET /api/v1/search?sourceId=…` sucht in den gespeicherten Kopfdaten einer
Quelle, nie live und nie im Inhalt. Alle Bedingungen gelten zugleich: `title` (jedes Wort muss als ganzes Wort im Titel
vorkommen, ohne Beachtung der Groß-/Kleinschreibung; Wörter trennt jedes Zeichen, das weder Buchstabe noch Ziffer ist;
kein Präfix, keine Abfragesprache; höchstens 8 Wörter und 200 Zeichen), `spaceKey` (wiederholbar, höchstens 20),
`pageId`, `parentPageId` (direkte Kinder), `version`, `modifiedFrom` (einschließlich) und `modifiedBefore`
(ausschließlich). `sort` ist `title` (Standard), `-modifiedAt` oder `pageId`, jeweils danach `pageId`; `limit` 1 bis
100. Treffer haben dieselben Felder wie die Seitenliste und den Cache-Stand ihres Space; `index.generation` nennt den
Indexstand. Wie bei der Seitenliste kann ein Stapel kürzer als `limit` sein; `nextCursor` gilt nur für dieselbe Anfrage
(sonst 400), ein neuer Indexstand ergibt `cache-generation-changed` (409). Rechte: mit `spaceKey` `metadata` für jeden
genannten Space, sonst für die Quelle; Treffer ohne Freigabe fallen weg. Ohne Index antwortet die Suche mit
`cache-miss` (404) bzw. ohne eingerichteten Index mit `cache-unavailable` (503).

**Space-Fingerprint:** `spaceFingerprint` (Schema `space-inventory/v1`) ist `sha256:<hex>` über
`{"schema":…,"sourceId":…,"spaceKey":…,"pages":[{"pageId":…,"metadataHash":…,"attributesHash":…},…]}` mit den Seiten
nach `pageId` sortiert (Zeichenkettenordnung), Escaping und UTF-8 wie oben. Er entsteht nur aus einer vollständig und
widerspruchsfrei gelisteten Inventur und spart den Seitenabgleich, nicht das Listen bei der Quelle. Die
Synchronisierung dazu (Paket `application.sync`, [#16](https://github.com/aresstack/space-harvester/issues/16)):
abgebrochene oder widersprüchliche Inventuren bestätigen keine Generation und entfernen nichts; eine fehlende Seite
wird erst entfernt, wenn die Quelle sie einzeln als nicht vorhanden oder in einen anderen Space verschoben meldet.

**Abgleich als Auftrag (#16):** `POST …/spaces/{spaceKey}/syncs` verlangt die Freigabe `sync` für den Space und
antwortet sofort mit 202. Der Lauf lädt nur neue und geänderte Seiten, übernimmt reine Metadaten- und Labeländerungen
ohne Inhaltsabruf und entfernt fehlende Seiten erst nach Einzelprüfung. Leser sehen seine Änderungen erst zusammen mit
der neuen Generation; ein abgebrochener, unvollständiger oder überholter Lauf hinterlässt nichts. `state` ist
`completed` nur mit neu bestätigter Generation, in der jede Seite abgeglichen ist; sonst `failed` mit `error.code`
`SYNC_INCOMPLETE` (nichts übernommen), `SYNC_CONFLICT` (nichts übernommen) oder `SYNC_PARTIAL` (Generation bestätigt,
aber Seiten nicht abgeglichen: neue ohne Inhalt, `summary.complete: false`, Cache-Stand `incomplete`; oder bekannte mit
altem Stand, Cache-Stand `stale`; nie `confirmed`, der nächste Lauf gleicht sie erneut ab), oder `cancelled`.
`summary` nennt das Delta (Zähler je Änderungsart, `failed`, `missing`, `generation`, `complete`, `spaceFingerprint`,
`notConfirmed` als fester Grund; `failedPageIds` nur mit `metadata`-Freigabe). Je Space läuft höchstens ein Abgleich (`sync-already-running`, 409); nur der
auslösende Client sieht ihn, andere bekommen `sync-not-found` (404). Solange Cache-Speicher (#26) oder Inhaltsabruf
(#8) nicht angebunden sind, antwortet der Start mit `sync-unavailable` (503). Einen Hintergrundabgleich plant
`space-harvester.sync.interval` (etwa `PT30M`) für die Spaces in `space-harvester.sync.spaces` (je `<sourceId>/<spaceKey>`);
ein noch laufender Abgleich wird nicht doppelt gestartet.

**Exporte (#11, #12):** `POST /api/v1/sources/{sourceId}/spaces/{spaceKey}/exports` startet einen Export aus dem
Cache und antwortet sofort mit 202 und `Location: /api/v1/exports/{exportId}`. Dort stehen Zustand (`queued`,
`running`, `completed`, `failed`, `cancelled`), Fortschritt und Fehlercode; `POST …/{exportId}/cancel` bricht
kooperativ ab. Nach Abschluss nennt `artifact` die Dateien `pages.ndjson` (flaches Seiten-Array, eine Seite je Zeile),
`tree.json` (ID-Baum mit `roots`) und `manifest.json` mit Größe, SHA-256 und Download-Adresse
`…/{exportId}/artifact/{datei}`; die Rohdaten je Seite liegen unter `raw/` und werden Byte für Byte ausgeliefert.
`artifact.complete` ist nur `true` bei bestätigter Cache-Generation ohne fehlende Seite. Start, Status, Abbruch und
Download verlangen `export` und `content` für den Space; einen Export sieht nur der Client, der ihn gestartet hat,
alle anderen erhalten `job-not-found` (404) wie bei einer unbekannten ID. Zu viele offene Aufträge ergeben
`too-many-jobs` (429). Artefakte liegen unter `space-harvester.export.directory` (Standard `data/exports`) und
leben so lange wie ihr Auftrag (`space-harvester.jobs.retention`, Standard 7 Tage): beim Start des Dienstes und vor
jedem neuen Export werden Artefakte ohne Auftrag und liegen gebliebene Zwischenstände abgebrochener Läufe entfernt.

Geplante Endpunkte: Sync-Stand und Space-Fingerprint im Cache
([#27](https://github.com/aresstack/space-harvester/issues/27)).

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
