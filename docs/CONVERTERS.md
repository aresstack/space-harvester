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
| `markdown` | `pages.md` | alle Seiten in einer Markdown-Datei (CommonMark mit GFM-Tabellen) |
| `docx` | `pages.docx` | Word-Dokument mit Inhaltsverzeichnis, Space und Seiten als Kapitelhierarchie (Abschnitt „Word“) |
| `pdf` | `pages.pdf` | PDF (A4) mit sichtbarem Inhaltsverzeichnis, Seitenzahlen und Lesezeichen; dasselbe Kapitelmodell wie Word (Abschnitt „PDF“) |

## Gemeinsame Regeln

- **Nur aus dem Cache.** Ein Converter sieht über `ConversionInput` die exportierten Seiten einer Cache-Generation und
  ihren ID-Baum. Er ruft nie Confluence auf und ändert weder Cache noch Rohdaten.
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
- **Kein Ergebnis-Cache in V1.** Jeder Export erzeugt seine Dateien neu. Wenn ein Cache kommt, ist sein Schlüssel der
  Input-Hash plus Converter-Version und Parameter.

Markdown, Word und PDF folgen demselben Kapitelmodell (`ExportChapters`): Kapitel 1 ist der Space, jede Seite ein
Unterkapitel nach ihrer Tiefe im Baum (Markdown: `#` Space, `##` Wurzelseiten …, tiefer als sechs Ebenen eine fette
Zeile; Abschnitte „Word“ und „PDF“); jede Seite mit Seiten-ID, Version und Link „In Confluence öffnen“. Verweise auf Seiten desselben Exports springen innerhalb der Datei
zur Seite (Markdown: `#page-<id>`, Word: Textmarke `page_<id>`, PDF: Sprungziel).

Word und PDF sind deterministisch: feste Zeitstempel im Archiv und kein Erstelldatum beziehungsweise feste
Dateikennung, keine Datumsangaben.
Das PDF nutzt ohne Konfiguration Helvetica und Courier (WinAnsi, westeuropäische Zeichen); andere Zeichen erscheinen
als `?`. Mit `space-harvester.export.pdf.font=/pfad/zur/Schrift.ttf` wird eine TrueType-Schrift eingebettet, die alle
ihre Zeichen darstellt (fett und kursiv dann ohne eigenen Schnitt).

## Word

`pages.docx` schreibt der Word-Exporter des Enterprise AI Client (`DocxExportWriter`, `MarkdownDocxRenderer`,
`ExportGraphics` auf Apache POI), als angepasste Kopie im Modul `:document-export` (Herkunft und Abweichungen in
[document-export/UPSTREAM.md](../document-export/UPSTREAM.md)). Der Core hängt nur von diesem Modul ab, nie von der
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
- **Bilder und Diagramme.** Eingebettet wird nur, was der Datenbestand abgelegt hat (`ExportAssets`, beschafft vom
  Auftrag „Bilder beschaffen“ nach dem Herunterladen): Anhänge und Bilder der Lese-Ansicht mit Status `STORED`. Alles
  andere (fehlend, keine Berechtigung, zu groß, nicht beschafft, fremde Adresse ohne abgelegte Kopie) gilt nicht als
  eingebettet: an seiner Stelle steht kursiv „[Abbildung nicht verfügbar: … – Grund]“, nie ein Platzhalter, der wie ein
  Bild aussieht. Kein Konverter ruft die Quelle oder fremde Adressen ab. Mermaid wird aus seiner Textquelle gezeichnet
  (mermaid-java, offline), SVG-Anhänge über Batik gerastert; PlantUML bleibt Codeblock, Gliffy erscheint als Hinweis wie
  unten. draw.io zeichnet der Diagramm-Dienst des Core (`Diagrams`, #190) aus den abgelegten Quelldaten, mit derselben
  JGraphX-Anpassung wie der Enterprise AI Client; was JGraphX nicht vollständig darstellen kann, ersetzt die abgelegte
  PNG-Vorschau des Plugins.
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
  Bilder, die nur als Anhang oder Adresse vorliegen, gelten nicht als eingebettet: an ihrer Stelle steht
  „[Abbildung nicht verfügbar: …]“, auch in Tabellenzellen.
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
| draw.io (auch `inc-drawio`) | gezeichnet aus den abgelegten Quelldaten (JGraphX, `Diagrams`, #190), sonst die abgelegte Vorschau des Plugins, sonst „[Abbildung nicht verfügbar: Diagramm (draw.io) …]“ |
| Gliffy | Hinweis mit Diagrammnamen: die Quelle ist ein Seitenanhang und nicht Teil der Payload |
| `include`, `excerpt-include` | Hinweis auf die eingebundene Seite |
| `toc`, `children` und andere berechnete Makros | Hinweis, dass Confluence sie berechnet |
| andere Makros | Hinweis mit Makronamen; vorhandener Text- oder Rich-Text-Inhalt folgt |

Diagramme sind nach Möglichkeit textuell. Eine Bildbeschreibung durch ein KI-Modell ist verlustbehaftet und gehört
nicht zu den Convertern, sondern später zu den Enrichments (#29). Anhänge mit draw.io-XML oder SVG kommen mit der
Anhangsliste (#9/#19).
