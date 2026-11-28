# Grenzen und Limits

Dieses Dokument führt jede feste Grenze im eigenen Code von Space Harvester: Größen, Anzahlen, Tiefen, Zeiten und
Parallelität. Grundlage sind Angelos Vorgabe vom 26.11.2026 (keine Magic Numbers, ADR 0036 Punkt 12) und seine
Entscheidungen F36-3 und F36-4 vom selben Tag.

Der Architekturtest `LimitInventoryTest` (`parity-tests`) hält das [Verzeichnis](#verzeichnis-aller-grenzkonstanten)
vollständig. Jede Konstante, die nach einer Grenze heißt (`MAX_…`, `…_TIMEOUT`, `…_BATCH`, `…_CHARS`, `…_BYTES`,
`CAPACITY`, `…_DEPTH` usw.), muss dort mit ihrem Wert stehen. Kommt eine neue hinzu oder ändert sich eine Zahl, schlägt
der Test fehl, bis die Zeile stimmt. Er verbietet keine Zahl. Was mit einer Grenze geschieht, sagt die Spalte
„Behandlung“.

## Grundsatz

**Künstliche Grenzen des Bestands werden beseitigt, nicht hochgesetzt.** Wo eine Grenze bleiben muss, ist sie eine
dokumentierte Einstellung. Was beim Erreichen einer Grenze geschieht, steht hier. Am schwersten wiegen Grenzen, die
unbemerkt zu unvollständigen Suchergebnissen, abgeschnittenen Inhalten, ungültigen Cursorn oder falschen
Löschentscheidungen führen.

| Art der Grenze | Behandlung |
|---|---|
| Künstliche Begrenzung des Dokument- oder Ressourcenbestands (`Bestandsgrenze`) | möglichst beseitigen |
| Stapel- und Seitengröße | einstellbar machen |
| Speicher- und Ressourcenbudget | einstellbar machen |
| Parallelität | einstellbar machen |
| Timeout | einstellbar machen |
| Aufbewahrungsdauer eines Caches | einstellbar machen |
| Modellkontext (Textbudgets für KI-Aufrufe) | aus den Modellfähigkeiten ableiten (Tokenlänge je Modell) |
| Externe Protokollgrenze | dokumentieren und berücksichtigen |
| Technische Invariante, mathematische Konstante, Darstellung | im Code belassen |

## Wie eine Grenze einstellbar wird (F36-3)

Es gibt keinen eigenen Mechanismus nur für Grenzen. Grenzen nutzen die vorhandene Konfiguration:

1. **Schlüssel unter `space-harvester.*`** in der gemeinsamen YAML, im Bereich, zu dem die Grenze gehört, zum Beispiel
   `space-harvester.jobs.…` oder `space-harvester.assets.extraction.…`. Der heutige Wert ist der dokumentierte
   Standard ([CONFIGURATION.md](CONFIGURATION.md)).
2. **Beide Loader und der Paritätstest.** Der Dienst bindet den Schlüssel über `@ConfigurationProperties`, die App
   über `AppSettingsLoader`, und `ConfigurationParityTest` prüft, dass beide denselben Wert lesen.
3. **JVM-System-Properties** gelten beim Dienst über Spring Boot (`-Dspace-harvester.…`). In der App kann ein Wert in
   der YAML eine Property oder Umgebungsvariable als Platzhalter `${NAME}` nennen.
4. **Typisierte Einstellung im Kern.** Der Kern bekommt ein Einstellungsobjekt (Record wie `JobLimits`,
   `ExtractionLimits`, `HarvesterConfiguration`) über Konstruktor oder Builder. Er ruft nie selbst
   `System.getProperty()` auf.
5. **Oberfläche.** Grenzen, die für Benutzer wichtig sind, kommen in die Einstellungen der App. Die Spalte „Stand“
   unten zeigt, ob eine Grenze schon einstellbar ist.
6. **Command Deck.** Die Einstellungen des Decks (Terminal, NDV, MVS-Binärdiagnose) liegen wie bisher in
   `app-state.json` unter `deck.*` und stehen in den Einstellungen der App. Zahlen mit Grenzen beschreibt eine
   Aufzählung nach `NumberSetting` (Schlüssel, Bezeichnung, Hinweis, Vorgabe, Grenzen); `NumberSettingsPanel` zeigt
   sie einheitlich, wie den Bereich „Video“.
7. **ADR.** Die ADR des Bereichs führt die Grenze in ihrem Abschnitt „Grenzen und Limits“ (ADR 0036 Punkt 12). Dieses
   Dokument ist die Gesamtliste, und beide müssen übereinstimmen.

## Die folgenreichsten Grenzen

Erhebung vom 26.11.2026, Stand develop `ef691aee`, und Entscheidungen F38-4, F39-10, F40-5, F40-6, F41-7 und F42-1.
„Folge“ sagt, was heute beim Erreichen der Grenze passiert.

| Grenze | Wert | Folge beim Erreichen | Plan |
|---|---|---|---|
| Volltext je Seite und Anhang (`LucenePageTextIndex.MAX_TEXT_CHARS`) | 1.000.000 Zeichen | Text danach wurde ohne Hinweis nicht indexiert | **beseitigt**: der Index nimmt den ganzen Text |
| Lesetext und Tika für lokale Dateien, BSCW, Anhänge und Fristen (`TikaDocumentText`) | 20 MiB, 1.000.000 Zeichen, 30 s, 2 parallel | `assets.extraction.*` galt dort nicht; lange Texte wurden nach 1 Mio. Zeichen ohne Hinweis abgeschnitten (F39-10) | Zeichengrenze **beseitigt** (Tika, Lesetext `DocumentExtractions`, `LargeFileTexts`). Größe, Zeit und Parallelität **einstellbar** über `assets.extraction.memory-limits."*"` (bzw. `max-file-size`), `idle-timeout`, `parallel`, im Dienst und im Harvester-Kern. Offen: Dokumentvorschau und Chat-Browser der App nutzen noch die Vorgaben; Lesetext abschnittsweise |
| Embedding-Dimension (`LuceneEnrichmentStore.MAX_VECTOR_DIMENSIONS`) | 1024 | Modelle mit 1536 oder 3072 Dimensionen bekamen keine Vektoren | **beseitigt**: `VectorCodec`, Dimension gibt das Modell vor |
| Abschnitte je Dokument (`EmbeddingProcessor.MAX_CHUNKS`) | 200 | nur die ersten etwa 70.000 Token waren semantisch suchbar | **beseitigt**: alle Abschnitte; es gilt nur das einstellbare `enrichment.documents.embedding-max-chars` |
| Wartende Funde (`FindingCataloging.CAPACITY`, `REMEMBERED`) | 1000 / 10.000 | weitere Funde fallen weg | beseitigen: zurückstellen statt verwerfen |
| Webhook-Warteschlange (`ChangeEvents.MAX_QUEUED`, `MAX_BODY_BYTES`) | 10.000 Seiten / 1 MiB | Meldungen fallen weg, erst die Inventur holt sie nach | Warteschlange beseitigen (eine Zeile je Seite), Rumpfgröße einstellbar |
| Hintergrundaufträge, Vorabladen (`JobLimits.DEFAULT_MAX_OPEN_BACKGROUND`, `DEFAULT_MAX_WAITING_PREFETCHES`) | 1000 / 5000 | weitere Aufträge werden abgewiesen; beim Vorabladen lädt der Aufrufer selbst | **einstellbar**: `space-harvester.jobs.max-open-background`, `.max-waiting-prefetches` (nur App) |
| Absätze und Sätze (`PassageProcessor.MAX_PASSAGES`, `PassageSegmentation.MAX_EMBEDDED_SENTENCES`) | 5000 / 2000 | bei langen Dokumenten fehlen die übrigen Absätze | beseitigen |
| Begriffe, Entitäten, Relationen, Akronyme je Dokument (`TermProcessor`, `EntityProcessor`, `RelationProcessor`, `PropertyProcessor`, `ActivityProcessor`, `AcronymProcessor`) | 500 bis 50.000 | die übrigen fehlen ohne Hinweis | beseitigen oder sichtbar markieren |
| Fakten je Ergebnis und Seite (`EnrichmentFacts.MAX_PER_RESULT`, `LuceneEnrichmentStore.MAX_PER_PAGE`) | 2000 / 1000 | Fakten darüber werden nicht geliefert | beseitigen: paginieren |
| Links und Tabellenzellen (`TikaDocumentStructure.MAX_LINKS`, `MAX_TABLE_CELLS`) | 1000 / 10.000 | die übrigen fehlen in der Struktur | beseitigen |
| Confluence-Listen (`RestJson.MAX_LIST_PAGES` × `LIST_LIMIT`) | 200 × 100 | Listen mit mehr als 20.000 Einträgen scheitern | beseitigen: weiterblättern, bis die Quelle endet |
| Baum und Listen in der REST-API (`PageQueries.MAX_TREE_LEVELS`, früher `MAX_PAGE_LIMIT`, `ContentListing.MAX_START`, `TermQueries.MAX_SCAN`) | 200 Ebenen je Antwort / 100 / 10.000 / 50.000 | spätere Einträge der Listen sind nicht erreichbar | beseitigen: Seitengröße einstellbar. **Listen erledigt:** `ContentListing` hat keinen höchsten Offset mehr; die Stapelgrößen von Seitenliste und Begriffsliste sind `space-harvester.query.max-page-limit` (100) und `max-term-scan` (50.000), beide Listen setzen sich über ihren Cursor fort. `space-harvester.query.max-tree-nodes` (50.000) bleibt einstellbar; größere Bäume liefert `…/tree` ebenenweise. **Baum erledigt (F40-6):** `GET …/tree` liefert ebenenweise (`root`, `levels`); ein Knoten mit `more` hat tiefere Kinder, die `root=<id>` nachliefert; die Gesamttiefe ist nicht begrenzt. 200 Ebenen je Antwort sind eine Invariante des JSON-Formats (Jackson schachtelt höchstens 500) |
| Cache-Vorschau und Explorer (früher `Payloads.MAX_DISPLAY_CHARS`) | entfallen (früher 2.000.000 Zeichen) | – | beseitigen: paginieren (F40-5). **Erledigt:** Die Reiter „Payload“ und „Storage“ zeigen den Inhalt vollständig; `ProgressiveText` füllt sie schrittweise (`ProgressiveText.STEP_CHARS` je EDT-Durchlauf, Invariante), sodass die Oberfläche bedienbar bleibt. Die Vorschau aus dem Cache (`PageNavigation.cachedChildren`, früher höchstens 1000 Kinder) ist paginiert, „Weitere laden“ blättert im Cache, auch ohne Quelle; der Seitenbaum ohne Live-Baum (`Explorer.wholeSpace`, früher 5000 Knoten) und die Kinder für Bereichspodcasts (früher 200) lesen alle Einträge. Die Stapelgröße ist `Explorer.BATCH` |
| Diagrammtexte (`DiagramLabels`, Zahl ohne Konstante) | 4000 Zeichen Code | der Code wurde vor dem Modellaufruf abgeschnitten (F41-7) | **beseitigt**: zu langer Code wird nach dem Tokenbudget des Modells (`ModelBudget`) an Zeilengrenzen in Teile zerlegt und teilweise beschriftet |
| Progressive Suchen (`ProgressiveSearches.DEFAULT_MAX_RUNNING`, `DEFAULT_MAX_WAITING`, `DEFAULT_RETENTION`, `DEFAULT_MAX_TIME_BUDGET`) | 256 laufend / 256 wartend / 5 min / 2 min | laufen schon `max-running` Suchen, wartet eine neue (`waiting`) und startet, sobald ein Platz frei wird; erst wenn auch `max-waiting` voll ist, wird sie mit 429 abgewiesen; eine neue verdrängt nie eine andere; ein größeres Zeitbudget wird gekürzt | **einstellbar**: `space-harvester.search.progressive.max-running`, `.max-waiting`, `.retention`, `.max-time-budget` (F38-4, ADR 0038 Punkt 10) |
| Speicher und Pakete (Desktop-`FileSystemAssetStore.DEFAULT_MAX_TOTAL_BYTES`, `ZipExportPackageReader`, `ImportController.MAX_PACKAGE_BYTES`, `LocalFilesSourceAdapter.MAX_FILE_MEGABYTES`, `BscwSourceAdapter`) | 20 GiB, 256 MiB, 4 GiB, 64 MB, Tiefe 50 | größere Dateien und tiefere Ordner werden nicht geerntet oder importiert | Speicherbudget einstellbar, Bestandsgrenzen beseitigen |
| KI-Textbudgets (Podcast, Zusammenfassungen, Fristen 12.000 Zeichen; früher auch Chat 24.000 Zeichen) | fest | Material wird vor dem Modellaufruf gekürzt | aus der Tokenlänge des Modells ableiten. **Chat erledigt:** Kontextblock der Suche (früher `ChatKnowledge.MAX_CONTEXT_CHARS` 24.000), ganze Seiten und Anhänge (früher `MAX_PAGE_CHARS` 12.000), Trefferauszüge (früher 3.000 je Treffer) und die Seite eines Unteragenten (früher `ChatTurn.SUB_AGENT_CHARS`) folgen dem Platz in der Anfrage (`ChatRoom`); ein gekürzter Anhang ist als gekürzt markiert. Die Zahl der Suchauszüge je Frage (früher fest `ChatKnowledge.MAX_SOURCES` 8) ist **einstellbar** im Menü am Dreieck der Chat-Leiste („Suchauszüge je Frage“, `chat.web.excerpts`, vorbelegt 8); „Suchqualität messen“ nimmt denselben Wert |
| Modellname in der MVS-Diagnose (`MvsDiagnosisTools`) | `gpt-oss-120b` | die Diagnose lief nur mit diesem Modell | **einstellbar** (Angelo 26.11.2026): Zeile „Diagnose“ unter Einstellungen › KI-Dienst › Modelle mit Schalter „Diagnose per KI“ (`ai.diagnosis-enabled`, Standard an) und Modell (`ai.diagnosis-model`, vorbelegt `gpt-oss-120b`, Anbieter-Präfix wie `openai/` egal) |
| Lesemengen der Chat-Werkzeuge (früher `ChatTools.READ_CHARS`, `ChatTools.PAGE_SIZE`, `ChatTurn.OPEN_PAGE_CHARS`, `DeckChatTools.TEXT_CHARS`, `VIEW_CHARS`, `VIEW_BLOCK_CHARS`, `SYSTEM_VIEW_CHARS`, `SYSTEM_BLOCK_CHARS`) | 12.000 Zeichen je Lesen / 100 Einträge je Blatt / 24.000 Zeichen offene Seite / 12.000 / 30.000 / 4.000 / 8.000 / 2.000 Zeichen | lange Seiten und Texte kamen nur in festen Stücken, auch wenn das Modell viel mehr Platz hatte | **aus dem Modell abgeleitet** (`ChatRoom`): jedes Ergebnis bringt so viel mit, wie in der laufenden Anfrage noch Platz hat (Kontextlänge abzüglich Ausgabelimit, Nachrichten und Werkzeugbeschreibungen; Aufrufe einer Runde teilen sich den Platz). Der Rest kommt mit Versatz (`versatz`, `ab`); die Liste hat statt Blättern den Versatz `ab`. Die Kontextlänge kommt aus Einstellungen › KI-Dienst › „Kontextlänge“ (für jeden Dienst sichtbar), sonst aus dem Modellkatalog des Dienstes (`context_length`, beim ersten Senden einmal je Laufzeit gefragt); ist keine bekannt, gibt es keine Grenze |
| Fristen im Command Deck außerhalb des Terminals (früher `FtpChatTools.TIMEOUT_SECONDS`, `EakteDocumentContent.TIMEOUT_SECONDS`, `HttpBetaViewServer.TIMEOUT`, `NetUseLogin.TIMEOUT_SECONDS`) | 120 / 120 / 60 / 60 s | langsame Hosts brachen ab | **einstellbar**: `DeckTimeouts` in `app-state.json` (`deck.timeouts.*`), Einstellungen › Mainframe › „Fristen im Command Deck“. Die Fristen des Terminals (Makros, Anmeldung, OpenTerm) bleiben bis zu einer Entscheidung |
| Fristen je Anfrage einzelner KI-Aufgaben (früher `RemoteAi.SUMMARY_TIMEOUT`, `LABEL_TIMEOUT`, `EXPANSION_TIMEOUT`) | 5 min / 60 s / 90 s | große Modelle oder langsame Dienste brachen ab | **einstellbar**: `ai.summary-timeout`, `ai.label-timeout`, `ai.expansion-timeout` (`AiServiceSettings.Timeouts`), Einstellungen › KI-Dienst › Modelle › „Fristen je Anfrage“ |
| Netz-Fristen der Quellen (früher `DelegatedConfluenceHttp.TIMEOUT`, `RoutedWebDavHttp.REQUEST_TIMEOUT`, `SourceDiagnostics.PROBE_TIMEOUT`) | 10 s / 90 s / 15 s | langsame Netze oder Proxys brachen Token-Prüfung, BSCW-Abrufe und Verbindungsprüfung ab | **einstellbar**: `network.timeouts.delegated-check`, `webdav-request`, `probe` (`SourceTimeouts`), für alle Quellen wie die Proxy-Frist |
| Fristen der App (früher `HttpModelDownload.CONNECT_TIMEOUT`/`RESPONSE_TIMEOUT`, `ChatWebBrowser.FIREFOX_START_TIMEOUT_MILLIS` (heute Browser-Start und Browser-Navigation), `DiagramLabels.TIMEOUT_SECONDS`, `VlcPcmAudioDecoder.NO_AUDIO_TIMEOUT_NANOS`) | 30 / 60 / 30 / 30 / 60 s | langsame Netze, Rechner oder Modelle brachen ab | **einstellbar**: `AppTimeouts` in `app-state.json` (`app.timeouts.*`), Einstellungen › Allgemein › „Fristen der App“ |
| Schritt blätternder Listen (früher `TermsTab.STEP`) | 120 Einträge | wer viele Fundstellen durchsieht, musste oft „Weitere laden“ drücken | **einstellbar**: `ListSteps` in `app-state.json` (`app.lists.terms-step`), Einstellungen › Allgemein › „Listen“; Obergrenze ist `TermQueries.MAX_LIMIT` |
| MVS-Binärdiagnose und Hex-Ansicht (früher `BinarySample.MAX_CAPTURE_BYTES`, `MvsDiagnosisTools.BUDGET`, Lesegröße 4096, Frist 120 s) | 8 MiB / 65536 / 4096 Bytes / 120 s | größere Stichproben waren unmöglich, die KI las höchstens 64 KiB | **einstellbar**: `MvsDiagnosisSettings` in `app-state.json` (`deck.ftp.diagnosis.sample-bytes`, `read-bytes`, `budget-bytes`, `timeout-seconds`), Einstellungen › Mainframe › „MVS-Binärdiagnose und Hex-Ansicht“. Das Modell kommt aus der Zeile „Diagnose“ (siehe oben) |

### Grenzen außerhalb des eigenen Codes

Der Test sieht nur eigenen Code. Grenzen in den unveränderten Kopien aus dem Enterprise AI Client
(`enterprise-ai-sources`, `enterprise-ai-keepass`, `comic-controls`) werden dort als
Quelleinstellung geändert, per PR auf EAC-main und danach `sync-from-upstream.sh`. Die Browser-Werkzeuge des Chats
liegen in Research4j (`browser-tools`, ADR 0046) und werden dort geändert. Bekannt sind:

| Grenze | Wert | Folge beim Erreichen |
|---|---|---|
| `SourceSettingsReader` maxDepth / maxResources | ≤ 100 / ≤ 1.000.000 | Inhalte dahinter werden nie geerntet |
| Confluence `MAX_READER_ATTACHMENTS` | 500 | weitere Anhänge fehlen |
| LocalFiles `PARTITION_DEPTH` | 20 | tiefere Ordner fehlen |
| Outlook `MAX_FOLDER_DEPTH` | 15 | tiefere Ordner fehlen |
| SharePoint-Änderungsschnappschüsse (früher `SharePointChangePages`) | entfallen | **Erledigt** (F42-1, ADR 0042 Punkte 5 und 6): kein Register mehr, der Cursor trägt seine Position; das exakte ChangeToken je Bibliothek wird mit der bestätigten Generation gespeichert (EAC #259, #261) |
| SharePoint-Änderungsseite, JSON | 500 / 32 MiB | |
| Confluence/Jira Reader | 100 / 50 MiB | |
| MediaWiki-Stapel, Git-Kinderstapel, NDV-Metadaten | 50 / 200 / 10.000 | |

Ebenfalls nicht erfasst sind Zahlen, die ohne eigene Konstante im Code stehen, etwa `subList(0, 50)`. Sie werden in
der Erhebung (Epic) gesucht und bekommen eine benannte Konstante, damit der Test sie sieht.

## Verzeichnis aller Grenzkonstanten

„Stand“: `Vorgabe` heißt, die Konstante ist der Standard einer Einstellung, die es schon gibt (meist unter dem Namen
`DEFAULT_…`). `fest` heißt, die Grenze lässt sich heute nur im Code ändern. Die Kategorie ist **vorläufig**: Ein Skript
hat sie aus Name und Ort abgeleitet, nur die oben genannten Grenzen sind von Hand geprüft. Die Erhebung im Epic prüft
jede Zeile und trägt die Folge beim Erreichen nach.

### `space-harvester-core`

| Konstante | Wert | Stand | Kategorie (vorläufig) | Behandlung |
|---|---|---|---|---|
| `AcronymProcessor.MAX_ACRONYMS` | `500` | fest | Bestandsgrenze | beseitigen |
| `AcronymProcessor.MAX_OCCURRENCES` | `5000` | fest | Bestandsgrenze | beseitigen |
| `AcronymResolution.BATCH` | `20` | fest | Stapel-/Seitengröße | einstellbar |
| `AcronymResolution.MAX_ACRONYMS` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AcronymResolution.MAX_ANSWER_CHARS` | `128 * 1024` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AcronymResolution.MAX_EXPANSIONS` | `3` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AcronymResolution.MAX_EXPANSION_CHARS` | `120` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AcronymResolution.MAX_PER_PAGE` | `2` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AcronymResolution.MAX_SNIPPETS` | `6` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AcronymResolution.SNIPPET_CHARS` | `160` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AcronymResolution.TEXT_CACHE` | `64` | fest | Cache-Aufbewahrung | einstellbar |
| `ActivityProcessor.MAX_ACTIVITIES` | `1000` | fest | Bestandsgrenze | beseitigen |
| `AiFactExtractor.DEFAULT_MAX_CHARS` | `12_000` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiQueryExpansion.DEFAULT_TIMEOUT` | `Duration.ofSeconds(6)` | Vorgabe | Timeout | einstellbar |
| `AiQueryExpansion.MAX_ANSWER_CHARS` | `64 * 1024` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AiQueryExpansion.MAX_QUESTION_CHARS` | `2000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AiQueryExpansion.MAX_VARIANT_CHARS` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS` | `8192` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_DEADLINE_MAX_TOKENS` | `8192` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_SOURCE_CODE_MAX_TOKENS` | `DEFAULT_CHAT_MAX_TOKENS` | Vorgabe (wie Text-Modell) | Modellkontext | aus Modellfähigkeit ableiten |
| `FlowLabels.Layout.DEFAULT` | `140` / `600` / `44` Zeichen | Vorgabe | Lesbarkeit der Diagrammknoten | Einstellungen › Diagramme |
| `AiServiceSettings.DEFAULT_IMAGE_MAX_TOKENS` | `16384` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_IMAGE_PLAN_MAX_TOKENS` | `16384` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_LOAD_TIMEOUT` | `Duration.ofMinutes(10)` | Vorgabe | Timeout | einstellbar |
| `AiServiceSettings.DEFAULT_PARALLEL` | `4` | Vorgabe | Parallelität | einstellbar |
| `AiServiceSettings.DEFAULT_PODCAST_ATTEMPTS` | `3` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `AiServiceSettings.DEFAULT_PODCAST_MAX_TOKENS` | `16384` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_PODCAST_SECTION_CHARS` | `3000` | Vorgabe | Modellkontext | einstellbar |
| `AiServiceSettings.DEFAULT_SUMMARY_MAX_TOKENS` | `32768` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_SUMMARY_PLAN_MAX_TOKENS` | `16384` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `AiServiceSettings.DEFAULT_TIMEOUT` | `Duration.ofSeconds(30)` | Vorgabe | Timeout | einstellbar |
| `AiServiceSettings.MAX_CHAT_MAX_TOKENS` | `1_000_000` | fest | Obergrenze einer Einstellung | prüfen: entfallen oder einstellbar |
| `AiServiceSettings.MAX_CONTEXT_TOKENS` | `1_048_576` | fest | Obergrenze einer Einstellung | prüfen: entfallen oder einstellbar |
| `AiServiceSettings.MAX_LOAD_TIMEOUT` | `Duration.ofHours(1)` | fest | Timeout | einstellbar |
| `AiServiceSettings.MAX_TASK_TIMEOUT` | `Duration.ofMillis(Integer.MAX_VALUE)` | fest | Protokollgrenze | bleibt: Socket-Timeouts sind `int`-Millisekunden (etwa 24 Tage) |
| `AiServiceSettings.MAX_PARALLEL` | `32` | fest | Parallelität | einstellbar |
| `AiServiceSettings.MAX_PODCAST_ATTEMPTS` | `5` | fest | Obergrenze einer Einstellung | prüfen: entfallen oder einstellbar |
| `AiServiceSettings.MAX_PODCAST_PAUSE_MILLIS` | `5000` | fest | Timeout | einstellbar |
| `AiServiceSettings.MAX_PODCAST_WORKERS` | `8` | fest | Parallelität | einstellbar |
| `AiServiceSettings.MAX_RETRIES` | `10` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AreaSummarizer.MAX_EXCERPT_CHARS` | `UpdateSummarizer.MAX_EXCERPT_CHARS` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AreaSummarizer.MAX_PATH` | `4` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AreaSummarizer.MAX_TITLE_CHARS` | `300` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `Assets.IN_MEMORY_BYTES` | `20L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `Assets.MAX_BACKFILLED` | `10_000` | fest | Bestandsgrenze | beseitigen |
| `Assets.MAX_CONCURRENT_DESCRIPTIONS` | `4` | fest | Parallelität | einstellbar |
| `Assets.MAX_CONCURRENT_DOWNLOADS` | `4` | fest | Parallelität | einstellbar |
| `Assets.MAX_IN_MEMORY_BYTES` | `100L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `Assets.UNLIMITED` | `Long.MAX_VALUE` | fest | Invariante/Darstellung | im Code belassen |
| `BackgroundFill.DEFAULT_PARALLELISM` | `2` | Vorgabe | Parallelität | einstellbar |
| `BackgroundFill.MAX_PARALLELISM` | `16` | fest | Parallelität | einstellbar |
| `BackgroundFill.SETTLE` | `Duration.ofSeconds(1)` | fest | Cache-Aufbewahrung | einstellbar |
| `BscwSourceAdapter.DEFAULT_DEPTH` | `20` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `BscwSourceAdapter.MAX_DEPTH` | `50` | fest | Bestandsgrenze | beseitigen |
| `BscwSourceAdapter.MAX_FILE_MEGABYTES` | `64` | fest | Bestandsgrenze | beseitigen |
| `BscwWebDavKnowledgeSource.DEFAULT_CHUNK_BYTES` | `8 * 1024 * 1024` | Vorgabe | Stapel-/Seitengröße | einstellbar |
| `BscwWebDavKnowledgeSource.MAX_ACTIVE_INVENTORIES` | `32` | fest | Parallelität | einstellbar |
| `BscwWebDavKnowledgeSource.MAX_BATCH` | `500` | fest | Stapel-/Seitengröße | einstellbar |
| `BscwWebDavKnowledgeSource.NAVIGATION_BATCH` | `200` | fest | Stapel-/Seitengröße | einstellbar |
| `BscwWebDavTransport.MAX_METADATA_BYTES` | `16 * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `BscwWebDavTransport.MAX_PAGE_REDIRECTS` | `5` | fest | Protokollgrenze | dokumentieren, berücksichtigen: Weiterleitungen der BSCW-Schnellsuche (Startordner → Suchordner, real zwei), nur auf demselben Server |
| `CacheFreshness.MAX_AGE` | `Duration.ofDays(365)` | fest | Cache-Aufbewahrung | einstellbar |
| `CandidateSelectionStrategy.SMALL_BATCH` | `10` | fest | Stapel-/Seitengröße | einstellbar |
| `CertificatePackage.MAX_NAME` | `60` | fest | Invariante/Darstellung | im Code belassen |
| `CertificateQrCodes.MAX_FILE_BYTES` | `30L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `CertificateQrCodes.MAX_PIXELS` | `40_000_000L` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ChangeEvents.MAX_BODY_BYTES` | `1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ChangeEvents.MAX_QUEUED` | `10_000` | fest | Bestandsgrenze | beseitigen |
| `ChangeFilter.MAX_LIMIT` | `1000` | fest | Stapel-/Seitengröße | einstellbar |
| `ChangeLog.MAX_DIFF_STEPS` | `20` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ChangeLog.MAX_PATH_DEPTH` | `50` | fest | Bestandsgrenze | beseitigen |
| `ChangeLogSettings.DEFAULT_RETENTION` | `Duration.ofDays(365)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `ChatNavigationLabeler.PARALLELISM` | `2` | fest | Parallelität | einstellbar |
| `CheckSettings.DEFAULT_PARALLEL` | `2` | Vorgabe | Parallelität | einstellbar |
| `Coalescer.CLOSE_WAIT` | `Duration.ofSeconds(5)` | fest | Timeout | einstellbar |
| `ConnectionDiagnosis.MAX_DETAIL_LENGTH` | `600` | fest | Invariante/Darstellung | im Code belassen |
| `ConnectionProbe.MAX_HEADER_BYTES` | `16 * 1024` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `ContentListing.DEFAULT_MAX_RESPONSE_BYTES` | `32L * 1024 * 1024` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `ContentListing.MAX_LIMIT` | `PageSearch.MAX_LIMIT` | fest | Stapel-/Seitengröße | einstellbar |
| `DelegatedConfluenceHttp.MAX_BYTES` | `1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `DiagramComposer.ATTEMPTS` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `DiagramComposer.MAX_ELEMENTS` | `5` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `DiagramComposer.MAX_MATERIAL_CHARS` | `4000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `DocumentNavigationBuilder.ANCHOR_CHARS` | `60` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `DomainTermGraph.MAX_NODES` | `40` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `EditedFactStore.ID_BYTES` | `16` | fest | Invariante/Darstellung | im Code belassen |
| `EnrichmentFacts.ID_BYTES` | `16` | fest | Invariante/Darstellung | im Code belassen |
| `EnrichmentFacts.MAX_PER_RESULT` | `2_000` | fest | Bestandsgrenze | beseitigen |
| `EnrichmentSettings.DEFAULT_EMBEDDING_MAX_CHARS` | `2_000_000` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `Enrichments.ATTEMPTS` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `Enrichments.MAX_PARALLEL` | `16` | fest | Parallelität | einstellbar |
| `Enrichments.TEXT_CHANGE_ATTEMPTS` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `EntityProcessor.MAX_ENTITIES` | `500` | fest | Bestandsgrenze | beseitigen |
| `ExportConversions.MAX_NOTICES` | `1000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `ExportConverters.DEFAULT_MAX_BYTES` | `0` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `ExportSettings.DEFAULT_MAX_SIZE` | `0` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `Exports.BATCH_SIZE` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `FactEdits.MAX_ARGUMENTS` | `32` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `FactEdits.MAX_PAYLOAD_CHARS` | `16_384` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `FactStore.MAX_CONDITIONS` | `16` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `FailureDiagnosis.MAX_CHAIN` | `12` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `FileSystemAssetStore.DEFAULT_MAX_TOTAL_BYTES` | `20L * 1024 * 1024 * 1024` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `FileSystemExportArtifactWriter.DEFAULT_MAX_TOTAL_BYTES` | `20L * 1024 * 1024 * 1024` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `FileSystemExtractionStore.MAX_SECTION_BLOCKS` | `10_000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `FileSystemExtractionStore.MOVE_ATTEMPTS` | `5` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `FindingCataloging.CAPACITY` | `1000` | fest | Bestandsgrenze | beseitigen |
| `FindingCataloging.PARALLELISM` | `2` | fest | Parallelität | einstellbar |
| `FindingCataloging.REMEMBERED` | `10_000` | fest | Bestandsgrenze | beseitigen |
| `GermanText.MAX_TERM_WORDS` | `6` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `Glossary.MAX_EVIDENCE` | `20` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `Glossary.MAX_EXPLANATION_CHARS` | `2000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `Glossary.MAX_LIMIT` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryClassification.MAX_ANSWER_CHARS` | `128 * 1024` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryClassification.TEXT_CACHE` | `64` | fest | Cache-Aufbewahrung | einstellbar |
| `GlossaryDerivation.MAX_EVIDENCE` | `3` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryModelPhase.BATCH` | `25` | fest | Stapel-/Seitengröße | einstellbar |
| `GlossaryModelPhase.MAX_ANSWER_CHARS` | `200_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryModelPhase.MAX_IS_A` | `3` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryModelPhase.MAX_SENTENCE` | `300` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryModelPhase.MAX_VERBS` | `5` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryRuleProposals.DEFAULT_MAX_TERMS` | `GlossaryRuleSettings.DEFAULT_MAX_TERMS` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `GlossaryRuleSettings.DEFAULT_MAX_TERMS` | `500` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `GraphNeighbourhood.DEFAULT_MAX_EDGES` | `100` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `GraphNeighbourhood.DEFAULT_MAX_NODES` | `50` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `GraphNeighbourhood.MAX_LIMIT` | `500` | fest | Stapel-/Seitengröße | einstellbar |
| `GraphNeighbourhood.MAX_SUBPAGES` | `200` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `GraphicCanvas.SETTLE_SLICE_MS` | `50` | fest | Timeout | im Code belassen: Wartescheibe beim Warten auf Batik, die Wartezeit selbst gibt der Aufrufer |
| `Harvester.DEFAULT_MAX_TEXT_FILE_BYTES` | `200L * 1024 * 1024` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `Harvester.DEFAULT_SEARCH_RUN_MAX_AGE` | `Duration.ofDays(30)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `Harvester.SEARCH_FILL_PARALLEL` | `4` | fest | Parallelität | einstellbar |
| `Holdings.MAX_PAGES_PER_MESSAGE` | `1000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `ImageSettings.DEFAULT_MAX_PER_RUN` | `500` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `JobEvent.MAX_DETAIL_CHARS` | `16_000` | fest | Invariante/Darstellung | im Code belassen |
| `JobLimits.DEFAULT_MAX_OPEN_BACKGROUND` | `1000` | Vorgabe | Warteschlange | einstellbar: `space-harvester.jobs.max-open-background` |
| `JobLimits.DEFAULT_MAX_OPEN_PER_OWNER` | `4` | Vorgabe | Warteschlange | einstellbar: `space-harvester.jobs.max-open-per-owner` |
| `JobLimits.DEFAULT_MAX_QUEUED` | `20` | Vorgabe | Warteschlange | einstellbar: `space-harvester.jobs.max-queued` |
| `JobLimits.DEFAULT_MAX_RUN_TIME` | `Duration.ofHours(6)` | Vorgabe | Timeout | einstellbar: `space-harvester.jobs.max-run-time` |
| `JobLimits.DEFAULT_MAX_WAITING_PREFETCHES` | `5_000` | Vorgabe | Warteschlange | einstellbar: `space-harvester.jobs.max-waiting-prefetches` (nur App) |
| `JobLimits.DEFAULT_PARALLEL_DOWNLOADS` | `4` | Vorgabe | Parallelität | einstellbar |
| `JobLimits.DEFAULT_PARALLEL_PREPARATIONS` | `2` | Vorgabe | Parallelität | einstellbar |
| `JobLimits.DEFAULT_RETENTION` | `Duration.ofDays(7)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `JobLimits.MAX_DOWNLOAD_CONNECTIONS` | `64` | fest | Obergrenze einer Einstellung | prüfen: entfallen oder einstellbar |
| `JobLimits.MAX_PARALLEL_DOWNLOADS` | `64` | fest | Parallelität | einstellbar |
| `JobLimits.MAX_PARALLEL_PREPARATIONS` | `16` | fest | Parallelität | einstellbar |
| `Jobs.RETRY_AFTER_PASS_ATTEMPTS` | `48` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `KeywordSearch.DEFAULT_MAX_QUERIES` | `4` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `KeywordSearch.MARK_BYTES` | `6` | fest | Invariante/Darstellung | im Code belassen |
| `KeywordSearch.MAX_DEPTH` | `1000` | fest | Bestandsgrenze | beseitigen |
| `KeywordSearch.MAX_LIMIT` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `KeywordSearch.MAX_QUERIES` | `8` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `KeywordSearch.MAX_TEXT_LENGTH` | `200` | fest | Invariante/Darstellung | im Code belassen |
| `KeywordSearch.PARALLEL_CHECKS` | `6` | fest | Parallelität | einstellbar |
| `KeywordSearch.REMEMBERED_SEARCHES` | `64` | fest | Cache-Aufbewahrung | einstellbar |
| `KnowledgeGraph.MAX_LIMIT` | `200` | fest | Stapel-/Seitengröße | einstellbar |
| `KnowledgeSourceHarvestSource.PROBE_BYTES` | `4 * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `KnowledgeSourceHarvestSource.TITLE_SCAN_BATCH` | `500` | fest | Stapel-/Seitengröße | einstellbar |
| `KnowledgeSourceHarvestSource.TITLE_SEARCH_DEPTH` | `1000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `KnowledgeTextSegmentation.DEFAULT_MAX_TOKENS` | `KnowledgeChunkingPolicy.DEFAULT_MAX_TOKENS` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `LargeFileGate.DEFAULT_MAX_CONCURRENT` | `2` | Vorgabe | Parallelität | einstellbar |
| `LargeFileGate.DEFAULT_THRESHOLD_BYTES` | `20L * 1024 * 1024` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `LargeFileGate.DEFAULT_WAIT` | `Duration.ofSeconds(30)` | Vorgabe | Timeout | einstellbar |
| `LiveDictationSettings.MAX_FIRST_MILLIS` | `5_000` | fest | Obergrenze einer Einstellung | im Code belassen: Anlauf der Live-Spracheingabe, einstellbar bis hierher |
| `LiveDictationSettings.MAX_STEP_MILLIS` | `10_000` | fest | Obergrenze einer Einstellung | im Code belassen: Takt der Live-Spracheingabe, einstellbar bis hierher |
| `LiveDictationSettings.MAX_WINDOW_SECONDS` | `29` | fest | Externe Protokollgrenze | dokumentieren: Whisper sieht genau 30 s, das Live-Fenster bleibt darunter |
| `ReadAloudRate.MAX_JUMP_SENTENCES` | `100` | fest | Obergrenze einer Einstellung | im Code belassen: weitester Sprung der Vorlese-Skala, einstellbar bis hierher (Erweitert › „Vorlesen – Regler“) |
| `ReadAloudRate.MAX_PERCENT` | `(int) Math.round(SpeechRate.MAX * PERCENT)` | fest | Externe Grenze | dokumentieren: schnellstes Tempo, das das Zeitstrecken des Vorlese-Ablaufs kann (`SpeechRate.MAX`); einstellbar bis hierher |
| `ReadAloudRate.MAX_STEP_PERCENT` | `50` | fest | Obergrenze einer Einstellung | im Code belassen: gröbstes Raster der Vorlese-Skala, einstellbar bis hierher |
| `LocalFilesSourceAdapter.DEFAULT_MAX_FILE_MEGABYTES` | `20` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `LocalFilesSourceAdapter.MAX_FILE_MEGABYTES` | `64` | fest | Bestandsgrenze | beseitigen |
| `LocalFolderHierarchy.NAVIGATION_BATCH` | `200` | fest | Stapel-/Seitengröße | einstellbar |
| `LuceneEnrichmentStore.MAX_FACTS_PER_PAGE` | `EnrichmentFacts.PROCESSORS.size() * EnrichmentFacts.MAX_PER_RESULT` | fest | Bestandsgrenze | beseitigen |
| `LuceneEnrichmentStore.MAX_PER_PAGE` | `1_000` | fest | Bestandsgrenze | beseitigen |
| `LucenePageCache.ACQUIRE_WAIT` | `Duration.ofSeconds(60)` | fest | Timeout | einstellbar |
| `LucenePageCache.CLOSE_WAIT` | `Duration.ofSeconds(10)` | fest | Timeout | einstellbar |
| `LucenePageCache.DEFAULT_MAX_PAYLOAD_BYTES` | `20 * 1024 * 1024` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `LucenePageCache.FILL_WAIT` | `Duration.ofMillis(500)` | fest | Timeout | einstellbar |
| `LucenePageCache.MAX_PAGE_SIZE` | `1000` | fest | Stapel-/Seitengröße | einstellbar |
| `LucenePageCache.SNAPSHOT_BATCH` | `500` | fest | Stapel-/Seitengröße | einstellbar |
| `LucenePageTextIndex.MAX_DOCUMENTS` | `8 * MAX_LIMIT` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `LucenePageTextIndex.MAX_LIMIT` | `1_000` | fest | Stapel-/Seitengröße | einstellbar |
| `LucenePageTextIndex.MAX_TERMS` | `32` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `ManualSyncRounds.REMEMBERED_ENDS` | `1000` | fest | Cache-Aufbewahrung | einstellbar |
| `MetadataFact.MAX_VALUE_LENGTH` | `512` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `MimeTree.MAX_HEADER` | `64 * 1024` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `MimeTree.MAX_LINE` | `1_000` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `ModelAnswer.MAX_VALUE_CHARS` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ModelStatementAnalyzer.MAX_LINES` | `40` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ModelStatementAnalyzer.MAX_SENTENCE_CHARS` | `600` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `NeighborCandidates.MAX_CANDIDATES` | `50` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `NeighborCandidates.MAX_PER_KIND` | `10` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `NeighborCandidates.MAX_SEEDS` | `10` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `NetworkState.FIRST_WAIT` | `Duration.ofSeconds(5)` | fest | Timeout | einstellbar |
| `NetworkState.LONGEST_WAIT` | `Duration.ofMinutes(5)` | fest | Timeout | einstellbar |
| `OllamaModelCatalog.MAX_BODY_BYTES` | `4 * 1024 * 1024` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `OneNotePreviewReader.MAX_BYTES` | `20L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `OneNotePreviewReader.MAX_CHARS` | `1_000_000` | fest | Bestandsgrenze | beseitigen |
| `PageBookmark.MAX_LENGTH` | `4096` | fest | Invariante/Darstellung | im Code belassen |
| `PageBookmark.MAX_VERSION_LENGTH` | `256` | fest | Invariante/Darstellung | im Code belassen |
| `PageConnections.BATCH` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `PageConnections.DEFAULT_MAX_EDGES` | `200` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageConnections.DEFAULT_MAX_PER_KIND` | `10` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageConnections.MAX_EDGES` | `500` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageConnections.MAX_OCCURRENCES` | `TermQueries.MAX_LIMIT` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageConnections.MAX_PER_KIND` | `50` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageConnections.MAX_SCAN` | `1_000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PageConnections.MAX_SEED_TERMS` | `1_000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PageConnections.MAX_SIMILAR_CHUNKS` | `16` | fest | Stapel-/Seitengröße | einstellbar |
| `PageConnections.MAX_SIMILAR_LOOKUPS` | `60` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PageConnections.MAX_TERM_LOOKUPS` | `200` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PageConnections.MAX_TERM_RELATIONS` | `50` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageFacts.MAX_LIMIT` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PageFacts.MAX_SCANNED` | `5_000` | fest | Bestandsgrenze | beseitigen |
| `PageFreshness.DEFAULT_LARGE_FILE_TIMEOUT` | `Duration.ofMinutes(10)` | Vorgabe | Timeout | einstellbar |
| `PageFreshness.DEFAULT_TIMEOUT` | `Duration.ofSeconds(30)` | Vorgabe | Timeout | einstellbar |
| `PageImageAnalysis.MAX_IMAGES` | `50` | fest | Bestandsgrenze | beseitigen |
| `PageLinks.BATCH` | `500` | fest | Stapel-/Seitengröße | einstellbar |
| `PageLinks.MAX_PAGES_PER_MESSAGE` | `1_000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageMarks.MAX_DEPTH` | `64` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageNavigation.MAX_TITLE_DEPTH` | `1000` | fest | Bestandsgrenze | beseitigen |
| `PageNavigation.MAX_TITLE_LIMIT` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `PagePassages.TITLE_CHARS` | `80` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PagePodcasts.GRANDCHILD_CHARS` | `300` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PagePodcasts.MAX_GRANDCHILDREN` | `6` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PagePodcasts.MAX_SUBPAGES` | `12` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PagePodcasts.SUBPAGE_CHARS` | `1500` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PageQueries.MAX_CHECKED` | `10_000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PageQueries.MAX_TREE_LEVELS` | `200` | fest | Stapel-/Seitengröße | Invariante: Ebenen je JSON-Antwort, Fortsetzung über `root` (F40-6) |
| `PageQueries.TREE_BATCH` | `1000` | fest | Stapel-/Seitengröße | einstellbar |
| `PageSearch.MAX_LIMIT` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `PageSearchQuery.MAX_SOURCE_IDS` | `50` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageSearchQuery.MAX_SPACE_KEYS` | `20` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageSearchQuery.MAX_TITLE_LENGTH` | `200` | fest | Invariante/Darstellung | im Code belassen |
| `PageSearchQuery.MAX_TITLE_TERMS` | `8` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PageSearchQuery.MAX_VALUE_LENGTH` | `255` | fest | Invariante/Darstellung | im Code belassen |
| `PageUrls.MAX_URL_LENGTH` | `2048` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `ParagraphAlignment.MAX_CELLS` | `1_000_000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PassageProcessor.MAX_PASSAGES` | `5_000` | fest | Bestandsgrenze | beseitigen |
| `PassageSegmentation.EMBEDDING_BATCH` | `32` | fest | Stapel-/Seitengröße | einstellbar |
| `PassageSegmentation.MAX_EMBEDDED_SENTENCES` | `2_000` | fest | Bestandsgrenze | beseitigen |
| `PassageSegmentation.MAX_PASSAGE_SENTENCES` | `8` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PassageSegmentation.MAX_PASSAGE_TOKENS` | `350` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PassageSnippets.MAX_CHARS` | `480` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PdfConverter.MAX_PAGES` | `PdfExportWriter.MAX_PAGES` | fest | Bestandsgrenze | beseitigen |
| `PodcastPictures.MAX_CANDIDATES` | `12` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastPlan.MAX_BEATS` | `8` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastPlan.MAX_SEGMENTS` | `4` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastPlan.MAX_TOPICS` | `8` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastProduction.MAX_REPORTED_REPAIRS` | `20` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastTables.MAX_ROWS` | `12` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastWriter.ATTEMPTS` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PodcastWriter.MAX_PLACES` | `3` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastWriter.MAX_STORY_BRIEF_CHARS` | `400` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastWriter.MAX_STORY_CHARS` | `3000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PodcastWriter.MAX_TURN_CHARS` | `450` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PptxSlideOrder.MAX_PART_BYTES` | `8L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PropertyProcessor.MAX_PROPERTIES` | `1000` | fest | Bestandsgrenze | beseitigen |
| `ProxyRoutes.MAX_CACHE_ENTRIES` | `1024` | fest | Cache-Aufbewahrung | einstellbar |
| `ProxyRoutes.MAX_RESOLVER_THREADS` | `4` | fest | Parallelität | einstellbar |
| `ProxySettings.DEFAULT_CACHE_TTL` | `Duration.ofMinutes(10)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `ProxySettings.DEFAULT_FAILURE_TTL` | `Duration.ofMinutes(1)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `ProxySettings.DEFAULT_RESOLVE_TIMEOUT` | `Duration.ofSeconds(20)` | Vorgabe | Timeout | einstellbar |
| `QrCodes.TEXT_PART_BYTES` | `800` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `QualitySuite.MAX_QUESTION_LENGTH` | `200` | fest | Invariante/Darstellung | im Code belassen |
| `QueryLimits.DEFAULT_MAX_PAGE_LIMIT` | `100` | `space-harvester.query.max-page-limit` | Stapel-/Seitengröße | einstellbar (erledigt; Fortsetzung über den Cursor) |
| `QueryLimits.DEFAULT_MAX_TERM_SCAN` | `50_000` | `space-harvester.query.max-term-scan` | Stapel-/Seitengröße | einstellbar (erledigt; Fortsetzung über den Cursor) |
| `ReadingViews.MAX_PREFETCH` | `25` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `ReadingViews.PREFETCH_PARALLEL` | `2` | fest | Parallelität | einstellbar |
| `RelatedSections.DEFAULT_MAX_PER_KIND` | `3` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `RelatedSections.MAX_LOOKUPS` | `60` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `RelatedSections.MAX_PER_KIND` | `20` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `RelationProcessor.MAX_RELATIONS` | `1000` | fest | Bestandsgrenze | beseitigen |
| `ReleaseRecomputation.CLOSE_WAIT` | `Duration.ofSeconds(10)` | fest | Timeout | einstellbar |
| `ReleaseRecomputation.WITHDRAWAL_BATCH` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `RelevanceSearch.MAX_CHUNKS` | `EmbeddingIndex.MAX_K` | fest | Stapel-/Seitengröße | einstellbar |
| `RelevanceSearch.MAX_DOCUMENT_CHARS` | `2000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `RelevanceSearch.MAX_LOOKUPS` | `3 * Reranking.MAX_WINDOW` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `RelevanceSearch.MAX_TEXT_LENGTH` | `500` | fest | Invariante/Darstellung | im Code belassen |
| `RelevanceSearch.PARALLEL_CHECKS` | `6` | fest | Parallelität | einstellbar |
| `RemoteAi.CATALOG_ATTEMPTS` | `4` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `RequestRoute.MAX_DEPTH` | `12` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `Reranking.MAX_WINDOW` | `100` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `RestJson.LIST_LIMIT` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `RestJson.MAX_LIST_PAGES` | `200` | fest | Bestandsgrenze | beseitigen |
| `RetrySettings.DEFAULT_RESUME_MAX_DELAY` | `Duration.ofMinutes(10)` | Vorgabe | Timeout | einstellbar |
| `RetrySettings.MAX_ATTEMPTS_LIMIT` | `10` | fest | Obergrenze einer Einstellung | prüfen: entfallen oder einstellbar |
| `RetrySettings.MAX_INITIAL_DELAY` | `Duration.ofSeconds(60)` | fest | Timeout | einstellbar |
| `RetrySettings.MAX_MAX_DELAY` | `Duration.ofSeconds(300)` | fest | Timeout | einstellbar |
| `RetrySettings.MAX_RESUME_INITIAL_DELAY` | `Duration.ofMinutes(10)` | fest | Timeout | einstellbar |
| `RetrySettings.MAX_RESUME_MAX_DELAY` | `Duration.ofHours(1)` | fest | Timeout | einstellbar |
| `RetrySettings.PAUSE_AFTER_LIMIT` | `1000` | fest | Stapel-/Seitengröße | einstellbar |
| `RuleBasedExpansion.MAX_TERMS` | `8` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SearchExecutionPolicy.DEFAULT_VARIANT_TIMEOUT` | `Duration.ofSeconds(10)` | Vorgabe | Timeout | einstellbar |
| `SearchExpansion.MAX_EXPANSIONS` | `3` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SearchHitLoads.REMEMBERED` | `256` | fest | Cache-Aufbewahrung | einstellbar |
| `SearchHitPrefetch.DEFAULT_WAIT` | `Duration.ofSeconds(5)` | Vorgabe | Timeout | einstellbar |
| `SearchHitPrefetch.MAX_TRACKED` | `256` | fest | Cache-Aufbewahrung | einstellbar |
| `SearchLegalKnowledge.MAX_BATCHES` | `3` | fest | Stapel-/Seitengröße | einstellbar |
| `SearchOrchestrator.CLOSE_TIMEOUT` | `Duration.ofSeconds(10)` | fest | Timeout | einstellbar |
| `SearchProfile.DEFAULT_LIMIT_PER_SEARCH` | `20` | Vorgabe | Stapel-/Seitengröße | einstellbar |
| `SearchProfile.DEFAULT_MAX_VARIANTS` | `8` | Vorgabe | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SearchProfile.DEFAULT_SEARCH_BUDGET` | `12` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `SearchProfile.DEFAULT_TIME_BUDGET` | `Duration.ofSeconds(30)` | Vorgabe | Timeout | einstellbar |
| `SearchRun.MAX_QUERY_LENGTH` | `1000` | fest | Invariante/Darstellung | im Code belassen |
| `SearchRun.MAX_SOURCES` | `200` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SearchRunId.MAX_LENGTH` | `64` | fest | Invariante/Darstellung | im Code belassen |
| `SearchTerms.MAX_QUERY_WORDS` | `6` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SemanticPreparation.DEFAULT_WAIT` | `Duration.ofSeconds(10)` | Vorgabe | Timeout | einstellbar |
| `SemanticPreparation.MAX_PAGES` | `50` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `SemanticPreparation.PARALLEL` | `4` | fest | Parallelität | einstellbar |
| `SemanticVariantSearch.MAX_SNIPPET_CHARS` | `300` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SemanticVariantSearch.TIME_BUDGET` | `Duration.ofSeconds(60)` | fest | Timeout | einstellbar |
| `SemanticVariantSearch.VARIANT_TIMEOUT` | `SemanticPreparation.DEFAULT_WAIT.plusSeconds(10)` | fest | Timeout | einstellbar |
| `SentenceSimplifier.MAX_SENTENCE_CHARS` | `1_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SignInFailures.MAX_DETAIL` | `120` | fest | Invariante/Darstellung | im Code belassen |
| `SignInRequests.DEFAULT_WAIT` | `Duration.ofMinutes(15)` | Vorgabe | Timeout | einstellbar |
| `SimplificationSettings.DEFAULT_PARALLEL` | `2` | Vorgabe | Parallelität | einstellbar |
| `SourceDataPurge.JOB_STOP_TIMEOUT` | `Duration.ofSeconds(30)` | fest | Timeout | einstellbar |
| `SourceManagement.JOB_STOP_TIMEOUT` | `Duration.ofSeconds(30)` | fest | Timeout | einstellbar |
| `SourceQueries.MAX_PAGE_ID_LIMIT` | `200` | fest | Stapel-/Seitengröße | einstellbar |
| `SourceQueries.MAX_PAGE_METADATA_LIMIT` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `SpaceSync.DEFAULT_WITHHELD_RETENTION` | `Duration.ofDays(30)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `StagedAttachment.HEAD_BYTES` | `64 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `StatementAnalysis.MAX_JOINED_LINES` | `10` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `StatementCheck.MAX_CORRECTION_CHARS` | `400` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `StatementSettings.DEFAULT_MAX_PARAGRAPHS` | `40` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `StatementSettings.DEFAULT_MAX_PARAGRAPH_CHARS` | `1500` | Vorgabe | Modellkontext | aus Modellfähigkeit ableiten |
| `StatementSettings.LIMIT_MAX_PARAGRAPHS` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `StatementSettings.LIMIT_MAX_PARAGRAPH_CHARS` | `8000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `StatementValidator.MAX_TERM_WORDS` | `12` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `StorageParser.MAX_COLSPAN` | `1000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `StorageParser.MAX_NESTING` | `64` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `SyncSettings.DEFAULT_BACKGROUND_PARALLELISM` | `2` | Vorgabe | Parallelität | einstellbar |
| `SyncSettings.DEFAULT_WITHHELD_RETENTION` | `Duration.ofDays(30)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `SyncSettings.MAX_BACKGROUND_PARALLELISM` | `16` | fest | Parallelität | einstellbar |
| `SynonymCandidates.EMBED_BATCH` | `64` | fest | Stapel-/Seitengröße | einstellbar |
| `SynonymCandidates.MAX_BUCKET` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SynonymCandidates.MAX_CONTEXTS` | `4` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SynonymCandidates.MAX_CONTEXT_CHARS` | `400` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SynonymCandidates.MAX_PHRASE_WORDS` | `6` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SynonymCandidates.MAX_SPELLING_GROUP` | `6` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SynonymCandidates.SNIPPET_CHARS` | `120` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `SynonymCandidates.TEXT_CACHE` | `64` | fest | Cache-Aufbewahrung | einstellbar |
| `SynonymDiscovery.BATCH` | `8` | fest | Stapel-/Seitengröße | einstellbar |
| `SynonymDiscovery.MAX_ANSWER_CHARS` | `128 * 1024` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TechnicalTerms.MAX_LIMIT` | `Glossary.MAX_LIMIT` | fest | Stapel-/Seitengröße | einstellbar |
| `TermKnowledge.MAX_CANDIDATES` | `5` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TermKnowledge.MAX_NEIGHBOURS` | `10` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TermKnowledge.MAX_PAGES` | `500` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TermKnowledge.MAX_PAGES_PER_STATEMENT` | `3` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TermKnowledge.MAX_STATEMENTS` | `20` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TermKnowledge.MAX_TERMS` | `5` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TermLedger.MAX_DIRTY_PAGES` | `5_000` | fest | Bestandsgrenze | beseitigen |
| `TermLedger.MAX_SELECTIONS` | `500` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TermProcessor.MAX_OCCURRENCES` | `50_000` | fest | Bestandsgrenze | beseitigen |
| `TermProcessor.MAX_TERMS` | `10_000` | fest | Bestandsgrenze | beseitigen |
| `TermQueries.MAX_LIMIT` | `200` | fest | Stapel-/Seitengröße | einstellbar |
| `TermQueries.MAX_SNIPPET_CHARS` | `300` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TermRelations.MAX_EVIDENCE` | `20` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TermRelations.MAX_LIMIT` | `200` | fest | Stapel-/Seitengröße | einstellbar |
| `TermSearch.MAX_HITS` | `20` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TermSearch.MAX_PROBES` | `24` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `TermSearch.MAX_TERMS` | `3` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TermSearch.MAX_WORDS` | `3` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TextContentDiffer.MAX_CHANGES` | `50` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TextContentDiffer.MAX_HEADING` | `120` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TextContentDiffer.MAX_HEADINGS` | `4` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TextContentDiffer.MAX_PARAGRAPH` | `500` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TextContentDiffer.MAX_TOTAL` | `8000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TextGrounding.MAX_QUOTE_WORDS` | `80` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.ATTEMPTS` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ThematicSummarizer.MAX_ANSWER_CHARS` | `128 * 1024` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_COMBINE_BRIEF_CHARS` | `120` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_COMBINE_EXAMPLES` | `3` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_COMBINE_TEXT_CHARS` | `400` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_COMBINE_TITLE_CHARS` | `120` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_DETAILS_CHARS` | `3000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_PARTIAL_TEXT_CHARS` | `2000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_PLAN_CHARS` | `60_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_PLAN_TEASER_CHARS` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_REQUEST_CHARS` | `32_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_STATEMENTS` | `30` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_STATEMENT_CHARS` | `800` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_STATEMENT_PAGES` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_TEXT_CHARS` | `4000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_TOPICS` | `60` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MAX_TOPIC_TITLE_CHARS` | `120` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.MIN_PART_CHARS` | `8_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ThematicSummarizer.PLANNED_PART_CHARS` | `12_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TikaDocumentStructure.MAX_LABEL_CHARS` | `1_000` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TikaDocumentStructure.MAX_LINKS` | `1_000` | fest | Bestandsgrenze | beseitigen |
| `TikaDocumentStructure.MAX_TABLE_CELLS` | `10_000` | fest | Bestandsgrenze | beseitigen |
| `TikaDocumentStructure.SPACE_CHECK_BYTES` | `8L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `TikaDocumentText.CACHE_ENTRIES` | `8` | fest | Cache-Aufbewahrung | einstellbar |
| `TikaDocumentText.NO_TIMEOUT` | `Duration.ofNanos(Long.MAX_VALUE)` | fest | Invariante | bleibt: bedeutet „ohne Zeitgrenze“ (`0` in `assets.extraction.idle-timeout` und `total-timeout`) |
| `TikaDocumentText.PREFIX_BYTES` | `64 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `UpdateSummarizer.MAX_CONTEXT_CHARS` | `400` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `UpdateSummarizer.MAX_EXCERPT_CHARS` | `1_500` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `UpdateSummarizer.MAX_FRAME_SPACES` | `50` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `UpdateSummarizer.MAX_PAGE_DIFF_CHARS` | `6_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `UpdateSummarizer.MAX_PATH` | `4` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `UpdateSummarizer.MAX_TITLE_CHARS` | `300` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `UpdateText.MAX_LIMIT` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `VerbQueries.MAX_LIMIT` | `500` | fest | Stapel-/Seitengröße | einstellbar |
| `VerbValence.MAX_EVIDENCE` | `3` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `VersionRecomputation.CLOSE_WAIT` | `Duration.ofSeconds(10)` | fest | Timeout | einstellbar |
| `VideoPresentations.BUSY_ATTEMPTS` | `40` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `VideoPresentations.BUSY_WAIT_MILLIS` | `3_000` | fest | Timeout | einstellbar |
| `VideoPresentations.CORRECTION_CHUNK_CHARS` | `3000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `VideoPresentations.NOTES_EXCERPT_CHARS` | `12_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `WebClientConfluenceTransport.MAX_CLIENTS` | `64` | fest | Parallelität | einstellbar |
| `XlsxCellRefs.MAX_PART_BYTES` | `8L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `XlsxCellRefs.MAX_SHEET_BYTES` | `4L * 1024 * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ZipExportPackageReader.MAX_JSON_BYTES` | `256L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ZipExportPackageReader.MAX_TEXT_BYTES` | `256L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |

### `space-harvester-app`

| Konstante | Wert | Stand | Kategorie (vorläufig) | Behandlung |
|---|---|---|---|---|
| `ActivitiesView.NARROW_SPACE_MAX` | `120` | fest | Invariante/Darstellung | im Code belassen |
| `ActivityLog.MAX_ENTRIES` | `500` | fest | Cache-Aufbewahrung | einstellbar |
| `AiDraft.MAX_CHAT_MAX_TOKENS` | `1_000_000` | fest | Obergrenze einer Einstellung | prüfen: entfallen oder einstellbar |
| `AiDraft.MAX_CONTEXT_TOKENS` | `1_048_576` | fest | Obergrenze einer Einstellung | prüfen: entfallen oder einstellbar |
| `AiSettingsPanel.MAX_CHECK_PARALLEL` | `16` | fest | Parallelität | einstellbar |
| `AiSettingsPanel.MAX_IMAGES_PER_RUN` | `1_000_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `AiSettingsPanel.MAX_SIMPLIFY_PARALLEL` | `16` | fest | Parallelität | einstellbar |
| `AnalysisView.MAX_STEP_ROWS` | `10` | fest | Invariante/Darstellung | im Code belassen |
| `AppExit.DEFAULT_TIMEOUT` | `Duration.ofSeconds(40)` | Vorgabe | Timeout | einstellbar |
| `AiSettingsPanel.TIMEOUT_MAX_AMOUNT` | `999` | fest | Invariante/Darstellung | im Code belassen: größte Zahl im Feld, die Einheit (bis Tage) wählt der Benutzer |
| `AppSettings.DEFAULT_CHECK_TIMEOUT` | `com.aresstack.spaceharvester.application.sync.PageFreshness.DEFAULT_TIMEOUT` | Vorgabe | Timeout | einstellbar |
| `AppSettings.DEFAULT_LARGE_FILE_TIMEOUT` | `com.aresstack.spaceharvester.application.sync.PageFreshness.DEFAULT_LARGE_FILE_TIMEOUT` | Vorgabe | Timeout | einstellbar |
| `AppSettings.DEFAULT_MAX_PAGE_BYTES` | `com.aresstack.spaceharvester.persistence.lucene.LucenePageCache.DEFAULT_MAX_PAYLOAD_BYTES` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `AppSettings.DEFAULT_MAX_TEXT_FILE_BYTES` | `com.aresstack.spaceharvester.core.Harvester.DEFAULT_MAX_TEXT_FILE_BYTES` | Vorgabe | Speicher-/Ressourcenbudget | einstellbar |
| `AppSettings.DEFAULT_RETENTION` | `Duration.ofDays(30)` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `AppSettings.MAX_PAGE_BYTES_LIMIT` | `Integer.MAX_VALUE - 8` | fest | Invariante/Darstellung | im Code belassen |
| `AttachmentViewerPanel.MAX_TEXT_CHARS` | `2_000_000` | fest | Bestandsgrenze | beseitigen |
| `BackButton.MAX_ENTRIES` | `15` | fest | Cache-Aufbewahrung | einstellbar |
| `BlockMarkerSpacing.MAX_HEADING_LEVEL` | `6` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `BlockMarkerSpacing.MAX_MARKER_INDENT` | `3` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `BlockMarkerSpacing.MAX_ORDERED_DIGITS` | `9` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `CachingMermaidImageRenderer.DEFAULT_CACHE_SIZE` | `32` | Vorgabe | Cache-Aufbewahrung | einstellbar |
| `ChatKnowledge.MAX_HIT_CHARS` | `3_000` | fest | Invariante/Darstellung | im Code belassen: Vorschau einer Quelle in der Quellenliste der Oberfläche; an das Modell geht, was in die Anfrage passt (`ChatRoom`) |
| `ChatPanel.MAX_NORMAL_EDITOR_HEIGHT` | `400` | fest | Invariante/Darstellung | im Code belassen |
| `ChatPanel.PARALLEL_MAX` | `com.aresstack.spaceharvester.app.ui.ParallelChoice.CUSTOM_MAX` | fest | Parallelität | einstellbar |
| `ChatPanel.QUOTE_CHIP_CHARS` | `28` | fest | Invariante/Darstellung | im Code belassen |
| `ChatPanel.QUOTE_TOOLTIP_CHARS` | `240` | fest | Invariante/Darstellung | im Code belassen |
| `SvgElements.LABEL_CHARS` | `60` | fest | Invariante/Darstellung | im Code belassen |
| `ChatPanel.QUIET_ROUNDS_DEFAULT` | `3` | Vorgabe | Stillstand der Werkzeugrunden (askai `stagnationRounds`) | einstellbar (Menü am Dreieck des Chats) |
| `ChatPanel.WEB_DELAY_MAX` | `10000` | fest | Timeout | einstellbar |
| `ChatPanel.WEB_DELAY_SLIDER_MAX` | `2000` | fest | Timeout | einstellbar |
| `ChatRoom.UNLIMITED` | `Integer.MAX_VALUE` | fest | Invariante/Darstellung | im Code belassen: Kennwert „keine Grenze“ (Kontextlänge unbekannt oder außerhalb eines Turns) |
| `ChatTools.MAX_TERM_CHARS` | `200` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `ChatTurn.MAX_QUERY_CHARS` | `KeywordSearch.MAX_TEXT_LENGTH` | fest | Invariante/Darstellung | im Code belassen: die Grenze der Suche selbst (früher fest 500; die Suche lehnte über 200 Zeichen ab) |
| `ComicDivider.GRIP_MAX` | `36` | fest | Invariante/Darstellung | im Code belassen |
| `DeckHome.MAX_COLUMNS` | `3` | fest | Invariante/Darstellung | im Code belassen |
| `DiagramLabels.CACHE_SIZE` | `1000` | fest | Cache-Aufbewahrung | einstellbar |
| `DocumentPane.MAX_PILL` | `28` | fest | Invariante/Darstellung | im Code belassen |
| `DoubleShiftShortcut.MAX_INTERVAL_MS` | `500` | fest | Invariante/Darstellung | im Code belassen |
| `DoubleShiftShortcut.MAX_PRESS_MS` | `500` | fest | Invariante/Darstellung | im Code belassen |
| `EvidencePopup.MAX_HEIGHT` | `420` | fest | Invariante/Darstellung | im Code belassen |
| `Explorer.ANALYSIS_LIMIT` | `500` | fest | Stapel-/Seitengröße | einstellbar |
| `Explorer.BATCH` | `100` | fest | Stapel-/Seitengröße | einstellbar |
| `Explorer.MATCH_LIMIT` | `50` | fest | Stapel-/Seitengröße | einstellbar |
| `Explorer.PREFETCH_LIMIT` | `25` | fest | Stapel-/Seitengröße | einstellbar |
| `Feedback.MAX_LINES` | `4` | fest | Invariante/Darstellung | im Code belassen |
| `Feedback.MAX_LINES_OPEN` | `9` | fest | Invariante/Darstellung | im Code belassen |
| `FilesChatTools.MAX_ENTRIES` | `300` | fest | Cache-Aufbewahrung | einstellbar |
| `FragmentedMp4AudioOnlySource.MAX_AUDIO_RUNS` | `1_000_000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `FragmentedMp4AudioOnlySource.MAX_FRAGMENTS` | `100_000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `FragmentedMp4AudioOnlySource.MAX_READ` | `MediaStreamBridge.CHUNK_BYTES` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `FtpChatTools.MAX_ENTRIES` | `300` | fest | Cache-Aufbewahrung | einstellbar |
| `GlossaryTab.SNIPPET_CHARS` | `120` | fest | Invariante/Darstellung | im Code belassen |
| `HuggingFaceClient.MAX_DOWNLOAD_ATTEMPTS` | `5` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `InfoPopup.MAX_HEIGHT` | `600` | fest | Invariante/Darstellung | im Code belassen |
| `InfoPopup.MAX_WIDTH` | `480` | fest | Invariante/Darstellung | im Code belassen |
| `MailMessage.MAX_DEPTH` | `8` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `MarkdownMessageView.MAX_STREAM_RENDER_DELAY_MILLIS` | `2000` | fest | Timeout | einstellbar |
| `MediaStreamBridge.CHUNK_BYTES` | `256 * 1024` | fest | Stapel-/Seitengröße | einstellbar |
| `MermaidDiagramPanel.MAX_RENDER_WIDTH` | `2400` | fest | Invariante/Darstellung | im Code belassen |
| `ModelRetry.MAX` | `Duration.ofSeconds(60)` | fest | Timeout | einstellbar |
| `Mp4AudioOnlySource.MAX_BOX_BYTES` | `32 * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `Mp4AudioOnlySource.MAX_CHUNKS` | `2_000_000` | fest | Stapel-/Seitengröße | einstellbar |
| `Mp4AudioOnlySource.REMOTE_READ_BYTES` | `256 * 1024` | fest | Stapel-/Seitengröße | einstellbar |
| `NdvBrowser.SEARCH_LIMIT` | `200` | fest | Stapel-/Seitengröße | einstellbar |
| `NdvChatTools.MAX_OBJECTS` | `300` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `PageDetailPanel.BELOW_RELATIONS_LIMIT` | `1000` | fest | Invariante/Darstellung | im Code belassen |
| `PageDetailPanel.BELOW_ROWS_LIMIT` | `1000` | fest | Invariante/Darstellung | im Code belassen |
| `ParallelChoice.CUSTOM_MAX` | `9999` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `PassagesTab.PREVIEW_CHARS` | `140` | fest | Invariante/Darstellung | im Code belassen |
| `PdfPages.MAX_PIXELS` | `40_000_000d` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `PodcastExport.MAX_WAV_DATA` | `0xFFFFFFFFL - 36` | fest | Externe Protokollgrenze | dokumentieren: WAV (RIFF) zählt Längen in 32 Bit, darüber meldet der Export „bitte als Video exportieren“ |
| `PresentationPages.MAX_PIXELS` | `40_000_000d` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ProgressiveText.STEP_CHARS` | `256 * 1024` | fest | Invariante/Darstellung | im Code belassen (begrenzt nur die Arbeit je EDT-Durchlauf, nie den Inhalt) |
| `ReaderImageStore.MAX_PIXELS` | `40_000_000L` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ReadingPane.MAX_IMAGES` | `200` | fest | Bestandsgrenze | beseitigen |
| `ReadingPane.MAX_WIDTH` | `720` | fest | Invariante/Darstellung | im Code belassen |
| `RecentTerms.MAX_TERMS` | `BackButton.MAX_ENTRIES` | fest | Cache-Aufbewahrung | einstellbar: folgt dem Verlauf am Zurück-Knopf |
| `RelationTab.RELATION_LIMIT` | `TermRelations.MAX_LIMIT` | fest | Stapel-/Seitengröße | einstellbar |
| `ScreenText.MAX_ROWS` | `300` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SearchBarLayout.MAX_WIDTH` | `360` | fest | Invariante/Darstellung | im Code belassen |
| `SearchView.LIMIT` | `25` | fest | Stapel-/Seitengröße | einstellbar |
| `SearchView.MAX_LISTED_SPACES` | `3` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SearchView.MAX_NAMED_AREAS` | `3` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SearchView.MAX_TITLE_HITS` | `5` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SettingDialogs.MAX_AMOUNT` | `9_999` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SlideChanges.MAX_SAMPLES` | `5_000_000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `SlideChanges.MOVIE_HEADER_BYTES` | `512` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `SpreadsheetPreviewData.MAX_CELL_CHARACTERS` | `2048` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `SpreadsheetPreviewData.MAX_COLUMNS` | `96` | fest | Invariante/Darstellung | im Code belassen |
| `SpreadsheetPreviewData.MAX_ROWS` | `5000` | fest | Bestandsgrenze | beseitigen |
| `StoredProgramChatTools.RESPONSE_CHARS` | `18000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `StoredProgramSearchDialog.PAGE_SIZE` | `300` | fest | Stapel-/Seitengröße | einstellbar |
| `SynonymRunForm.MAX_PAIRS` | `100_000` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TabHeader.TITLE_MIN_CHARS` | `4` | fest | Invariante/Darstellung | im Code belassen |
| `TechnicalTermsPanel.MAX` | `TechnicalTerms.MAX_LIMIT` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `TermGraphView.LABEL_ROUNDS` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `TermGraphView.PARALLEL_GAP` | `22` | fest | Parallelität | einstellbar |
| `TermGraphView.WORK_BUDGET` | `24_000_000L` | fest | Modellkontext | aus Modellfähigkeit ableiten |
| `TermsTab.REMEMBERED` | `200` | fest | Cache-Aufbewahrung | einstellbar |
| `TextToolCalls.ANNOUNCE_MAX_CHARS` | `300` | fest | Invariante/Darstellung (Erkennung eines Werkzeugaufrufs als Text) | im Code belassen |
| `TextWithPicture.MAX_PICTURE_WIDTH` | `280` | fest | Invariante/Darstellung | im Code belassen |
| `TiledDiagramRenderer.MAX_TILE_SVG_SIZE` | `800.0` | fest | Invariante/Darstellung | im Code belassen |
| `VlcPcmAudioDecoder.READ_BYTES` | `64 * 1024` | fest | Stapel-/Seitengröße | einstellbar |
| `WhisperSpeechTranscriber.READ_BYTES` | `16_000 * 2 * 5` | fest | Stapel-/Seitengröße | einstellbar |
| `WordDocumentPreview.MAX_PREVIEW_CHARACTERS` | `2_000_000` | fest | Bestandsgrenze | beseitigen |

### `space-harvester-server`

| Konstante | Wert | Stand | Kategorie (vorläufig) | Behandlung |
|---|---|---|---|---|
| `ConfluenceCompatController.DEFAULT_LIMIT` | `25` | Vorgabe | Stapel-/Seitengröße | einstellbar |
| `ImportController.MAX_PACKAGE_BYTES` | `4L * 1024 * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `ProgressiveSearches.DEFAULT_MAX_RUNNING` | `256` | Vorgabe | Parallelität | einstellbar: `space-harvester.search.progressive.max-running` |
| `ProgressiveSearches.DEFAULT_MAX_WAITING` | `256` | Vorgabe | Warteschlange | einstellbar: `space-harvester.search.progressive.max-waiting` |
| `ProgressiveSearches.DEFAULT_MAX_TIME_BUDGET` | `Duration.ofMinutes(2)` | Vorgabe | Timeout | einstellbar: `space-harvester.search.progressive.max-time-budget` |
| `ProgressiveSearches.DEFAULT_RETENTION` | `Duration.ofMinutes(5)` | Vorgabe | Cache-Aufbewahrung | einstellbar: `space-harvester.search.progressive.retention` |

### `command-deck`

| Konstante | Wert | Stand | Kategorie (vorläufig) | Behandlung |
|---|---|---|---|---|
| `BetaViewHttp.MAX_REDIRECTS` | `10` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `CopybookMemberIndex.TTL_NANOS` | `Duration.ofSeconds(60).toNanos()` | fest | Cache-Aufbewahrung | einstellbar |
| `FtpProfile.DEFAULT_TIMEOUT_SECONDS` | `60` | Vorgabe | Timeout | einstellbar |
| `HttpBetaViewServer.DEFAULT_TIMEOUT` | `Duration.ofSeconds(60)` | Vorgabe | Timeout | einstellbar (erledigt): `deck.timeouts.betaview-seconds` |
| `HttpConnectTunnel.MAX_HEADER` | `16 * 1024` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `HttpEakteConnection.MAX_LOCAL_REDIRECTS` | `5` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `JobOutput.MAX_MISSES` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `JobOutput.MAX_PROBE` | `200` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `LineDiff.MAX_CELLS` | `25_000_000L` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `LoginScript.UNLOCK_TIMEOUT` | `Duration.ofSeconds(10)` | fest | Timeout | einstellbar |
| `LoopbackRelay.ACCEPT_TIMEOUT_MILLIS` | `30_000` | fest | Timeout | einstellbar |
| `MacroPlayer.AID_TIMEOUT` | `Duration.ofSeconds(10)` | fest | Timeout | einstellbar |
| `MacroPlayer.TEXT_TIMEOUT` | `Duration.ofSeconds(5)` | fest | Timeout | einstellbar |
| `MvsListingService.DEFAULT_PAGE_SIZE` | `200` | Vorgabe | Stapel-/Seitengröße | einstellbar |
| `NdvAnalysis.HIERARCHY_DEPTH` | `5` | fest | Bestandsgrenze | beseitigen |
| `NetUseLogin.DEFAULT_TIMEOUT` | `Duration.ofSeconds(60)` | Vorgabe | Timeout | einstellbar (erledigt): `deck.timeouts.sharepoint-login-seconds` |
| `OpenAmLogin.MAX_STEPS` | `25` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `OpenTermConnector.TUNNEL_TIMEOUT_MILLIS` | `20_000` | fest | Timeout | einstellbar |
| `SamlNavigator.MAX_SAML_POSTS` | `3` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `SamlNavigator.MAX_STEPS` | `30` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `TextDecoding.MAX_BYTES` | `20L * 1024 * 1024` | fest | Speicher-/Ressourcenbudget | einstellbar |

### `document-export`

| Konstante | Wert | Stand | Kategorie (vorläufig) | Behandlung |
|---|---|---|---|---|
| `ExportGraphics.MAX_PIXELS` | `2000` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `MarkdownDocxRenderer.MAX_BOOKMARK` | `40` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `MarkdownDocxRenderer.MAX_HEADING_LEVEL` | `9` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `MarkdownDocxRenderer.MAX_LEVELS` | `9` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `MarkdownDocxRenderer.MAX_PICTURE_HEIGHT_POINTS` | `620d` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `MarkdownDocxRenderer.MAX_PICTURE_POINTS` | `450d` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
| `MarkdownExportWriter.MAX_LEVEL` | `6` | fest | Protokollgrenze | dokumentieren, berücksichtigen |
| `PdfExportWriter.MAX_PAGES` | `20_000` | fest | Bestandsgrenze | beseitigen |
| `PdfSlidesWriter.MAX_PAGE_HEIGHT` | `1400f` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |

### `mainframe-languages`

| Konstante | Wert | Stand | Kategorie (vorläufig) | Behandlung |
|---|---|---|---|---|
| `SourceRelations.DDM_DEPTH` | `3` | fest | Bestandsgrenze | beseitigen |
| `SourceRelations.NATURAL_DEPTH` | `5` | fest | Bestandsgrenze | beseitigen |

### `mermaid-renderer`

| Konstante | Wert | Stand | Kategorie (vorläufig) | Behandlung |
|---|---|---|---|---|
| `BatikBBoxService.CACHE_MAX_SIZE` | `512` | fest | Cache-Aufbewahrung | einstellbar |
| `GraalJsExecutor.MAX_WARM_RENDERS` | `100` | fest | Speicher-/Ressourcenbudget | einstellbar |
| `GraalJsExecutor.TIMEOUT_SECONDS` | `600` | fest | Timeout | einstellbar |
| `MermaidRenderer.SVG_CACHE_SIZE` | `20` | fest | Cache-Aufbewahrung | einstellbar |
| `MermaidSvgFixup.TARGET_MAX_DIMENSION_PX` | `2000.0` | fest | Ressourcen- oder Ergebnisgrenze (prüfen) | prüfen |
