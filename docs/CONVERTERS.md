# Ausgabe-Converter (#30)

Converter stehen ganz am Ende der Pipeline. Ein Export schreibt immer zuerst das Raw-First-Artefakt (`pages.ndjson`,
`raw/`, `tree.json`, `manifest.json`). Nur wenn der Aufruf `format=…` nennt, entstehen danach aus denselben Seiten
zusätzliche Dateien:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/sources/wiki/spaces/DEV/exports?format=markdown&format=json"
```

| `format` | Datei | Inhalt |
|---|---|---|
| `json` | `pages.json` | JSON-Array, ein Objekt je Seite: `header`, `depth`, `revision`, `rawSha256`, `content` (native Payload) |
| `json-package` | `export.zip` | `pages.json` wie `json`, dazu `assets.json` (Zuordnung Seite → Datei, Medientyp, `sha256`, Status, relativer Pfad) und `assets/<Seiten-ID>/` mit Bildern, draw.io-Originalen, gezeichneten Grafiken und `*.graph.json` (Knoten und Verbindungen); dazu `attachments.json` (Format `attachments/v1`: je Dokument-Anhang `pageId`, `attachmentId`, `title`, `mediaType`, `size`, `sha256`, `status`, `reason`, `path`, `textPath`) und `attachments/<Seiten-ID>/` mit dem Original und seinem Text als `<Datei>.txt` (ohne lesbaren Text `textPath: null`, Grund `text-unavailable`; ausgeschlossene Anhänge ohne Dateien, Einstellung `export.attachments.*`); seit `json-package/3` außerdem `raw/` (Payload jeder Seite Byte für Byte), `views/` (Lese-Ansicht mit Makro-Inhalten, sofern abgelegt), `tree.json` (Baum mit Geschwister-Reihenfolge) und `manifest.json` (Abschnitt „JSON-Paket als Datenbestand“); keine Base64-Einbettung ([ADR 0018](adr/0018-medienbeschaffung-und-otp-im-export.md), [ADR 0023](adr/0023-dateianhaenge-im-export.md)); Version `json-package/3` |
| `markdown` | `pages.md` | alle Seiten in einer Markdown-Datei (CommonMark mit GFM-Tabellen) |
| `markdown-package` | `export-markdown.zip` | `pages.md` wie `markdown`, die Abbildungen aber als Dateien `assets/<sha256>.png` relativ verlinkt statt als `data:`-URI; jede Abbildung einmal ([ADR 0018](adr/0018-medienbeschaffung-und-otp-im-export.md)) |
| `docx` | `pages.docx` | Word-Dokument mit Inhaltsverzeichnis, Space und Seiten als Kapitelhierarchie (Abschnitt „Word“) |
| `pdf` | `pages.pdf` | PDF (A4) mit sichtbarem Inhaltsverzeichnis, Seitenzahlen und Lesezeichen; dasselbe Kapitelmodell wie Word (Abschnitt „PDF“) |
| `analysis` | `analysis.json` | Analysedaten der exportierten Seiten, getrennt von den Originaldaten (#359, Abschnitt „Analyse-Export“) |

## Gemeinsame Regeln

- **Nur aus dem Datenbestand.** Ein Converter sieht über `ConversionInput` die exportierten Seiten einer
  Cache-Generation, ihren ID-Baum, die beim Herunterladen abgelegten Bilder (`assets()`, aus `assets.json`) mit den
  gezeichneten Diagrammen (`assets().rendition(entry)`) und die Lese-Ansicht je Seite (`readingView(pageId)`, aus
  `views/`, #218) mit dem Inhalt ausgeführter Makros (`include`, `excerpt-include`, `children`). Er ruft nie Confluence
  oder fremde Adressen auf und ändert weder Cache noch Rohdaten: einmal beschaffen, beliebig oft konvertieren. Ohne
  verfügbare Lese-Ansicht bleibt der Hinweis auf das Makro, gemeldet als `OMITTED_CONTENT`.
- **Linear in Baumreihenfolge.** Konvertierte Dateien folgen dem Baum in Tiefensuche (Geschwister wie in `tree.json`).
  `pages.ndjson` bleibt nach Seiten-ID sortiert. Die Hierarchie wird nie in die Seitenliste eingebettet.
- **Nachvollziehbar.** `manifest.json` nennt unter `conversions` je Datei Format, Medientyp, Converter-Version und den
  Input-Hash `conversion-input/v1`. Der Hash ist SHA-256 über je eine Zeile
  `depth TAB pageId TAB metadataHash TAB rawSha256 LF` in Baumreihenfolge. Gleicher Hash und gleiche Version ergeben
  dieselbe Datei.
- **Unverändert gelesen.** Hat sich eine Seite seit dem ersten Durchlauf im Cache geändert, scheitert der Export
  (`CHANGED_DURING_EXPORT`), statt zwei Stände zu mischen.
- **Grenzen.** Jede konvertierte Datei darf höchstens `space-harvester.export.max-converted-bytes` groß werden
  (Standard 512 MiB). Wird die Grenze überschritten, scheitert der Export mit `CONVERSION_TOO_LARGE`, nichts wird
  abgeschnitten.
- **Rechte.** Konvertierte Dateien liegen im selben Artefakt. Sie sind nur über dieselbe Download-Route erreichbar,
  mit den Freigaben `export` und `content` und nur für den Client, der den Export gestartet hat.
- **Fehlendes erkennbar melden.** Fehlt ein Bild, ein Diagramm oder ein Anhang im Datenbestand oder lässt es sich
  nicht darstellen, nennt das Dokument die Stelle, und der Converter meldet sie mit
  `ConversionInput.report(ConversionNotice(pageId, kind, reference, reason))` (`MISSING_IMAGE`, `MISSING_DIAGRAM`,
  `MISSING_ATTACHMENT`, `OMITTED_CONTENT`; `reason` etwa der Status aus `assets.json`). Die Konvertierung aus dem
  Datenbestand liefert sie in `ConvertedFile.notices()` und `noticeCount()` (gleiche Hinweise einmal, höchstens 1000
  in der Liste), der REST-Download in `X-Conversion-Notices` (Zahl) und `X-Conversion-Notice-Kinds`
  (`missing-image=2, missing-diagram=1`).
- **Kein Ergebnis-Cache in V1.** Jeder Export erzeugt seine Dateien neu. Wenn ein Cache kommt, ist sein Schlüssel der
  Input-Hash plus Converter-Version und Parameter.

## JSON-Paket als Datenbestand

`export.zip` (`json-package/3`) enthält den Datenbestand eines Exports verlustfrei, damit ein anderes Programm oder
eine andere Space-Harvester-Installation ihn ohne Quelle wieder einlesen kann. `pages.json` bleibt unverändert die
lesbare Fassung (Inhalt inhaltlich gleich, nicht bytegleich; Antworten, die kein JSON sind, dort `content: null`).
Dazu kommen:

- `raw/<Seiten-ID>.json` (sonst `.bin`): die Payload jeder Seite Byte für Byte; ihr SHA-256 ist `rawSha256` aus
  `pages.json`. Unsichere Seiten-IDs heißen `id-<Hash>`.
- `views/<Seiten-ID>.json`: die beim Herunterladen abgelegte Lese-Ansicht zur exportierten Version
  (`space-harvester/export-reading-view/v1`, wie im Exportverzeichnis), sonst fehlt die Datei.
- `tree.json`: `roots`, je Knoten `id`, `url`, `children` in der Geschwister-Reihenfolge des Baums.
- `manifest.json` (`space-harvester/export-package/v1`): `converter`, `pageSnapshotSchema`, `sourceId`, `spaceKey`,
  `spaceName`, `rootPageId`, `pageCount`, je Seite in Baumreihenfolge `pageId`, `resourceId`, `depth`, `revision`,
  `metadataHash` und `attributesHash` (der Kopfdaten aus `pages.json`, zum Prüfen), `retrievedAt`, `mediaType`, `nativeFormat`, `rawSha256`, `size`, `raw`, `view`
  (Pfad oder `null`) und unter `assets` das vollständige Bildmanifest des Datenbestands
  (`space-harvester/export-assets/v1`, `null` ohne Beschaffung). Die Dateien dazu findet ein Import über `sha256` in
  `assets.json` und `attachments.json`.

Nicht im Paket: ältere Revisionen, Abgleichstand, Analysen und Fakten (Analyse-Export), Glossar,
Begriffsbeziehungen, Suchindex und Embeddings; ein Import rechnet sie neu oder beginnt ohne sie.

### Import

`Harvester.packageImport()` (`ExportPackageImport`, Auftrag `IMPORT`) liest ein solches Paket in den Cache einer
eingerichteten Quelle ein, auch auf einem Rechner ohne Zugang zur Quelle. Der Import läuft wie ein Abgleich mit dem
Paket statt der Quelle: Die Seiten des Pakets sind die Inventur, `raw/` der Abruf; er bestätigt eine neue Generation
des Space und meldet sie wie ein Abgleich an Volltext und Anreicherungen. `changesSince` der Generation ist der
früheste Abruf im Paket, der nächste Abgleich mit der Quelle holt alles Neuere nach. Ist die Payload einer Seite
älter als ihre Kopfdaten (nach einem Abgleich nur der Kopfdaten), kommt die Seite mit der Version ihrer Payload in den
Cache; der nächste Abgleich lädt sie dann neu.

- **Prüfung.** Gelesen wird je ZIP-Eintrag über seinen Namen, nie entpackt. Kopfdaten müssen zu `metadataHash` und
  `attributesHash` passen, jede Payload zu `rawSha256`, jede Datei zu ihrem `sha256`; Payloads höchstens so groß wie
  der Cache sie annimmt, Dateien wie die Asset-Ablage; jede Seite muss im Space des Pakets liegen. Ein Paket vor
  `json-package/3` lehnt der Import ab (`import-unsupported`), ein verändertes scheitert (`import-damaged`), ohne
  etwas zu übernehmen.
- **Ziel.** Die Zielquelle wählt der Aufrufer (meist die Quelle des Pakets); die Hashes werden für sie neu berechnet.
  Ein leerer Space nimmt jedes Paket auf. Hat der Space schon Daten, ersetzt nur ein Paket des ganzen Space sie und
  nur auf ausdrücklichen Wunsch (`replace`): dann gilt das Paket auch bei gleicher Version, Seiten außerhalb werden
  entfernt (`import-space-not-empty`, `import-subtree-into-filled-space`). Entschieden und geplant wird auf demselben
  bestätigten Stand; bestätigt ein anderer Lauf währenddessen eine Generation, scheitert der Import
  (`import-conflict`), ohne etwas zu übernehmen.
- **Dateien.** Bilder, Anhänge und gezeichnete Diagramme kommen unter denselben Kennungen (Anhang, Version) in die
  Asset-Ablage wie beim Beschaffen, Texte der Anhänge unter der eingestellten Extraktion, Lese-Ansichten in ihre
  Ablage (ohne Bildliste). Ein neuer Export mit Bildern fragt die Quelle weiterhin nach den Anhängen der Seiten; die
  abgelegten Dateien lädt er dann nicht noch einmal. Volltext und Anreicherungen erfahren von der neuen Generation
  auch, wenn danach eine Datei scheitert oder der Auftrag abgebrochen wird.
- `Preview` (`preview(file, sourceId)`) nennt vorab Quelle, Space, Seiten- und Dateizahl und ob der Space Daten hat.

## Analyse-Export (#359)

`format=analysis` schreibt `analysis.json` (Schema `space-harvester/analysis-export/v1`, Converter `analysis/3`):
alle Analysedaten der exportierten Seiten, getrennt von den Originaldaten. Am sinnvollsten auf Abruf über die
Konvertierung eines fertigen Exports (`ExportConversions.convert(…, "analysis", …)`, App: Knopf „Analyse“); dann
entsteht die Datei erst beim Klick.

```json
{ "format": "space-harvester/analysis-export/v1", "sourceId": "wiki", "spaceKey": "DEV", "rootPageId": null,
  "processors": [{ "id": "acronyms", "version": "2" }, { "id": "terms", "version": "1" }],
  "pages": [{ "sourceId": "wiki", "spaceKey": "DEV", "pageId": "100", "title": "…", "url": "…",
              "parentPageId": null, "depth": 0, "revision": "3", "rawSha256": "sha256:…", "inputSha256": "sha256:…",
              "enrichments": {
                "acronyms": { "state": "CURRENT", "processorVersion": "2", "currentProcessorVersion": "2",
                              "inputSha256": "sha256:…", "revision": "3", "computedAt": "…", "warnings": [],
                              "errorCode": null, "payload": { … } },
                "outline":  { "state": "MISSING", "processorVersion": null, …, "payload": null } },
              "facts": [{ "id": "…", "type": "ACRONYM_DEFINITION", "role": "DEFINES", "origin": "AUTOMATIC",
                          "review": "UNREVIEWED", "state": "CURRENT", "processorId": "acronyms",
                          "processorVersion": "2", "inputSha256": "sha256:…", "revision": "3",
                          "payload": { … }, "arguments": [{ "role": "SHORT_FORM", "ordinal": 0, "value": "api" }],
                          "anchor": { "section": null, "charStart": 4, "charEnd": 7 } }],
              "images": [{ "attachmentId": "att1", "title": "plan.png", "mediaType": "image/png",
                           "sha256": "sha256:…", "describer": "openai-compatible-vision-v1", "modelVersion": "…",
                           "createdAt": "…", "description": "…" }] }],
  "attachments": [{ "pageId": "100", "attachmentId": "att7", "title": "bericht.pdf",
                    "mediaType": "application/pdf", "sha256": "sha256:…", "status": "STORED", "reason": null,
                    "text": "…" },
                  { "pageId": "100", "attachmentId": "att8", "title": "film.mp4", "mediaType": "video/mp4",
                    "sha256": null, "status": "EXCLUDED", "reason": "…", "text": null }] }
