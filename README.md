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
- **Noch nicht unterstützt:** OAuth und ein Spring Cloud Config Server.

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
| `GET /api/v1/sources/{sourceId}/pages/{pageId}` | Native Payload einer Seite aus dem Cache, Byte für Byte; Cache-Stand in Headern, `ETag` = `rawSha256` |
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
`refresh=true` liefert nie stattdessen den Cache-Stand; bis die Prüfung gegen die Quelle angebunden ist (#16, #8),
antwortet es mit `refresh-unavailable` (501). Was nicht im Cache liegt oder nicht sichtbar ist, ist `cache-miss`
(404); ohne eingerichteten Cache (#26) antworten die Routen mit `cache-unavailable` (503).

Geplante Endpunkte: Space-Fingerprint aus bestätigt vollständigen Läufen
([#16](https://github.com/aresstack/space-harvester/issues/16)), asynchrone Exporte
([#10](https://github.com/aresstack/space-harvester/issues/10)–[#12](https://github.com/aresstack/space-harvester/issues/12)).

## Aufbau

Ein Gradle-Projekt, geschichtet nach Paketen unter `com.aresstack.spaceharvester`:

- `api` – REST-Controller, Antwortmodelle, OpenAPI-Konfiguration
- `application` – Anwendungsfälle, frameworkneutral (kein Spring)
- `config` – Spring-Konfiguration, die Anwendungsfälle verdrahtet
- `source` – Quellenadapter; `source.confluence` baut den übernommenen Confluence-Adapter headless zusammen
- `output` – Exportformate und Artefakte (folgt mit #11)

`source` und `output` kennen weder `api` noch einander; `LayeringTest` (ArchUnit) prüft diese Regeln. Desktop- oder Swing-Code aus dem Enterprise AI Client
wird nicht übernommen.

Der Confluence-Code stammt unverändert aus dem Enterprise AI Client und liegt als Kopie im Gradle-Subprojekt
[`enterprise-ai-sources`](enterprise-ai-sources/UPSTREAM.md); dort stehen Herkunft, Regeln und der Abgleich. Der Build prüft per Prüfsummen, dass die Kopie unverändert ist.

Versionen stehen zentral in `gradle/libs.versions.toml` (Spring Boot 4.1.1, springdoc-openapi 3.1.1);
die Spring-Starter erhalten ihre Versionen aus der Spring-Boot-BOM. Das Jar wird reproduzierbar gebaut
(feste Zeitstempel und Dateireihenfolge, keine Build-Zeit in `build-info.properties`); der Wrapper prüft
die SHA-256 der Gradle-Distribution.
