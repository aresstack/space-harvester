# Space Harvester – Umsetzungsplan

**Architekturstand:** 16. Oktober 2026 · **Status:** verbindlicher Arbeitsplan, Implementierungen jeweils über eigene PRs.  
**Leitentscheidung:** Raw-First, **eingebettetes Apache Lucene** als persistenter Cache **und** Suchindex, flache Seiten plus separater ID-Baum, versionierte Metadaten und optionale Enrichments; Konvertierung erst am Ende.

Zentrale Planung: [Roadmap #20](https://github.com/aresstack/space-harvester/issues/20). Dieses Dokument beschreibt das **Vorgehen**; die Issues enthalten die **konkret abzuarbeitenden Akzeptanzkriterien**. Bei Widersprüchen zur älteren Text-First-Planung gilt die hier beschriebene Raw-First-Entscheidung, bis eine neue explizite Entscheidung getroffen wird.

## 1. Ziel und bewusst nicht im ersten Inkrement

Space Harvester ist ein **headless, leichtgewichtiger, quellenunabhängiger Read-Cache, Harvester und Suchdienst** mit Confluence Data Center als erster Quelle. Er inventarisiert Quellen, ruft native Daten ab, bewahrt sie möglichst originalgetreu, indiziert ausgewählte Metadaten, synchronisiert Änderungen und bietet eine eigene REST-/OpenAPI-Abfrageschicht sowie Exporte.

**Nicht** im initialen Rohdaten-MVP: eine verpflichtende Markdown-/PDF-/Word-Konvertierung, generative Text-KI, Embeddings, ein vollständiger Confluence-REST-Klon, Binärdownloads von Bildern/Anhängen, ein externes Datenbanksystem, eine UI oder weitere Quellenadapter. OAuth2-Benutzerdelegation bleibt als separates **verbindliches Implementierungsziel von #24** bestehen, ist aber keine Voraussetzung für einen geschützten technischen Exportdurchstich.

**Technologiewahl:** Java 21, Spring Boot, der bereits übernommene Connector aus `enterprise-ai-sources` und **Apache Lucene auf lokalem persistierendem Speicher**. Kein PostgreSQL, kein Pflicht-H2, keine externe Such- oder Datenbankinfrastruktur. Bei Bedarf können Testfälle mit einem temporären Lucene-Verzeichnis laufen. Lucene ist **keine relationale Transaktionsdatenbank**; Commit-, Recovery- und Generationenlogik müssen ausdrücklich entworfen werden.

## 2. Grundmodell und Invarianten

### 2.1 Eine Seite bleibt ein flaches Objekt

- Primärer Schlüssel: mindestens `sourceId + pageId`; bei bewahrter Historie zusätzlich `revision`. Confluence-Page-IDs sind nicht über verschiedene Instanzen eindeutig.
- Pro Seite werden Verwaltungsfelder wie `sourceId`, `spaceKey`, `pageId`, `parentId`, Titel, Version, Änderungsdatum, Quell-URL und Cache-/Abrufzeit indiziert.
- **Native Payload:** Originalbytes des empfangenen Confluence-REST-JSON-HTTP-Bodys **nach Transportdekodierung**, einschließlich angeforderter `body.storage`-Repräsentation. Ein DTO dient ausschließlich Typisierung/Metadaten; die Payload wird **niemals aus DTOs rekonstruiert**.
- `rawSha256` bezeichnet einen Hash **über diese tatsächlich gespeicherten Bytes**. `metadataHash` ist der mit PR #32 festgelegte Fingerprint über `page-metadata/v1` (`schema`, `sourceId`, `pageId`, `spaceKey`, `title`, `parentPageId`, `version`, `modifiedAt`), **ohne** `attributes`/Labels. Ein getrennter versionierter `attributesHash` über normalisierte, stabil verfügbare und sortierte Quellattribute erfasst z. B. Labeländerungen (#6/#16). Andere Felder/Algorithmen dürfen nicht unter demselben Hashnamen vermischt werden.
- Original-JSON ist nur vollständig **im Umfang der abgerufenen REST-Antwort**. Anhänge, Plugin-Daten oder nicht angeforderte Expand-Felder sind nicht automatisch enthalten (#7).
- **Bytegenaue Original-Ansicht** und **normalisierte Darstellung** sind verschiedene API-Produkte: in einem zusammengesetzten JSON-Container können sich Serialisierung und Whitespaces ändern, obwohl `data` dieselbe Struktur besitzt.

### 2.2 Der Baum ist separat

- Hierarchie wird über `parentId` / Page-IDs abgebildet, standardmäßig **ohne** Seiteninhalte. Es gibt ein `roots[]`-Array, da ein Space mehrere Root-Seiten haben kann.
- Keine permanente Verdopplung aller Seitenpayloads in verschachtelten Baumobjekten; die API erzeugt den Baum aus indizierten Beziehungen.
- Flache Seite/Seitenliste und Tree-View müssen auf demselben ausgewiesenen Synchronisations-/Cache-Stand beruhen. Verwaiste Eltern, Zyklen, Verschiebungen, Löschungen und unvollständige Abrufe nicht verstecken.

### 2.3 Antwortcontainer nur zusätzlich

Schematisch, **kein bereits implementierter Vertrag**:

```json
{
  "data": {
    "id": "123456",
    "type": "page",
    "title": "Architektur",
    "version": { "number": 7 },
    "body": { "storage": { "value": "<p>Beispiel</p>", "representation": "storage" } }
  },
  "metadata": {
    "sourceId": "confluence-intern",
    "spaceKey": "DEV",
    "pageId": "123456",
    "parentId": null,
    "harvestedAt": "2026-10-16T15:00:00Z",
    "rawSha256": "sha256:...",
    "metadataHash": "sha256:...",
    "cacheState": "current"
  },
  "enrichments": {}
}
```

`data` ist ein Confluence-Objekt (ggf. semantisch gleiches JSON); `metadata` enthält Harvesting-/Revisions-/Cacheinformationen; `enrichments` enthält **optionale, separate** Analyseergebnisse. Für die **ursprüngliche Bytefolge** wird ein eigener Raw-View/Payload-Stream angeboten. Fehlende Enrichments bedeuten **keinen** fehlgeschlagenen Raw-Export.

### 2.4 Medien und Konvertierung

- `body.storage` enthält Referenzen auf eingebettete Bilder und Diagramme, **nicht** deren Binärdaten. Originale Referenzen immer in Raw-Payload erhalten, optionale normalisierte Referenzliste später (#9).
- **Keine** Bild-, Anhangs-, draw.io- oder Makroausführung im Raw-MVP; spätere Downloads **separat oder on demand** (#19).
- **Converter erst an der Ausgabegrenze:** Native JSON/NDJSON-Ausgabe zuerst (#11). Markdown/PDF/DOCX später über versionierte Converter (#30); keine Änderung am Raw-Cache durch Conversion.
- Optionale klassische NLP-Enrichments (Akronyme, Entitäten, Eigenschaften, Aktivitäten, Wort-/Subjekt–Prädikat–Objekt-Relationen) erst nach stabilem Cache (#29). Für jede linguistische Analyse nur eine **sichere interne Textprojektion** ohne unbekannte/dynamische Makroausführung; das ist kein Pflicht-Markdown-Export.
- **Generative KI nur optional für spätere Bildbeschreibungen** vorgesehen; Bildbeschreibungen tragen Asset-Hash, Modell/Version, Zeitpunkt und Provenienz und unterliegen demselben Berechtigungsschutz wie das Bild.

## 3. Technische Komponenten und Grenzen

| Komponente | Aufgabe | Darf nicht |
| --- | --- | --- |
| Upstream-Quellenadapter (`enterprise-ai-client`) | Confluence-Transport, Basic/PAT, optionale `PartitionListingSource` und künftige `StoredContentSource` | Einen zweiten Confluence-REST-Client im Harvester erzwingen |
| Ausgehender Quelltransport (#33/#34) | Quellenbezogenes mTLS (Windows-MY/PKCS12) und konfigurierbare Proxy-/PAC-Route über bestehende Upstream-Ports, getrennt von HTTP-Authentifizierung | Zertifikate/Token an fremde Ziele weitergeben; PAC-Ausfall stillschweigend mit DIRECT umgehen; Upstream-Fachlogik duplizieren |
| Konfiguration / Quellenkatalog | YAML, Quell-ID, Authentifizierungsstrategie, optionale Secrets | Azure/Config Server/OAuth im Basisbetrieb erzwingen |
| Harvester-Anwendungsfälle | Inventory, Seitenabruf, Sync, Cache-Freshness, Jobsteuerung | An Spring, Confluence-DTO oder Lucene-API fest gekoppelt sein |
| **Lucene-Cache** (#26) | Unveränderte Payloadbytes in binären StoredFields, Verwaltungsindex, Parent-Beziehungen, Cache-Generation, Enrichment-Verweise | Als SQL-Datenbank/ACID-Mehrtabellentransaktion ausgegeben werden; sensible Daten ungefiltert ausliefern |
| Query/Tree-API (#27) | Einzelne Raw-Seite, flache paginierte Liste, ID-Hierarchie, kontrollierte Metadata-/Enrichment-Views | Große Inhaltsbäume ohne Limits zusammenstellen; Berechtigungen umgehen |
| Suche (#28) | Titel-/Metadatenfilter aus Lucene; später sichere Body-Volltextprojektion | Rohes `body.storage` inklusive dynamischer Makros ungeprüft als sichtbare Snippets indexieren |
| Ausgabe (#11/#30) | Plain JSON/NDJSON und später Converter-Ergebnisse, Manifest, Downloads | Raw-Original überschreiben |
| Zugriffsschutz (#14/#24) | Technische Client-Rechte und spätere Nutzerdelegation trennen | Servicekonto-Leserecht als Endnutzerfreigabe behandeln |

**Lucene-Layout:** Index-/StoredFields mindestens `sourceId`, `pageId`, `spaceKey`, `parentId`, `title`, `version`, `modifiedAt`, `metadataHash`, den getrennten `attributesHash` (#6/#16), `rawSha256`, `rawJson` und Cachezustand. Exakte Queries/DocValues, Feldtypen, Historie, Segment-/Commitstrategie und Größenlimits werden in #26 spezifiziert. Ein einziger kontrollierter Writer je Index; Reader-Refresh und Index-Lebenszyklus explizit testen. Große Binärassets nicht in Lucene speichern; bei späterem Bedarf ein einfaches lokales Dateiverzeichnis verwenden. Bereits vorhandene Lucene-Bausteine aus dem Enterprise AI Client evaluieren (#2/#26), **ohne** sie ungeprüft als Snapshot-Repository einzusetzen.

## 4. Eigene REST API vor Confluence-Kompatibilitätsfassade

Die folgenden Routen sind **Zielverträge**, keine bereits vorhandenen Funktionen (Ausnahmen: `/api/v1/info` und nach Merge von #25 die Quellen-/Space-Abfragen).

| Route | Bedeutung / Ergebnis |
| --- | --- |
| `GET /api/v1/sources` | Konfigurierte Quellen, ohne Secrets (#25) |
| `GET /api/v1/sources/{sourceId}/spaces` | Sichtbare Spaces (#4/#25) |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/pages/metadata` | Live oder explizit als Cache-Ansicht ausgewiesene Metadaten ohne Body, paginiert (#5) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}` | Einzelnes natives Quellobjekt aus dem geschützten Cache (Raw-View, ohne Harvester-Metadaten) (#27) |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}?refresh=true` | Berechtigte explizite Aktualitätsprüfung der Seite gegen Confluence-Metadaten; Raw-Body bei Änderung aktualisieren. Bei Prüffehlern kein unmarkierter Stale-Fallback, sondern dokumentierter Fehler (#16/#27). Standard `refresh=false`: Cache-only mit gekennzeichnetem Stand. |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}/metadata` | **Kanonische Einzel-Metadatenroute** ohne Space-Key: Revision, Metadaten- und Attributhashes, Freshness und optionale Enrichments ohne Body. Ein Space-Wechsel ändert nicht die Page-ID (#6/#27). |
| `GET /api/v1/sources/{sourceId}/pages/{pageId}?view=extended` | Container mit `data`, `metadata`, `enrichments` (#27) |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/pages` | Flache, **paginierte** Seitenliste; Content nur wenn ausdrücklich angefordert (#27) |
| `GET /api/v1/sources/{sourceId}/spaces/{spaceKey}/tree` | Separater Baum mit `roots[]`, IDs und Kindern; mehrere Roots (#27) |
| `GET .../tree?include=metadata` | Optional Meta je Node; `content` und `enrichments` nur mit Limits und separater Berechtigungsprüfung (#27) |
| `GET /api/v1/search` | Zunächst Titel/Metadaten/Filter; sichere Volltextsuche später (#28) |
| `POST /api/v1/sources/{sourceId}/spaces/{spaceKey}/exports` | Geschützter asynchroner Rohdatenexport (#10–#12) |

**Wichtig:** Eigene API ist bewusst **nicht** automatisch Confluence-REST-kompatibel. Exakte Confluence-Semantik (`expand`, `_links`, Pagination, Statuscodes) ist ein **späteres, separat getestetes Read-only-Produktinkrement** (#31). Erst benötigte Lese-Endpunkte dort übernehmen, nicht die gesamte Schreib-API duplizieren.

Flache JSON-Ausgabe: ein Array von Seiten mit `pageId`, Header (Version und verfügbare Quellmetadaten) und Content (native Storage-Daten), **ohne** eingebettete Baumstruktur. Hierarchie **separat** als `roots[]`/Child-IDs ausliefern. Große Spaces über Cursor/NDJSON/Streaming statt ungebremstem synchronem Gesamt-Array bedienen. Natives Einzelobjekt und vereinfachtes flaches Exportformat sind **unterschiedliche Ansichten**.

## 5. Cache-Aktualität und Synchronisierung

1. **Inventarisieren:** Für jede konfigurierte Quelle Space- und Seitenmetadaten **vollständig paginiert** abrufen (#4/#5/#167); `discover("space:X")` ist wegen Homepage-Unterbaum und Limits kein geeigneter vollständiger Inventarlauf.
2. **Abgleichen:** Quellrevision und `metadataHash` (`page-metadata/v1`, PR #32) sowie den unabhängigen `attributesHash` mit dem zuletzt **vollständig** bestätigten Indexstand vergleichen (#6/#16). Eine reine Label-/Attributänderung erfordert lediglich eine Aktualisierung der Cache-Metadaten, nicht automatisch einen erneuten Abruf des nativen Bodys.
3. **Inhalte nur gezielt laden:** Neue/geänderte Seiten über optionale native `StoredContentSource`-Capability upstream holen (#2/#7), Raw-JSON-Bytes speichern und Header/Parent-Beziehungen aktualisieren (#8/#26).
4. **Löschungen/Unsichtbarkeit unterscheiden:** Eine fehlende Seite bei fehlerhafter Pagination, verlorenem Zugriff oder abgebrochenem Lauf darf nicht als bestätigte Löschung behandelt werden.
5. **Sync-Generation veröffentlichen:** `complete=true` beim letzten Cursor bedeutet nur das Ende der Pagination, **nicht** einen atomaren Confluence-Snapshot. Erst nach vollständiger, fehlerfrei und auf offensichtliche Inkonsistenzen geprüfter Enumeration eine neue bestätigte Bestands-Generation markieren; partieller Lauf bleibt sichtbar und nicht „vollständig“. Wiederaufnahme, Retry und Index-Commit dokumentieren (#12/#13/#16/#26).
6. **Freshness kenntlich machen:** `lastCheckedAt`, Metadatenrevision, `cacheState` (z. B. fresh/stale/incomplete/not-fetched) und optionalen Abfrage-Modus dokumentieren. **Cache-Hit ist kein Beweis für Aktualität oder Zugriffsberechtigung.**
7. **Enrichments invalidieren:** Bei verändertem Input-Hash oder Prozessor-/Modellversion ausschließlich die passenden abgeleiteten Ergebnisse als veraltet markieren (#29/#19).

**Abfrage-Vertrag:** `?refresh=true` verlangt eine echte upstream-seitige Metadaten-/Versionsprüfung mit Timeout/Rate-Limits. Nur bei Änderung wird der Rohinhalt nachgeladen; bei Fehler kein unmarkierter Fallback aus dem Cache. Die Berechtigung zur Aktualisierung bleibt von der Berechtigung zur Datenausgabe getrennt. Die genaue OpenAPI-Fehlerabbildung steht in #27.

Ein Periodic Sync ist sinnvoll, aber die konkrete Kadenz ist **konfigurierbar und kein fest behaupteter SLA**. Auf Zuruf kann ein technischer Client Refresh/Export als gesicherten Job anstoßen. Bei Confluence-Ausfall sind gespeicherte Daten als **stale** gekennzeichnet; ob sie ausgeliefert werden dürfen, entscheidet explizite Cache-Policy **und** unabhängige Berechtigungsprüfung.

## 6. Sicherheitsrahmen vor produktiver Ausgabe

- Servicekonto (Basic/PAT, minimal nötige Leserechte) erntet Daten; daraus folgt **keine** pauschale Endnutzerfreigabe.
- Raw-Payloads, Seitenüberschriften im Baum, Referenzen, Akronyme, Snippets, Bildbeschreibungen und konvertierte Artefakte sind potenziell sensibel.
- **MVP-Betriebsmodus:** Nur autorisierte technische Clients erhalten Rohdaten; ggf. ausdrücklich für die gesamte Nutzergruppe freigegebene Teilkorpora. Fehlende Freigabe → **fail closed** (#14).
- **Delegierter Zielmodus (#24):** Aktuelles Nutzerleserecht über Confluence mit dem diesem Benutzer zugeordneten delegierten Token prüfen; 401/403/404, Login-Redirect, Timeout und unklare Antwort verweigern Ausgabe. Kein Vertrauen in frei gesetzte Usernamen oder ins Service-PAT für diese Prüfung. Durchsetzung auch im konsumierenden Knowledge-/MCP-Dienst erforderlich.
- Konfiguration: lokale, **nicht eingecheckte** YAML darf Passwort oder PAT direkt enthalten; Umgebungsvariablen, Spring Cloud Config und Azure Key Vault sind **optionale** Quellen (#3/#22). Fehlende Secrets nicht stillschweigend als anonyme Anmeldung auslegen.
- **Produktive Confluence-Data-Center-Anbindung:** Laut dokumentiertem Live-Test ist ein **ausgehendes TLS-Clientzertifikat zusätzlich zu PAT/Basic** erforderlich (#33). mTLS, die Server-CA/Trust-Policy, die Proxy-Route (#34) und die HTTP-Benutzeridentität sind getrennte Konfigurations-/Sicherheitsaspekte. Windows-MY im tatsächlich ausführenden Dienstkonto und PKCS12-Dateien für portable Deployments unterstützen; weder private Schlüssel einchecken noch bei aktivem mTLS ohne Zertifikat fortfahren.
- **Proxy/Transport:** #34 soll bestehendes `win-proxy-java`, `HttpRoutePort` und Confluence-Transportschnittstellen wiederverwenden. Ein möglicher Spring-WebClient-/asynchroner HTTP-Transport wird als **eigener ADR-/Test-Schritt** entschieden und darf nicht stillschweigend die Confluence-Fachlogik oder das Raw-First-Modell ersetzen. mTLS (#33) muss unabhängig vom vollständigen PAC-Ausbau direkt testbar bleiben.
- Log/API/Index/Artefakte dürfen keine Passwörter, PATs, OAuth-Tokens, Auth-Header oder Cookies enthalten. Rohdaten-Endpunkte nicht schon wegen „interner Netzbetrieb“ ungeschützt lassen.

## 7. Umsetzung in kleinen PRs

**Paralleler Arbeitsmodus:** Die folgenden M0–M8-Etappen definieren **Produkt-/Integrationsmeilensteine, keine serielle Reihenfolge für Entwickler oder Claude-Code-Threads**. Der verbindliche [Parallelisierungsplan](PARALLEL_WORKSTREAMS.md) bündelt die Tickets in sieben gleichzeitig bearbeitbare Stränge A–G, mit eigenen Branches, Datei-Ownern und schmalen Port-Verträgen. An Lucene (#26), Sync (#16), Jobs (#12), API (#27), Zugriffsschutz (#14) und Raw-Payload-Spike (#7) kann **parallel** gearbeitet werden; Integrations-PRs warten nur auf die tatsächlich benötigten Ports und grünen Tests. Netzwerk-Live-Tests (#33/#34) blockieren nicht die Mock-basierte Entwicklung.

**Stand bei dieser Planung:** PR #35 (Einzelmetadaten, `page-attributes/v1`) und PR #37 (mTLS-Basis) sind gemergt; PR #36 (PAC/Proxy mit mTLS integrieren) und PR #38 (Raw-Spike) laufen. Die echten Windows-/PAC-/mTLS-Integrationstests sind noch ausstehend.



**M0 – Fundament / laufende Arbeit (bereits gestartet)**
- [x] Spring-Boot-/REST-Grundgerüst: #1, PR #21.
- [x] Headless-Vendoring von Confluence/source-api/adyton/network: PR #23.
- [x] Upstream-Pagination: [enterprise-ai-client PR #167](https://github.com/aresstack/enterprise-ai-client/pull/167) gemergt, über PR #32 in `enterprise-ai-sources` synchronisiert (#2/#5).
- [x] YAML-Quellen, Basic/PAT, Spaces: [space-harvester PR #25](https://github.com/aresstack/space-harvester/pull/25) gemergt (#3/#4/#24 Teil A). **Laufende Folge-PRs nicht wegen der Cache-Architektur erweitern.**

**M0b – Gleichrangige Betriebsblocker für die reale Confluence-Verbindung (#33 und #34; parallel zu M1/M2, vor Live-Tests und Library-Nutzung im Unternehmensnetz)**
- [ ] **#33 – ausgehendes mTLS je Confluence-Quelle:** Windows-MY mit Dienstkonto-Zertifikat und PKCS12, explizite Alias-/Schlüsselwahl, vorhandene Upstream-`ClientCertificates`/`ClientCertificateSockets` anbinden, Basic/PAT unabhängig erhalten. Integrationstest mit HTTPS-Server, der Clientzertifikat verlangt. **Ohne #33 kein belastbarer Live-Durchstich zur aktuellen produktiven Confluence-Instanz**; Fake-Confluence-/Unit-Tests dürfen unabhängig fortgesetzt werden.
- [ ] **#34 – Proxy-/WPAD-/PAC-Routing: ebenso Betriebsblocker wie #33 für Library und Service** im betroffenen Unternehmensnetz. Bestehenden `win-proxy-java`-/`HttpRoutePort`-Pfad über Spring konfigurieren und echte pro-Ziel-Routen anwenden; PAC-/Windows-Dienstkontext, Fehlermeldungen und HTTPS CONNECT prüfen. Die bereits in #34 vorgesehene WebClient-/echte asynchrone HTTP-I/O-Integration im ADR und mit Regressionstests gegen bisherigen Confluence-Transport behandeln, keinen zweiten Confluence-Fachclient bauen. Eine lediglich vorhandene Bibliothek ohne **tatsächlich angewendete** PAC-Routen ist nicht betriebsfähig.
- [ ] Gemeinsamer Integrationstest **mTLS + HTTPS CONNECT** bei benötigter Proxyroute, ohne die isolierte Fertigstellung von #33 vom vollständigen Umfang von #34 abhängig zu machen.
- **Abnahme:** Erstes isoliertes mTLS-Gate: direkte mTLS-Metadatenabfrage mit PAT und Zertifikat. **Produktives Verbindungs-/Library-Gate: dieselbe fachliche Abfrage über die vom Unternehmens-PAC für die Ziel-URL gewählte Route (ggf. HTTPS CONNECT) mit mTLS erfolgreich**; auch beim Einbinden des Connectors in den headless Java-21-Dienst mit dem tatsächlichen Dienstkonto. Kein impliziter DIRECT-Fallback, keine Secrets/Schlüssel/Access-Token in Logs/HTTP-Responses.

**M1 – Vollständige Metadaten (#5/#6)**
- [x] Paginierter **Live**-Space-Inventar-Endpunkt ohne Content, Cursor/Limit 1–100, keine Trunkierung: [PR #32](https://github.com/aresstack/space-harvester/pull/32) gemergt (#5).
- [x] `metadataHash` nach dem festgelegten Schema `page-metadata/v1` mit Tests für Version/Parent/Titel/Space: PR #32 (Teil von #6).
- [ ] #6: Einzel-Metadatenroute ohne Space-Key, getrennter versionierter `attributesHash` und optional aggregierter Space-Fingerprint; `history.createdDate`/`createdBy` zuerst im Upstream ergänzen.
- **Abnahme:** alle Seiten eines Fake-Spaces inkl. mehrerer Root-Seiten über mehrere Cursor vollständig inventarisierbar.

**M2 – Native Rohdaten und Snapshot (#7/#2/#8/#9/#24 Teil B)**
- [ ] #7: durch reale/fixture-basierte REST-Beispiele `body.storage`, Version, Space, Ancestors, Labels und ggf. Zusatzabrufe klären; ADR zur erwarteten Vollständigkeit.
- [ ] Upstream: **optionale `StoredContentSource`-Capability**, die den empfangenen JSON-Body samt Revision durch vorhandenen Confluence-Transport liefert; `KnowledgeSourcePort.load()` unverändert lassen; **keinen zweiten REST-Client** schreiben.
- [ ] #8: DTOs/Metadaten getrennt vom Raw-Body, Header/Content-Modell und exakte Payload-/Metadatenhashes.
- [ ] #9: Referenzen bewahren, ohne Bilder herunterzuladen.
- **Abnahme:** Page-JSON aus Fake-Confluence ist nach Abruf bytegleich im Snapshot; vor und nach DTO-Mapping identische Raw-Bytes.

**M3 – Lucene persistent (#26)**
- [ ] Repository-Port für Raw-Snapshots und Header/Hierarchiebeziehungen; Lucene-Implementierung und Feldschema.
- [ ] Speicher-/Index-Lebenszyklus, Reader/Writer, Neustart, sichere Upserts, Sicherung und Korruptions-/Recovery-Verhalten.
- [ ] Enrichment-Dokumenttyp/-Schlüssel *vorsehen*, Prozessoren **noch nicht implementieren**.
- **Abnahme:** Rohdaten, Version und Parent-ID sind nach Prozessneustart auffindbar und bytesicher, ohne externe Datenbank.

**M4 – Synchronisation und Jobs (#10/#12/#13/#16)**
- [ ] Vollständigen Space ernten (alle Roots), geänderte Seiten gezielt neu laden, entfernte erkennen, Sync-Generation und Status.
- [ ] Hintergrund-Sync und gesicherte manuelle Jobauslösung mit Progress, Retry, Cancel.
- **Abnahme:** zweiter Lauf ohne Änderungen lädt keine unveränderten Bodies; abgebrochene/fehlerhafte Läufe werden nicht als vollständig ausgewiesen.

**M5 – Abfragen und Suche (#27/#28/#14)**
- [ ] Raw-Einzelseite, flache paginierte Pages, ID-Tree, erweiterter Metadatencontainer.
- [ ] Titel-/Metadatensuche auf Lucene, autorisierte Cursor/Filter und Freshness-Angaben. Body-Volltext nur über später geprüfte statische Indexprojektion.
- [ ] Berechtigungsschutz für Rohdaten **und** Baum-/Suchmetadaten.
- **Abnahme:** Seitenliste/Tree konsistent, große Spaces ohne ungehemmtes RAM-Wachstum, unberechtigte Requests geben keine IDs/Titel/Content preis.

**M6 – Artefakte und MVP-Härtung (#11/#15)**
- [ ] Plain JSON/NDJSON: flaches Seiten-Array bzw. Stream; separater ID-Baum und Manifest. Keine zwingende Converter-Pipeline.
- [ ] CI/E2E-Tests für Auth, Paginierung, native Bytes, Cache-Neustart, partielle Syncs, Fehler, Sicherheit und große Datenmengen.
- **Abnahme:** Ein autorisierter technischer Client kann einen vollständigen, reproduzierbaren Raw-Space-Export mit Vollständigkeitsnachweis herunterladen.

**M7 – Verbindliche Sicherheits-/Integrationsziele (eigenständige PRs)**
- [ ] #24 Teil C: OAuth2-Delegation und `canRead(userContext,sourceId,pageId)` implementieren, getrennt von Erntekonto, ohne OAuth-Registrierung im Basic/PAT-Start zu erzwingen. Live-E2E erst mit adminseitiger Application Link.
- [ ] #22: Azure Key Vault nur optional (kein MVP-Blocker für lokalen Betrieb); offener Rest von #3: optionaler Config Server.
- **Abnahme:** Zugriff auf geschützte Cache-Antworten unter Endnutzerrechten prüfbar und fail closed. Erstes technisches Raw-MVP aus M6 darf schon vorher gesichert nutzbar sein.

**M8 – Optionale Erweiterungen (nach MVP)**
- [ ] #29: klassische, einzeln versionierbare NLP-/Fact-Enrichments mit sicherer interner Textprojektion.
- [ ] #19: Medien separat/on demand, ggf. generative KI-Bildbeschreibungen mit Modell- und Asset-Hash.
- [ ] #30: Converter (Markdown, PDF, DOCX) **nur am Ende**; Plain JSON gehört bereits zu #11.
- [ ] #31: ausgewählte Confluence-kompatible Read-only-Endpunkte erst nach Kompatibilitätstests.
- [ ] #17/#18: optionale UI und weitere Quellenadapter.

## 8. Teststrategie / Definition of Done

**Tests pro Ausbaustufe**: Unit-Tests für reine Ports/DTOs/Hashes/Tree-Bildung; HTTP-Integrationstests mit Fake-Confluence (Basic/PAT und Fehler), bei #33 mit erzwungenem Clientzertifikat und bei #34 mit PAC-/Proxy-Route inklusive `CONNECT` und Java-21-/Executable-JAR-Prüfung; Lucene-Dateisystemtests auf temporären Verzeichnissen; Neustart-/Recovery-/Sync-Generationen; ArchUnit für Schichtgrenzen; OpenAPI/Problem-Details-Tests. Später Live-Testplan für Confluence Data Center, TLS/mTLS, OAuth-Application-Link und tatsächliche Berechtigungswechsel.

**MVP-Definition:** Mehrere Quellen ohne externe Datenbank konfigurierbar; Confluence-Spaces und deren **vollständige** Seitenmetadaten abrufbar; Snapshot-Body nativ und ohne Content-Transformation erhalten; Lucene speichert Bytes, Revision, Parent, Hash und Freshness; Sync lädt nur neue/geänderte Seiten; Seiten flach, Baum separat, begrenzte Suche/Metadatencontainer; Plain JSON/NDJSON+Manifest; keine Bilderbytes; abgesicherte technische API und korrekte Fehl-/Unvollständigkeitszustände. Weder PostgreSQL/H2 noch Markdown/PDF/Word-Converter, LLM-Textextraktion oder vollständige Confluence-API-Kompatibilität erforderlich.

**Regel für Änderungen:** Bestehende Quellbibliotheken in `enterprise-ai-sources` **nicht** patchen; Erweiterungen upstream entwickeln und synchronisieren. Abgeschlossene PRs #167/#25/#32 bleiben in ihrem Scope; neue Upstream-Fähigkeiten (z. B. `history`/`StoredContentSource`) in separaten PRs entwickeln. Neue Arbeit als überprüfbare, möglichst kleine PRs auf aktuellem `main` durchführen und erst mit grünen Tests zusammenführen.

## 9. Offene Detailentscheidungen (in den zugehörigen Issues abschließen)

1. #7: vollständige `expand`-Liste/zusätzliche Endpunkte und maximal sinnvolle Raw-Payload-Größe.
2. #26: Lucene-Feldschema, Versionierung/Historie, Größe von StoredFields, Generation-/Commit-/Backup-Konzept und sichere lokale Verschlüsselung bzw. Dateisystemrechte.
3. #16/#27: Cache-TTL, Refresh-Policy, Stale-Antwortverhalten und Cursor-Semantik über Index-Generationen.
4. #28: Analyzer-Sprache und Schutz der statischen Volltextprojektion; **Metadatensuche zuerst**.
5. #14/#24: technischer Client-Zugang, identitätsgebundene delegierte Nutzerprüfung und Freigabemodell.
6. #33/#34: **beide** sind produktive Connectivity-Gates (auch bei Wiederverwendung als Library) im betroffenen Unternehmensnetz: Quellzertifikat aus Windows-MY/PKCS12, effektives Dienstkonto und Trust-Policy, PAC-Fail-Closed, asynchroner HTTP-Transport und Test-/ADR-Ergebnis. Direkten mTLS-Test und kombinierten mTLS+PAC/CONNECT-Test voneinander unterscheiden.
6. #11/#30: Grenze zwischen synchronen paginierten APIs und großen asynchronen Streaming-/Download-Artefakten.

Diese Punkte **nicht** stillschweigend als bereits implementiert oder endgültig entschieden darstellen.