```

`images` nennt jedes exportierte Bild der Seite (`assets.json`), für dessen Bytes das eingestellte Vision-Modell schon
eine Beschreibung abgelegt hat, auch ein einzeln beschriebenes ohne Bildanalyse der Seite (ADR 0021). Über `sha256`
gehört sie genau zu den exportierten Bytes; ohne beschaffte Bilder oder ohne Beschreibung bleibt die Liste leer.
Beschrieben wird dabei nichts.

`attachments` (ADR 0023, seit `analysis/3`, Schema additiv) steht auf oberster Ebene neben `pages`, nicht in den
Seitenobjekten: je Dokument-Anhang (PDF, Office, Mail …) einer exportierten Seite in der Reihenfolge von
`attachments.json` `pageId`, `attachmentId`, `title`, `mediaType`, `sha256` und der beim Beschaffen gelesene `text`.
`text` gibt es nur für abgelegte Anhänge (`status` `STORED`) mit nicht leerem Text; sonst ist er `null`, und `status`
und `reason` sagen warum wie in `attachments.json` (`text-unavailable` bei abgelegtem Anhang ohne lesbaren Text,
`sha256` `null`, wenn nichts abgelegt ist). Anhänge von Seiten außerhalb des Exports fehlen; ohne beschaffte Anhänge
ist die Liste leer. Die Texte liest der Converter je Eintrag erst beim Schreiben aus der Ablage, ohne sie zu sammeln;
gelesen oder umgewandelt wird dabei nichts Neues.

- **Zuordnung.** Je Seite `sourceId`, `spaceKey`, `pageId`, die exportierte `revision` und `rawSha256` wie in
  `pages.json`; `inputSha256` ist der Eingabe-Hash dieser Version, wie ihn die Prozessoren bekämen. Seiten linear in
  Baumreihenfolge, die Hierarchie nur über `parentPageId`.
- **Ein Abschnitt je Prozessor** unter `enrichments`, Schlüssel ist die Prozessor-ID: erst die aktuellen Prozessoren,
  die für die Seite gelten, in Ausführungsreihenfolge, dann gespeicherte Ergebnisse nicht mehr vorhandener Prozessoren
  nach ID. Neue Prozessoren (Begriffe, Passagen, Bildbeschreibungen) erscheinen ohne Schemaänderung. `payload` ist das
  JSON des Prozessors unverändert aus der Ablage; Bezüge darin (etwa Asset-ID und SHA-256) bleiben, wie der Prozessor
  sie schreibt.
- **Zustand** gegenüber der exportierten Version: `CURRENT` (gleicher Eingabe-Hash, aktuelle Prozessorversion),
  `STALE` (andere Fassung oder Prozessorversion, oder Prozessor nicht mehr vorhanden: `currentProcessorVersion` null),
  `FAILED` (`errorCode`, `payload` null), `MISSING` (nichts gespeichert). Nichts wird nachgerechnet, keine Ablage
  verändert; fehlende Ergebnisse berechnet der übliche Anreicherungslauf.
- **Fakten** (#182) sind die aus den Ergebnissen abgeleiteten `MetadataFact`s der Seite mit Herkunft
  (`processorId`, `processorVersion`, `inputSha256`, `revision`), dazu manuelle (`origin` `MANUAL`, `processorId`
  `manual`) und der Prüfstatus `review` wie im Faktenblatt. Sie kommen aus dem heutigen Faktenspeicher; `state` sagt
  wie bei den Abschnitten, ob ein Fakt zur exportierten Version gehört (`CURRENT`) oder nicht (`STALE`, etwa nach
  einem neueren Abgleich oder einer neuen Prozessorversion): automatische Fakten bei gleichem `inputSha256` und
  `CURRENT`-Abschnitt ihres Prozessors, manuelle bei gleichem `inputSha256`.
- Die Lese-Ansicht (`reading-view`) ist keine Analyse und fehlt; Suchläufe und Begriffsbeziehungen sind nicht
  seitenbezogen und gehören nicht in diese Datei.
- Anders als die übrigen Formate hängt die Datei außer vom Datenbestand vom Stand der Anreicherungen ab: Gleicher
  Input-Hash ergibt dieselben Seiten, aber nicht unbedingt dieselben Abschnitte.

## Gemeinsamer Dokumentbaum

Markdown, Word und PDF sind drei Ausgaben desselben Dokumentbaums: `ExportChapters` im Core baut ihn aus Seiten,
Baum und Bildern des Datenbestands (`pages` + `tree` + `assets`), die Writer im Modul `:document-export` (Kopien der
Exporter des Enterprise AI Client ohne Swing, [document-export/UPSTREAM.md](../document-export/UPSTREAM.md)) geben
ihn nur aus. Keiner der drei interpretiert die Seiten selbst.

- **Kapitel.** Kapitel 1 ist der Space, betitelt mit seinem Namen aus der Quelle (`ConversionInput.spaceName()`; ohne
  Namen „Space <Schlüssel> (<Quelle>)“), jede Seite ein Unterkapitel nach ihrer Tiefe im Baum, linear in
  Baumreihenfolge (Markdown: `#` Space, `##` Wurzelseiten …, tiefer als sechs Ebenen eine fette Zeile; Abschnitte
  „Word“ und „PDF“). Jede Seite beginnt mit Seiten-ID, Version und Link „In Confluence öffnen“. Verweise auf Seiten
  desselben Exports springen innerhalb der Datei zur Seite (Markdown: `#page-<id>`, Word: Textmarke `page_<id>`,
  PDF: Sprungziel). Bei einem Teilbaum oder einer Einzelseite (`rootPageId` im Manifest, #186) ist wie bei
  `KnowledgeExportUseCase.subtree`/`page` im Enterprise AI Client die Startseite Kapitel 1 (Titel des Dokuments,
  davor kein Space-Kapitel), ihre Unterseiten folgen eine Ebene darunter; für Word und PDF gilt dasselbe. Den
  Seitentitel wiederholt der Core nicht als Überschrift im Kapitelinhalt (gewollte Abweichung,
  [PARITY.md](PARITY.md)).
- **Inhalt.** Aus `body.storage.value` über dasselbe Markdown für alle drei Formate (Tabelle unten).
- **Bilder.** Eingebettet wird nur, was der Auftrag „Bilder beschaffen“ nach dem Herunterladen abgelegt hat
  (`ExportAssets`, Status `STORED`, SHA-geprüft): Anhänge und Bilder der Lese-Ansicht; in Markdown als `data:`-URI.
  Alles andere (fehlend, keine Berechtigung, zu groß, nicht beschafft, fremde Adresse ohne abgelegte Kopie) gilt
  nicht als eingebettet: an seiner Stelle steht kursiv „[Abbildung nicht verfügbar: …]“ mit Grund, auch in
  Tabellenzellen, nie ein Platzhalter, der wie ein Bild aussieht. SVG rastert Batik.
- **Diagramme.** Der Diagramm-Dienst des Core (`application.diagrams.Diagrams`, #190) zeichnet offline mit denselben
  Renderern wie der Enterprise AI Client: Mermaid aus dem Diagrammtext der Seite (mermaid-java; Markdown behält
  zusätzlich den Codeblock), draw.io aus den abgelegten `mxfile`-Quelldaten (JGraphX, auch `inc-drawio` von einer
  anderen Seite). Was JGraphX nicht vollständig darstellen kann, ersetzt die abgelegte PNG-Vorschau des Plugins.
  Ist das Diagramm beim Beschaffen gezeichnet, wird die Vorschau gar nicht erst geladen (`REDUNDANT` in
  `assets.json`, [ADR 0018](adr/0018-medienbeschaffung-und-otp-im-export.md)).
  PlantUML bleibt Codeblock, Gliffy ein Hinweis. Die textuelle Quelle bleibt für Suche und KI maßgeblich.
- **Hinweise.** Jede Abbildung, die das Dokument als nicht verfügbar zeigt, und jedes Diagramm, das sich nicht
  zeichnen lässt, meldet der Converter einmal als `ConversionNotice` (Regel oben): `MISSING_IMAGE` mit Anhangname oder
  Adresse ohne Query und Benutzerangaben, `MISSING_DIAGRAM` mit Diagrammname oder `mermaid`; Grund `missing`,
  `forbidden`, `too-large`, `unavailable`, `sign-in-required` (Einmalcode nicht eingegeben, ADR 0018),
  `not-collected` oder `render-failed`.
- **Parität.** Maßstab ist der Enterprise AI Client ([PARITY.md](PARITY.md)); `:parity-tests` vergleicht die drei
  Formate mit dessen Exporten, bekannte Lücken stehen in `parity-tests/src/test/resources/known-gaps.properties`.

Word und PDF sind deterministisch: feste Zeitstempel im Archiv und kein Erstelldatum beziehungsweise feste
Dateikennung, keine Datumsangaben.
Das PDF nutzt ohne Konfiguration Helvetica und Courier (WinAnsi, westeuropäische Zeichen); andere Zeichen erscheinen
als `?`. Mit `space-harvester.export.pdf.font=/pfad/zur/Schrift.ttf` wird eine TrueType-Schrift eingebettet, die alle
ihre Zeichen darstellt (fett und kursiv dann ohne eigenen Schnitt).

## Word

`pages.docx` schreibt der Word-Exporter des Enterprise AI Client (`DocxExportWriter`, `MarkdownDocxRenderer`,
`ExportGraphics` auf Apache POI) im Modul `:document-export`. Der Core hängt nur von diesem Modul ab, nie von der
Swing-Anwendung.

- **Kapitelmodell.** Kapitel 1 ist der Space (Formatvorlage Überschrift 1), jede Seite ein Unterkapitel nach ihrer
  Tiefe im Baum: Wurzelseiten Überschrift 2, ihre Kinder Überschrift 3 und so weiter bis Überschrift 9, tiefer fett.
  Überschriften im Seiteninhalt liegen unter der Kapitelüberschrift ihrer Seite. Die Reihenfolge ist die
  Baumreihenfolge aus `tree.json`; der Navigationsbereich von Word zeigt damit den Seitenbaum.
- **Inhaltsverzeichnis.** Hat der Export mindestens eine Seite, steht vorn ein echtes Feld `TOC \o "1-9" \h \z \u`
  über alle Überschriftenebenen, und das Dokument weist Word an, die Felder beim Öffnen zu aktualisieren
  (`updateFields`). Bis dahin steht dort ein Hinweis (F9). LibreOffice aktualisiert das Feld erst über
  „Extras › Aktualisieren“.
- **Inhalt.** Aus dem Storage-Format über dasselbe Markdown wie `pages.md`: Listen als Word-Listen (jede nummerierte
  Liste beginnt bei 1), Tabellen als Word-Tabellen mit wiederholter Kopfzeile, Links als Hyperlinks (nur
  `http(s)`/`mailto`), Code als schattierter Block in Consolas, Infokästen als Zitat. Jede Seite trägt eine Textmarke
  `page_<id>`; Verweise auf Seiten desselben Exports springen dorthin.
- **Bilder und Diagramme** wie im Abschnitt „Gemeinsamer Dokumentbaum“.
- **Speicher.** Apache POI hält ein Dokument als XML-Objekte im Speicher, gemessen etwa 170 KiB je Seite mit 3 KB
  Markdown (2000 Seiten: 327 MiB). Deshalb verlässt der Inhalt jedes Kapitels das POI-Dokument direkt nach dem
  Kapitel als XML in eine temporäre Datei; beim Schreiben setzt der Exporter ihn an seine Stelle zurück. Im Speicher
  bleiben nur das aktuelle Kapitel, Formatvorlagen, Listennummern, Linkziele und eingebettete Bilder: gemessen etwa
  4 KiB je Seite (4000 Seiten: 18 MiB). Eine eigene Seitengrenze für Word gibt es deshalb nicht; es gilt
  `max-converted-bytes`. Die temporären Dateien liegen im Temp-Verzeichnis der JVM und werden in jedem Fall gelöscht.

## PDF

`pages.pdf` schreibt der PDF-Exporter des Enterprise AI Client (`PdfExportWriter` auf PDFBox), angepasst im selben
Modul `:document-export` (Abweichungen in [document-export/UPSTREAM.md](../document-export/UPSTREAM.md)). Word und PDF
bekommen dieselben Kapitel aus demselben Markdown (`ExportChapters` im Core).

- **Kapitelmodell** wie bei Word: Kapitel 1 ist der Space, jede Seite ein Unterkapitel nach ihrer Tiefe im Baum. Jedes
  Kapitel beginnt auf einer neuen PDF-Seite; Lesezeichen folgen derselben Hierarchie.
- **Inhaltsverzeichnis.** Hat der Export mindestens eine Seite, stehen vorn sichtbare Inhaltsseiten: jeder Titel nach
  Hierarchie eingerückt, rechts seine Seitenzahl, der Eintrag springt zum Kapitel. Die Zahl der Inhaltsseiten steht
  vorher fest (aus der Zahl der Kapitel), deshalb reicht ein Durchlauf; lange Exporte bekommen mehrere Inhaltsseiten.
- **Seitenzahlen.** Jede Seite trägt unten mittig „Seite / Gesamt“.
- **Inhalt.** Überschriften, Listen, Tabellen mit Rahmen und Kopfzeile, Codeblöcke (Courier, grau hinterlegt, lange
  Zeilen umbrochen), Zitate und Hinweise, Hyperlinks (extern als Adresse, intern als Sprung zum Kapitel der Seite).
  Bilder und Diagramme wie im Abschnitt „Gemeinsamer Dokumentbaum“.
- **Speicher.** Die Inhalte der Seiten gehen sofort in eine temporäre Datei (PDFBox-Scratch-Datei); im Speicher bleiben
  Seitenbaum, Gliederung und Verweise. Deshalb gilt weiter die Grenze von 20 000 PDF-Seiten je Datei; darüber scheitert
  nur diese Konvertierung (`CONVERSION_TOO_LARGE`). Bilder halten nur die des aktuellen Kapitels.

## Storage-Format und Formatverluste

Markdown, Word und PDF lesen den Seiteninhalt aus `body.storage.value` der nativen Payload, dem internen
Confluence-Format. Fehlt er, steht an seiner Stelle ein sichtbarer Hinweis.

| Storage-Element | Ergebnis |
|---|---|
| Überschriften `h1`–`h6` | Überschrift unter der Seitenüberschrift (Ebene = Seitentiefe + Ebene, höchstens 6) |
| Absätze, fett, kursiv, Code, Zeilenumbruch | gleichwertig; Unterstreichen, Durchstreichen, Farben und Ausrichtung entfallen |
| Listen, verschachtelt; Aufgabenlisten | Listen; Aufgaben als `[x]` / `[ ]` |
| Tabellen | GFM-Tabelle; Zellen werden einzeilig (`<br>`), verbundene Zellen und verschachtelte Tabellen entfallen |
| Makros `code`, `noformat` | Codeblock mit Sprache |
| Makros `info`, `note`, `warning`, `tip`, `panel`, `expand` | Zitatblock mit Beschriftung |
| `ac:link` auf eine Seite | Link auf deren Sprungmarke `#page-<id>`, wenn sie im Export liegt, sonst nur der Text |
| `ac:link` auf Anhang, `ac:image` | Link auf einen Anhang: Verweis `attachment:<Datei>`. Bild: eingebettet, wenn der Datenbestand es abgelegt hat (Markdown als `data:`-URI), sonst „[Abbildung nicht verfügbar: …]“ mit Grund; ein externes Bild (`ri:url`) ohne abgelegte Kopie zusätzlich mit Link „Adresse“ |
| Mermaid | gezeichnetes Diagramm (Word, PDF, Markdown) und in Markdown zusätzlich der Codeblock mit der Textquelle |
| PlantUML | Codeblock mit der Textquelle des Diagramms |
| draw.io (auch `inc-drawio`) | die beim Beschaffen gezeichnete Grafik (`rendition`), sonst gezeichnet aus den abgelegten Quelldaten (JGraphX, `Diagrams`, #190), sonst die abgelegte Vorschau des Plugins, sonst „[Abbildung nicht verfügbar: Diagramm (draw.io) …]“ |
| Gliffy | Hinweis mit Diagrammnamen: die Quelle ist ein Seitenanhang und nicht Teil der Payload |
| `include`, `excerpt-include`, `children` und jedes andere berechnete Makro (z. B. `toc`, `contentbylabel`, Jira-Abfrage) | Inhalt aus der abgelegten Lese-Ansicht: das n-te Makro eines Namens ist das n-te äußerste Element mit diesem `data-macro-name`, nur wenn Speicherformat und Ansicht gleich viele davon haben; eine Jira-Abfrage mit Tabelle wird ein eigener Block. Selbst umgesetzte Makros (Code, Diagramme, Panels, Status, einzelnes Jira-Ticket …) bleiben, wie sie sind; Unsichtbares (`display:none`, Skripte) entfällt, Bilder der Ansicht über ihre Adresse. Ohne verfügbare Ansicht Hinweis auf die eingebundene Seite bzw. die berechnete Liste, gemeldet als `OMITTED_CONTENT` (Verweis: Seitentitel oder `children`; Grund `not-collected`, `foreign-content`, `no-reading-view`, `page-changed`, `source-unavailable`, `not-rendered`, `view-count-mismatch`). Andere berechnete Makros ohne Ansicht behalten ihren Hinweis ohne Meldung |
| `toc` und andere berechnete Makros | Hinweis, dass Confluence sie berechnet |
| andere Makros | Hinweis mit Makronamen; vorhandener Text- oder Rich-Text-Inhalt folgt |

Diagramme bleiben in Suche und KI textuell; gezeichnet werden sie nur für die Ausgabe. Eine Bildbeschreibung durch ein
KI-Modell ist verlustbehaftet und gehört nicht zu den Convertern, sondern zu den Enrichments (#29).
