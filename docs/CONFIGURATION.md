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

## Konfiguration zur Laufzeit

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
