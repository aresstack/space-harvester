# Space Harvester – parallele Entwicklungsstränge

**Stand:** 16.10.2026 · **Verbindliche Grundlage:** [Implementierungsplan](IMPLEMENTATION_PLAN.md) und [Roadmap #20](https://github.com/aresstack/space-harvester/issues/20).  
**Zweck:** Den bisherigen M0–M8-Plan **nicht mehr als serielle Ticketwarteschlange** behandeln. Meilensteine bleiben für die Produktabnahme sinnvoll; Arbeit an unabhängigen Bausteinen läuft **gleichzeitig**.

> **Module seit #100:** Die hier genannten Pakete liegen in `space-harvester-core` (`application`, `source`, `persistence`, `output`, `embedding`) oder `space-harvester-server` (`api`, `config`, `security`, UI). Zuordnung: [MODULES.md](MODULES.md).

## Arbeitsmodus: sieben aktive Stränge

Die Kennungen A–G bezeichnen **Arbeitsstränge**, nicht automatisch bereits gestartete Claude-Code-Sessions oder fest zugewiesene Personen. Ein Strang arbeitet auf **eigenem Feature-Branch/Worktree**, ein verantwortlicher Thread koordiniert alle Änderungen an seinen Dateien. PRs einzeln reviewen und mergen; den Code der anderen Stränge nicht ungefragt umschreiben.

| Strang / unmittelbare Priorität | Zugeordnete Issues | **Jetzt unabhängig ausführbare Arbeit** | Integration / späteres Gate | Primär zu ändernder Bereich |
| --- | --- | --- | --- | --- |
| **A – Konnektivität** (aktiv) | #33, #34; [Upstream win-proxy-java #4](https://github.com/aresstack/win-proxy-java/issues/4) | #33-Code via PR #37 gemergt; **PR #36** (#34, PAC) mit mTLS aus aktuellem main zusammenführen, geprüfte Secret-Redaktion und CI; getrennte reale Windows-/PAC-Tests vorbereiten | **Gemeinsamer** PAT + mTLS + PAC/HTTPS-CONNECT-Livetest im Dienstkonto; spätere WebClient-/asynchrone Transport-SPI separat | `source/network`, `source/confluence/ConfluenceSources`, Spring-Netz-/TLS-Konfiguration |
| **B – Native Daten und Quellfähigkeit** (aktiv) | #7, #2, #8, #9; #24 (nur native Content-Fähigkeit) | **PR #38** mit Raw-Payload-Spike/ADR; danach Upstream-SPI für unveränderte JSON-HTTP-Body-Bytes über vorhandenen Connector; quellenneutralen `PageSnapshot`-Vertrag festlegen; Fixturetests; Medienreferenzen nur erhalten | Upstream-PR mergen und **unverändert** nach `enterprise-ai-sources` synchronisieren; bytegenauer Live-/Fixture-Nachweis | Upstream `source-api` / `source-confluence`, Harvester-`application/snapshot`; vendorte Kopie niemals direkt ändern |
| **C – Persistenz und Suche** (sofort starten) | #26, später #28 | Lucene-Indexschema, lokales Repository, `StoredField` für native Bytes, stabile IDs + Revisions-/Parent-Felder, Neustart-/Recovery-/Upsert-Tests **mit Fake-Snapshots**; Such-Analyzer/Indexleser konzipieren | Integration mit dem in B festgelegten Snapshot-/Repository-Port; Titel-/Metadatensuche nach stabilem Lucene-Index, keine externe Datenbank | Eigenes `persistence/lucene`-Paket; Index-Tests |
| **D – Synchronisierung und Vollständigkeit** (sofort starten) | #16, Rest von #6, #10, #13 | **Reine Sync-Planung** aus vollständig paginierter Inventur und den bereits implementierten `page-metadata/v1`-/`page-attributes/v1`-Fingerprints; Space-Fingerprint aus sortierten IDs **nur bei bestätigtem vollständigem Lauf**; Zustände `incomplete/stale/confirmed`, Abbruch/Umzug/Lösch-Simulationen | Persistenz von Generationen an C anbinden; nativen Body-Abruf aus B ergänzen; keine echten Löschungen aus fehlgeschlagenen Läufen | `application/sync` und Fixturetests, **nicht** Lucene-Implementierung |
| **E – Jobs und Exporte** (sofort starten) | #12, #11, #13 (Job-Fehler), #15 (Jobtests) | Quellenneutrale Jobzustände, Status/Cancel/Progress, asynchroner Ablauf mit Fake-Leseport; Manifest und **flache** JSON-/NDJSON-Streaming-Schnittstelle plus separater ID-Baum **ohne** Confluence-Abfragen oder Lucene-Import | Tatsächlichen Page-Reader/Cache von C und bestätigte Generationen von D anbinden; API-Zugriffsschutz F; End-to-End-Export danach | `application/jobs`, `application/export`, Jobs-/Exportcontroller und deren Tests |
| **F – API-Sicherheit und Benutzerrechte** (sofort starten) | #14, #24 (Delegation/Port), #3 Rest; #22 **optional** | Zugriffsschutz für **bestehende** HTTP-Endpunkte/technische Clients, Test auf 401/403/fail-closed, Secrets und Logs prüfen; separaten `canRead`-Port und Rechtekontext definieren **ohne** den Live-OAuth-Flow schon vorauszusetzen | Berechtigung an Cache-Pages, Baum, Suche und Export von E/G durchsetzen; delegiertes OAuth erst mit bestätigtem Confluence-Application-Link testen | `security` / Spring-Security-Konfiguration, Auth-Integrationstests; **nicht** `ConfluenceSources` |
| **G – Query-API, Baum und Suche** (sofort starten, zuerst vertragsbasiert) | #27, #28, #15 | API-/OpenAPI-Vertrag und DTOs; pure Tree-Builder aus **flachen** `sourceId/pageId/parentId`-Einträgen, mehrere Roots/Umzüge/Zyklen; Cache-Read-Port als Fake; kontrollierte Cursor/Limit-Semantik; Suchquery-/Filtervertrag | Index-Reader C anschließen, Sync-Stand D anzeigen, F-Zugriffsschutz durchsetzen; `?refresh=true` nur über echten B-/D-Upstream-Check, **nie** scheinbar frischen Cache liefern | `application/query`, `application/tree`, eigene API-Routen/Tests; vorhandene Quellencontroller nicht blind ersetzen |

**Wichtig:** B, C, D, E, F und G können **jetzt** gleichzeitig an separaten Zweigen beginnen. Lediglich ihre **Integration** wartet auf wenige gemeinsame Ports. A läuft bereits. Keine künstliche Kette „erst #7 vollständig, dann #8 vollständig, dann #26 vollständig, dann #12“.

## 1. Zwei kurze Schnittstellenabstimmungen statt serieller Entwicklung

**Vertragsfenster 1 – Native Snapshots / Repository (B + C + D + G):** Innerhalb des ersten kleinen PR/ADR den minimalen quellenneutralen Vertrag festlegen: Ressourcenidentität (`sourceId + pageId`), `revision`, `parentId`, unveränderte `nativePayload`-Bytes, `rawSha256`, Hash-Schemata, Read-/Write-/Query-Ports und fachliche Fehler. C entwickelt währenddessen mit Test-Fakes; B integriert erst später die nativen Bytes. Fachliche Ports dürfen **keine Lucene-Typen**, Web-/Spring-Typen oder Confluence-DTOs in die Anwendungsdomäne tragen.

**Vertragsfenster 2 – Konsistenz / Exporte / Berechtigungen (C + D + E + F + G):** Gemeinsame Semantik der bestätigten Cache-Generation, `stale`-/`incomplete`-Sicht, Cursor/Stabilität, `refresh=true`-Fehlerfall, Reader-API für Exportjobs und autorisierte Ausgabe. Ein kurzer Interface-PR reicht; danach Fakes und Implementierungen **parallel**, nicht erst nach Fertigstellung aller anderen.

Bei einem nötigen Portbruch Änderungs-PR an den **Port-Owner** geben und Consumer-Threads informieren. Keine mehrfachen konkurrierenden Änderungen an `application/sources` oder `ConfiguredSourceCatalog`.

## 2. Merge- und Konfliktregeln

1. **Ein PR = ein klarer Scope, ein eigener Branch**; keine Sammel-PRs über alle offenen Tickets und keine parallelen Commits auf denselben Branch. Ein gemeinsamer Basiscommit muss nicht auf allen Threads gleich bleiben.
2. **Dateibesitz vorab klären:** A hat vorerst exklusiv `ConfluenceSources`, `ConfiguredSourceCatalog` und Proxy-/TLS-Verdrahtung. F baut Sicherheitsfilter unabhängig davon. B bearbeitet den Confluence-Adapter **upstream**; Harvester-Vendor-Dateien werden nur über das Sync-Skript geändert. C besitzt den Lucene-Adapter. E/G teilen keine Controller-Datei ohne abgesprochenen Split.
3. **PRs nicht auf andere PRs blockieren, wenn nur Tests/Fakes betroffen sind.** Auch ohne vollständigen Cache können Pure Domain-Tests, Jobstatus, Serializer, Tree-Algorithmen, Spring-Security-Gates und Index-Neustarttests implementiert werden.
4. **Vor jedem Merge:** Branch gegen aktuellen `main` prüfen, CI grün und Review-Hinweise bearbeitet; bei Draft **zuerst Ready for Review auslösen**, nicht auf irgendeinen CI-Status als Voraussetzung für Ready warten. Merge unabhängig vom Status anderer Arbeitsstränge; nur echte Konflikte/Abhängigkeiten lösen.
5. **Keine unsicheren Zwischenzustände:** Zugriffsregeln fail-closed, keine Secrets in Logs, kein `complete=true` als transaktionalen Quellsnapshot interpretieren, keine unbestätigten Löschungen. Der produktive Unternehmensnetztest benötigt A vollständig; lokale/Fake-Tests nicht.
6. **Integrations-PRs** kombinieren fertige Ports/Adapter, anstatt frühe PRs nachträglich in riesige Cross-Cutting-Änderungen zu verwandeln. CI/Regressionstests sind Teil jedes Strangs (#15).

## 3. Konkreter Start-Backlog (ohne weitere Tickets zu erzeugen)

| Arbeitsstrang | Nächster eng abgegrenzter PR | Unmittelbare Definition of Done |
| --- | --- | --- |
| A | PR #36 nach #37 zusammenführen; PAC + mTLS gleichzeitig verdrahten | Geeignete PAC-Routen/CONNECT, kein Zertifikatsverlust, Log-Redaktion, grüner Build; echter Windows-Test separat nachhalten |
| B | PR #38 Spike abschließen, dann separater Upstream-`StoredContentSource`-PR | Native JSON-Body-Bytes mit unverändertem Hash über vorhandenen Confluence-Transport + Tests |
| C | `#26`: Lucene-SnapshotRepository + Indexschema auf temporärem Verzeichnis | Roundtrip der Payloadbytes, Upsert, Parent-Abfragen, Neustart/Recovery |
| D | `#16`: reiner Change-/Sync-Planner inkl. Rest-`#6`-Space-Fingerprint | Neue/geänderte/umgezogene/verschwundene Seiten unterscheiden, unvollständige Enumeration erzeugt keine bestätigte Löschung |
| E | `#12`: Job-State-Machine + Fake-Runner, danach `#11`-NDJSON | Start/Status/Progress/Cancel/Error ohne echte Confluence-/Lucene-Abhängigkeit |
| F | `#14`: geschützter technischer API-Zugriff und Tests | Anonymer Zugriff auf sensible API-Daten verweigert, Berechtigungsregeln vor Integration klar |
| G | `#27`: Tree-Builder + Query-Port-Fake und API-Spezifikation | Flache Pages und IDs-only-Tree konsistent, mehrere Roots und Paging-Tests |

**Kapazitätsregel:** sieben technische Stränge dürfen parallel vorankommen; nicht 27 konkurrierende Cross-Cutting-PRs eröffnen. Sobald ein Strang seinen ersten PR abgegeben hat, kann er innerhalb seines Besitzbereichs weitere unabhängige Tests/Adapter bauen.

## 4. Nachrücker und bewusst späterer Ausbau

- **#28**: Lucene-Metadaten-/Titelsuche nach C-Indexschema; Query-Port kann G bereits vorher definieren.
- **#9**: Referenzen/Attachment-Metadaten in B konservativ erhalten; späteres echtes Asset-Laden ist **#19**.
- **#15**: Querschnitt über alle sieben Stränge, eigene Integrations-/Lasttest-PR erst, wenn die öffentlichen Ports feststehen.
- **#22**: Optionaler Azure Key Vault und restlicher Config Server aus #3, **kann unabhängig als späterer Strang nachrücken**, nicht Pflicht für lokale Entwicklungs-/PAT-Tests.
- **#29**: Enrichment-SPI/Fake-Prozessor kann nach stabilen Snapshot-Ports in einem zusätzlichen Thread vorbereitet werden; NLP-Produktion erst nach C.
- **#30**: Converter-SPI für Markdown/PDF/Word kann nach dem Export-Port E vorbereitet werden; **keine** Pflicht-Konvertierung während Harvesting. Plain JSON/NDJSON gehört zu #11.
- **#31**: Confluence-Kompatibilitätsmatrix als *reine Spezifikation/Tests* schon unabhängig recherchierbar, die Read-only-Fassade erst nach G/C.
- **#17**: UI-Entwurf erst mit vereinbartem OpenAPI-Vertrag, kein Backend-Blocker.
- **#18**: Quellenneutralität an Ports/Fixtures überprüfen; ein zweiter konkreter Adapter ist später.

## 5. Was tatsächlich „fertig“ bedeutet

**Parallelisierung schafft Durchsatz, keine Freigabe zum Ignorieren von Gates.**

- **Technischer MVP:** bestätigte vollständige Ernte mit bytegenauen Rohdaten, persistenter Lucene-Cache, inkrementeller Sync, Pages/Tree/Search/JSON-Export, Autorisierung und nachvollziehbaren Cache-Zuständen (`#7/#8/#26/#16/#10/#27/#28/#11/#12/#14`).
- **Produktives Confluence-Live-Gate:** PAT + mTLS + PAC/HTTPS-CONNECT (inklusive Windows-Dienstidentität) funktionieren zusammen (`#33/#34`); das bleibt von lokalem Mock-basieren Feature-Fortschritt getrennt.
- **Spätere Erweiterungen:** NLP-Enrichments, Medienbytes und Beschreibungen, Markdown/PDF/Word, Confluence-API-Fassade, UI und weitere Quellen (`#19/#29/#30/#31/#17/#18`).

## 6. Status bei Erstellung

- **Gemergt:** [#35](https://github.com/aresstack/space-harvester/pull/35) (Einzelmetadaten/`attributesHash`) und [#37](https://github.com/aresstack/space-harvester/pull/37) (isoliertes mTLS).
- **Bereits in Arbeit:** [#36](https://github.com/aresstack/space-harvester/pull/36) (PAC/Proxy; Integration mit mTLS prüfen), [#38](https://github.com/aresstack/space-harvester/pull/38) (Raw-First-Spike, Draft).
- **Upstream-Härtung:** [win-proxy-java #4](https://github.com/aresstack/win-proxy-java/issues/4) für DNS-/IP-/Zeit-PAC-Helper und Downloader-Limit.
- **Noch nicht automatisch gestartet:** Die separaten Entwicklungsstränge C–G benötigen jeweils einen eigenen ausführenden Thread/Agenten. Dieses Dokument koordiniert ihre Arbeit, führt sie nicht selbst im Hintergrund aus.

**Quelle der Wahrheit:** Architektur in `IMPLEMENTATION_PLAN.md`, Ausführung/Parallelisierung hier, konkrete Akzeptanzkriterien in den jeweiligen Issues. Falls ein Thread andere Prioritäten oder Schnittstellen benötigt, hier nachvollziehbar aktualisieren – nicht eigenmächtig die Raw-First-/Lucene-Entscheidungen ändern.
