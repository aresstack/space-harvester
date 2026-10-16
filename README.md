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
`config/application-local.yml` (von Git ignoriert, wird mit `--spring.profiles.active=local` geladen). Zugangsdaten
kommen über Platzhalter aus Umgebungsvariablen, nie aus Dateien im Repository:

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

- **Anmeldearten:** `none` (anonym, Standard), `basic` (Benutzername und Passwort als HTTP Basic) und `pat`
  (Personal Access Token als `Authorization: Bearer`). Die Zugangsdaten gehen über einen `SecretProvider` an den
  Confluence-Adapter; weitere Secret-Speicher wie Azure Key Vault (#22) kommen später daneben.
- **Prüfung beim Start:** Fehlerhafte Quellen verhindern den Start; die Meldung nennt jede Quelle und jedes Feld.
  Eine nicht erreichbare Quelle verhindert den Start nicht, Verbindungen entstehen erst bei Anfragen.
- **Keine Geheimnisse nach außen:** Passwort und Token erscheinen weder in API-Antworten noch in Logs.
- **Noch nicht unterstützt:** Client-Zertifikate (mTLS), OAuth und ein Spring Cloud Config Server.

## API-Übersicht

| Pfad | Zweck |
|---|---|
| `GET /api/v1/info` | Name, Version und API-Version des Dienstes |
| `GET /api/v1/sources` | Konfigurierte Quellen (ID, Typ, Titel; ohne Verbindungsdaten) |
| `GET /api/v1/sources/{sourceId}/spaces` | Alle sichtbaren Spaces einer Quelle (Key, Name, Startseite), ungekürzt |
| `GET /v3/api-docs` | OpenAPI-Dokument der REST-API (`/api/**`) |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /actuator/health` | Health; Probes unter `/actuator/health/liveness` und `/actuator/health/readiness` |
| `GET /actuator/info` | Build-Informationen |

Fehler werden als Problem Details (RFC 9457, `application/problem+json`) beantwortet. Bei Quellen unterscheidet
`type` die Fälle: `source-not-found` (404, unbekannte Quellen-ID), `source-access-denied` (502, Anmeldung
abgelehnt), `source-unavailable` (503, nicht erreichbar) und `source-invalid-response` (502).

Geplante Endpunkte: Seitenmetadaten mit Hashwerten ([#5](https://github.com/aresstack/space-harvester/issues/5),
[#6](https://github.com/aresstack/space-harvester/issues/6)), asynchrone Exporte
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
