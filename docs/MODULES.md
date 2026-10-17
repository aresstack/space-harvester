# Module: Core-Bibliothek und Spring-Boot-Server (#100)

**Stand:** 17.10.2026 · **Issue:** [#100](https://github.com/aresstack/space-harvester/issues/100)

Space Harvester wird in eine eigenständig nutzbare Bibliothek und einen Spring-Boot-Dienst getrennt, der die
Bibliothek verwendet. Dieses Dokument ordnet den bestehenden Code den Modulen zu und hält die Entscheidungen fest,
die vor der Migration getroffen sein müssen. Die Migration selbst folgt in kleinen PRs (Abschnitt 6).

## 1. Zielbild

```text
space-harvester/                 Gradle-Wurzel: nur Aggregator und gemeinsame Konventionen
├── enterprise-ai-sources/       unveränderte Upstream-Kopie (Java 8), bleibt wie sie ist
├── space-harvester-core/        java-library: Fachlogik, Quellenadapter, Cache, Export, öffentlicher Einstieg
└── space-harvester-server/      Spring Boot: Main, REST, Fassade, UI, Sicherheit, Verdrahtung, bootJar

space-harvester-server ──► space-harvester-core ──► enterprise-ai-sources
space-harvester-core   ─X─► space-harvester-server, Spring Boot, Servlet, Spring Security
```

Weitere unabhängige Subprojekte (`:nlp-parser` aus #98, `:nlp-model-training` aus #99) stehen gleichrangig daneben
und hängen von keinem der beiden Module ab.

## 2. Befund: Die Grenze existiert schon

Die Paketstruktur trennt Fachlogik und Spring bereits sauber. Gezählt auf `main` (7b4635f):

| Paket | Dateien | Spring-Importe | Abhängigkeiten nach außen |
| --- | --- | --- | --- |
| `application.*` | 187 | keine | nur JDK (ArchUnit-Regel `applicationIsFrameworkNeutral`) |
| `source`, `source.confluence`, `source.network` | 25 | nur `WebClientConfluenceTransport` | `enterprise-ai-sources`, win-proxy-java, Jackson 3, Reactor Netty, **Spring WebClient** |
| `persistence.lucene`, `persistence.files` | 15 | keine | Lucene 10 |
| `output.convert`, `output.export` | 13 | keine | jsoup, PDFBox, Jackson 3 |
| `embedding.onnx` | 5 | keine | ONNX Runtime, DJL-Tokenizer |
| `api`, `api.confluence` | 19 | alle | Spring MVC, springdoc |
| `config` | 19 | alle | Spring Boot (Konfigurationseigenschaften, Beans) |
| `security` | 11 | fast alle | Spring Security, Servlet |
| Wurzel `SpaceHarvesterApplication` | 1 | ja | `@SpringBootApplication` |

Kein Paket aus `application`, `source`, `persistence`, `output` oder `embedding` importiert `api`, `config` oder
`security`. Die Trennung ist deshalb vor allem ein Verschieben ganzer Pakete, kein Umbau der Fachlogik.

## 3. Zuordnung

### `:space-harvester-core`

| Paket | Begründung |
| --- | --- |
| `application.*` (access, assets, compat, convert, enrichment, export, harvest, jobs, query, ranking, snapshot, sources, sync, tree, `ServiceInfo`) | Anwendungsfälle, Ports und Domänentypen; frameworkneutral |
| `source`, `source.confluence`, `source.network` | Quellenadapter (Confluence, Dateien), Proxy/PAC, mTLS, asynchroner Transport; ohne sie kann die Bibliothek nichts ernten |
| `persistence.lucene`, `persistence.files` | Umsetzung der Ports SnapshotRepository, PageCacheReader, PageSearchIndex, JobStore, SyncBaselineStore, EnrichmentStore, AssetStore |
| `output.convert`, `output.export` | Konverter JSON/Markdown/DOCX/PDF und Exportdateien |
| `embedding.onnx` | lokales Embedding-Modell hinter dem Port `EmbeddingProvider` |
| neu: `com.aresstack.spaceharvester.Harvester` | kleiner öffentlicher Einstieg ohne Spring (Abschnitt 4) |

### `:space-harvester-server`

| Paket / Datei | Begründung |
| --- | --- |
| `SpaceHarvesterApplication` | Boot-Main |
| `api`, `api.confluence` | REST-Controller, Fehlerabbildung, OpenAPI, Confluence-Fassade |
| `config` | Spring-Verdrahtung, `@ConfigurationProperties`, SSL-Bundles, Build-Info, UI-Auslieferung |
| `security` | Spring Security, Client-Tokens, `AccessContext` aus dem Request |
| `src/main/resources` (`application*.yml`, `ui/`) | Serverkonfiguration und Weboberfläche |
| Gradle: `bootJar`, `bootJarPacCheck`, `demoConfluence`, `springBoot.buildInfo` | Paketierung und Laufzeitprüfungen des ausführbaren JARs |

### Tests

Tests ziehen mit ihrem Paket um. Ausnahmen, weil sie einen Spring-Kontext brauchen:

| Test | Ziel | Grund |
| --- | --- | --- |
| `source.NativeSnapshotSourceTest` | Server | `@SpringBootTest` |
| `demo.*` (DemoConfluence, DocumentedExamplesTest, Walking Skeleton, LargeSpaceMetadataTest), `TestClient` | Server | prüfen den Dienst über HTTP; Task `demoConfluence` nutzt `DemoConfluence` |
| `source.ConfluenceDelegatedAccessTest`, `source.network.PacRoutingTest` | Core | nutzen nur `OutputCaptureExtension` aus spring-boot-test; im Core als reine Testabhängigkeit erlaubt |
| `LayeringTest` | beide | wird je Modul geteilt; Regeln werden übernommen, nicht gelockert, und um Modulregeln ergänzt (Core kennt kein `api`, `config`, `security`, keine Spring-Boot-/Servlet-/Security-Typen) |

Testressourcen: `embedding/` und `test-hosts` gehen in den Core, `confluence-contract/` und `sentinel.pac` in den
Server; was beide brauchen, liegt in beiden.

## 4. Entscheidungen

1. **Jobs bleiben im Core.** `application.jobs` (Warteschlange, Zustände, `JobContext` mit Fortschritt und
   kooperativem Abbruch, `JobStore`-Port) ist frameworkneutral und genau die Fortschritts- und Abbruchschnittstelle,
   die #100 für lang laufende Operationen verlangt. Der Server übernimmt REST-Annahme, Eigentümerprüfung, Executor und
   Konfiguration (`JobsConfiguration`). So gibt es keine zweite Auftragsverwaltung.
2. **Quellenadapter bleiben im Core.** Eine Bibliothek ohne Confluence-Adapter könnte nichts ernten. Ein eigenes
   Konnektor-Modul ist eine spätere Ausbaustufe; die Pakete `source.*` sind dafür schon abgegrenzt (ArchUnit-Regel
   `copiedUpstreamCodeIsUsedOnlyBySourceAdapters`).
3. **Spring WebClient im Core als dokumentierte Ausnahme.** `WebClientConfluenceTransport` nutzt WebClient aus
   `spring-webflux` auf Reactor Netty (ADR 0001). Das ist Spring Framework als HTTP-Client-Bibliothek, kein Spring
   Boot, kein ApplicationContext, keine Autokonfiguration. Ein Austausch gegen Reactor Netty direkt würde den
   abgenommenen Transport (Redirects, Größengrenze, Zeitlimits, PAC, mTLS) neu schreiben und ist nicht Teil von #100.
   Eine ArchUnit-Regel im Core beschränkt Spring-Typen auf genau diese Klasse; der synchrone Rückfall
   (`async-transport: disabled`) kommt ganz ohne Spring aus.
4. **Konfiguration.** Die `@ConfigurationProperties`-Klassen bleiben im Server und bilden die YAML-Schlüssel
   unverändert auf die Core-Einstellungstypen ab (`SourceSettingsConfig`, `ProxySettings`, `JobLimits`,
   `OnnxEmbeddingSettings` usw., die schon heute frameworkneutral sind). Keine Konfigurationsschlüssel ändern sich.
5. **Öffentlicher Einstieg.** `com.aresstack.spaceharvester.Harvester` baut aus Quellen-Einstellungen und einem
   Cache-Verzeichnis dieselben Objekte, die heute `config` verdrahtet, und bietet Quellen, Abgleich, Seiten, Suche und
   Export als Methoden an. Der Server nutzt weiter die einzelnen Anwendungsfälle; der Einstieg ist eine zusätzliche
   Tür, keine zweite Implementierung. Paketnamen bleiben unverändert, damit Historie und Importe stabil bleiben.
6. **Veröffentlichung vorbereiten, nicht veröffentlichen.** `maven-publish` mit POM-Metadaten (Lizenz, SCM,
   Entwickler), Sources- und Javadoc-JAR; Koordinaten `com.aresstack:space-harvester-core`. Weil der Core die Kopie
   `enterprise-ai-sources` braucht, bekommt auch sie eine Publikation
   (`com.aresstack:space-harvester-enterprise-ai-sources`), damit die Core-POM keine unveröffentlichte
   Projektabhängigkeit enthält; der Code der Kopie bleibt unverändert, nur ihr `build.gradle` gehört uns. Eine
   Gradle-Prüfung stellt sicher, dass die Core-POM weder Spring Boot noch Servlet noch unbekannte Projekte nennt.
   Signatur und Veröffentlichung kommen erst mit einem Release-Prozess; es werden keine Secrets angelegt.
7. **Abhängigkeitsversionen im Core.** Bisher liefert die Spring-Boot-BOM die Versionen für Jackson 3, Reactor
   Netty und Spring WebFlux. Der Core darf diese BOM nicht in seine POM tragen; die Versionen stehen daher im
   Versionskatalog, abgestimmt auf Spring Boot 4.1.1, und der Server erzwingt weiter über die BOM dieselben.

## 5. Java 8 für den Core? Befund und Empfehlung

Geprüft wurde, was ein Java-8-Core kosten würde (Zählung über die Core-Pakete, 245 Dateien):

| Hindernis | Umfang |
| --- | --- |
| Records | 119 Deklarationen in 79 Dateien, darunter alle Ports, Snapshots, Hash-Eingaben und Ergebnisse |
| Switch-Ausdrücke, Pattern Matching `instanceof`, `sealed` | 15 + 15 + 5 Dateien |
| `List.of`/`Map.of`/`copyOf`, `Stream.toList()`, `var` | 71 + 22 + 5 Dateien |
| Lucene 10.5 | verlangt Java 21. Letzte Java-8-Version ist 8.11 mit anderer API und anderem Indexformat: der persistente Cache, ADR 0003 und die Vektoren aus ADR 0004 (`KnnFloatVectorField`) müssten neu gebaut werden |
| Jackson 3 (`tools.jackson`) | verlangt Java 17; Rückbau auf Jackson 2 |
| Spring WebFlux 7 | verlangt Java 17; der asynchrone Transport (ADR 0001) müsste ersetzt werden |
| PDFBox 3, jsoup, ONNX Runtime | Java 8 möglich |

Ein Java-8-Core wäre damit ein Neubau großer Teile von Cache, Suche, Transport und Domänenmodell, mit hohem Risiko
für die festgelegten Hashes (`page-metadata/v1`, `page-attributes/v1`), die Konverter-Versionen und das Indexformat.

**Empfehlung und Festlegung:** Der Core bleibt Java 21 (Toolchain und `release 21`). Java-8-Anwendungen
(MainframeMate, askai-java8) binden Space Harvester über die REST-API an; der gemeinsame Java-8-Boden bleibt die
Upstream-Bibliothek in enterprise-ai-client, aus der `enterprise-ai-sources` kopiert ist. Sollte später ein
Java-8-Client nötig sein, wäre ein kleines eigenes Modul mit den API-DTOs und einem HTTP-Client der richtige Weg,
nicht ein zurückportierter Core.

## 6. Migrationsschritte

Jeder Schritt ist ein eigener PR; danach ist `main` grün, alle REST-Routen bleiben gleich.

1. Dieses Dokument.
2. **Server-Modul:** `src/`, die Boot-Konfiguration aus `build.gradle` und `bootJarPacCheck`/`demoConfluence` per
   `git mv` nach `space-harvester-server/`; die Wurzel wird Aggregator. Kein Code ändert sich.
3. **Core-Modul:** die Pakete aus Abschnitt 3 mit ihren Tests per `git mv` nach `space-harvester-core/`,
   `java-library` mit eigenen Abhängigkeiten, `LayeringTest` je Modul, Modulregeln.
4. **Öffentlicher Einstieg und Veröffentlichung:** `Harvester` mit einem Test, der ohne Spring und ohne HTTP-Server
   des Dienstes einen Space gegen eine Fake-Confluence abgleicht und exportiert; `maven-publish` und POM-Prüfung;
   ADR 0005; README und Dokumente mit den neuen Pfaden; Workflows auf die neuen Testverzeichnisse.

## 7. Offene Punkte

- Ein eigenes Konnektor-Modul (`space-harvester-connector-confluence`) erst, wenn ein zweiter Quellentyp außerhalb
  der Upstream-Kopie dazukommt.
- Signatur, Release-Workflow und tatsächliche Veröffentlichung auf Maven Central entscheidet Angelo später.
- Ein Austausch von Spring WebClient gegen Reactor Netty direkt würde die letzte Spring-Abhängigkeit des Core
  entfernen; nur sinnvoll zusammen mit einer neuen Abnahme des Transports.
