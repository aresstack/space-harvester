# Konfiguration: lokale YAML, optionaler Config Server, Reihenfolge

**Stand:** 16.10.2026 · Issue [#3](https://github.com/aresstack/space-harvester/issues/3). Quellen, Anmeldearten und
mTLS: [README](../README.md#quellen-konfigurieren); Secrets und Azure Key Vault: [SECRETS.md](SECRETS.md); Proxy:
README, Abschnitt „Proxy und PAC/WPAD“.

## Lokal, ohne Server

Der Dienst braucht nur `application.yml` und optional eine eigene Datei wie `config/application-local.yml` (von Git
ignoriert, Profil `local`). Eine Demo ohne produktive Secrets mit Fake-Confluence: Profil `demo`
(`config/application-demo.yml`, Ablauf in [E2E.md](E2E.md)).

Die Quellen-ID (`space-harvester.sources[].id`) und die Verbindungsparameter stehen in der Konfiguration; Space-Keys
sind fachliche Daten und kommen nur in Anfragen (`/sources/{sourceId}/spaces/{spaceKey}/…`) und Freigaben vor.

## Optionaler Spring Cloud Config Server

Mit dem Profil `config-server` holt der Dienst beim Start zusätzlich Konfiguration von einem Config Server:

```bash
SPRING_PROFILES_ACTIVE=config-server,azure-keyvault \
SPRING_CLOUD_CONFIG_URI=https://config.example.org \
java -jar space-harvester.jar
```

- Gefragt wird `GET {SPRING_CLOUD_CONFIG_URI}/space-harvester/{aktive Profile}`; Anmeldung am Config Server über
  `spring.cloud.config.username`/`password` bzw. `headers` (Werte wieder als Platzhalter; nie als
  `user:passwort@` in `SPRING_CLOUD_CONFIG_URI`, weil die URL in Umgebung und Logs landen kann).
- Der Import ist `optional:`. Ist der Server nicht erreichbar, startet der Dienst mit der lokalen Konfiguration. Wer ihn verpflichtend will, setzt `spring.cloud.config.fail-fast=true`.
- Ohne das Profil wird kein Config Server gefragt. Statt des Profils geht auch direkt
  `spring.config.import=optional:configserver:https://…`.
- **Secrets gehören nicht in den Config Server.** Die Remote-YAML enthält Platzhalter wie `token: ${CONFLUENCE_PAT}`;
  der Harvester löst sie selbst auf (Umgebungsvariable, lokale Datei oder mit Profil `azure-keyvault` aus dem Vault).
  Ein Platzhalter ohne Wert verhindert den Start.
- Eingebunden ist der offizielle Spring Cloud Config Client aus Spring Cloud 2025.1. Für Spring Boot 4.1 gibt es
  Spring Cloud (2026.0) bisher nur als Meilenstein; der Client ist schmal und durch `ConfigServerImportTest` gegen
  einen nachgebildeten Server geprüft. Bei 2026.0 GA wird die Version in `gradle/libs.versions.toml` umgestellt.

## Laufzeitkonfiguration im Server

KI-Dienst, Suche, Synchronisierung und Speicherort bilden die Laufzeitkonfiguration (ADR 0015) mit fortlaufender
Version; der Start ist Version 1. Gelesen wird sie beim Start aus `space-harvester.ai.*`, `space-harvester.search.*`,
`space-harvester.sync.*`, `space-harvester.cache.directory`/`max-age`/`version-check` und `space-harvester.network.*`.

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `space-harvester.search.semantic` | `false` | semantische Suche erlaubt (wirkt nur mit Embedding-Modell) |
| `space-harvester.search.rerank` | `true` | Treffer über das Rerank-Modell umordnen, wenn eines eingestellt ist |
| `space-harvester.search.prefetch-hits` | `true` | Treffer der nativen Quellensuche zuerst in den Cache laden |

- `GET /api/v1/configuration`: wirksame Version, `activatedAt` und die Werte. Vom API-Key und vom Proxy-Passwort
  steht nur `apiKeySet` bzw. `passwordSet`; Adressen ohne Query.
- `POST /api/v1/configuration`: ein JSON-Objekt flacher Schlüssel mit oder ohne Präfix, z. B.
  `{"ai.embedding-model":"text-embedding-3-small","ai.embedding-dimensions":"1536","sync.interval":"P1D"}`. Die
  Werte überlagern die wirksame Konfiguration; nicht genannte bleiben, auch der API-Key. Leerer Wert oder `null`
  setzt zurück, `sync.spaces[0]` ersetzt die ganze Liste. Antwort 200 mit `outcome` (`ACTIVATED`, `UNCHANGED`),
  `version` und `restartRequired`; 400 `configuration-rejected` mit `problems` (`key`, `message`, nie der Wert).
  `?dryRun=true` prüft nur (`outcome` `VALID`). Proxy und TLS (`network.*`) und unbekannte Schlüssel lehnt der Endpunkt
  ab; sie wechseln über die Konfigurationsquelle.
- `cache.directory` wirkt erst nach einem Neustart (`restartRequired: true`); bis dahin bleibt der bisherige Ort.
- Beide Routen verlangen einen Client mit `manage-configuration: true` ([SECURITY.md](SECURITY.md)).
- Mit dem Config Server: Löst ein Refresh ein `EnvironmentChangeEvent` mit Schlüsseln unter `space-harvester.` aus,
  liest der Dienst die Konfiguration neu und aktiviert sie; das Log nennt nur Version und Schlüssel. Einen
  Refresh-Endpunkt gibt der Dienst nicht frei. Was zuvor per `POST` geändert wurde, überschreibt der Refresh.
- Die bestehenden Bausteine (Embeddings, Reranker, Zeitplan) lesen ihre Properties noch beim Start; sie melden sich
  erst nach und nach beim `ConfigurationManager` an. Bis dahin ist eine neue Version gespeichert und abrufbar, aber
  noch nicht überall wirksam.

## Reihenfolge der Quellen

Höhere zuerst, geprüft in `ConfigServerImportTest` und `AzureKeyVaultSecretsTest`:

1. Kommandozeile (`--…`)
2. Java-Systemeigenschaften, Umgebungsvariablen
3. Config Server (Profil `config-server`) und Azure Key Vault (Profil `azure-keyvault`); der Vault ist nur für
   Platzhalter gedacht, daher sollten sich beide nie dieselbe Einstellung teilen
4. `application-<profil>.yml` (auch `config/`), dann `application.yml`

Remote und lokal schließen sich also nicht aus: Der Config Server überschreibt die Dateien, Umgebung und Kommandozeile
überschreiben den Config Server.

**Achtung bei Listen:** Spring nimmt eine Liste wie `space-harvester.sources` oder `space-harvester.security.clients`
vollständig aus der Quelle mit dem höchsten Rang, der überhaupt ein Element davon setzt. Ein einzelnes
`--space-harvester.sources[0].title=…` ersetzt daher die ganze Quellenliste aus dem Config Server. Listen deshalb
immer an genau einer Stelle pflegen.

## Konfiguration zur Laufzeit (ADR 0015)

Modelle, KI-Dienst, Suchoptionen, Synchronisierung und Netzwerk lassen sich ohne Neustart ändern
([ADR 0015](adr/0015-konfiguration-zur-laufzeit.md)). Der `ConfigurationManager` im Core prüft eine neue
Konfiguration, lässt sie von den betroffenen Komponenten vorbereiten und aktiviert sie atomar. Neue Anfragen und
Aufträge benutzen danach die neue Konfiguration, laufende enden mit der bisherigen. Ist etwas ungültig oder lehnt eine
Komponente ab (etwa ein Modell, das der KI-Dienst nicht kennt), bleibt die bisherige Version wirksam.

**Eine geänderte YAML-Datei allein löst nichts aus.** Weder Spring Boot noch der Core beobachten die Datei. Im Server
übernimmt nur ein ausdrücklicher Weg die neue Konfiguration; die App übergibt sie beim Speichern der Einstellungen.

| Einstellung | Schlüssel (`space-harvester.`…) | Zur Laufzeit |
| --- | --- | --- |
| KI-Dienst, Adresse, API-Key, Frist | `ai.base-url`, `ai.api-key`, `ai.timeout`, `ai.trust-bundle` | ja, neue Client-Instanzen |
| Modelle je Funktion, global für alle Quellen | `ai.chat-model`, `ai.embedding-model`, `ai.embedding-dimensions`, `ai.rerank-model` | ja, neue Anfragen |
| Freigabe für externe Verarbeitung | `ai.external-processing-sources` | ja |
| Semantische Suche (Haken, Standard aus) | `search.semantic` | ja; ohne Embedding-Modell bleibt sie aus |
| Reranking, wenn ein Modell eingestellt ist | `search.rerank` (Standard `true`) | ja |
| Treffer der Quellensuche zuerst in den Cache | `search.prefetch-hits` (Standard `true`) | ja |
| Aktualisierung je Seite: „Immer Version prüfen“ (Standard), „Zeitbasiert ohne Versionsprüfung“, „Zeitbasiert mit Versionsprüfung“ | `cache.refresh-strategy` (`always-check-version`, `time-based`, `time-based-with-version-check`) mit `cache.max-age`; alternativ `cache.max-age` und `cache.version-check` | ja |
| Änderungsquelle Webhooks (braucht einen Confluence-Administrator) | `sync.events` (Standard `false`) | ja |
| Zeitplan des Nachholabgleichs | `sync.interval` (z. B. `P1D`, mindestens `PT1M`, ohne: kein Zeitplan), `sync.spaces` | ja, Scheduler neu gesetzt |
| Nachholen vor jeder Suche | `sync.before-search` (Standard `false`) | ja |
| Nachholen beim Start | `sync.catch-up-on-start` (Standard `false`) | ja, ab dem nächsten Start |
| Unbekannte Seiten mit Inhalt laden | `sync.load-unknown` (Standard `false`; sonst aktualisiert ein Abgleich nur bekannte Seiten) | ja |
| Sperrfrist gesperrter Seiten | `sync.withheld-retention` (Standard `30d`) | ja |
| Proxy und TLS | `network.proxy.*`, `network.tls.*` | ja, neue Verbindungen |
| Quellen | `sources[]` bzw. Quellenverwaltung | ja, über die Quellenverwaltung |
| Speicherort von Cache und Indizes | `cache.directory` | **nein**, wirkt nach kontrolliertem Neustart |

Alle Änderungsquellen (`sync.*`) sind optional und standardmäßig aus. Ohne sie bleibt der Cache über die
Aktualisierungsstrategie je Seite und die Beschaffung der Suchtreffer aktuell; Webhooks ergänzen das, wo ein
Administrator sie registriert, sind aber nie Voraussetzung.

Dauern als ISO-8601 (`PT6H`, `P1D`) oder in Spring-Kurzform (`30s`, `10m`, `6h`, `1d`).

Ein Wechsel des Embedding-Modells oder seiner Dimension versioniert den Vektorindex: Vorhandene Vektoren gelten als
veraltet und werden im Hintergrund aus dem Cache neu berechnet; Seiteninhalte werden dafür nicht erneut von der
Quelle geladen.

Fehler meldet der Dienst je Feld als `key` (ohne Präfix, z. B. `ai.embedding-dimensions`) und deutschen Satz, nie
mit dem abgelehnten Wert.
