# document-export

Word- und PDF-Ausgabe des [Enterprise AI Client](https://github.com/aresstack/enterprise-ai-client) als eigene Bibliothek für
Space Harvester. Angelo wollte keinen zweiten, einfacheren Word-Writer: der Export von Space Harvester soll genauso
aussehen wie der des Enterprise AI Client, mit echtem Inhaltsverzeichnis, Word-Überschriften, Listen, Tabellen,
Links, Codeblöcken und Abbildungen. Diese Klassen hängen dort an der Swing-Anwendung; hier stehen sie ohne Swing.
Mermaid zeichnet mermaid-java (GraalJS, offline), SVG rastert Batik, wie im Enterprise AI Client. Der Core
(`DocxConverter`, `PdfConverter`, `MarkdownConverter`) hängt nur von diesem Modul ab.

Anders als `enterprise-ai-sources` und `comic-controls` ist das **keine unveränderte Kopie**: die Klassen hängen
upstream an `KnowledgeDocument`, am Mermaid- und SVG-Renderer der Oberfläche und an `PdfExportWriter`. Die
Anpassungen sind klein und unten einzeln aufgeführt. Der erste Commit dieses Moduls enthält die Dateien unverändert
(nur die Paketzeile geändert), der zweite die Anpassungen; `git diff` zwischen beiden zeigt genau die Abweichungen.

## Herkunft

Stand `aresstack/enterprise-ai-client@7b719e31007edf8888dca60986bff238d01e7608` (letzte Änderung der Dateien
`d027c95`), Lizenz MIT (`LICENSE`, mitkopiert).

| Hier (`com.aresstack.spaceharvester.document`) | Upstream |
| --- | --- |
| `DocxExportWriter` | `proasteion/exedra/…/exedra/document/DocxExportWriter.java` |
| `MarkdownDocxRenderer` | `proasteion/exedra/…/exedra/document/MarkdownDocxRenderer.java` |
| `ExportGraphics` | `proasteion/exedra/…/exedra/document/ExportGraphics.java` |
| `MermaidImageRenderer` | `proasteion/exedra/…/exedra/ui/markdown/MermaidImageRenderer.java` |
| `AresStackMermaidImageRenderer` | `proasteion/exedra/…/exedra/ui/markdown/AresStackMermaidImageRenderer.java` (nur Paketzeile) |
| `SvgImageRenderer` | `proasteion/exedra/…/exedra/ui/markdown/SvgImageRenderer.java` (nur Paketzeile) |
| `ExportDocumentNode` | `astu/acropolis/…/acropolis/knowledge/ExportDocumentNode.java` |
| `ExportImage` | `astu/acropolis/…/acropolis/knowledge/ExportImage.java` (nur Javadoc) |
| `PdfExportWriter` | `proasteion/exedra/…/exedra/document/PdfExportWriter.java` |
| `MarkdownExportWriter` | `proasteion/exedra/…/exedra/document/MarkdownExportWriter.java` |

## Abweichungen

- **ExportDocumentNode** ohne `KnowledgeDocument`: der Inhalt ist das Markdown selbst. Neu ist `anchor()`, die
  Sprungmarke des Kapitels.
- **ExportGraphics**: Mermaid- und SVG-Renderer werden übergeben (`standard()` hat keine,
  `rendering()` die beiden Renderer oben, so nutzt es der Core). Ohne Renderer bleibt Mermaid
  Code und SVG gilt als nicht darstellbar; beides steht in `problems()`. Neu sind `with(List<ExportImage>)` und
  `forget()`, damit ein langer Export nur die Bilder des aktuellen Kapitels im Speicher hält.
- **DocxExportWriter**: Kapitel lassen sich einzeln in Baumreihenfolge übergeben (`open(…)`, `Chapters.chapter(…)`,
  `finish(…)`); `write(root, out)` bleibt und nutzt denselben Weg. Der Inhalt jedes Kapitels verlässt das
  POI-Dokument nach dem Kapitel als XML in eine temporäre Datei und kommt beim Schreiben an seine Stelle zurück
  (Speicher, siehe `docs/CONVERTERS.md › Word`). Kapitel mit Sprungmarke bekommen eine Textmarke auf der Überschrift.
  Autor ist „Space Harvester“ statt „Enterprise AI Client“. Ausgabe deterministisch: kein Erstelldatum, feste
  Zeitstempel der Archiveinträge. Kein `ExportWriter`-Interface (es gibt nur das Word-Format).
- **MarkdownDocxRenderer**: `isMermaid` liegt hier statt in `PdfExportWriter`. Links `#<anker>` springen zur
  Textmarke des Kapitels (`bookmark(…)`); inline `<br>` (Zeilenumbruch in Tabellenzellen konvertierter
  Confluence-Seiten) wird ein Zeilenumbruch statt Text; eine leere Kopfzeile (Confluence-Tabelle ohne Kopf) entfällt.

- **PdfExportWriter**: PDFBox 3 statt 2 (Schriften über `Standard14Fonts`). Kapitel lassen sich wie bei Word einzeln
  übergeben (`open(title, chapters, anchors)`, `Chapters.chapter(…)`, `finish(out, documentId)`); die Inhaltsseiten
  werden aus der angekündigten Kapitelzahl vorab reserviert. Seiteninhalte gehen in eine temporäre Datei
  (`IOUtils.createTempFileOnlyStreamCache`), `maxPages` beendet ein zu großes Dokument
  (`PageLimitExceededException`). Wahlweise eingebettete TrueType-Schrift statt Helvetica. Links `#<anker>` springen
  zum Kapitel. Ein Bild in einer Tabellenzelle ist „Abbildung nicht verfügbar“ beziehungsweise „Abbildung in der
  Tabelle nicht dargestellt“ statt „[Bild: …]“. Inline `<br>` und leere Kopfzeile wie im Word-Renderer. Lange Wörter
  werden in linearer statt quadratischer Zeit umbrochen (ein Wort mit 200 000 Zeichen dauerte sonst Minuten).
  Feste Dateikennung vom Aufrufer, kein Datum; Ersteller „Space Harvester“.
- **MarkdownExportWriter**: Kapitel einzeln (`chapter(title, anchor, markdown, images, depth)`), mit
  `<a id="…"></a>` vor der Überschrift als Ziel von Links `#<anker>`; Leerzeilen im Inhalt (Codeblöcke) bleiben. Ein
  unbekanntes Bild, das keine Webadresse ist, wird „Abbildung nicht verfügbar“ und gemeldet (wie in Word und PDF;
  upstream blieb es stehen). Kein `ExportWriter`-Interface.

Verbesserungen, die beiden Seiten nützen (Textmarken, `<br>`, leere Kopfzeile, Speicher), gehören auch in den
Enterprise AI Client; dort ändert sie ein eigener PR, nicht dieses Modul.

## Abhängigkeiten

Apache POI `poi-ooxml` (hier 5.5.1, upstream 5.2.3 wegen der Tika-Bindung) und flexmark 0.62.2 mit Tabellen,
Autolinks und Durchgestrichen, dieselbe Fassung wie upstream. Eigene Publikation
`com.aresstack:space-harvester-document-export`.
