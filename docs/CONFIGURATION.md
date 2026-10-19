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

KI-Dienst, Suche, Synchronisierung, Speicherort und freigegebene Ordner bilden die Laufzeitkonfiguration (ADR 0015) mit fortlaufender
Version; der Start ist Version 1. Gelesen wird sie beim Start aus `space-harvester.ai.*`, `space-harvester.search.*`,
`space-harvester.sync.*`, `space-harvester.cache.directory`/`max-age`/`version-check`, `space-harvester.local-files.*`
und `space-harvester.network.*`.

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
- In der Bibliothek (`Harvester.builder().configuration(manager)`; die Desktop-App baut ihn so und übernimmt Änderungen ihrer Datei über `manager.apply`, siehe [DESKTOP_APP.md](DESKTOP_APP.md)) wirkt eine aktivierte
  Version sofort: Aktualität vorhandener Inhalte und Frist für gesperrte Seiten je Quelle (`freshnessFor`,
  `syncFor`) beim nächsten Abruf bzw. Lauf, Embedding-Modell und Reranker für neue Suchen (alte Vektoren werden ohne
  neuen Abruf der Seiten neu berechnet, sobald die Suche sie braucht), der Zeitplan aus `sync.spaces` mit Abstand und
  Betriebsart je Quelle; `catch-up-on-start` je Quelle beim Bauen; Webhooks (`sync.events`) bei jedem Aufruf; Proxy
  und TLS (`network.*`), solange am Builder weder `proxy` noch `trust` gesetzt ist (die App setzt beide). Noch
  nicht live: Bestandsinventur-Zeitplan, `before-search` und `search.prefetch-hits` (nur beim Bauen).
- Im Server wirkt eine aktivierte Version ebenso sofort (`LiveConfiguration`): Aktualität (`cache.*`,
  `sync.sources.<Quelle>.max-age`/`refresh-strategy`) beim nächsten Seitenabruf, Frist für gesperrte Seiten je Quelle
  beim nächsten Lauf, Freigabe an den KI-Dienst je Quelle (`ai.external-processing-sources`) für Embeddings und
  Reranker, Reranker an, aus oder neues Modell (`ai.rerank-model`, `ai.base-url`, `search.rerank`) für neue Suchen,
  das Vision-Modell (`ai.vision-model`, ADR 0021) für die nächste Bildbeschreibung, auch erstmals eingeschaltet (ein
  Wechsel macht vorhandene Bildanalysen veraltet), der Zeitplan aus `sync.spaces` mit Abstand und Betriebsart je Quelle (eine unbekannte Quelle oder ein Abstand unter
  einer Minute lehnt die Änderung ab), Webhooks (`sync.events`) bei jedem Aufruf und `catch-up-on-start` je Quelle
  beim Start. Das Embedding-Modell wechselt live
  oder schaltet ab, wenn beim Start eines eingestellt war; **Embeddings erstmals einschalten braucht einen Neustart**
  (die Kette entsteht nur mit `ai.embedding-model` beim Start). Noch nicht live im Server: Bestandsinventur-Zeitplan,
  `before-search`, `search.prefetch-hits` und `search.semantic` (Suche und Vorabladen werden beim Start
  gebaut) sowie das Chat-Modell (Bean `AiServiceSettings` aus den Properties).

## Suchlauf-Protokoll und Fakten (#182)

Jede Schlagwortsuche (ein Stapel), Relevanzsuche und progressive Suche hinterlässt einen Lauf im Protokoll
(`SearchRunLog` im Core, Vorbild `KnowledgeSearchRunPort` des Enterprise AI Client): Art, Aufrufer, Anfrage, Beginn,
Dauer, Ergebnis je Quelle (`searched`, `indexed`, `not-supported`, `unavailable`) und Zähler je Stufe (`queries`,
`discovered`, `load-queued`, `pending`, `confirmed`, `delivered`). Die Suchen einer progressiven Suche und die
Schlagwortsuchen in der Vorbereitung einer Relevanzsuche sind Teile (`partOf`) ihres Laufs. Antworten nennen die
Kennung als `searchRun`.

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `space-harvester.search.run-log.enabled` | `true` | Protokoll führen; `false`: keine Läufe, `GET /api/v1/search/runs` liefert `enabled: false` |
| `space-harvester.search.run-log.max-age` | `30d` | Läufe danach löschen (beim Start und laufend); höchstens 10 000 Läufe |

