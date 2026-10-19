# Quellenadapter

Space Harvester spricht Wissensquellen nur über den quellenneutralen Port
`application.sources.HarvestSource` an. Umgesetzt wird er im Paket `source` über die Adapter aus
`enterprise-ai-sources`, einer unveränderten Kopie aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client) (#2, PR #23). Einen zweiten
Confluence-Client gibt es nicht.

## Auswahl

`ConfiguredSourceCatalog` baut aus `space-harvester.sources[*]` je Eintrag eine Quelle. `type` wählt den Adapter
(`ConfiguredSourceCatalog.TYPES`, heute `confluence` über `ConfluenceSourceProvider` und `files` über
`LocalFilesSourceProvider`; weitere Typen hängt ein einbettendes Programm mit `Harvester.builder().sourceAdapter` ein). Unbekannte Typen,
fehlerhafte Optionen, Zertifikate oder Proxy-Einstellungen verhindern den Start, und alle Fehler werden gemeinsam
gemeldet. Konfiguration siehe README, „Quellen konfigurieren“.

## Vertrag

| Methode von `HarvestSource` | Upstream-Fähigkeit | Zweck |
|---|---|---|
| `partitions()` | `KnowledgeSourcePort.partitions()` | alle sichtbaren Spaces |
| `pageMetadata(space, cursor, limit)` | `PartitionListingSource` | alle Seiten eines Space ohne Inhalt, mit Cursor (#5) |
| `pageMetadata(pageId)` | `ResourceMetadataSource` | eine Seite ohne Inhalt, unabhängig vom Space (#6) |
| `storedPage(pageId)` | `StoredContentSource` | native Antwort Byte für Byte mit Kopfdaten aus derselben Antwort (#8) |
| `attachments(pageId)` | `AttachmentListSource` | alle Anhänge einer Seite als Metadaten, auch nicht eingebettete, mit allen Listenantworten Byte für Byte; nie Dateibytes (#9) |

- Fehlt einer Quelle eine Fähigkeit, antwortet die Methode mit `SourceAccessException` `UNSUPPORTED`, nie mit
  einem leeren Ergebnis. `storedPage` und `attachments` sind deshalb Default-Methoden.
- Fehler der Quelle werden auf `SourceAccessException.Reason` abgebildet: `NOT_FOUND`, `ACCESS_DENIED`,
  `UNAVAILABLE`, `RATE_LIMITED`, `INVALID_RESPONSE`, `UNSUPPORTED`. Eine Antwort über `maxResponseBytes` ist
  `INVALID_RESPONSE` und wird nie gekürzt. Die Fehlertexte enthalten keine Zugangsdaten und keine Seiteninhalte.
- Robustheit (#13):

  | Fall in der Quelle | Verhalten | API |
  |---|---|---|
  | Netzfehler, Zeitüberschreitung, 5xx | bis `maxRetries` (Standard 2) wiederholt, exponentiell gestreut | 503 `source-unavailable` |
  | 429 | ebenso wiederholt; danach `RATE_LIMITED` | 429 `source-rate-limited` |
  | 401, Weiterleitung zur Anmeldung | nie wiederholt, `ACCESS_DENIED` | 502 `source-access-denied` |
  | 403 auf eine Einzelseite | wie eine unbekannte Seite, `NOT_FOUND`; die Space-Liste lässt sie ebenfalls aus | 404 `source-object-not-found` |
  | 404 | `NOT_FOUND` | 404 |
  | gekürzte `metadata.labels` | Attribut `confluence.labels.complete=false`, `PageMetadata.attributesComplete()` ist `false` | sichtbar in `attributes` |

  `Retry-After` wird noch nicht ausgewertet, weil der Transport keine Antwort-Header weitergibt. OTP/2FA betrifft nur
  Datei-Downloads über die Web-Sitzung, nicht die REST-Abrufe des Dienstes. Ein wiederholter Cursor bricht die
  Listung ab (`InventoryCollector`).
- Kopfdaten (`PageMetadata`) sind für alle drei Wege gleich aufgebaut; `metadataHash` (`page-metadata/v1`) und
  `attributesHash` (`page-attributes/v1`) stimmen deshalb zwischen Liste, Einzelseite und Snapshot überein.

Darauf bauen:

- `SourceQueries`: Metadaten live für die API (#5, #6)
- `SourceQueries.attachments`: Anhangsliste live für die API (#9)
- `snapshot.SourceSnapshots`: `PageSnapshot` aus `storedPage`, zugleich `PageContentFetcher` der
  Synchronisierung (#16)

## Quellen ohne Partitionen (#187)

Die meisten Quellen des Enterprise AI Client (MediaWiki, Git, FTP/JES, NDV, Outlook, SharePoint, BetaView) haben
keine `PartitionListingSource`, sondern nur `KnowledgeSourcePort.discover`/`load`. Der Core liest sie wie die
Indexierung dort (`IndexKnowledgeUseCase.indexSource`: ein `discover` im Umfang der Quelle, dann `load` je Ressource):

- **Umfang:** `SourceAdapter.OpenedSource.discovering(port, scope)`; `KnowledgeSourceProviderAdapter` öffnet einen
  Upstream-`KnowledgeSourceProvider` wie `KnowledgeSourceManagement.open` (`validate`, Adresse aus `locator` und
  `KnowledgeSourceType.addressFor`, `open`, `scope`). Einstellungen stehen unter `options` mit den Schlüsseln des
  Upstream-Typs; Anmeldung, Zertifikate und `base-url` kennt dieser Weg noch nicht (#188). Hat der Port doch eine
  `PartitionListingSource`, gilt diese, der Umfang wird nicht benutzt.
- **Partition:** genau eine je Quelle, Schlüssel = Quellen-ID, Name = Titel der Quelle, ohne Startseite.
  Bereiche des Ports (`partitions()`) gibt es auf diesem Weg nicht.
- **Seiten-ID:** die Ressourcen-ID als Base64url ohne Auffüllung (`wiki:wiki.intern/w/Start` → `d2lraTp3aWtp…`),
  zurückgelesen nur in genau dieser Kodierung, sonst `UNSUPPORTED`; `resourceId` bleibt die Ressourcen-ID. Eltern
  (`parentId`) werden ebenso kodiert, daraus entsteht der Seitenbaum im Cache.
- **Seitenliste** (`pageMetadata(partition, cursor, limit)`): der erste Stapel ruft immer `discover` auf, Folgestapel
  lesen aus derselben Discovery (Cursor `d1.<Versatz>.<Fingerabdruck>`). Liefert die Quelle für einen Folgestapel einen
  anderen Stand, endet die Listung mit `UNAVAILABLE` statt zwei Stände zu mischen. Höchstzahl und Tiefe bestimmt der
  Umfang der Quelle (`SourceScope.maxResources`, `maxDepth`).
- **Einzelseite und Inhalt** (`pageMetadata(pageId)`, `storedPage`): über `load`. Abgelegt wird der Lesetext des
  Adapters (`KnowledgeDocument.text()`) als UTF-8, Medientyp `text/markdown`, Format `knowledge-document`
  (`NativePayload.DOCUMENT_TEXT`); Kopfdaten aus demselben Laden. Eine Weiterleitung (Wiki-Redirect) ist für die
  angefragte Seite `NOT_FOUND`, wie im Enterprise AI Client gilt sie nur unter ihrem Ziel. `NOT_FOUND` bestätigt keine
  Löschung (`confirmsDeletion` bleibt `false`).
- **Suche:** Volltext über den Cache (#180) mit `KnowledgeDocumentTextProjector` (`knowledge-document-text/v1`,
  Text unverändert), ebenso für Anreicherungen und Embeddings; eine Quelle mit `SearchableKnowledgeSource` sucht
  zusätzlich selbst, mit denselben Seiten-IDs. Titelsuche ohne eigene Suche über die Discovery; Adressen sind
  Ressourcen-IDs und Bookmarks der Quelle (`<schema>:<locator>/…`, `<schema>://<locator>/…`).
- **Export:** wie jede Partition; Markdown, Word und PDF lesen den Lesetext als Blöcke (Überschriften, Absätze,
  Listen, Codeblöcke), statt auf fehlendes Storage-Format hinzuweisen.
- **Fehler:** nur die Art (`Quelle: NOT_FOUND` …), ohne die Meldung des Adapters, die Seitentitel, Pfade oder Jobnamen
  nennen kann.

Die Rechte je Seite gelten wie bei jeder Quelle über die `AccessPolicy`; nicht lesbare Seiten fehlen in Listen,
Suche und Export.

**Freigegebene Ordner** für `type: files`: `new LocalFilesSourceAdapter(() -> new LocalFileGrants(allowAll, roots))`,
eingehängt über `Harvester.builder().sourceAdapter`, wie `LocalFileAccessPolicy`/`FileAccessConfig` im Enterprise AI
Client. Das Verzeichnis der Quelle ist immer freigegeben; ein Link hinaus führt nur in einen freigegebenen Ordner,
sonst `ACCESS_DENIED`. Gelesen wird die Freigabe bei jedem Zugriff. Ohne Angabe gilt wie bisher nur das Verzeichnis.

Noch offen in #187: die einzelnen Quelltypen (je Typ ein PR), Anmeldung über `SecretProvider` (#188), Bereiche
(`partitions()`) für Quellen ohne Seitenliste, Freigaben in der Konfiguration des Dienstes.

## Dokumente: Typerkennung und Text (#183)

Erkennung und Extraktion kommen wie die Adapter aus der Kopie (`deigma/document-tika`, `DocumentSourceFileTextExtractor`,
Ablauf wie in der Composition Root des Enterprise AI Client): `DocumentExtraction.detector()` erkennt nach Endung und
Angabe, sonst mit Apache Tika am Inhalt; `DocumentExtraction.registry()` liest Markdown und Klartext direkt, alles
andere (PDF, Word, Excel, PowerPoint, OpenDocument, RTF, E-Mail) über Tika. Tika läuft in Version 3.3 statt 2.9.1
wie im Upstream, weil der Space Harvester PDFBox 3 und POI 5.5 nutzt; flexmark ist ausgeschlossen wie dort.
`application.assets.DocumentText` ist der Port, `source.TikaDocumentText` die Umsetzung; nur sie kennt die Kopie.

| Grenze | Wert | Folge |
|---|---|---|
| Größe je Datei | 20 MiB (`TikaDocumentText.DEFAULT_MAX_BYTES`, wie ADR 0002 und `maxFileMegabytes`) | kein Text, `document-text-too-large` |
| Laufzeit je Datei | 30 s, eigener Thread, danach unterbrochen | `document-text-timeout`, später erneut |
| gleichzeitige Läufe | 2; ein hängender Lauf hält seinen Platz | `document-text-busy`, später erneut |
| Textlänge | 1 000 000 Zeichen, wie der Volltextindex je Seite | gekürzt, `truncated` |

- **Lokale Dateien** (`type: files`): `LocalFilesSourceAdapter` übergibt Erkennung und Extraktoren an den Provider.
  Den Lesetext für Volltextindex und Anreicherungen bildet `LocalFilesTextProjector` aus dem abgelegten Original:
  Textdateien wie bisher als UTF-8 (`file-text/v1`), Dokumente über Tika (`file-document-text/v1`). Ein defektes,
  verschlüsseltes oder zu großes Dokument hat keinen Text; eine Zeitüberschreitung lässt einen vorhandenen
  Indexeintrag stehen.
- **Anhänge im AssetStore:** Beim Ablegen prüft `Assets` den Typ am Inhalt (Magic Bytes). Ein Binärformat mit
  Signatur (PDF, Office, Bild …) ersetzt eine abweichende Angabe der Quelle, ein erkannter Typ ersetzt eine fehlende
  oder nichtssagende (`application/octet-stream`); Textformate (XML, JSON, Klartext) und bloße Behälter (ZIP, OLE2)
  ändern die Angabe nicht, damit etwa draw.io-Daten Diagramme bleiben. Danach richtet sich `AssetKind`. Dokumente
  (`AssetKind.OTHER`) bekommen beim Ablegen über `harvest` ihren Text als Anreicherung an der Datei (`AssetText`, je
  Inhalt und Extraktion unter `texts/` im Asset-Verzeichnis, mit dem Inhalt entfernt); `Assets.text` liefert ihn
  oder liest ihn nach. Fehler halten das Ablegen nicht auf und erscheinen nur als fester Code im Protokoll.
- Offen in #183: Anhangstexte im Volltextindex und in der API, Vorschau und PDF-Konvertierung über Confluence
  `documentConversion` als Asset-Variante.

## Einen Adapter ergänzen

1. Fehlende Fähigkeiten upstream in `source-api` als optionale Capability-Interfaces ergänzen, nicht durch
   Änderungen an `KnowledgeSourcePort.load()`.
2. Die Kopie mit `enterprise-ai-sources/sync-from-upstream.sh sync <commit>` übernehmen; die Kopie wird nie lokal
   geändert (`verifyUpstreamCopy` prüft das im Build).
   Übernommen, aber im Core noch nicht registriert: `source-git` (Git, ein Ref, aktueller Stand, mit
   `PageNavigationSource`; JGit wie im Upstream, #187), `source-ftp` (FTP für MVS-Datensätze und JES-Spool über Apache
   Commons Net, #187), `source-ndv` (Natural Development Server, #187). Die Registrierung folgt über den allgemeinen Pfad für Quellen
   ohne Partitionen.
3. Den Typ in `ConfiguredSourceCatalog` registrieren, für einen Upstream-Provider ohne Seitenliste über
   `KnowledgeSourceProviderAdapter`. `KnowledgeSourceHarvestSource` bildet jeden `KnowledgeSourcePort` mit seinen
   Fähigkeiten ab, ohne `PartitionListingSource` über `discover`/`load` (siehe oben).

## Tests

- `KnowledgeSourceHarvestSourceTest`: Abbildung der Ressourcen, fehlende Fähigkeiten ergeben `UNSUPPORTED`; Quellen
  ohne Partitionen mit `InMemoryKnowledgeSource` aus den Fixtures der Kopie (eine Discovery je Listung, fremder Cursor,
  Änderung während der Listung, Weiterleitung, Fehlertexte, Titelsuche, Adressen).
- `HarvesterDiscoveredSourceTest`: dieselbe Fake-Quelle ohne Partitionen und ohne Suche über
  `KnowledgeSourceProviderAdapter` durch Abgleich, Katalog, Cache, Volltextsuche und Markdown-Export; nicht lesbare
  Seiten fehlen in Liste und Suche.
- `LocalFileGrantsTest`: Links aus dem Verzeichnis nur in freigegebene Ordner, Freigaben ohne Neustart.
- `PageMetadataApiTest`, `SinglePageMetadataApiTest`, `NativeSnapshotSourceTest`: alle Wege über echtes HTTP gegen
  die Fake-Confluence aus den Test-Fixtures der Kopie.
- `TikaDocumentTextTest`, `LocalFilesTextProjectorTest`, `AssetDocumentTextTest`: Text aus zur Laufzeit erzeugten
  Dokumenten (`DocumentFixtures`: DOCX, XLSX, PPTX, PDF, ODT, RTF), Erkennung am Inhalt gegen eine falsche Angabe,
  Grenzen; `HarvesterFullTextTest` findet Word und PDF lokaler Dateien im Volltextindex.
- Upstream: `ConfluenceStoredContentTest`, `GitKnowledgeSourceTest`, `GitKnowledgeSourceContractTest` und die übrigen Adaptertests laufen in `:enterprise-ai-sources:test` mit.
