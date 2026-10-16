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

## API-Übersicht

| Pfad | Zweck |
|---|---|
| `GET /api/v1/info` | Name, Version und API-Version des Dienstes |
| `GET /v3/api-docs` | OpenAPI-Dokument der REST-API (`/api/**`) |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /actuator/health` | Health; Probes unter `/actuator/health/liveness` und `/actuator/health/readiness` |
| `GET /actuator/info` | Build-Informationen |

Fehler werden als Problem Details (RFC 9457, `application/problem+json`) beantwortet.

Geplante Endpunkte: Spaces auflisten ([#4](https://github.com/aresstack/space-harvester/issues/4)),
Seitenmetadaten mit Hashwerten ([#5](https://github.com/aresstack/space-harvester/issues/5),
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