Beides wirkt nur beim Start, nicht über `POST /api/v1/configuration`. Mit `space-harvester.cache.directory` liegt das
Protokoll als eigener Lucene-Index in `search-runs/`, sonst im Speicher; in der Bibliothek
`Harvester.builder().searchRuns(true, maxAge)` (unter `cache/search-runs`).

- **Datenschutz:** Wie im Enterprise AI Client steht die Anfrage im Lauf (höchstens 1000 Zeichen), nie im Log;
  scheitert das Protokoll, läuft die Suche weiter und das Log nennt nur den Fehlertyp. Lesen darf einen Lauf nur sein
  Aufrufer (gleicher `jobOwner`) oder ein technischer Client mit `manage-configuration`; sonst 404, wie bei fremden
  Aufträgen.
- **Zähler ohne fremde Seiten:** Gezählt werden nur geprüfte Seiten. Für Endnutzer mit delegierter Prüfung entfallen
  `discovered`, `load-queued` und `pending` (die Quelle hat die Treffer noch nicht für ihn bestätigt); ungeprüfte
  Vektorkandidaten der Relevanzsuche zählen nie.
- Die Kandidatenliste je Lauf, Vertiefen und der Trichter für den Chat bleiben im Client.

**Fakten** entstehen beim Speichern einer Anreicherung aus ihrem Ergebnis, im selben Lucene-Index (`enrichments/`,
Schema `enrichment-store/v4`; ein v1/v2/v3-Bestand erhält sie beim Öffnen; ohne Cache-Verzeichnis im Speicher) und mit ihm
ersetzt und gelöscht. Typen nach
dem Faktenkatalog des Enterprise AI Client: `ACRONYM_CANDIDATE` und `ACRONYM_DEFINITION` (aus `acronyms`), `MENTION`
(aus `entities`), `PROPERTY` (aus `properties`), `EVENT` (aus `activities`) und `RELATION` (aus `relations`);
`MENTION`, `EVENT` und `RELATION` auch aus der Satzanalyse `statements` (unten);
Argumente u. a. `SHORT_FORM`, `LONG_FORM`, `TERM`, `SUBJECT`, `OBJECT`, `VALUE`, `PREDICATE`, Rollen wie `ACTOR`,
`STATUS`, `QUALIFIER`, normalisiert verglichen. Prozessor-Versionen und Hash-Schemata bleiben unverändert.

