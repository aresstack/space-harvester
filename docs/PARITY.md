# Funktionsparität: Enterprise AI Client und Space Harvester Core

**Stand:** 18.10.2026 · **Entscheidung:** [ADR 0012](adr/0012-core-als-gemeinsame-bibliothek.md) · **Verglichen:**
`aresstack/enterprise-ai-client` `main` 7b719e3 (#182) mit `aresstack/space-harvester` `main` e0bb6f8.

Space Harvester Core soll die Wissensquellen- und Dokumentverarbeitung des Enterprise AI Client (EAC) mindestens
funktionsgleich bereitstellen (ADR 0012, Paritätsregel). Dieses Dokument hält am Code fest, was der EAC heute kann,
ob der Core es gleichwertig, teilweise oder gar nicht kann, und welches Issue die Lücke schließt.

## Lesehilfe

**Bewertung**

| Wert | Bedeutung |
|---|---|
| **gleich** | gleichwertig, auch wenn anders umgesetzt |
| **SH+** | Core kann mehr als der EAC |
| **teilweise** | Grundlage da, Funktionsumfang fehlt noch; Issue genannt |
| **fehlt** | nicht vorhanden; Issue genannt |
| **in Arbeit** | Strang Q, R oder L baut es gerade; nur Verweis |
| **EAC** | gehört nach ADR 0012, Punkt 4, nicht in die Bibliothek |
| **bewusst nicht** | durch Angelos Entscheidung ausgeschlossen; Ersatzweg genannt |
| **gewollt anders** | bewusst andere Umsetzung mit Begründung; Ersatzweg genannt |

**Pfadkürzel EAC**

| Kürzel | Pfad |
|---|---|
| `conf/` | `proasteion/emporion/holkas/source-confluence/src/main/java/com/aresstack/enterpriseai/proasteion/emporion/holkas/confluence/` |
| `know/` | `astu/acropolis/src/main/java/com/aresstack/enterpriseai/astu/acropolis/knowledge/` |
| `rag/` | `astu/acropolis/src/main/java/com/aresstack/enterpriseai/astu/acropolis/rag/` |
| `doc/` | `proasteion/exedra/src/main/java/com/aresstack/enterpriseai/proasteion/exedra/document/` |
| `app/` | `proasteion/application/src/main/java/com/aresstack/enterpriseai/proasteion/application/` |
| `chalco/` | `astu/acropolis/chalcotheca/` |

**Pfadkürzel SH**

| Kürzel | Pfad |
|---|---|
| `core/` | `space-harvester-core/src/main/java/com/aresstack/spaceharvester/` |
| `server/` | `space-harvester-server/src/main/java/com/aresstack/spaceharvester/` |
| `desk/` | `space-harvester-app/src/main/java/com/aresstack/spaceharvester/app/` |

**Ausgangslage.** Der Confluence-Code ist in beiden Repositories derselbe: `enterprise-ai-sources` ist eine
unveränderte Kopie (`diff -r` leer für `source-confluence` und `source-api`). Die meisten Unterschiede liegen deshalb
nicht im HTTP-Code, sondern darin, welche Ports der Core anbindet. Angebunden sind über
`core/source/KnowledgeSourceHarvestSource.java`:

- `PartitionListingSource`, `ResourceMetadataSource`, `StoredContentSource` und `AsyncStoredContentSource`,
- `AttachmentListSource` und `SearchableKnowledgeSource`,
- von `PageViewSource` nur `isAttachment` und `loadAttachmentFile`.

Nicht angebunden sind:

- `PageNavigationSource`, `AddressableKnowledgeSource` und `ImageSource`,
- `PageViewSource.pageView` und `loadViewImage`,
- `crossReferences`, `discover` und `load`,
- `DrawioDiagramRenderer`.

## 1. Confluence-Konnektor

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Spaces auflisten, einzelner Space mit Startseite | `conf/ConfluenceRestClient#spaces`, `#space` | Kopie über `KnowledgeSourceHarvestSource#partitions`; `server/api/SourcesController` | gleich |
| Seiten eines Space flach, paginiert (Cursor, ≤ 100) | `conf/ConfluenceKnowledgeSource#resources` | Kopie über `pageMetadata(partition, cursor, limit)`; `core/application/sync/InventoryCollector` | gleich |
| Seite in `body.storage` mit Version, Space, Vorfahren, Labels, History | `#storedPage`, `SNAPSHOT_EXPAND` | Kopie, sync und async (`core/source/confluence/WebClientConfluenceTransport`), byte-genau als `NativePayload` | gleich |
| Gerenderte Ansicht `body.view` / `export_view` (Makros von Confluence ausgeführt, Jira-Platzhalter, Lese-HTML) | `#readingPage`, `conf/ConfluencePageView`, `conf/ConfluenceHtmlText`, `#pageView` | `core/application/reading/ReadingViews` (#176), REST `GET …/pages/{pageId}/reading-view`, Ablage `reading-view/v1`; im Vergleichstest gleich (Seiten, Lesetext, Bilder) | gleich |
| Labels samt Vollständigkeit | `#toPage`, `LABELS_COMPLETE` | Kopie; `attributesHash` | gleich |
| Anhangsliste, Download mit Bytegrenze | `#storedAttachments`, `#loadAttachmentFile` | Kopie; `core/application/assets/Assets`; fremde Weiterleitungen bewusst aus (SSRF, #90) | gleich |
| Anhang-Vorschau und Konvertierung (`/rest/documentConversion`) | `#loadAttachmentPreview`, `#conversionPath` | keine | fehlt → #183 |
| CQL-Suche mit Auszug, Space-Filter in der Anfrage | `#search`, `ConfluenceKnowledgeSource#cql` | dieselbe Quelle über `KnowledgeSourceHarvestSource#searchPages(text, limit, SearchSpaces)`: `KeywordSearch.Query` mit `spaceKeys`/`excludedSpaceKeys` (REST `spaceKey`, `excludeSpaceKey`) wird zu `space in (…)` und `space not in (…)` in der CQL, ohne Obergrenze (`SourceQuery.withIncludedPartitions`/`withExcludedPartitions`) | gleich (#179) |
| URL-Auflösung: `pageId=`, `/spaces/K/pages/ID`, `/x/…` | `conf/ConfluenceLinks`, `#resolveAddress` | `core/application/query/PageUrls` | gleich |
| URL-Auflösung: `display/KEY/Titel`, Space-Startseite, `confluence://`, Ressourcen-IDs | `#pageIdByTitle`, `know/ResolveKnowledgeResourceUseCase`, `KnowledgeBookmarkIds` | `Harvester.addresses()` (`PageAddresses`), `pageByUrl`, REST `GET /api/v1/addresses` (#239); dazu Lesezeichen `space-harvester://…` | gleich (Vergleichstest Adressen) |
| Paginierung, Wiederholung bei 429/5xx mit Backoff, Fehlerabbildung, HTML-statt-Datei-Erkennung | `#paged`, `#retryable`, `#statusFailure`, `HtmlResponseDiagnosis` | Kopie; `maxRetries=2`, `InFlightLimit`, `SourceAccessException.Reason` | gleich |
| Anmeldung none / Basic / PAT, nur mTLS | `ConfluenceAuthorization`, `ClientCertificates` | `core/source/ConfluenceSourceAdapter`, `ConfiguredClientCertificates` (+ Thumbprint und Gültigkeit) | gleich |
| Einmalcode (2FA) und Web-Session für Downloads | `conf/OneTimeCodeForm`, `ConfluenceWebSession`, `DownloadAccess`; `know/UnlockingDocumentReader` | Kopie vorhanden, nicht angebunden | fehlt → #185 |
| Delegierte Anmeldung (Benutzertoken je Anfrage) | keine | `DelegatedConfluenceHttp`, `DelegatedAccessPolicy` | SH+ |
| Querverweise und ausgehende Links | `#crossReferences`, `#discoverLinks`, `#visiblePageTitles` | `PageNavigation#references`: ausgehend, eingehend wenn die Quelle sie vollständig kennt; dazu Linkumschreibung im Export | gleich (Vergleichstest, ausgehend) |

## 2. Schlagwortsuche

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Live-Suche über mehrere Quellen, Status je Quelle | `know/SearchKnowledgeSourcesUseCase` | `core/application/query/KeywordSearch` (`SEARCHED`/`NOT_SUPPORTED`/`UNAVAILABLE`, `complete`) | gleich |
| Paginierung | `limit`, Vorladen über `KnowledgePrefetchService` | Cursor je Quelle, „Weitere laden“ | gleich |
| Metadaten und Auszüge je Treffer | `SourceSearchHit`, `ConfluenceRestClient#excerpt` | `KeywordSearch.Hit` (Version, Änderung, URL, Space, Elternseite, Auszug) | gleich |
| Rechte je Treffer | eigene Anmeldung | `AccessGuard` je Treffer, gesperrte fallen ohne Spur weg | SH+ |
| Rückfall auf lokalen Index für Quellen ohne eigene Suche | `SearchKnowledgeSourcesUseCase` (Index-Fallback) | `KeywordSearch` sucht Quellen ohne eigene Suche im Volltextindex des Caches (`PageTextIndex`, Status `INDEXED`, REST `indexed`); Index-Treffer verlangen zusätzlich das Inhaltsrecht | gleich (Vergleichstest Suche, #180) |
| Lokale Volltextsuche über zwischengespeicherte Inhalte mit Textfenstern | `chalco/anagraphai/…/LuceneRawKnowledgeIndex`, `RawTextWindows`, `chalco/pinakes/…/LuceneTextIndex` | `LucenePageTextIndex` über den Text der Seiten im Cache (`PageTextIndexing` mit Projektoren für Confluence und lokale Dateien), Auszug um den Suchbegriff; lokale Dateien nur als Text (UTF-8, JSON, XML), PDF und Word werden nicht extrahiert (`LocalFilesTextProjector`), der EAC liest sie über `DocumentExtraction.registry()` | teilweise → #183 (Textdateien gleich laut Vergleichstest Suche: dieselben Dateien, Auszug mit Suchbegriff, ohne Rücksicht auf Groß-/Kleinschreibung) |
| Treffer der Live-Suche transparent in den Cache laden | `know/KnowledgePrefetchService`, `SearchDrivenIndexingUseCase` | `SearchHitPrefetch` lädt je Stapel höchstens 10 sichtbare Live-Treffer über `PageRenewal` in den Cache, nicht für delegierte Benutzer; `Harvester.builder().prefetchSearchHits(boolean)`, Standard an | gleich (Vergleichstest Suche) |

## 3. Navigation

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Spaces als Wurzeln | `know/PageNavigationUseCase` | `GET /sources/{id}/spaces` live | gleich |
| Seitenbaum ganzer Space live | live je Knoten | `core/application/navigation/PageNavigation#children` live je Knoten (#177), REST `…/spaces/{key}/children`, `…/pages/{pageId}/children`, `/ancestors`, `/links`; daneben `PageQueries#tree` aus dem Cache | gleich (Vergleichstest) |
| Kindseiten eines Knotens live, in Confluence-Reihenfolge, mit `hasChildren` | `ConfluenceKnowledgeSource#children`, `#treeBatch` | `PageNavigation#children`: live, Reihenfolge der Quelle, `hasChildren`, Stapel mit Cursor | gleich (Vergleichstest) |
| Vorfahren und Breadcrumbs mit Space-Namen | `#position`, `ConfluenceRestClient#lineage` | `PageNavigation#position`: Space mit Namen, Vorfahren von oben, Rechte je Vorfahr | gleich (Vergleichstest) |
| Hierarchie anderer Quellen (Ordner in Git) | `PageNavigationSource` in `source-git` | keine | fehlt → #187 |

## 4. Seitenabruf

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Authentifizierung beim Abruf | `ConfluenceKnowledgeSource#withSession` | Kopie; Client-Token am Dienst | gleich |
| Rechteprüfung | `chalco/tamias/…/ClientResourceAccessPolicy` (Einbenutzer) | `AccessGuard`, `CorpusReleasePolicy`, `DelegatedAccessPolicy` je Seite | SH+ |
| Versionsabgleich und Aktualität | `KnowledgePrefetchService#seenRevision`, `ContentHasher` | `core/application/sync/PageFreshness`, `refresh=true`, Hashes `page-metadata/v1`, `page-attributes/v1` | SH+ |
| Quelle nicht erreichbar → Cache-Stand | Prefetch | `PageQueries#page` liefert Cache bei `UNAVAILABLE`/`RATE_LIMITED` | gleich |
| Text der Seite zum Lesen | `know/LoadKnowledgeDocumentUseCase`, `KnowledgeDocumentReader` (aus `body.view`) | `ReadingViews` (#176): Lesetext aus `body.view` mit ausgeführten Makros | gleich (Vergleichstest Leseansicht) |
| Ausgeführte Makros (`include`, `excerpt-include`, `children`) in Word, PDF und Markdown | über `body.view` im Export | Leseansicht im Datenbestand (`views/`, `export-reading-view/v1`, `ConversionInput#readingView`, Q #222), in Word, PDF und Markdown aus der Leseansicht, ohne Quellabfrage (R #230); fehlt sie, Hinweis und `OMITTED_CONTENT` | gleich (Vergleichstest Makros) |
| Text für die KI-Anreicherung | `KnowledgeDocumentReader` (aus `body.view`) | `PageTextProjector`, `ConfluenceStorageTextProjector` (aus `storage`) | gewollt anders: fremd gerendert eingebundene Inhalte wären in der gemeinsamen Anreicherung ein Rechteleck; wer die Seite lesen darf, bekommt den Lesetext über `ReadingViews` |

## 5. Cache

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Originaldaten persistent | keine (In-Memory-Payloads, Index hält Text) | `core/persistence/lucene/LucenePageCache` (Rohbytes + SHA-256) | SH+ |
| Versioniert, inkrementell, Löschungen | `RefreshKnowledgeSourceUseCase`, `chalco/ResourceArchive` | `SpaceSync`, `SyncPlanner`, `RemovalCheck`, Generationen | SH+ |
| Transparentes Nachladen | `KnowledgePrefetchService` | `PageQueries`, `PageCacheFill` (ADR 0010) | gleich |

## 6. Bilder

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Bildreferenzen im Storage (`ac:image`, `ri:attachment`, `ri:url`) | — | `StorageParser#image` | SH+ |
| Bilder der Leseansicht (Inline, Vorschaubilder, draw.io-Vorschau; ohne Emoticons) | `conf/ConfluencePageView`, `#viewImage`, `#loadViewImage` | keine | in Arbeit (Strang Q, Runde 7: Bildreferenzen aus Anhängen und `PageViewSource`) |
| Authentifizierter Abruf, dauerhafte, versionierte Ablage, Wiederverwendung | `doc/ExportGraphics`, `exedra/…/reader/ReaderImageStore` (flüchtig) | `Assets`, `core/persistence/files/FileSystemAssetStore` (SHA-256, versioniert) | SH+ |
| Bilder beim Herunterladen mitbeschaffen, Asset-Manifest im Datenbestand | `know/KnowledgeExportUseCase#figures` | keine | in Arbeit (Strang Q, Runde 7) |
| Dateityp am Inhalt erkennen (Magic Bytes) | `deigma/document-tika/…/TikaContentDetector`, `ExportGraphics#decode` | nur Medientyp der Quelle und Endung (`AssetKind`, `SimpleContentDetector`) | teilweise → #183 |
| Bildbeschreibung durch ein Vision-Modell | `astu/acropolis/…/image/SourceImageAnalysisUseCase`, `ImageAnalysisCache` | Port `ImageDescriber` und Ablage, aber keine Implementierung | teilweise → #184 |

## 7. Anhänge

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Auflisten mit Metadaten (Größe, Medientyp, Version, Änderung, Container) | `#storedAttachments`, `know/ListKnowledgeAttachmentsUseCase` | `SourceQueries.attachments`, `PageAttachment` | gleich |
| Abrufen mit Grenze, Ablage | `#loadAttachmentFile` | dasselbe plus versionierter Store, `AssetTooLargeException`, Speichergrenze | SH+ |
| Text aus Anhängen und Dateien (PDF, DOCX, XLSX, PPTX, ODF, RTF) | `deigma/document-tika/…/TikaResourceExtractor`, `know/SourceAttachmentReader`, `DocumentSourceFileTextExtractor` | keine (`LocalFilesSourceAdapter` mit leerer `ExtractionRegistry`) | fehlt → #183 |
| Vorschau von Office- und PDF-Anhängen | `#loadAttachmentPreview` | keine | fehlt → #183 |

## 8. Diagramme

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| draw.io erkennen (Makro, Revision, `inc-drawio`) | `ConfluencePageView#drawioPreviews`, `#storageDrawioPreviews` | `StorageParser`, `AssetKind.DIAGRAM`, auch `inc-drawio` (#210) | gleich |
| draw.io als Grafik (mxfile → SVG, sonst PNG-Vorschau) | `conf/DrawioDiagramRenderer`, `doc/ExportGraphics` | `core/application/diagrams/Diagrams`; in Word, PDF und Markdown gezeichnet (#210) | gleich; gezeichnete Diagramme liegen als Rendition im Datenbestand (#220) |
| draw.io-XML und SVG textuell erhalten | — | Asset-Ernte bevorzugt `DIAGRAM` > `VECTOR` > `IMAGE` | SH+ |
| Mermaid erkennen (Confluence-Makros) | nur Markdown-Fences | `StorageParser` (`mermaid`, `mermaid-cloud`, `mermaid-macro`) → Codeblock | SH+ |
| Mermaid als Grafik | `doc/ExportGraphics#mermaid`, `exedra/ui/markdown/AresStackMermaidImageRenderer` | `Diagrams`; in Word, PDF und Markdown als Grafik (#206) | gleich; Leseansicht der App mit Diagrammen in Arbeit (L) |
| PlantUML, Gliffy | — | Codeblock bzw. Hinweis | SH+ |

## 9. Word-Export

Maßstab: `doc/DocxExportWriter`, `doc/MarkdownDocxRenderer`, `doc/ExportGraphics` über `know/KnowledgeExportUseCase`.
Im Core: Modul `:document-export` mit dem Exporter des EAC (Word #175, PDF #202), konvertiert aus dem Datenbestand;
Bilder kommen aus dem Asset-Manifest (#200), Diagramme aus `Diagrams`. Der Vergleichstest zeigt für Word keine
Lücke mehr.

| Fähigkeit | EAC | Core (main) | Bewertung |
|---|---|---|---|
| Inhaltsverzeichnis als Feld, Felder beim Öffnen aktualisieren | `fldSimple TOC`, `enforceUpdateFields` | dieselben (`DocxExportWriter`) | gleich |
| Kapitelstruktur aus der Hierarchie, Überschriften 1–9, Space-Name als Wurzelkapitel | `Heading1`–`Heading9` | `Heading1`–`Heading9`, Wurzelkapitel (#213) | gleich |
| Tabellen mit wiederholter Kopfzeile | ja, mit Zellausrichtung | ja, mit Zellausrichtung | gleich |
| Bilder eingebettet, skaliert, Bildunterschrift, Meldung fehlender Bilder | `run.addPicture`, `ExportGraphics#problems` | `run.addPicture` aus dem Asset-Manifest, Meldungen fehlender Bilder | gleich |
| Mermaid und draw.io als Grafik | ja (draw.io über Renderer) | ja (`Diagrams`) | gleich |
| Hyperlinks, Codeblöcke, Listen | ja | ja | gleich |
| Interne Seitenlinks innerhalb des Dokuments | — | Lesezeichen `page_<id>` | SH+ (in `:document-export` erhalten) |
| Durchgestrichen, Dokumenteigenschaften (Titel, Autor) | ja | ja | gleich |

## 10. Weitere Formate und Datenbestand

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| JSON (Raw-First, `pages.json`, `pages.ndjson`, `tree.json`, Manifest) | — | `Exports`, `JsonArrayConverter` | SH+ |
| Aus dem gespeicherten Datenbestand konvertieren, ohne erneuten Abruf | nein (lädt jede Seite neu) | Cache-Generation (ADR 0010); Konvertierung bestehender Exporte | SH+; Konvertierung aus Datenbestand in Arbeit (Q, Runde 7) |
| Herkunft je Konvertierung (Konverterversion, Eingabe-Hash) | — | `manifest.conversions` | SH+ |
| PDF: sichtbares Inhaltsverzeichnis über mehrere Seiten mit Einrückung, Seitenzahlen und Sprüngen (`tableOfContents()`), Seitenzahlen „12 / 85“, Kapitel je neuer Seite, Bilder, Mermaid | `doc/PdfExportWriter` | `core/output/convert/PdfConverter`: nur Lesezeichen (`PDDocumentOutline`) und interne Sprünge, Bilder als `[Bild: …]`; `PdfConverterTest` prüft kein Inhaltsverzeichnis | in Arbeit (Strang R, gemeinsames Kapitelmodell für Word und PDF) |
| Markdown: eine Datei, Bilder als PNG-Data-URI eingebettet, Mermaid als Code und Grafik, fehlende Bilder gemeldet | `doc/MarkdownExportWriter` | `MarkdownConverter`: Sprungmarken und interne Seitenlinks (besser als EAC, bleiben), Bilder nur als `attachment:`-Verweis, Mermaid nur als Code | in Arbeit (Strang R, „Export-Parität: Markdown, Word, PDF und Bilder“, gemeinsamer Dokumentbaum) |
| Verlinktes Inhaltsverzeichnis in Markdown | — | — | in keinem; keine Paritätslücke |
| Exportumfang einzelne Seite oder Teilbaum | `KnowledgeExportUseCase` | nur ganze Spaces (`ExportScope`) | fehlt → #186 |
| Portables ZIP (JSON + Bilder) | — | — | in Arbeit (Q, Runde 7, nachrangig) |

## 11. Semantische Suche

Allgemeine Infrastruktur gehört in den Core, Rechercheplanung bleibt im EAC (ADR 0012, Punkt 4).

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Embeddings über OpenAI-kompatiblen Dienst, Modellkatalog | `embedding-openai/…/OpenAiCompatibleEmbeddingAdapter`, `model-kipitz/…/KipitzModelCatalogAdapter` | dieselben Klassen (Kopie) über `core/source/ai/RemoteAi` | gleich |
| Lokale Embeddings im Prozess (ONNX) | `text-local/…/LocalEmbeddingAdapter`, `text-onnx` | entfernt (ADR 0011) | bewusst nicht; der EAC hängt seinen lokalen Adapter als eigenen `EmbeddingProvider` ein → #188 |
| Vektorablage je Modell | `chalco/pinakes/…/FileVectorIndex` (exakt) | `LuceneEnrichmentStore` (HNSW) | gleich |
| Zerlegung in Abschnitte mit Satzgrenzen und Token-Budget | `astu/…/knowledge/KnowledgeChunker`, `KnowledgeChunkingPolicy`, `SentenceSplitter` | `KnowledgeTextSegmentation` mit denselben Klassen (`SentenceSplitter`, `KnowledgeChunkingPolicy.defaults()`: 350 Tokens, ein Satz Überlappung) im `EmbeddingProcessor` (#252); offen: Token-Budget und Überlappung einstellbar wie `knowledge.chunk.maxTokens`, `knowledge.chunk.overlapSentences` (`AppConfigLoader`) | teilweise → #181 |
| Semantische Passagen (Themenwechsel) | `astu/acropolis/…/passage/PassageSegmentation`, `TopicClusterer` | keine | fehlt → #181 |
| Hybride Suche (Volltext + Vektor, Reciprocal Rank Fusion) | `rag/RetrieveKnowledgeUseCase`, `rag/ReciprocalRankFusion` | keine Fusion: Schlagwortsuche heute live in der Quelle, für Quellen ohne eigene Suche im Volltextindex (Suche nur über den Cache folgt nach ADR 0013), semantische Suche nur mit Haken bzw. `sort=relevance` und nur über die Vektoren (`reranker` = `embedding-cosine@…`) | gewollte Abweichung ([ADR 0013](adr/0013-suche-ueber-den-cache.md)) |
| Reranking über Modell (`/rerank`) | `model-kipitz/…/KipitzRerankAdapter`, `rag/CrossEncoderRanking` | `ModelReranker` über `POST /rerank` des KI-Dienstes, eingeschaltet mit `space-harvester.ai.rerank-model` (`AiServiceSettings.rerankModel`, `RemoteAi.reranker()`), sonst `CosineReranker` (#248), nur in der semantischen Suche (ADR 0013); gewollte Abweichung: kein `topK`, der Core ordnet alle Kandidaten nur um und schneidet nichts ab | gleich (#181) |
| Verwandte Abschnitte | `chalco/KnowledgeRelationsPort`, `know/IndexKnowledgeRelations` | keine | fehlt → #181 |
| Live-Prüfung jedes Treffers, Freigabe je Quelle für externe Verarbeitung | — | ADR 0011, Punkte 4 und 7 | SH+ |
| Suchlauf-Protokoll | `search-run-api/…/KnowledgeSearchRunPort`, `search-run-h2` | keines | fehlt → #182 |
| Metadaten-Fakten je Ressource | `fact-api/…/FactStorePort`, `fact-h2` | Anreicherungen je Seite (`EnrichmentStore`), nicht abfragbar | fehlt → #182 |
| RAG-Chat, Evidenzbewertung, Folgerecherche, Research-Agent | `rag/RagChatUseCase`, `RetrievalTriage`, `EvidenceEvaluator`, `research-*` | — | EAC |
| Gedächtnis, Browser je Chat, Werkzeugfreigaben, Schalter je Chat | `memory-*`, `browser-*`, `astu/approval/*`, `ChatRecord` | — | EAC |

## 12. Weitere Quellen und Quellenabstraktion

Der Core bindet nur Quellen mit `PartitionListingSource` an; `discover`/`load` ruft er nie auf. Eine Quelle, die nur
`KnowledgeSourcePort` anbietet, hätte im Core keine Partitionen und keine Seiten.

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Lokale Dateien | `source-localfiles` | `core/source/LocalFilesSourceAdapter` (Kopie) | gleich, ohne Text → #183 |
| Freigegebene Ordner für lokale Dateien | `source-api/…/files/LocalFileAccessPolicy` | nur Option `directory` | teilweise → #187 |
| Quellen ohne Partitionen (`discover`/`load`) | `KnowledgeSourcePort` | keine | fehlt → #187 |
| MediaWiki, Git, FTP/JES, NDV, Outlook, SharePoint, BetaView | `proasteion/emporion/holkas/source-*` | keine | fehlt → #187 |
| Quellen zur Laufzeit hinzufügen, entfernen, prüfen, Daten zurückziehen | `SourceDefinitionStore`, `acro/source/KnowledgeSourceManagement`, `SourceConnectionCheck`, `SourceDataWithdrawal` | `Harvester.sourceManagement()` (#227): `validate`, `test`, `add`, `change`, `remove` mit Rückzug von Cache, Anreicherungen und Assets | gleich (Vergleichstest Quellenverwaltung), mit `setEnabled` und Ablehnung desselben Ziels unter neuer ID |
| Eigene Adapter, Embedding-Anbieter, Reranker, Secret-Speicher einhängen | Kompositionspunkt `app/composition` | `Harvester.builder()` mit `sourceAdapter`, `secretProvider`, `embeddings`, `reranker` (#226) | gleich |
| Werkzeuge für Agenten (`knowledge_search`, `_read`, `_tree`, `_attachments`, `_resolve_url`) | `astu/acropolis/…/mcp/*Tool` | — | EAC (Adapter); die Use-Cases dahinter liefern #177, #178, #180 |

## 13. Netzwerk und Sicherheit

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Proxy-Route je Ziel (PAC, Registry, fester Proxy, `nonProxyHosts`, fail-closed) | `app/net/HttpRoutes`, `app/config/NetworkConfig` | `core/source/network/ProxyRoutes`, `ProxySettings` | gleich |
| Eigenes PAC-Ermittlungsskript | `NetworkConfig#pacDiscoveryScript` | `ProxySettings#pacDiscoveryScript` bei PAC-Ermittlung über PowerShell oder WScript (#234) | gleich |
| Proxy-Anmeldung (Basic, CONNECT-Tunnel) | `app/security/ProxyAuthenticator`, `ProxyAuthMode` (`NONE`, `BASIC`) | `ProxySettings.Authentication` mit Geheimnis `network:proxy` (#234) | gleich |
| Vertrauen: JVM, Windows-ROOT, Windows-CA, eigene CA-Datei | `app/net/TrustPolicy` | `TrustSettings` und `TrustPolicy` über `Harvester.builder().trust()` (#234); ohne eingeschalteten JVM-Truststore kein stiller Rückfall darauf | gleich |
| Client-Zertifikat Windows-MY / PKCS12 | `ClientCertificates`, `app/security/ClientCertificateFactory` | `ConfiguredClientCertificates` | gleich |
| Secrets: DPAPI, je Sitzung abgefragt | `app/security/DpapiSecretStore`, `AskedSecretProvider` | App `SecretVault`, `SessionSecrets` | gleich |
| Secrets: KeePassRPC | `proasteion/platform/security-keepassrpc/…/KeePassRpcSecretProvider` | keine eigene; einhängbar über `Harvester.builder().secretProvider(SourceSecrets)` (#226) | gleich über den Port; der KeePassRPC-Adapter bleibt im EAC |
| Secrets: Azure Key Vault | — | Server-Profil `azure-keyvault` | SH+ |
| Verbindungsdiagnose mit Ursache (Zertifikat, Proxy, DNS) und Stufentest | `app/net/ConnectionDiagnosis`, `exedra/…/settings/ConnectionProbe` | `SourceManagement.diagnose` → `SourceDiagnosis` mit Stufen Proxy-Route, DNS, TCP, TLS, Anmeldung, API, REST `GET /api/v1/sources/{id}/diagnosis` (#238); Ursache je Auftrag aus `JobEvent` (docs/DIAGNOSTICS.md) | gleich |
| HTTP-Kopfzeilen | `NetworkConfig`: nur der User-Agent, keine frei konfigurierbaren Kopfzeilen | `userAgent`, global gesetzt | gleich |

## 14. Desktop-App

| Fähigkeit | EAC | App | Bewertung |
|---|---|---|---|
| Leseansicht mit Bildern, Diagrammen, Bildergalerie | `exedra/…/reader/ReaderHtmlView`, `ImageSlideshowPanel` | `desk/…/content/ReadingPane` (Reiter „Leseansicht“): HTML aus `ReadingViews`, Bilder über `Assets.viewImage`, Mermaid über `Diagrams`, fehlende Bilder markiert, Klick öffnet Galerie mit ◀/▶ | gleich (#192) |
| Auftragskarte mit „Exportieren als …“, Ausgabeordner | — | in Arbeit (Strang L, Runde 7) | in Arbeit |

## Lücken und Issues

| Issue | Thema | Strang |
|---|---|---|
| #176 | Leseansicht (`body.view`/`export_view`) als Core-Use-Case | Q |
| #177 | Live-Navigation: Kindseiten, Breadcrumbs, Querverweise | Q |
| #178 | URL- und Ressourcenauflösung vollständig | Q |
| #179 | Space-Filter der Schlagwortsuche in der CQL-Anfrage | Q, erledigt |
| #180 | Volltextindex über den Cache, Index-Rückfall, Treffer vorladen | Q, erledigt |
| #181 | Zerlegung, Rerank-Modell, verwandte Abschnitte (keine hybride Suche, ADR 0013) | Q |
| #182 | Suchlauf-Protokoll und Metadaten-Fakten als Core-Ports | Q |
| #183 | Textextraktion (Anhänge und PDF/Word lokaler Dateien im Volltextindex), Typerkennung am Inhalt, Vorschauen von Anhängen | Q |
| #184 | `ImageDescriber` über den externen KI-Dienst | Q |
| #185 | Einmalcode-Anmeldung (2FA) als Port | Q |
| #186 | Export einzelner Seiten und Teilbäume | Q |
| #187 | Quellen ohne Partitionen und weitere Quelltypen des EAC | Q |
| #188 | `Harvester.builder()` erweiterbar; Quellenverwaltung zur Laufzeit | Q |
| #189 | Netzwerk: Proxy-Anmeldung, PAC-Skript, Vertrauen, Kopfzeilen (#234), Verbindungsdiagnose (#238) | Q, erledigt |
| #190 | draw.io- und Mermaid-Rendering im Core | R |
| #192 | Leseansicht der App mit Bildern und Diagrammen | L |
| #193 | Vergleichstests EAC und Core mit denselben Testdaten | S |
| #218 | Ausgeführte Makros aus der Leseansicht in den Konvertern (KI-Text bleibt auf `storage`) | Q, dann R |

In Arbeit und deshalb ohne neues Issue:

- Konvertierung aus dem Datenbestand, Asset-Manifest und Bildbeschaffung beim Herunterladen (Q, Runde 7),
- Leseansicht der App mit Bildern und Diagrammen (L, #192),
- Auftragskarte und Ausgabeordner (L).

## Vergleichstests

Modul `:parity-tests` (#193). Maßstab ist der Export des Enterprise AI Client; der Core darf mehr können, nie
weniger. Verglichen wird die Struktur, nicht die Bytes: Leser für DOCX (OOXML im ZIP), PDF (PDFBox) und Markdown
bilden jede Datei auf ein neutrales `DocumentOutline` ab, `ParityComparison` vergleicht zwei davon.

### Testdaten

`ParitySpace` legt im `FakeConfluence` (in beiden Repositories gleich) den Space `PARITY` „Paritätsprüfung“ an:
Handbuch → Architektur, Komponenten → Ebene 4 … Ebene 7 (sieben Gliederungsebenen unter dem Space), Betrieb. Darin:
externer Link und `mailto`, Tabelle 3 × 3 mit Kopfzeile, verschachtelte Listen, Hervorhebungen, Code-Makro,
eingebettetes SVG aus der Anhangsliste, interner `ac:link`, Mermaid-Makro, draw.io-Makro mit Anhang, ein fehlendes
Bild (404) und ein nicht ladbares externes Bild, sieben Merksätze. Jede Seite hat `body.storage` und eine
handgeschriebene `export_view`. Bilder sind SVG, weil der Fake Anhänge als Text speichert.

### Ablauf

1. **Referenzen des Enterprise AI Client:** `parity-tests/eac/generate.sh [pfad-zum-checkout]` baut den
   Enterprise AI Client am Commit aus `src/test/resources/eac/COMMIT`, exportiert den Paritäts-Space über
   `KnowledgeExportUseCase.subtree` als Word, PDF und Markdown und legt `parity.{docx,pdf,md}` samt
   `problems-<format>.txt` (Meldungen von `ExportGraphics`) unter `src/test/resources/eac/` ab, dazu je Seite den
   Lesetext aus `load` und die Bildzahl aus `pageView` unter `reading/` sowie Kinder, Breadcrumbs und Querverweise
   (`children`, `position`, `crossReferences`) in `navigation.properties`, außerdem die Schritte der
   Quellenverwaltung (`KnowledgeSourceManagement` mit dem echten Confluence-Provider über dem Fake) in
   `management.properties`, die Seite je Adressform (`resolveAddress`, Bookmarks) in `addresses.properties` sowie die
   Suche (`SearchKnowledgeSourcesUseCase` über eine indexierte Dateiquelle, Vorladen der Confluence-Treffer mit
   `KnowledgePrefetchService`) in `search.properties`.
   Die CI von Space Harvester baut den Enterprise AI Client nicht; die Referenzen sind eingecheckt und werden bei einem neuen
   Commit des Enterprise AI Client neu erzeugt.
2. **Core:** `CoreParityTest` startet den Fake als HTTP-Server, lädt denselben Space herunter, konvertiert ihn
   aus dem Datenbestand als Word, PDF und Markdown, holt je Seite die Leseansicht (`Harvester.readingViews()`) und
   die Navigation (`Harvester.navigation()`), spielt die Schritte der Quellenverwaltung mit
   `Harvester.sourceManagement()` nach, sucht mit `Harvester.keywordSearch()` und vergleicht mit den Referenzen. Bericht:
   `parity-tests/build/reports/parity/parity-report.md` (in der CI in der Job-Zusammenfassung und im Artefakt),
   Core-Exporte daneben unter `core/`.
3. **Sperrklinke:** Jede Prüfung, bei der der Core weniger kann, steht in
   `src/test/resources/known-gaps.properties` mit Issue oder Strang. Eine neue Lücke lässt den Test scheitern;
   eine geschlossene meldet der Bericht („aus known-gaps entfernen“).

### Prüfpunkte

| Prüfung | Formate | Regel |
|---|---|---|
| Kapitel und Überschriften | alle | jeder Titel des Maßstabs im Core |
| Tiefste Gliederungsebene | alle | Core ≥ Maßstab |
| Inhaltsverzeichnis, Seitenzahlen | Word, PDF (Markdown nur wenn eine Seite eins hat) | Core ≥ Maßstab |
| Felder beim Öffnen aktualisieren | Word | Core ≥ Maßstab |
| PDF-Lesezeichen | PDF | Core ≥ Maßstab |
| Eingebettete Bilder | alle | Core ≥ Maßstab (Data-URI oder lokale Datei in Markdown) |
| Hinweise auf fehlende Bilder | alle | meldet der Maßstab welche, meldet sie auch der Core |
| Tabellen | Word, Markdown | jede Tabelle (Zeilen × Spalten, Kopfzeile) im Core |
| Externe Links | alle | Ziele des Maßstabs ⊆ Core |
| Interne Sprünge, Mermaid-Quelltext | alle | Core ≥ Maßstab |
| Text | alle | ≥ 97 % der Wörter des Maßstabs im Core (ohne Zahlen und Exporter-Hinweise) |
| Ausgeführte Makros | alle (#218) | jeder Satz, den `include` oder `excerpt-include` aus einem anderen Space einsetzt, steht im Core; keine `OMITTED_CONTENT`-Meldung |
| Leseansicht | je Seite (#176) | jede Seite mit Ansicht im Core, Lesetext wie bei Text, Bilder Core ≥ Maßstab |
| Navigation | Space und je Seite (#177) | Space-Name gleich; Kindseiten und Breadcrumbs gleich mit Reihenfolge; ausgehende Seitenverweise des Maßstabs im Core |
| Adressen | je Form (#178) | dieselbe Seite wie beim Maßstab für Seiten-ID, `/spaces/…/pages/…`, `display/KEY/Titel`, `viewpage.action?spaceKey&title`, Space-Startseite, `confluence://`-Bookmark und Ressourcen-ID; keine Seite für unbekannten Titel und fremden Host |
| Suche | je Suchbegriff und Live-Treffer (#180) | Quelle ohne eigene Suche: dieselben Dateien wie beim Maßstab (eine, zwei, Großschreibung, keine), jeder Auszug zeigt den Suchbegriff; jeder Treffer der Live-Suche in Confluence danach vorgeladen |
| Quellenverwaltung | Szenario (#188) | je Schritt dasselbe Ergebnis: prüfen (vollständig, ohne Basis-URL, dasselbe Ziel doppelt), Verbindung testen (erreichbar, nicht erreichbar, unvollständig), hinzufügen, ändern mit und ohne neues Ziel (Daten bleiben bzw. werden zurückgezogen), abschalten, entfernen (Daten zurückgezogen, gemessen am Cache) |

Stand nach #213: Word, PDF und Markdown haben keine bekannten Lücken mehr (`known-gaps.properties` ist leer). Der
Enterprise AI Client verliert die Ziele externer Links in allen Formaten, der Core behält sie. Quellenverwaltung:
keine bekannten Lücken.

Gewollte Abweichung: Der Enterprise AI Client wiederholt jeden Seitentitel eine Ebene tiefer als Überschrift im
Kapitelinhalt. Der Core lässt das weg; `kapitel` und `ebenen` werten die direkte Wiederholung nicht, `text` zählt
Wörter aus Überschriften nur einmal.

### Live-Lauf gegen echte Daten

Denselben Space einmal im Enterprise AI Client (Space-Knoten oder Startseite, „Exportieren“ als Word, PDF und
Markdown) und einmal in der Desktop-App (Herunterladen, dann Exportieren in denselben Formaten) exportieren. Dann
im Checkout von Space Harvester:

```bat
gradlew.bat :parity-tests:run --args="C:\Exporte\eac.docx C:\Exporte\sh.docx C:\Exporte\eac.pdf C:\Exporte\sh.pdf C:\Exporte\eac.md C:\Exporte\sh.md --out C:\Exporte\paritaet.md"
```

Oder einmal `gradlew.bat :parity-tests:installDist` und danach
`parity-tests\build\install\parity-report\bin\parity-report.bat <eac-datei> <core-datei> … --out bericht.md`.
Die Paare sind immer „Enterprise AI Client, dann Space Harvester“; das Format folgt der Dateiendung. Der Bericht
ist Markdown, Rückgabewert 1 heißt: Der Core kann irgendwo weniger.

### Noch nicht abgedeckt

Die Trefferauswahl der Live-Suche vergleicht `:parity-tests` nicht, weil der Fake feste Treffer liefert und beide Seiten
dieselbe CQL bauen. Anhänge fehlen noch, dafür fehlen Core-Schnittstellen: Text aus Anhängen #183,
Export einer Seite oder eines Teilbaums wie im Enterprise AI Client #186. Bekannte, gewollte Abweichungen (Core darf
hier mehr): interne Seitenlinks, Raw-JSON, Herkunft im Manifest, textuelle Diagrammquellen, PlantUML als Code.
