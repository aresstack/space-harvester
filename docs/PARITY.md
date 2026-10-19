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
| Einmalcode (2FA) und Web-Session für Downloads | `conf/OneTimeCodeForm`, `ConfluenceWebSession`, `DownloadAccess`; `know/UnlockingDocumentReader`, `OneTimeCodePrompt` | Erkennung, „Code erneut senden“ und Sitzung je Quelle unverändert im kopierten Confluence-Adapter; `KnowledgeSourceHarvestSource` meldet die Abfrage als `SignInRequiredException` mit `SignInChallenge`; ein Auftrag wartet in der Phase „Wartet auf Anmeldung“ an derselben Stelle und lädt nach der Annahme dieselbe Datei neu (`Harvester.signIns()` → `SignInRequests`: `pending()`, `waiting(jobId)`, `submit(id, char[])` → `ACCEPTED`/`REJECTED`/`FAILED`/`GONE`, `resend`, `decline`, `onChange`); `Builder.signInWait` Standard 0 (wartet nie), empfohlen `SignInRequests.DEFAULT_WAIT` 15 min; ohne Anmeldung fehlen die übrigen Dateien mit `SIGN_IN_REQUIRED` und `ExportConversions.collectImages` holt sie nach; der Code wird nur an die Quelle gegeben und danach überschrieben (#303, ADR 0018); die Desktop-App zeigt die Abfrage wie `exedra/…/reader/ReaderSignIn` unter Aufträge und in der Leseansicht (`app/…/ui/SignInSlot`: Hinweis der Quelle, Fehler des letzten Versuchs, Codefeld, „Code erneut senden“, „Bestätigen“, „Ohne Anmeldung fortfahren“) und setzt `signInWait(SignInRequests.DEFAULT_WAIT)` (#312); der Server bietet dieselbe Abfrage als REST für eigene Aufträge: `GET /api/v1/sign-ins`, `POST …/{id}/code`, `POST …/{id}/resend` (502 ohne Antwort der Quelle), `DELETE …/{id}`, fremde Anforderungen 404, Wartezeit `space-harvester.export.sign-in-wait` Standard `PT0S` (#308, optionale Erweiterung) | gleich, SH+: auch ein laufender Export fragt nach dem Einmalcode, im EAC gilt die Abfrage dort als fehlendes Bild |
| Delegierte Anmeldung (Benutzertoken je Anfrage) | keine | `DelegatedConfluenceHttp`, `DelegatedAccessPolicy` | SH+ |
| Querverweise und ausgehende Links | `#crossReferences`, `#discoverLinks`, `#visiblePageTitles` | `PageNavigation#references`: ausgehend, eingehend wenn die Quelle sie vollständig kennt; dazu Linkumschreibung im Export | gleich (Vergleichstest, ausgehend) |

## 2. Schlagwortsuche

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Live-Suche über mehrere Quellen, Status je Quelle | `know/SearchKnowledgeSourcesUseCase` | `core/application/query/KeywordSearch` (`SEARCHED`/`NOT_SUPPORTED`/`UNAVAILABLE`, `complete`) | gleich |
| Paginierung | `limit`, Vorladen über `KnowledgePrefetchService` | Cursor je Quelle, „Weitere laden“ | gleich |
| Metadaten und Auszüge je Treffer | `SourceSearchHit`, `ConfluenceRestClient#excerpt` | `KeywordSearch.Hit` (Version, Änderung, URL, Space, Elternseite, Auszug) | gleich |
| Rechte je Treffer | eigene Anmeldung | `AccessGuard` je Treffer, gesperrte fallen ohne Spur weg; für delegierte Benutzer (`END_USER`) prüft die Suche nur die Endliste aus dem Cache seitenweise, Funde der Quelle dienen nur zum Bewerten und Laden, `Results.found()` bleibt leer und der Cursor nennt keine ungeprüften Seiten; technische Clients unverändert, die progressive Suche erbt die Regel (#323, ADR 0014 Punkt 4, Festlegung Berechtigung) | SH+ |
| Rückfall auf lokalen Index für Quellen ohne eigene Suche | `SearchKnowledgeSourcesUseCase` (Index-Fallback) | Mit Cache sucht `KeywordSearch` im Volltextindex des Caches (ADR 0013), damit auch über Quellen ohne eigene Suche (`PageTextIndex`, Status `INDEXED`, REST `indexed`); Treffer verlangen zusätzlich das Inhaltsrecht; ohne Cache (Server ohne `space-harvester.cache.directory`) liefert sie die geprüften Treffer der Quellen, Quellen ohne eigene Suche sind dann `NOT_SUPPORTED` | gleich (Vergleichstest Suche, #180) |
| Lokale Volltextsuche über zwischengespeicherte Inhalte mit Textfenstern | `chalco/anagraphai/…/LuceneRawKnowledgeIndex`, `RawTextWindows`, `chalco/pinakes/…/LuceneTextIndex` | `LucenePageTextIndex` über den Text der Seiten im Cache (`PageTextIndexing` mit Projektoren für Confluence und lokale Dateien), Auszug um den Suchbegriff; lokale Dateien als Text und PDF, Word, Excel, PowerPoint, ODF und RTF über `TikaDocumentText` wie im EAC über `DocumentExtraction.registry()` (`LocalFilesTextProjector`, Schema `file-document-text/v1`, #328); Anhangstexte noch nicht im Index | teilweise (Q, #328) → #183 (Textdateien gleich laut Vergleichstest Suche: dieselben Dateien, Auszug mit Suchbegriff, ohne Rücksicht auf Groß-/Kleinschreibung; Dokumenttext gleich laut Vergleichstest Dokumenttext) |
| Treffer der Live-Suche transparent in den Cache laden | `know/KnowledgePrefetchService`, `SearchDrivenIndexingUseCase` | `SearchHitPrefetch` lädt die gefundenen Seiten eines Stapels, deren Version nicht im Cache liegt, seitenweise mit den Rechten des Aufrufers über den Beschaffungs-Use-Case in den Cache, in der Rangfolge von Titel und Auszug (`space-harvester.search.loads`, Standard alle); Server und Core warten standardmäßig nicht darauf (#296, `Harvester.builder().searchWait(Duration)`, Standard 0), die Desktop-App wartet bis 5 s (`AppRuntime`: `searchWait(SearchHitPrefetch.DEFAULT_WAIT)`), delegierte Benutzer warten höchstens 5 s und laden nur während ihres Aufrufs; Nachzügler zählen in `Results.loading()` (REST `loading`) und rücken mit dem nächsten Stapel nach (#255); `Harvester.builder().prefetchSearchHits(boolean)`, Standard an | gleich (Vergleichstest Suche) |
| Schlagworte aus der Frage ableiten | `research/…/knowledge/ResearchQueries` (deterministische Schlagwort-Rückfälle `keywordQueries`, `literalQuery`, `isConcise`) | `SearchTerms` (`keywordQueries`, `literalQuery`, `isConcise`): eine Frage mit mehr als sechs Wörtern geht als ihre stärksten wörtlichen Begriffe, dann ihre Schlüsselwörter einzeln und die ersten beiden zusammen an die Suche jeder Quelle, ohne Füll- und Fragewörter; höchstens `space-harvester.search.discovery.max-queries` Anfragen (Standard 4, 1 bis 8) parallel, Funde je Quelle reihum ohne Dopplung zusammengeführt; im Cache sucht weiter der Text des Aufrufers; `Harvester.builder().discoveryQueries(int)` (#296). Zusätzlich entfernt `SearchTerms` Wörter einer Bitte vor der Schlüsselwortwahl | gleich (#181, #296) |
| Kurze lexikalische Suchausdrücke (interne Kürzel unverändert) | `research/…/knowledge/LexicalDiscoveryStrategy` (`accepts` = `isConcise`, `fallbackQuery` = `literalQuery`, `planningRules` für das Modell des Agenten) | `KeywordSearch` schickt einen knappen Suchausdruck unverändert und leitet aus einem Satz zuerst `SearchTerms.literalQuery` ab (Kürzel wie `HaVdB`, `BS-PIMA` in ihrer Schreibung); die `planningRules` sind Anweisungen an das Modell des Agenten und bleiben im Client | gleich (#181, #296) |
| Ladereihenfolge nach Titel und Auszug (Cross-Encoder, entfernt nichts) | `astu/…/knowledge/ExcerptRanking` (#154) | `ExcerptPriority`: der Cross-Encoder bewertet Titel und Auszug der Funde gegen den Suchtext und bestimmt die Ladereihenfolge und die Reihenfolge der vorläufigen Treffer; er verwirft nichts, schickt nur Texte aus Quellen mit Freigabe für externe Verarbeitung und fällt bei jedem Fehler auf die Reihenfolge der Quellen zurück; die Rangfolge der Treffer aus dem Cache bleibt BM25 (ADR 0013); `space-harvester.search.excerpt-ranking` (Standard an), `Harvester.builder().excerptRanking(boolean)`; im Server folgt die Bewertung dem live tauschbaren Reranker (#296) | gleich (#181, #296) |
| Vorläufige Treffer sofort, Laden mit Fortschritt und Abbruch | — (`SearchDrivenIndexingUseCase` wartet, bis gefundene Seiten geladen und indexiert sind) | `KeywordSearch.Results.found()`: alle Funde der Quellen sofort mit Titel und Auszug der Quelle, nach Titel und Auszug bewertet oder in der Reihenfolge der Quelle; `ranked()` liefert zuerst die Treffer aus dem Cache (BM25), dann die vorläufigen; ein geladener Treffer rückt bei der nächsten Suche nach vorn, statt zu verschwinden (Identität aus Quelle und Seiten-ID); `progress()` mit `total`, `loaded`, `failed`, `cancelled`, `done` („Die Suche ist abgeschlossen“); `KeywordSearch.progress(id)`/`cancel(id)`, REST `found[]`, `progress{…}`, `GET`/`DELETE /api/v1/search/progress/{id}`; der Abbruch verwirft nur wartende Abrufe, ein laufender endet regulär; vorläufige Treffer brauchen die quellweite Freigabe für `metadata` (sonst Einzelprüfung), ihren Auszug zeigen sie nur mit Leserecht auf den Inhalt (#296, ADR 0013 Fortschreibung) | SH+ |
| Auswahl der Beschaffung, Vorrang des Vordergrunds, Hintergrundfüllung | — (`KnowledgePrefetchService` lädt jeden Fund) | ADR 0016: vorläufige Trefferliste, Laden in Rangfolge, Fortschritt und Abbruch je Suche (#296); austauschbare `CandidateSelectionStrategy` (ORDER, TOP_N, ADAPTIVE; Server `space-harvester.search.candidate-selection`, Standard `adaptive`, und `space-harvester.search.loads`, beim Start gelesen), `AcquisitionScheduler` (`Harvester.acquisition()`: `status()`, `pauseBackground()`, `request(caller, key, Priority)` → `Ticket`; `PageQueries.acquire(caller, key, background)`) mit eigenen Plätzen für den Vordergrund, Pause NONE, FOREGROUND, USER_ACTION, COOLDOWN, DISABLED, IDLE oder CLOSED, Fehlschläge mit Backoff; dauerhafte Hintergrundfüllung `BackgroundFill` (`Harvester.backgroundFill()`) lädt NOT_LOADED und OUTDATED, nie gesperrte oder per Policy ausgeschlossene Seiten, Warteschlange überdauert das Beenden, Abschalten je Quelle zieht Wartende zurück; Inhalte nicht für den KI-Dienst freigegebener Quellen gehen nie an ihn und bekommen keine Embeddings (#332). Stand über `Harvester.acquisitionStatus()` und `GET /api/v1/acquisition` (nur Zähler). Einstellungen `sync.background-fill`, `sync.background-parallelism` 1 bis 16, je Quelle `sync.sources.<Quelle>.background-fill` (#324), in der App unter Einstellungen › Cache und je Quelle unter Synchronisierung (#326). Gemeinsame Verarbeitungskette ist `PageQueries.page` mit `EnrichingCacheFill`. In der App zeigt die Tafel „Hintergrundbefüllung“ unter Status (`app/…/ui/BackgroundPlate`, #335), ob und warum geladen oder pausiert wird. Dazu kommen Warteschlange, laufende Abrufe („Lädt gerade“ = Vordergrund plus Hintergrund), Geladenes und Gescheitertes, gesamt und je Quelle, alle 2 s neu. Offen: Katalogisieren aller Funde als Kopfdaten (braucht den Space-Schlüssel im Fund des EAC) | Erweiterung, weitgehend umgesetzt (ohne Paritätspflicht) |

## 3. Navigation

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Spaces als Wurzeln | `know/PageNavigationUseCase` | `GET /sources/{id}/spaces` live | gleich |
| Seitenbaum ganzer Space live | live je Knoten | `core/application/navigation/PageNavigation#children` live je Knoten (#177), REST `…/spaces/{key}/children`, `…/pages/{pageId}/children`, `/ancestors`, `/links`; daneben `PageQueries#tree` aus dem Cache | gleich (Vergleichstest) |
| Kindseiten eines Knotens live, in Confluence-Reihenfolge, mit `hasChildren` | `ConfluenceKnowledgeSource#children`, `#treeBatch` | `PageNavigation#children`: live, Reihenfolge der Quelle, `hasChildren`, Stapel mit Cursor | gleich (Vergleichstest) |
| Vorfahren und Breadcrumbs mit Space-Namen | `#position`, `ConfluenceRestClient#lineage` | `PageNavigation#position`: Space mit Namen, Vorfahren von oben, Rechte je Vorfahr | gleich (Vergleichstest) |
| Seiten über ihren Titel finden, auch nicht abgeglichene | — (nur exakter Titel bei der Adressauflösung: `ConfluenceRestClient#pageIdByTitle`) | `PageNavigation#findByTitle(caller, sourceId, spaceKeys, title, limit)` → `TitleMatches(sourceId, pages, truncated)`: „Titel enthält“, Spaces wählbar (leer = alle), `limit` 1 bis 100, braucht `metadata` auf die Quelle, jeder Treffer läuft durch die Zugriffsprüfung, `truncated` zählt nur sichtbare Seiten; Confluence über die CQL-Suche des EAC-Adapters mit Space-Filter und Titelprüfung (höchstens 1000 Funde geprüft), Ordner über den Dateinamen; Fähigkeit `title-search`; REST `GET /api/v1/sources/{id}/pages?title=…&spaceKey=…&limit=…` (Standard 25) (#305); der Titelfilter des Explorers fragt darüber die Quellen, auch nach nie geladenen Seiten (#314) | SH+ |
| Hierarchie anderer Quellen (Ordner in Git) | `PageNavigationSource` in `source-git` | keine | fehlt → #187 |

## 4. Seitenabruf

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Authentifizierung beim Abruf | `ConfluenceKnowledgeSource#withSession` | Kopie; Client-Token am Dienst | gleich |
| Rechteprüfung | `chalco/tamias/…/ClientResourceAccessPolicy` (Einbenutzer) | `AccessGuard`, `CorpusReleasePolicy`, `DelegatedAccessPolicy` je Seite | SH+ |
| Versionsabgleich und Aktualität | `KnowledgePrefetchService#seenRevision`, `ContentHasher` | `core/application/sync/PageFreshness`, `refresh=true`, Hashes `page-metadata/v1`, `page-attributes/v1`; Zeitfenster ab Inhaltsabruf bzw. letzter Prüfung (`space-harvester.cache.max-age`, Standard `0s`) und abschaltbare Versionsprüfung (`space-harvester.cache.version-check`, sonst Neuabruf), im Core `Harvester.builder().cacheFreshness(CacheFreshness)`; Rechte werden auch bei frischen Seiten geprüft (#256, ADR 0013) | SH+ |
| Quelle nicht erreichbar → Cache-Stand | Prefetch | `PageQueries#page` liefert Cache bei `UNAVAILABLE`/`RATE_LIMITED` | gleich |
| Text der Seite zum Lesen | `know/LoadKnowledgeDocumentUseCase`, `KnowledgeDocumentReader` (aus `body.view`) | `ReadingViews` (#176): Lesetext aus `body.view` mit ausgeführten Makros | gleich (Vergleichstest Leseansicht) |
| Ausgeführte Makros (`include`, `excerpt-include`, `children`) in Word, PDF und Markdown | über `body.view` im Export | Leseansicht im Datenbestand (`views/`, `export-reading-view/v1`, `ConversionInput#readingView`, Q #222), in Word, PDF und Markdown aus der Leseansicht, ohne Quellabfrage (R #230); fehlt sie, Hinweis und `OMITTED_CONTENT` | gleich (Vergleichstest Makros) |
| Text für die KI-Anreicherung | `KnowledgeDocumentReader` (aus `body.view`) | `PageTextProjector`, `ConfluenceStorageTextProjector` (aus `storage`) | gewollt anders: fremd gerendert eingebundene Inhalte wären in der gemeinsamen Anreicherung ein Rechteleck; wer die Seite lesen darf, bekommt den Lesetext über `ReadingViews` |

## 5. Cache

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Originaldaten persistent | keine (In-Memory-Payloads, Index hält Text) | `core/persistence/lucene/LucenePageCache` (Rohbytes + SHA-256) | SH+ |
| Versioniert, inkrementell, Löschungen | `RefreshKnowledgeSourceUseCase`, `chalco/ResourceArchive` | `SpaceSync`, `SyncPlanner`, `RemovalCheck`, Generationen | SH+ |
| Abgleich ohne Erstbefüllung, Betriebsarten, optional nach Zeitplan | `RefreshKnowledgeSourceUseCase` (Abgleich auf Knopfdruck über den Index) | Seitenkatalog und Inhaltsindex mit getrennten Lebenszyklen; Betriebsart `SyncMode`: `catalog-only` (Standard, pflegt nur Kopfdaten, neue Versionen bekannter Seiten als `PageChange.Kind.OUTDATED`), `sync-cache` (lädt bekannte Seiten mit neuer Version neu), `full` (lädt auch unbekannte Seiten); `space-harvester.sync.mode`, REST `POST …/syncs?mode=…` (`loadUnknown` veralteter Alias), `SyncSummary.notLoaded`/`outdated`/`moved`; Zeitplan je Space über `Harvester.reconcile(sourceId, spaceKey, interval, mode)`, `stopReconciling`, `reconciliations()`, REST `GET /api/v1/reconciliations` (#266, #279, ADR 0014); kein Anspruch auf einen ständig aktuellen Gesamtbestand | optionale Erweiterung |
| Getrennte Löschfeststellung | `ResourceArchive#removeByUri`: an der Quelle weggefallen, ohne Unterscheidung zwischen gelöscht und nicht mehr sichtbar | `RemovalCheck.Verdict`: `DELETED` nur bei bestätigter Löschung (`HarvestSource.confirmsDeletion()`, heute lokale Dateien) wird entfernt; `INACCESSIBLE` (für das technische Konto nicht sichtbar) wird sofort gesperrt und aus den Indizes genommen, die Rohdaten nach `space-harvester.sync.withheld-retention` (Standard 30 Tage) entfernt; `SyncSummary`/REST mit `withheld` und `purged`, `removed` zählt nur bestätigte Löschungen; Benutzerrechte werden bei jeder Ausgabe unabhängig geprüft (#263, [ADR 0014](adr/0014-abgleich-berechtigung-suche-getrennt.md)) | SH+ |
| Katalogstand und Vorschau je Space | keine (Abgleich auf Knopfdruck ohne Vorschau) | `Harvester.catalog(sourceId, spaceKey)` → `SpaceCatalog` (Zustand `UNKNOWN`/`PARTIAL`/`CATALOGED`, bekannte, gespeicherte, veraltete und zurückgehaltene Seiten, Generation, `confirmedAt`, `changesSince`); `Harvester.preview(…, SyncMode)` → `SyncPreview` mit den Seiten, die ein Abgleich neu, geändert, verschoben, entfernt, zurückgehalten oder ungeprüft fände, ohne etwas zu schreiben; REST `GET …/spaces/{spaceKey}/catalog`, `GET …/spaces/{spaceKey}/sync-preview?mode=…` (#281) | SH+ |
| Transparentes Nachladen | `KnowledgePrefetchService` | `PageQueries`, `PageCacheFill` (ADR 0010) | gleich |
| Änderungsquellen: Webhooks, CQL-Nachholen, Zeitplan | keine (Abgleich auf Knopfdruck, suchgesteuertes Vorladen) | optional, keine Voraussetzung und keine Erstbefüllung (Angelo 18.10.). Nachholen umgesetzt (#279): Änderungssuche per CQL seit dem letzten bestätigten Stand (`SyncBaseline.changesSince`) plus Papierkorb je Space über `HarvestSource.listsChanges()`, `changedPageMetadata`, `removedPageMetadata`; `Harvester.catchUp(sourceId, spaceKey, since, mode)`/`catchUpAll`, REST `POST …/syncs?listing=changes&since=…`, beim Start über `space-harvester.sync.catch-up-on-start` (Standard aus). Webhooks umgesetzt (#330, M), wo ein Confluence-Administrator sie einrichtet: `Harvester.changeEvents()` → `ChangeEvents.receive(sourceId, body, signature, eventKey)` mit `Receipt` ACCEPTED, DISABLED, UNKNOWN_SOURCE, BAD_SIGNATURE oder UNREADABLE; wirksam nur mit `sync.events` (global oder je Quelle, live) und einem Geheimnis unter `space-harvester.webhooks.secrets.<Quelle>`; Wirkung nur im Katalog (bekannte neue Version OUTDATED, unbekannte Seite NOT_LOADED, entfernt DELETED, Rechte geändert → veraltet bzw. gesperrt), kein Inhaltsabruf. „Vor jeder Suche“ (Q) folgt. Ohne Änderungsquelle füllt allein die Suche den Index; verpasste Löschungen erkennt die Inventur (ADR 0014). Kein Anspruch auf einen ständig aktuellen Gesamtbestand | optionale Erweiterung; Nachholen da (#279), Webhooks umgesetzt (#330, M): Confluence-Ereignisse über `POST /webhooks/v1/sources/{id}/events` außerhalb von `/api` (Confluence signiert nur, `X-Hub-Signature` mit HMAC-SHA256 und Geheimnis je Quelle, nicht in `GET /configuration`; Antworten 202, 400, 401, 404, 413 über 1 MiB), nur Katalog-Markierung |

## 6. Bilder

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Bildreferenzen im Storage (`ac:image`, `ri:attachment`, `ri:url`) | — | `StorageParser#image` | SH+ |
| Bilder der Leseansicht (Inline, Vorschaubilder, draw.io-Vorschau; ohne Emoticons) | `conf/ConfluencePageView`, `#viewImage`, `#loadViewImage` | keine | in Arbeit (Strang Q, Runde 7: Bildreferenzen aus Anhängen und `PageViewSource`) |
| Authentifizierter Abruf, dauerhafte, versionierte Ablage, Wiederverwendung | `doc/ExportGraphics`, `exedra/…/reader/ReaderImageStore` (flüchtig) | `Assets`, `core/persistence/files/FileSystemAssetStore` (SHA-256, versioniert) | SH+ |
| Bilder beim Herunterladen mitbeschaffen, Asset-Manifest im Datenbestand | `know/KnowledgeExportUseCase#figures` | eigener Auftrag „Bilder beschaffen“ nach jedem Export (`ExportAssetCollection`, #200), SHA-256-adressiert, `assets.json` (`ExportAssetManifest`) mit Status je Datei, darunter `SIGN_IN_REQUIRED` und `REDUNDANT` (#303); Nachholen über `ExportConversions.collectImages` | gleich (#200, #303) |
| Dateityp am Inhalt erkennen (Magic Bytes) | `deigma/document-tika/…/TikaContentDetector`, `ExportGraphics#decode` | `DocumentText#detect` über dieselbe `DocumentExtraction.detector()` wie im EAC (#328); beim Ablegen eines Anhangs wird der Typ an den Magic Bytes geprüft und bei Bedarf korrigiert | gleich (Vergleichstest Dokumenttext: derselbe Typ je Datei) |
| Bildbeschreibung durch ein Vision-Modell | `astu/acropolis/…/image/SourceImageAnalysisUseCase`, `ImageAnalysisCache` | Port `ImageDescriber` und Ablage, aber keine Implementierung; Diagramme mit textueller Quelle (draw.io, SVG, Mermaid) werden statt einer Beschreibung textuell verwendet (`*.graph.json` im `json-package`, #306). Offen: Beschreibung durch ein Vision-Modell (nicht beauftragt) | teilweise → #184 |

## 7. Anhänge

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Auflisten mit Metadaten (Größe, Medientyp, Version, Änderung, Container) | `#storedAttachments`, `know/ListKnowledgeAttachmentsUseCase` | `SourceQueries.attachments`, `PageAttachment` | gleich |
| Abrufen mit Grenze, Ablage | `#loadAttachmentFile` | dasselbe plus versionierter Store, `AssetTooLargeException`, Speichergrenze | SH+ |
| Text aus Anhängen und Dateien (PDF, DOCX, XLSX, PPTX, ODF, RTF) | `deigma/document-tika/…/TikaResourceExtractor`, `know/SourceAttachmentReader`, `DocumentSourceFileTextExtractor` | `core/application/assets/DocumentText` mit `TikaDocumentText` (#328): dieselben übernommenen Klassen `DocumentExtraction` und `DocumentSourceFileTextExtractor`, Tika 3.3.2 statt 2.9.1 (PDFBox 3.0.8 statt 2), Grenzen 20 MiB je Datei, 30 s je Lauf, 2 Läufe gleichzeitig, 1 Mio. Zeichen; `Assets.text()` → `AssetText`; lokale Dateien im Volltextindex. Noch offen: Anhangstexte im Index mit den Rechten der Seite, REST für `Assets.text` | teilweise (Q, #328) → #183; Text und Typ gleich laut Vergleichstest Dokumenttext |
| Vorschau von Office- und PDF-Anhängen | `#loadAttachmentPreview` | keine | fehlt → #183 |

## 8. Diagramme

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| draw.io erkennen (Makro, Revision, `inc-drawio`) | `ConfluencePageView#drawioPreviews`, `#storageDrawioPreviews` | `StorageParser`, `AssetKind.DIAGRAM`, auch `inc-drawio` (#210) | gleich |
| draw.io als Grafik (mxfile → SVG, sonst PNG-Vorschau) | `conf/DrawioDiagramRenderer`, `doc/ExportGraphics` | `core/application/diagrams/Diagrams`; in Word, PDF und Markdown gezeichnet (#210), in der Leseansicht als PNG des Core (#322) | gleich; gezeichnete Diagramme liegen als Rendition im Datenbestand (#220) |
| draw.io-XML und SVG textuell erhalten | — | Asset-Ernte bevorzugt `DIAGRAM` > `VECTOR` > `IMAGE` | SH+ |
| draw.io-Vorschau-PNG nicht doppelt laden | lädt Quelldaten und Vorschau | Ist das Diagramm aus den Quelldaten gezeichnet, wird die PNG-Vorschau des Plugins nicht geladen (`REDUNDANT`, Grund `drawio-preview` in `assets.json`, zählt nicht als fehlend); nur bei unlesbaren oder nicht darstellbaren Quelldaten ist sie der Ersatz (#303, ADR 0018) | SH+ |
| Mermaid erkennen (Confluence-Makros) | nur Markdown-Fences | `StorageParser` (`mermaid`, `mermaid-cloud`, `mermaid-macro`) → Codeblock | SH+ |
| Mermaid als Grafik | `doc/ExportGraphics#mermaid`, `exedra/ui/markdown/AresStackMermaidImageRenderer` | `Diagrams`; in Word, PDF und Markdown als Grafik (#206) | gleich; die Leseansicht der App zeigt Mermaid als Grafik des Core (`Assets.rendition`, #192) |
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
| Aus dem gespeicherten Datenbestand konvertieren, ohne erneuten Abruf | nein (lädt jede Seite neu) | Cache-Generation (ADR 0010); `ExportConversions.convert` konvertiert einen abgeschlossenen Export in jedes Format ohne erneuten Abruf, REST `GET /api/v1/exports/{id}/conversions/{format}`; `ExportConversions.dataset` bzw. `GET /api/v1/exports/{id}/dataset` (`ExportDatasetInfo`) nennt Seiten, Vollständigkeit, Bildstand, Formate und je Stand die Mediendateien der letzten Beschaffung (`media()`: abgelegt, fehlend, wegen Anmeldung offen, bewusst weggelassen; #313, optionale Erweiterung) | SH+ |
| Herkunft je Konvertierung (Konverterversion, Eingabe-Hash) | — | `manifest.conversions` | SH+ |
| PDF: sichtbares Inhaltsverzeichnis über mehrere Seiten mit Einrückung, Seitenzahlen und Sprüngen (`tableOfContents()`), Seitenzahlen „12 / 85“, Kapitel je neuer Seite, Bilder, Mermaid | `doc/PdfExportWriter` | `core/output/convert/PdfConverter`: nur Lesezeichen (`PDDocumentOutline`) und interne Sprünge, Bilder als `[Bild: …]`; `PdfConverterTest` prüft kein Inhaltsverzeichnis | in Arbeit (Strang R, gemeinsames Kapitelmodell für Word und PDF) |
| Markdown: eine Datei, Bilder als PNG-Data-URI eingebettet, Mermaid als Code und Grafik, fehlende Bilder gemeldet | `doc/MarkdownExportWriter` | `MarkdownConverter`: Sprungmarken und interne Seitenlinks (besser als EAC, bleiben), Bilder nur als `attachment:`-Verweis, Mermaid nur als Code | in Arbeit (Strang R, „Export-Parität: Markdown, Word, PDF und Bilder“, gemeinsamer Dokumentbaum) |
| Verlinktes Inhaltsverzeichnis in Markdown | — | — | in keinem; keine Paritätslücke |
| Exportumfang einzelne Seite oder Teilbaum | `KnowledgeExportUseCase` | ganzer Space oder ab einer Startseite samt Unterseiten: `ExportRequest.withRoot(rootPageId)`, `Exports.start(sourceId, spaceKey, rootPageId, owner, formats)`, REST `POST …/spaces/{key}/exports` mit `{"rootPageId":"…"}`; Daten, Formate und Rechteprüfung wie beim Space, eine fehlende oder nicht lesbare Startseite lässt den Auftrag mit `ROOT_NOT_FOUND` scheitern, `JobStatus` nennt `rootPageId` und `rootTitle`; eingelesen wird bei Bedarf trotzdem der ganze Space (#315); die App lädt jede Explorer-Seite samt Unterseiten so herunter (#314). Einzelseite und Tiefe (#333): `ExportRequest.withPage(pageId)` (= `withRoot(pageId).withDepth(0)`), `withDepth(Integer)` (null = alle Ebenen, 0 = nur die Startseite, nur mit `rootPageId`), `Exports.start(sourceId, spaceKey, rootPageId, depth, owner, formats)`, REST mit `{"rootPageId","depth"}` (negative Tiefe oder Tiefe ohne `rootPageId` → 400 `invalid-request`); die Startseite ist die erste Kapitelwurzel wie im EAC, sofern ihr Inhalt exportiert werden konnte (sonst meldet der Auftrag sie als Fehler), lose Seiten unter einer gesperrten Seite stehen unter dem Wurzelkapitel | gleich (Vergleichstest Exportumfang); Tiefe nur im Core |
| Markdown mit Bilderordner | — (Markdown nur als eine Datei mit Data-URIs) | Format `markdown-package` (`MarkdownPackageConverter`): ZIP mit `pages.md` wie `MarkdownConverter` und jeder Abbildung einmal als `assets/<sha256>.png` (Bilder, gezeichnete draw.io- und Mermaid-Diagramme), im Text relativ verlinkt; Mermaid bleibt zusätzlich als Quelltext; `markdown` bleibt eine Datei wie im EAC (#319) | SH+ |
| Portables ZIP (JSON + Bilder) | — | Format `json-package` (`JsonPackageConverter`): ZIP mit `pages.json`, `assets.json` mit relativen Pfaden, `assets/<Seiten-ID>/` mit Bildern und Anhängen, draw.io-Original, gezeichnetem SVG und `<Name>.graph.json` (Knoten und Verbindungen für KI-Systeme); keine Base64-Einbettung, nicht abgelegte Dateien nur mit Status; `json` bleibt unverändert (#306, ADR 0018) | SH+ |

## 11. Semantische Suche

Allgemeine Infrastruktur gehört in den Core, Rechercheplanung bleibt im EAC (ADR 0012, Punkt 4).

| Fähigkeit | EAC | Core | Bewertung |
|---|---|---|---|
| Embeddings über OpenAI-kompatiblen Dienst, Modellkatalog | `embedding-openai/…/OpenAiCompatibleEmbeddingAdapter`, `model-kipitz/…/KipitzModelCatalogAdapter` | dieselben Klassen (Kopie) über `core/source/ai/RemoteAi` | gleich |
| Lokale Embeddings im Prozess (ONNX) | `text-local/…/LocalEmbeddingAdapter`, `text-onnx` | entfernt (ADR 0011) | bewusst nicht; der EAC hängt seinen lokalen Adapter als eigenen `EmbeddingProvider` ein → #188 |
| Vektorablage je Modell | `chalco/pinakes/…/FileVectorIndex` (exakt) | `LuceneEnrichmentStore` (HNSW) | gleich |
| Zerlegung in Abschnitte mit Satzgrenzen und Token-Budget | `astu/…/knowledge/KnowledgeChunker`, `KnowledgeChunkingPolicy`, `SentenceSplitter` | `KnowledgeTextSegmentation` mit denselben Klassen (`SentenceSplitter`, `KnowledgeChunkingPolicy.defaults()`: 350 Tokens, ein Satz Überlappung) im `EmbeddingProcessor` (#252); Token-Budget und Überlappung einstellbar wie `knowledge.chunk.maxTokens`, `knowledge.chunk.overlapSentences`: `space-harvester.ai.embedding-chunk-tokens` (350), `embedding-chunk-overlap-sentences` (1), im Core `Harvester.builder().embeddingChunking(tokens, overlap)` (#256) | gleich (#181) |
| Semantische Passagen (Themenwechsel) | `astu/acropolis/…/passage/PassageSegmentation`, `TopicClusterer` | keine | fehlt → #181 |
| Semantische Suche über frisch gefundene Seiten | `SearchDrivenIndexingUseCase#searchAndIndex` (Agent-Werkzeug `knowledge_search`): live suchen, Treffer vorladen, zerlegen, einbetten, darauf warten | mit Haken (`sort=relevance`, „Semantische Suche (experimentell)“) bereitet `RelevanceSearch` selbst vor: Quellen finden wie in der Schlagwortsuche, gefundene Seiten mit den Rechten des Aufrufers laden, fehlende oder veraltete Embeddings im Aufruf berechnen (höchstens 10 s, sonst `complete=false`), dann einmal über die Vektoren suchen (#258, `SemanticPreparation`, ADR 0013) | gleich, mit Zeitgrenze |
| Progressive Suche: Suchanfragen aus der Frage, in Wellen, Ende nach Qualität | — im EAC; Vorbild askai-java8 Branch `arch`: `SearchSuggestion` (Suchanfrage mit Zweck und Priorität 1 = höchste), `ScopingAssistantOutputParser` (geprüfte JSON-Antwort des Modells, eine Reparatur, sonst Fehler), `TeamAgentPlaybook` (Prompt), `ScopingSupportView` (Vorschläge als Tags) | `Harvester.progressiveSearch()` → `SearchOrchestrator.searchAsync(caller, query, SearchProfile[, listener])` → `ProgressiveSearch` (`id`, `result()`, `cancel()`); die ursprüngliche Anfrage sucht sofort, `QueryExpansionStrategy` erzeugt Varianten mit abnehmend vielen Begriffen (höchstens `maxVariants` 8, nie alle Kombinationen): mit Chat-Modell (`space-harvester.ai.chat-model`, `AiServiceSettings.chat()`) fragt `AiQueryExpansion` es über den aus dem EAC übernommenen `OpenAiCompatibleChatAdapter` (Module chat-api und chat-openai über `sync-from-upstream.sh`) nach JSON `queries[text, priority]`, prüft streng (nur Suchbegriffe, keine CQL oder Operatoren, Dubletten und die ursprüngliche Anfrage fallen weg, `max_tokens` 1024) und fällt nach 6 s, bei Fehler oder ungültiger Antwort auf `RuleBasedExpansion` zurück; `SearchProfile.expansion()` `AI` | `RULES`, `Harvester.searchProfile()` wählt `AI`, wenn ein Chat-Modell eingerichtet ist (#302); Wellen und Parallelität 3, Frist 10 s und ein weiterer Versuch je Anfrage (`SearchExecutionPolicy`); Freigabe in der Reihenfolge der Prioritäten, Bewertung immer gegen die ursprüngliche Anfrage (`HitScorer`, Cross-Encoder nur für freigegebene Quellen), Zusammenführung nach Quelle und Seiten-ID; Ende nach `SearchQualityPolicy` (Modus `QUALITY`: mindestens 5 Treffer über 0,5; erreicht schon die ursprüngliche Anfrage das Ziel, endet die Suche sofort mit `QUALITY_REACHED`) oder `ALL_VARIANTS`, begrenzt durch 30 s und 12 Anfragen; `ProgressiveResult` mit Treffern, Varianten, Abschlussgrund (`QUALITY_REACHED`, `ALL_VARIANTS_DONE`, `BUDGET_EXHAUSTED`, `CANCELLED`, `FAILED`), Ladeaufträgen und Dauer; `ProgressiveSearchListener` meldet unterwegs (#299, #302, ADR 0017); in der App ist der Haken „Semantische Suche“ diese Suche: Originalanfrage zuerst, Varianten als Schlagwörter unter der Suchleiste, Einsortieren nach Bewertung, Abschlussgrund in der Statuszeile (`app/…/ui/SearchView`, #310). REST: `POST /api/v1/search/progressive` (Suchtext, Quellen, Spaces, Modus, Budgets, `expansion`, `wait`; 202 mit `Location` und Zwischenstand, mit `wait` 200 mit Ergebnis), `GET …/{id}`, `DELETE …/{id}` (204); Treffer mit Bewertung, `foundBy` und `confirmed`, Varianten mit Status und neuen Treffern; fremde Suchen 404, delegierte Benutzer warten immer, bei 256 laufenden Suchen 429 (#318). Offen: Anbindung an den semantischen Index im Core | Erweiterung, in Arbeit (ohne Paritätspflicht) |
| Hybride Suche (Volltext + Vektor, Reciprocal Rank Fusion) | `rag/RetrieveKnowledgeUseCase`, `rag/ReciprocalRankFusion` | keine Fusion: die Schlagwortsuche findet über die Suche der Quellen, lädt die Funde in den Cache und liefert Treffer nur aus dessen Volltextindex (`KeywordSearch.searchesTheCache()`, REST `origin=cache`, #255; ohne konfigurierten Cache die geprüften Treffer der Quellen, `origin=source`), semantische Suche nur mit Haken bzw. `sort=relevance` und nur über die Vektoren (`reranker` = `embedding-cosine@…`) | gewollte Abweichung ([ADR 0013](adr/0013-suche-ueber-den-cache.md)) |
| Reranking über Modell (`/rerank`) | `model-kipitz/…/KipitzRerankAdapter`, `rag/CrossEncoderRanking` | `ModelReranker` über `POST /rerank` des KI-Dienstes, eingeschaltet mit `space-harvester.ai.rerank-model` (`AiServiceSettings.rerankModel`, `RemoteAi.reranker()`), sonst `CosineReranker` (#248), nur in der semantischen Suche (ADR 0013); gewollte Abweichung: kein `topK`, der Core ordnet alle Kandidaten nur um und schneidet nichts ab | gleich (#181) |
| Verwandte Abschnitte | `chalco/KnowledgeRelationsPort`, `know/IndexKnowledgeRelations` | `RelevanceSearch.related` mit `RelatedSections` (#260), REST `GET /api/v1/sources/{sourceId}/pages/{pageId}/related`: dieselben Kantenarten (`SIMILAR_TO` ab 0,80 ohne das Stück und seine Nachbarstücke, `PARENT`, `NEXT`/`PREVIOUS`, `SAME_SECTION` nächstgelegen zuerst), je Art höchstens drei, erste Kante je Ziel gewinnt, `LINKS_TO` fehlt in beiden; zusätzlich Rechte je Zielseite. Das Anreichern der RAG-Treffer mit Nachbarn (`RetrieveKnowledgeUseCase`, `relationSeeds`/`relationWeight`) bleibt Rechercheorchestrierung im EAC | gleich (#181, am Code verglichen) |
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
| Lokale Dateien | `source-localfiles` | `core/source/LocalFilesSourceAdapter` (Kopie) | gleich, mit Text aus PDF und Office-Dateien (#328) |
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
| Secrets: KeePassRPC | `proasteion/platform/security-keepassrpc/…/KeePassRpcSecretProvider` | Core: keine eigene, einhängbar über `Harvester.builder().secretProvider(SourceSecrets)` (#226). App: Modul `:enterprise-ai-keepass`, unverändert aus dem EAC übernommen und in der CI gegen den Upstream-Stand geprüft (`sync-from-upstream.sh verify`); `KeePassAiCredentials` holt den API-Key des KI-Dienstes aus dem Passwortfeld eines KeePass-Eintrags wie im EAC (#291) | gleich (Core über den Port, App für den KI-Dienst) |
| Secrets: Azure Key Vault | — | Server-Profil `azure-keyvault` | SH+ |
| Verbindungsdiagnose mit Ursache (Zertifikat, Proxy, DNS) und Stufentest | `app/net/ConnectionDiagnosis`, `exedra/…/settings/ConnectionProbe` | `SourceManagement.diagnose` → `SourceDiagnosis` mit Stufen Proxy-Route, DNS, TCP, TLS, Anmeldung, API, REST `GET /api/v1/sources/{id}/diagnosis` (#238); Ursache je Auftrag aus `JobEvent` (docs/DIAGNOSTICS.md) | gleich |
| HTTP-Kopfzeilen | `NetworkConfig`: nur der User-Agent, keine frei konfigurierbaren Kopfzeilen | `userAgent`, global gesetzt | gleich |

## 14. Desktop-App

| Fähigkeit | EAC | App | Bewertung |
|---|---|---|---|
| Leseansicht mit Bildern, Diagrammen, Bildergalerie | `exedra/…/reader/ReaderHtmlView`, `ImageSlideshowPanel` | `app/…/ui/content/ReadingPane` (Reiter „Leseansicht“ im Bereich Inhalte, der seit #300 eine reine Leseansicht ist: Seite über den Explorer oder „Seiten-ID oder Adresse“): HTML aus `ReadingViews`, Bilder über `Assets.viewImage`, Mermaid über `Diagrams`, draw.io als PNG des Core aus den Quelldaten (`Assets.viewDiagram`, Rendition `drawio-png-1`, #322, #314; das Vorschaubild nur als Rückfall), fehlende Bilder markiert, Klick öffnet Galerie mit ◀/▶ | gleich (#192) |
| Ort der Seite und Querverweise beim Lesen | `exedra/…/knowledge/PageNavigationController`, `PageNavigationPanel` (Kompass: Platz im Baum der Quelle und Querverweise der aktiven Seite, Klick öffnet im Reader) | `app/…/ui/content/PageDetailPanel` (#329): Pfad unter dem Seitentitel (Space-Name, dann die Vorfahren, klickbar, aus `PageNavigation#position`, bricht lang um) und Reiter „Verweise“ (aus `PageNavigation#references` über `Workspace.references`, einmal je Seite beim Öffnen des Reiters geladen, Klick öffnet die Seite); den Baum zeigt dauerhaft der Explorer links | gleich (#177) |
| Aufträge mit Export in Formate, Ausgabeordner | — | `app/…/ui/ActivitiesView`: Liste über die volle Breite mit Symbol-Aktionen je Zeile (`RowActions`: PDF, Word, Markdown, JSON (Format `json-package` mit Medien, #310), Diagnose, Inhalte, Löschen; „Markdown mit Medien“ = `markdown-package`; ein Formatklick exportiert sofort aus dem Datenbestand, nicht verfügbare Aktionen deaktiviert mit Grund), darunter Auftrag, Ergebnis und Exportieren (Ausgabeordner, Exportstand, erzeugte Dateien, Medien gesichert, fehlend, Anmeldung erforderlich, bewusst ausgelassen, #314); Fortschrittsbalken mit „Abbrechen“ (#300) | SH+ |
| Explorer Wissensquelle → Space → Seite, Herunterladen von dort | — | `app/…/ui/Explorer` dauerhaft links: Wissensquelle → Space → Seite → Unterseite, bedarfsgesteuert über `navigation().children`, mit Titelfilter über `findByTitle` (#314); der gewählte Knoten ist der Bereich der Suche („Überall suchen“ hebt ihn auf); Download-Symbol und Kontextmenü „Herunterladen“ an Quellen, Spaces und Seiten starten den Export-Auftrag sofort (`Downloads`, eine Seite samt Unterseiten mit `rootPageId`, #314), der eigene Bereich Herunterladen entfällt (#300); Inhalte mit Umschalter „Baum \| Liste“ (#314) | SH+ |
| Einrichtungsassistent beim ersten Start, später über die Einstellungen | `exedra/…/ui/setup/SetupWizardPanel`, `ModelPreselection`, `PreferredModels` | `app/…/ui/SetupWizard` mit Schrittleiste Wissensquellen, Netzwerk, KI-Dienst, Modelle, Abschluss (KI-Dienst vor Modelle wie im EAC, weil die Modellliste vom Dienst kommt); Modelle werden wie im EAC vorgewählt (`PreferredModels` aus `setup-models.properties`), gelten global für alle Quellen; beim ersten Start und unter Einstellungen → Einrichtungsassistent | gleich (#274, #285) |
| Wissensquelle anlegen und bearbeiten | Quellendialog des EAC | `app/…/ui/SourceForm` auf einer Seite, gleich in der Quellenverwaltung und im Assistenten (ersetzt den Quellenassistenten): Art und Adresse zuerst, Anzeigename und Kennung vorgeschlagen, Anmeldung, Client-Zertifikat, Serverzertifikate der Quelle und Freigabe für den KI-Dienst je Quelle (`AiServiceSettings.externalProcessingSources`); Proxy gilt für alle Quellen unter Einstellungen → Netzwerk | gleich (#285) |
| Einstellungsmenüs (Dienst, Modelle, Sicherheit, System, Index) | `exedra/…/ui/settings/ServiceTab`, `ModelsTab`, `SecurityTab`, `SystemTab`, `IndexEditor` | `app/…/ui/SettingsView` mit den Reitern Allgemein, KI-Dienst, Modelle (Rerank-Modell; Abschnittsgröße und Überlappung unter „Erweitert“), Netzwerk (Proxy, Serverzertifikate), Cache (Aktualisierungsstrategie, Frist für gesperrte Seiten, „Hintergrundbefüllung im Leerlauf“ mit „Gleichzeitig im Hintergrund“ 1 bis 16, #326) und Sicherheit (Tresor); die Werte des `IndexEditor` für den Chat (Kontextgröße, Nachrecherchen) bleiben im Client | teilweise (#274, #285, #289, #293): Änderungen wirken ohne Neustart außer bei Quellen, Proxy, Zertifikaten, Ablage, neuen Zugangsdaten sowie Abschnittsgröße und Überlappung; lehnt der Core eine Änderung ab, bleibt die bisherige Einstellung aktiv und der Grund steht unter Einstellungen › Konfiguration; Index-Editor-Werte für den Chat bleiben im Client |
| Einstellungen zur Laufzeit ändern | — (Einstellungen gelten nach dem Speichern bzw. ab der nächsten Frage) | `ConfigurationManager` im Core: prüfen, vorbereiten, atomar aktivieren, Ablehnung rollt zurück; laufende Aufträge behalten ihre Instanzen; Aktualisierungsstrategie `RefreshStrategy` (ALWAYS_CHECK_VERSION Standard, TIME_BASED, TIME_BASED_WITH_VERSION_CHECK), Betriebsart `SyncMode` (CATALOG_ONLY, SYNC_CACHE, FULL); Server `GET`/`POST /api/v1/configuration` (ADR 0015). Mit `Harvester.builder().configuration(manager)` wirken live je Quelle: Aktualisierungsstrategie und Höchstalter, Frist für gesperrte Seiten, Zeitpläne aus `sync.spaces`, Nachholen beim Start, Reranker und Embeddings an/aus/Modellwechsel, Freigabe an den KI-Dienst (#284). Noch nicht live: Inventurintervall, Nachholen vor der Suche, Vorladen der Treffer; die App übergibt den Manager an den Builder und übernimmt KI-Dienst, Modelle, KI-Freigabe je Quelle, Aktualisierung und Synchronisierungsregeln ohne Neustart (Neustart der Laufzeit bei Quellen, Proxy, Zertifikaten, Ablage, neuen Zugangsdaten sowie Abschnittsgröße und Überlappung; #289); der Server übernimmt aktivierte Versionen über `LiveConfiguration` ebenso ohne Neustart (#290), lehnt eine unbekannte Quelle oder einen Zeitplanabstand unter einer Minute ab und braucht einen Neustart nur für einen neuen Cache-Speicherort (`cache.directory`, `restartRequired`) und wenn Embeddings erstmals eingeschaltet werden; dort zusätzlich noch nicht live: semantische Suche (`search.semantic`) und Chat-Modell (docs/CONFIGURATION.md). Eine Suche hält Embedding-Modell und Version für den ganzen Vorgang fest | SH+ (#270, #284, #289, #290); offen: Inventurintervall, Webhooks, Nachholen vor der Suche, Vorladen der Treffer |

## Lücken und Issues

| Issue | Thema | Strang |
|---|---|---|
| #176 | Leseansicht (`body.view`/`export_view`) als Core-Use-Case | Q |
| #177 | Live-Navigation: Kindseiten, Breadcrumbs, Querverweise | Q; App: Pfad und Reiter „Verweise“ (#329, L) |
| #178 | URL- und Ressourcenauflösung vollständig | Q |
| #179 | Space-Filter der Schlagwortsuche in der CQL-Anfrage | Q, erledigt |
| #180 | Volltextindex über den Cache, Index-Rückfall, Treffer vorladen | Q, erledigt |
| #181 | Zerlegung, Rerank-Modell, verwandte Abschnitte (keine hybride Suche, ADR 0013) | Q |
| #182 | Suchlauf-Protokoll und Metadaten-Fakten als Core-Ports | Q |
| #183 | Textextraktion (Anhänge und PDF/Word lokaler Dateien im Volltextindex), Typerkennung am Inhalt, Vorschauen von Anhängen | Q; teilweise (#328): Extraktion, Typerkennung, lokale Dateien; offen Anhangstexte im Index, REST, Vorschauen |
| #184 | `ImageDescriber` über den externen KI-Dienst | offen, nicht beauftragt; Diagramme textuell erledigt (#306) |
| #185 | Einmalcode-Anmeldung (2FA) als Port | erledigt: Core (#303), Server-REST (#308), App (#312) |
| #186 | Export einzelner Seiten und Teilbäume | erledigt: Teilbaum (#315), Einzelseite und Tiefe (#333), Vergleichstest (#333) |
| #187 | Quellen ohne Partitionen und weitere Quelltypen des EAC | Q |
| #188 | `Harvester.builder()` erweiterbar; Quellenverwaltung zur Laufzeit | Q |
| #189 | Netzwerk: Proxy-Anmeldung, PAC-Skript, Vertrauen, Kopfzeilen (#234), Verbindungsdiagnose (#238) | Q, erledigt |
| #190 | draw.io- und Mermaid-Rendering im Core | erledigt: Export (#206, #210, #220), Leseansicht (#322, #314) |
| #192 | Leseansicht der App mit Bildern und Diagrammen | L |
| #193 | Vergleichstests EAC und Core mit denselben Testdaten | S |
| #218 | Ausgeführte Makros aus der Leseansicht in den Konvertern (KI-Text bleibt auf `storage`) | Q, dann R |

Rückmeldungen an den Upstream (Enterprise AI Client):

- `SourceQuery` (source-api) kennt keine Titeloption. Der Core sucht Titel deshalb über die Volltextsuche des Confluence-Adapters und prüft den Titel selbst; mit einer Titeloption (CQL `title ~`) im Upstream würde die Titelsuche auch Seiten finden, die die Volltextsuche nicht unter den ersten 1000 Funden liefert, und käme per Sync in den Core (#305).
- `OpenAiCompatibleChatAdapter` (chat-openai) liest die Antwort ohne Größengrenze. Der Core begrenzt die Antwort für Suchvarianten über `max_tokens` 1024 und wertet höchstens 64 Ki Zeichen aus; eine Lesegrenze im Stream gehört in den Upstream-Adapter (#302).
- Funde der Suche (source-api) tragen keinen Space-Schlüssel. Deshalb kann der Core nicht jeden Fund als Kopfdaten katalogisieren (ADR 0016, offen).

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

Für den Dokumenttext (#183) liegen unter `src/test/resources/documents/` acht kleine Dateien (DOCX, XLSX, PPTX,
ODT, RTF, HTML, TXT, Markdown); dazu kommen die Word- und PDF-Referenz des Enterprise AI Client.

### Ablauf

1. **Referenzen des Enterprise AI Client:** `parity-tests/eac/generate.sh [pfad-zum-checkout]` baut den
   Enterprise AI Client am Commit aus `src/test/resources/eac/COMMIT`, exportiert den Paritäts-Space über
   `KnowledgeExportUseCase.subtree` als Word, PDF und Markdown und legt `parity.{docx,pdf,md}` samt
   `problems-<format>.txt` (Meldungen von `ExportGraphics`) unter `src/test/resources/eac/` ab, dazu eine Einzelseite
   und einen Teilbaum ab einer Seite als Markdown (`scope-page.md`, `scope-subtree.md`, #186), je Seite den
   Lesetext aus `load` und die Bildzahl aus `pageView` unter `reading/` sowie Kinder, Breadcrumbs und Querverweise
   (`children`, `position`, `crossReferences`) in `navigation.properties`, außerdem die Schritte der
   Quellenverwaltung (`KnowledgeSourceManagement` mit dem echten Confluence-Provider über dem Fake) in
   `management.properties`, die Seite je Adressform (`resolveAddress`, Bookmarks) in `addresses.properties` sowie die
   Suche (`SearchKnowledgeSourcesUseCase` über eine indexierte Dateiquelle, Vorladen der Confluence-Treffer mit
   `KnowledgePrefetchService`) in `search.properties` und je Testdokument den Typ (nach Name und Inhalt sowie nur nach
   dem Inhalt, `DocumentExtraction.detector()`) und den Text (`DocumentSourceFileTextExtractor`) unter `documents/`
   (`EacDocumentText`).
   Die CI von Space Harvester baut den Enterprise AI Client nicht; die Referenzen sind eingecheckt und werden bei einem neuen
   Commit des Enterprise AI Client neu erzeugt.
2. **Core:** `CoreParityTest` startet den Fake als HTTP-Server, lädt denselben Space herunter, konvertiert ihn
   aus dem Datenbestand als Word, PDF und Markdown, holt je Seite die Leseansicht (`Harvester.readingViews()`) und
   die Navigation (`Harvester.navigation()`), spielt die Schritte der Quellenverwaltung mit
   `Harvester.sourceManagement()` nach, sucht mit `Harvester.keywordSearch()`, liest die Testdokumente mit `TikaDocumentText` und vergleicht mit den Referenzen. Bericht:
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
| Dokumenttext | je Datei (#183) | Typ nach dem Inhalt gleich; Text wie bei Text |
| Exportumfang | Einzelseite und Teilbaum, Markdown (#186) | Prüfungen wie beim ganzen Space; dieselben Seitenkapitel in derselben Reihenfolge und Ebene; keine Seite außerhalb des Umfangs |
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
dieselbe CQL bauen. Anhänge von Confluence-Seiten fehlen noch, dafür fehlen Core-Schnittstellen: Anhangstexte im Index und Vorschauen #183 (den Dokumenttext je Datei vergleicht der Abschnitt Dokumenttext seit #328). Bekannte, gewollte Abweichungen (Core darf
hier mehr): interne Seitenlinks, Raw-JSON, Herkunft im Manifest, textuelle Diagrammquellen, PlantUML als Code.