**Satzanalyse über das Chat-Modell** (Prozessor `statements`, wie Issue #108 im Enterprise AI Client): zusätzlich zum
regelbasierten Weg schlägt das Chat-Modell `ai.chat-model` je Absatz Erwähnungen, Handlungen mit Rollen und
Eigenschaften mit Wortbereichen vor; behalten wird nur, was wörtlich im Satz steht, optional bestätigt eine zweite
Ja/Nein-Anfrage jede Aussage. Satzgrenzen wie bei `passages` (OpenNLP, ADR 0020), kein Wortartmodell. Die Fakten tragen
`STATUS` `extracted`, `model_validated`, `uncertain` oder `unresolved_reference`, `QUALIFIER` auch `conditional`,
Herkunft `AUTOMATIC` mit Prozessor `statements` und einer Version samt Chat-Modell (ein Modellwechsel macht sie
veraltet). Berechnet wird im Hintergrund der Anreicherungen nach Abgleich oder Abruf.

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `space-harvester.enrichment.statements.enabled` | `false` | Satzanalyse einschalten; ohne Chat-Modell wird sie übersprungen |
| `space-harvester.enrichment.statements.verify` | `true` | jede Handlung und Relation mit einer zweiten Anfrage prüfen (verneint: verworfen) |
| `space-harvester.enrichment.statements.max-paragraphs` | `40` | höchstens so viele Absätze (Modellanfragen) je Seite, höchstens 200 |
| `space-harvester.enrichment.statements.max-paragraph-chars` | `1500` | längere Absätze in Folgen ganzer Sätze bis zu dieser Länge, höchstens 8000 |

- **Datenschutz:** Absätze gehen nur aus Quellen in `ai.external-processing-sources` an den Dienst, sonst nie, auch
  nicht bei einer Neuberechnung. Das Log nennt nur Prozessor, Seite und Fehlertyp, nie Text oder Antwort.
- Die Schlüssel wirken beim Start (in der Bibliothek `Harvester.builder().statements(…)`); Chat-Modell und Freigabe
  folgen der wirksamen Konfiguration. Scheitert ein Absatz, ist das ganze Ergebnis fehlgeschlagen (kein halbes).
- `MENTION`, `EVENT` und `RELATION` entstehen dann doppelt, regelbasiert und aus `statements`, mit eigenen Kennungen.
  `processor=` an beiden Fakten-Endpunkten wählt einen Weg (etwa `entities`, `statements` oder `manual`), ohne ihn
  kommen alle. Endet ein Ergebnis an der Grenze von 2000 Fakten, fallen Erwähnungen vor Handlungen und Relationen weg.

- `GET /api/v1/sources/{sourceId}/pages/{pageId}/facts[?type=…][&processor=…]`: das Faktenblatt einer Seite mit derselben Prüfung
  wie das Lesen der Seite und `freshness` je Fakt. Je Prozessor und Seite gibt es höchstens 2000 Fakten;
  `truncated` nennt die Prozessoren, deren Fakten an dieser Grenze enden (sonst leer).
- `GET /api/v1/facts?type=…&argument=SHORT_FORM:API[&sourceId|sourceIds|spaceKey][&processor=…][&cursor&limit]`: Fakten über alle
  Seiten, für die der Aufrufer `metadata` und `content` hat (geprüft am Space im Cache); andere fallen ohne Spur weg,
  es gibt keine Gesamtzahl.

**Manuelle und geprüfte Fakten** (wie `FactOrigin`/`FactReview` im Enterprise AI Client) liegen außerhalb des Caches
unter `space-harvester.facts.directory` (Standard `data/facts`, im Kern `<dataDirectory>/facts`, wirksam nach
Neustart): `facts.json` (Schema `facts/v1`, atomar ersetzt) und `audit.jsonl` (nur angehängt), nach dem Muster der
Begriffsbeziehungen. Ein Neuaufbau des Caches oder der Anreicherungen berührt sie nicht. Die Payloads stammen aus dem
Inhalt der Seiten; das Verzeichnis gehört nur dem Dienstkonto. Jeder Fakt trägt `origin` (`AUTOMATIC`, `MANUAL`),
`review` (`UNREVIEWED`, `CONFIRMED`, `REJECTED`) und `revision` (`0`, solange niemand ihn geprüft hat). Manuelle Fakten
berührt kein Prozessorlauf. Ein geprüfter automatischer Fakt behält seinen Status, solange sein Ergebnis dieselbe
Aussage liefert; liefert die Neuberechnung etwas anderes, bleibt die geprüfte Kopie mit alter Herkunft (dann `stale`)
und der neue Fakt erscheint ungeprüft unter eigener Kennung. Ändern verlangt zusätzlich `sync` an der Seite, eine
veraltete `revision` gibt 409 (`fact-conflict`):

- `POST …/pages/{pageId}/facts` mit `{type, role, payload, arguments: [{role, value}], anchor}`: manueller Fakt.
- `PUT …/pages/{pageId}/facts/{factId}/review` mit `{review, revision}`; `UNREVIEWED` nimmt einen automatischen Fakt
  wieder heraus.
- `DELETE …/pages/{pageId}/facts/{factId}?revision=`: manueller Fakt oder geprüfte Kopie; automatische nur verwerfen.
- `GET …/pages/{pageId}/facts/{factId}/history`: Protokoll mit Bearbeiter (`jobOwner`), Zeitpunkt, Status, Revision.

## Anhänge und große Dateien (#19)

Anhänge, Vorschauen und Bilder der Lese-Ansicht liegen beim Laden, Ablegen, Lesen aus der Ablage, bei der
Textextraktion (Tika), der Bildbeschreibung und im Export vollständig im Speicher, wie im Enterprise AI Client
(`READER_MAX_BYTES` = 100 MiB in `ConfluenceKnowledgeSource`). Damit wenige große Dateien den Heap nicht füllen, gilt
für alle diese Wege eine gemeinsame Schranke (`LargeFileGate` im Core): Dateien über der Schwelle laufen nur in
begrenzter Zahl gleichzeitig. Groß ist eine Datei nach ihrer angegebenen Größe; nennt die Quelle für einen Anhang keine,
zählt er als groß. Ein weiterer Aufruf wartet höchstens die Wartezeit und scheitert dann vorübergehend mit
`source-rate-limited` (REST 429); der Anhang gilt dabei nie als defekt, die nächste Ernte versucht es erneut. Die
bisherige Grenze von 4 gleichzeitigen Downloads gilt unverändert daneben.

Die Schranke begrenzt Laden, Lesen aus der Ablage, Extraktion und Bildbeschreibung. Ein abgelegter Anhang geht über
`GET …/assets/{id}/content` als Strom aus der Ablage an den Client und hält seinen Platz, bis die Antwort geschrieben
oder abgebrochen ist; die Antwort hat keine Content-Length, und passt der Inhalt am Ende nicht zu seinem Hash, bricht
sie ab, statt als vollständig zu gelten. Bei Vorschauen und im Export ist der Platz frei, sobald die Datei gelesen ist,
die Bytes liegen danach bis zum Ende der Antwort bzw. Konvertierung im Speicher. Bilder der Lese-Ansicht nennen ihre Größe erst nach
dem Download; sie werden ohne Platz geladen (begrenzt durch die 4 Downloads und `max-size`) und erst beim Ablegen von
der Schranke erfasst.

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `space-harvester.assets.max-size` | `100MB` | Größte einzelne Datei; größere werden weder geladen noch gekürzt abgelegt (`asset-too-large`) |
| `space-harvester.assets.large-file-threshold` | `20MB` | Dateien darüber fallen unter die Schranke |
| `space-harvester.assets.max-concurrent-large-files` | `2` | So viele große Dateien höchstens gleichzeitig |
| `space-harvester.assets.large-file-wait` | `30s` | Wartezeit auf einen freien Platz, danach `source-rate-limited` |

Alle vier wirken nur beim Start. In der Bibliothek entsprechen ihnen `Harvester.builder().maxAssetBytes(…)`
(Standard 100 MiB), `.largeAssetThresholdBytes(…)` (20 MiB) und `.maxConcurrentLargeAssets(…)` (2); die Wartezeit ist
dort fest 30 s.

- **Heap:** Mit 2 gleichzeitigen großen Dateien bis 100 MiB mindestens 512 MiB Heap einplanen (`-Xmx512m`, besser
  mehr bei vielen gleichzeitigen Nutzern); Download, Hash, Ablage und Auslieferung arbeiten auf demselben
  `byte[]`, die Textextraktion braucht zusätzlich Platz für den Text (begrenzt durch die Grenzen der Extraktion).
  Wer `max-size` oder `max-concurrent-large-files` erhöht, erhöht den Heap entsprechend.
- **Kopien:** Der Confluence-Transport legt einen erfolgreichen Körper gleich in der angekündigten Länge an, das
  JSON-Paket liest jede Datei erst beim Schreiben, und Export-Bilder übernehmen ihre Bytes ohne Kopie. Eine Kopie
  bleibt in `ConfluenceHttpResponse` aus dem Enterprise AI Client (Konstruktor und `body()`; Vorschlag als
  Upstream-Patch 08).
- **Textextraktion:** Tika liest nur Anhänge bis 20 MiB (`TikaDocumentText.DEFAULT_MAX_BYTES`), größere bekommen
  keinen Text. Die Textlänge wird erst nach der Extraktion auf `maxChars` (1 Mio. Zeichen) gekürzt, weil der
  Extraktor aus dem Enterprise AI Client den ganzen Text als String liefert; eine Grenze schon beim Schreiben des
  Textes bräuchte eine Änderung dort.

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

Das gilt auch für Proxy, PAC und TLS: Eine Umstellung bricht keinen laufenden Auftrag ab. Seitenabrufe, die gerade
laufen oder auf eine freie Verbindung warten, enden noch über den bisherigen Weg. Erst die folgenden Abrufe nehmen die
neue Route, Anmeldung und das neue Vertrauen ([ADR 0015, Nachtrag](adr/0015-konfiguration-zur-laufzeit.md)).

**Eine geänderte YAML-Datei allein löst nichts aus.** Weder Spring Boot noch der Core beobachten die Datei. Im Server
übernimmt nur ein ausdrücklicher Weg die neue Konfiguration; die App übergibt sie beim Speichern der Einstellungen.

| Einstellung | Schlüssel (`space-harvester.`…) | Zur Laufzeit |
| --- | --- | --- |
| KI-Dienst, Adresse, API-Key, Frist | `ai.base-url`, `ai.api-key`, `ai.timeout`, `ai.trust-bundle` | ja, neue Client-Instanzen |
| Modelle je Funktion, global für alle Quellen | `ai.chat-model`, `ai.embedding-model`, `ai.embedding-dimensions`, `ai.rerank-model`, `ai.vision-model` | ja, neue Anfragen |
| Freigabe für externe Verarbeitung | `ai.external-processing-sources` | ja; die Seiten der Quelle im Cache werden neu angereichert (siehe unten) |
| Semantische Suche (Haken, Standard aus) | `search.semantic` | ja; ohne Embedding-Modell bleibt sie aus |
| Reranking, wenn ein Modell eingestellt ist | `search.rerank` (Standard `true`) | ja |
| Treffer der Quellensuche zuerst in den Cache | `search.prefetch-hits` (Standard `true`) | ja |
| Aktualisierung je Seite: „Immer Version prüfen“ (Standard), „Zeitbasiert ohne Versionsprüfung“, „Zeitbasiert mit Versionsprüfung“ | `cache.refresh-strategy` (`always-check-version`, `time-based`, `time-based-with-version-check`) mit `cache.max-age`; alternativ `cache.max-age` und `cache.version-check` | ja |
| Änderungsquelle Webhooks (braucht einen Confluence-Administrator) | `sync.events` (Standard `false`) | ja |
| Zeitplan des Nachholabgleichs | `sync.interval` (z. B. `P1D`, mindestens `PT1M`, ohne: kein Zeitplan), `sync.spaces` | ja, Scheduler neu gesetzt |
| Nachholen vor jeder Suche | `sync.before-search` (Standard `false`) | ja |
| Nachholen beim Start | `sync.catch-up-on-start` (Standard `false`) | ja, ab dem nächsten Start |
| Betriebsart von Zeitplan und Nachholen: nur katalogisieren (Standard), Cache synchronisieren, vollständig | `sync.mode` (`catalog-only`, `sync-cache`, `full`) | ja |
| Sperrfrist gesperrter Seiten | `sync.withheld-retention` (Standard `30d`) | ja |
| Regelmäßige Bestandsinventur (nur Metadaten) | `sync.inventory-interval` (ohne: keine) | ja |
| Hintergrundfüllung: Katalogseiten ohne oder mit veraltetem Inhalt in Leerlaufzeit laden (ADR 0016) | `sync.background-fill` (Standard `true`) | ja |
| Parallelität der Hintergrundfüllung | `sync.background-parallelism` (Standard `2`, `1` bis `16`) | ja |
| Abweichende Regeln je Quelle | `sync.sources.<Quelle>.*` (siehe unten) | ja |
| Proxy und TLS | `network.proxy.*`, `network.tls.*` | ja, neue Verbindungen: gemerkte Routen samt PAC-Ergebnissen werden verworfen, Proxy-Anmeldung und Vertrauen ohne Bundle neu gesetzt, alle Quellen neu geöffnet (Trust-Bundles und Client-Zertifikate neu gelesen); laufende Anfragen enden über ihre Verbindung |
| Quellen | `sources[]` bzw. Quellenverwaltung | ja, über die Quellenverwaltung |
| Freigegebene Ordner für lokale Dateien (siehe unten) | `local-files.allow-all` (Standard `false`), `local-files.roots` | ja, beim nächsten Dateizugriff |
| Speicherort von Cache und Indizes | `cache.directory` | **nein**, wirkt nach kontrolliertem Neustart |
| Speicherort der Begriffsbeziehungen und Sperrlisten (#120, außerhalb des Caches) samt Ergebnis-Cache der KI-Akronymauflösung (#122, `ai-cache.json`: nur Fingerabdrücke und Beziehungs-IDs, Auslösung über `POST /api/v1/term-relations/refresh?sourceId=` mit `{"kind":"acronym"}`, Auftrag unter `/api/v1/term-relations/refresh-jobs/{id}`) | `term-relations.directory` (Standard `data/term-relations`; im Core `<dataDirectory>/term-relations`) | **nein**, wirkt nach Neustart |

### Regeln je Quelle

Der Reiter „Synchronisierung“ der Wissensquellen stellt die Regeln je Quelle ein; was er nicht setzt, kommt aus den
globalen Werten oben. Modelle gelten immer global. Schlüssel `sync.sources.<Quelle>.<Feld>` (oder
`sync.sources[<Quelle>].<Feld>`), gegliedert wie in der Oberfläche:

| Bereich | Feld | Bedeutung |
| --- | --- | --- |
| Erkennung | `interval` | automatische Änderungssuche (CQL `lastmodified`) in diesem Abstand, mindestens `PT1M` |
| Erkennung | `inventory-interval` | regelmäßige Bestandsinventur, nur Metadaten (global: `sync.inventory-interval`) |
| Erkennung | `events`, `catch-up-on-start`, `before-search`, `mode` | Webhooks, Nachholen beim Start, vor jeder Suche, Betriebsart |
| Aktualisierung | `refresh-strategy`, `max-age` | Aktualität vorhandener Inhalte; ohne eigenes Zeitfenster gilt `cache.max-age` |
| Bereinigung | `withheld-retention` | wie lange gesperrte Rohdaten bleiben |
| Beschaffung | `background-fill` | Hintergrundfüllung nur für diese Quelle ab- (`false`) oder anschalten (global: `sync.background-fill`) |

Im Core liefern `HarvesterConfiguration.syncFor(sourceId)` und `freshnessFor(sourceId)` die wirksamen Werte.

Alle Änderungsquellen (`sync.*` außer der Hintergrundfüllung) sind optional und standardmäßig aus. Ohne sie bleibt der Cache über die
Aktualisierungsstrategie je Seite und die Beschaffung der Suchtreffer aktuell; Webhooks ergänzen das, wo ein
Administrator sie registriert, sind aber nie Voraussetzung.

Die Hintergrundfüllung hängt nicht an `sync.mode`: Die Betriebsart legt nur fest, was der Abgleich spiegelt oder
entfernt. Ist die Füllung an, lädt sie mit technischen Zugangsdaten der Quelle Seiten des Katalogs, die noch keinen
Inhalt haben (`NOT_LOADED`) oder deren Inhalt veraltet ist (`OUTDATED`), zuletzt geänderte zuerst; nie gesperrte
Seiten (`WITHHELD`) und nie, was die Zugriffspolicy oder die KI-Freigabe der Quelle ausschließt. Abschalten geht
global mit `sync.background-fill=false` oder je Quelle mit `sync.sources.<Quelle>.background-fill=false`; ein
eigener Wert je Quelle geht dem globalen vor.

### Freigegebene Ordner für lokale Dateien

Wie „Dateizugriff“ im Enterprise AI Client (`files.access.allowAll`, `files.access.roots`): Die Quellenart `files`
liest nur unterhalb ihres eigenen Verzeichnisses und der freigegebenen Ordner. Standard ist kein Zugriff darüber
hinaus.

| Schlüssel | Bedeutung |
| --- | --- |
| `local-files.allow-all` | `true`: alles, was das Konto des Dienstes lesen darf |
| `local-files.roots` | weitere Ordner, absolut; als YAML-Liste oder als Text getrennt durch `;` (Kommas kommen in Pfaden vor) |

```yaml
space-harvester:
  local-files:
    roots:
      - D:\Wissen\Handbücher
      - \\fileserver\teams\doku
```

- Verglichen wird der wirkliche Ort (Links aufgelöst) bei jedem Dateizugriff; ein Link aus dem Verzeichnis einer
  Quelle hinaus führt nur in einen freigegebenen Ordner.
- Ein Ordner, den es noch nicht gibt, gewährt nichts, bis er da ist, etwa ein gemapptes oder UNC-Laufwerk, das
  später verbunden wird; dafür ist kein Neustart nötig. Ebenso gewährt ein Eintrag, der eine Datei ist, nichts. Nur
  ein relativer Pfad wird abgelehnt (`problems` mit `key: local-files.roots`, nie der Pfad); beim Speichern greift
  die Prüfung nicht auf das Dateisystem zu.
- Eine Freigabe liest nichts ein; eingelesen wird nur, was eine Quelle nennt.
- `POST /api/v1/configuration` nimmt `local-files.allow-all` und `local-files.roots` (Text mit `;` oder Liste) an;
  `GET` zeigt sie unter `localFiles`.

### Webhooks

Ein Confluence-Administrator registriert je Quelle einen Webhook auf
`https://<Dienst>/webhooks/v1/sources/<Quelle>/events` mit einem Geheimnis (Ereignisse für Seiten: angelegt,
geändert, verschoben, wiederhergestellt, in den Papierkorb gelegt, entfernt, Berechtigungen). Der Dienst nimmt
Aufrufe nur an, wenn für die Quelle `sync.events` gilt und das Geheimnis unter
`space-harvester.webhooks.secrets.<Quelle>` steht (im Server aus Datei, Umgebung oder Config Server; nie in
`GET /api/v1/configuration`, nie im Log). Die Route braucht keinen Client-Token; sie prüft die Signatur
`X-Hub-Signature: sha256=<HMAC-SHA256 des Rumpfs>` und antwortet 202 (angenommen), 401 (Signatur fehlt oder falsch),
404 (unbekannte Quelle, Ereignisse aus oder kein Geheimnis) oder 400 (keine lesbare Meldung). Ein Aufruf markiert nur
den Katalog (ADR 0014): veraltet, nicht geladen, gelöscht oder gesperrt; Inhalte lädt die Suche. In der Bibliothek
setzt `Harvester.builder().webhookSecret(quelle, geheimnis)` das Geheimnis, `Harvester.changeEvents().receive(…)`
nimmt Aufrufe einer eigenen Empfangsstelle an.

Dauern als ISO-8601 (`PT6H`, `P1D`) oder in Spring-Kurzform (`30s`, `10m`, `6h`, `1d`).

### Beschaffung und Hintergrundfüllung (ADR 0016)

Welche Funde einer Suche sofort laden, liest der Server beim Start; diese beiden Schlüssel wechseln nicht über
`POST /api/v1/configuration`. Im Core heißen sie `Harvester.Builder.candidateSelection` und `searchLoads`.

| Schlüssel (`space-harvester.`…) | Standard | Bedeutung |
| --- | --- | --- |
| `search.candidate-selection` | `adaptive` | welche Funde einer Suche jetzt laden: `order` (alle, Reihenfolge der Quellen), `top-n` (die besten `search.loads` nach Titel und Auszug), `adaptive` (so viele, wie der Vordergrund bald schafft; Regel in ADR 0016) |
| `search.loads` | ohne Grenze (100) | Obergrenze je Stapel, 1 bis 100; allein gesetzt heißt es `top-n` wie bisher |

Die Hintergrundfüllung (`sync.background-fill`, `sync.background-parallelism`, `sync.sources.<Quelle>.background-fill`,
Tabellen oben) folgt dagegen der Laufzeitkonfiguration; im Core gilt das mit `Harvester.Builder.configuration`, sonst
`backgroundFill` und `backgroundParallelism`. Ihre Warteschlange liegt unter `<cache.directory>/fill-queue` und
überdauert einen Neustart; ohne Cache-Verzeichnis gibt es keine Füllung. Sie lädt als technische Aktion des Servers mit
den Zugangsdaten der Quelle, nie mit der Anmeldung eines Benutzers, und über dieselbe Warteschlange wie die Suche:

- Die Suche hat vier eigene Plätze; je Quelle bleibt ihr mindestens einer frei.
- Solange eine Suche wartet oder lädt, eine Aktion des Benutzers den Hintergrund anhält und noch 5 s nach der letzten
  Seite der Suche beginnt der Hintergrund keine neue Seite. Eine laufende Seite endet immer regulär; unterbrochen
  wird nie.
- Wartet eine Seite schon im Hintergrund, stuft die Suche sie hoch, statt sie ein zweites Mal zu laden; lädt sie
  schon, wartet die Suche auf denselben Abruf.
- Abschalten zieht übergebene Anfragen zurück; die Warteschlange bleibt und geht beim Einschalten weiter.

`GET /api/v1/acquisition` (nur Clients mit `manage-configuration`, im Core `Harvester.acquisitionStatus()`) zeigt den
Stand: `enabled`, `pause` (`NONE`, `FOREGROUND`, `USER_ACTION`, `COOLDOWN`, `DISABLED`, `IDLE`, `CLOSED`),
Warteschlangen von Suche und Hintergrund, geladene und gescheiterte Seiten seit dem Start, `promoted`, `parallelism`,
`lastScan` und dieselben Zähler je Quelle, nur Zähler, nie Seiten-IDs oder Suchtexte.

Ein Wechsel des Embedding-Modells oder seiner Dimension versioniert den Vektorindex: Vorhandene Vektoren gelten als
veraltet und werden im Hintergrund aus dem Cache neu berechnet; Seiteninhalte werden dafür nicht erneut von der
Quelle geladen.

**Embedding je Absatz (#181, PR #388):** Mit Embedding-Modell bettet der Prozessor `embedding` je semantischem Absatz
ein (`EmbeddingProcessor.withPassages`, ADR 0020), im Server wie im Core, ohne eigenen Schlüssel. Wie im Client wird
ein Absatz nur nach Tokens begrenzt; nur mit gesetztem `ai.embedding-chunk-chars` (Standard 0, ohne Zeichengrenze) wird
ein Absatz darüber geteilt. Der Umstieg auf diese Standards bettet vorhandene Seiten beim nächsten Start einmal im gedrosselten
Hintergrund neu ein; bis dahin findet die Relevanzsuche eine solche Seite nur über ihre Schlagworte. Budget und
Überlappung der Zerlegung (`ai.embedding-chunk-tokens`, `ai.embedding-chunk-overlap-sentences`) stehen in [EMBEDDINGS.md](EMBEDDINGS.md). Die
Version des Prozessors nennt die Absatzbildung (`+passages:<Fingerabdruck>`); beim Umstieg veralten vorhandene
Embeddings und werden aus dem Cache neu berechnet. Die Dokumentnavigation (Prozessor `navigation`, PR #396) liest
diese Embeddings je Absatz; sie hat keine eigenen Schlüssel und folgt `ai.embedding-model` und der Freigabe
`ai.external-processing-sources`. Die Themenabschnitte benennt mit `ai.chat-model` die Kopie `ChatSectionLabeler`
(PR #401, ADR 0020), nur in der Anreicherung und nur für freigegebene Quellen, mit fester Frist von 60 s und
höchstens zwei Benennungen zugleich (nicht einstellbar); ohne Chat-Modell bleibt der erste Satz, bei Ausfall des
Modells ebenso mit `reason=model-unavailable`. Ein anderes Chat-Modell macht gespeicherte Navigationen alt.

**Wechsel der Freigabe (`ai.external-processing-sources`):** Wird eine Quelle neu freigegeben oder ihre Freigabe
entzogen, reiht der Dienst (im Core der Harvester mit `Harvester.Builder.configuration`, im Server mit
Embedding-Modell oder eingeschalteter Satzanalyse beim Start) beim Aktivieren alle Seiten der Quelle im Cache zur
Neuberechnung von `embedding`, `passages` und `navigation` ein, eingeschaltet auch von `statements`, gedrosselt im Hintergrund der Anreicherungen (höchstens 50 zugleich wartend), ohne
neuen Abruf von der Quelle, ohne neue Version und ohne 503. Neu freigegeben rechnen sie mit Modell; entzogen ohne:
Absätze und Navigation nach Struktur (`source-not-approved`), die Embeddings der Quelle entfallen. Ob eine Berechnung
das Modell fragen darf, prüft ihre Ausführung: Ab dem Aktivieren eines Entzugs fragt auch keine schon eingereihte
Berechnung das Modell mehr; nur eine, die gerade läuft, rechnet zu Ende, und ihr Ergebnis wird verworfen. Die
Satzanalyse rechnet nach einer Freigabe mit dem Chat-Modell nach; nach einem Entzug entfallen ihr Ergebnis und ihre
Fakten. Das gilt auch ohne Embedding-Modell, sobald ein Chat-Modell eingestellt ist. `images`
rechnet nur auf Abruf und prüft die Freigabe dann; schon gespeicherte Bildbeschreibungen bleiben nach einem Entzug
lesbar (nur mit den Rechten an der Seite), neue entstehen erst nach erneuter Freigabe. Wechselte die Freigabe, während der Dienst nicht lief, oder brach
ein Lauf ab, gleicht der Start das einmal im selben Hintergrund ab, allein aus den gespeicherten Ergebnissen: Nur
Seiten, die nicht zur Freigabe passen (nicht freigegeben, aber mit Embedding oder ohne `source-not-approved`;
freigegeben, aber mit `source-not-approved` oder ohne Ergebnis eines Prozessors, der für die Seite gilt), rechnen neu,
und nur mit den Prozessoren, deren Ergebnis nicht passt. Das Log nennt nur Quelle und Anzahl der
Seiten.

Fehler meldet der Dienst je Feld als `key` (ohne Präfix, z. B. `ai.embedding-dimensions`) und deutschen Satz, nie
mit dem abgelehnten Wert.
