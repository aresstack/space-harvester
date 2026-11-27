# ADR 0045: Schreibumgebung im Chat: Dokumente und Grafiken als Artefakte, die die KI abschnittsweise bearbeitet

- Status: **Angenommen** (27.11.2026) nach Angelos Vorgabe.
- Quelle: Angelos Nachrichten vom 27.11.2026, 15:44 bis 15:54 MEZ: „Wir brauchen eigentlich sowas wie einen Editor für
  die KI, dass sie halt Abschnitte überarbeiten kann. […] Sonst macht die KI das ja immer, dass sie alles komplett neu
  generiert. […] Vielleicht auch an mehreren Texten parallel, wo dann aber immer der Status gespeichert wird. […] Das
  ist im Prinzip eigentlich dasselbe, wie wenn eine KI in der IDE programmiert. […] Ich würde das Tool gerne
  aktivierbar machen, genau wie die Websuche. […] Erstmal nur als Markdown, und dann kann man ihn per Knopfdruck
  exportieren nach Word oder PDF.“ Dazu: SVG im Dokument, Grafiken als eigene Artefakte mit einfacher Leinwand,
  Markieren wie bei der Zitat-Funktion, Word-Import, Formatvorlagen beim Word-Export.
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md): Wissen des Chats per Verweis und Werkzeug
  - [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md): Projekte; Artefakte sind dort Arbeitsergebnisse
- Vorbild: Ask AI, `AgentArtifactStore` (Artefakt mit fester Kennung und Revision, derselbe Speicher für Ansicht und
  Werkzeuge). Dort gibt es nur „ganzes Dokument ersetzen“. Ein Werkzeug für Abschnitte gibt es weder in Ask AI noch
  im Enterprise AI Client; es entsteht hier neu.

## Ausgangslage

Der Chat kann heute nur in seine Antwort schreiben. Soll ein längerer Text entstehen und danach geändert werden, gibt
das Modell ihn jedes Mal ganz neu aus. Das dauert, und unbeteiligte Stellen können sich dabei ändern. Vorhanden und
wiederzuverwenden sind: die Werkzeugschleife des Chats ohne Rundengrenze (`ChatTools`, eigene Werkzeuggruppen wie
`StoredProgramChatTools` und `ModelChatTools`), die Schalter „Websuche“ und „Interne Suche“ im Eingabefeld
(`ChatPanel`), die Ablage der Chats je Bereich (`ChatStore`), die Markdown-Anzeige mit SVG, Mermaid und draw.io
(`app.ui.markdown`), die Exporte nach Markdown, Word und PDF samt Grafiken (`ChatExport`, Modul `document-export`),
die Bildgenerierung (`DiagramComposer`, Zeilen „Bildgenerierung – Plan“ und „– Einzelteile“) und die Zitat-Auswahl
(`QuoteSelection`).

## Entscheidungen

### 1. Artefakte gehören zum Chat

Ein Chat kann beliebig viele Artefakte haben. Es gibt zwei Arten: **Dokument** (Markdown) und **Grafik** (SVG). Jedes
Artefakt hat eine feste Kennung, einen Titel und eine Folge von Revisionen. Jede Änderung, ob von der KI oder vom
Benutzer, ist eine neue Revision; nichts wird überschrieben, „Rückgängig“ geht Revision für Revision zurück.

Die Artefakte liegen im Datenordner der App beim Chat (`ChatStore`) und gehen mit ihm in den Papierkorb, zurück und
in die endgültige Löschung. Ansicht und Werkzeuge der KI arbeiten auf demselben Speicher. Der Vertrag (Kennung,
Revision, Schreibergebnis, Schreiben mit erwarteter Revision für die Ansicht) folgt dem `AgentArtifactStore` aus Ask AI.

### 2. Ein Dokument ist eine geordnete Liste von Abschnitten

Ein Abschnitt hat eine feste Kennung, eine Ebene, eine Überschrift und einen Text. Der Text reicht bis zur nächsten
Überschrift, gleich welcher Ebene; Unterabschnitte sind eigene Abschnitte. Der Text vor der ersten Überschrift ist die
Einleitung. Kennungen bleiben über alle Revisionen gleich und werden nie neu vergeben. Das Markdown des Dokuments ist
die Aneinanderreihung der Abschnitte. Überschriften in Codeblöcken zählen nicht.

Dieses Modell ist reine Logik ohne Oberfläche und wird mit erfundenen Texten getestet.

### 3. Die KI bearbeitet nur über Werkzeuge, und nur abschnittsweise

Eigene Werkzeuggruppe neben `ChatTools`. Das Modell bekommt das Dokument nicht im Prompt, sondern liest es über die
Werkzeuge:

| Werkzeug | Wirkung |
|---|---|
| `dokumente_auflisten` | Artefakte dieses Chats mit Kennung, Art und Titel |
| `dokument_anlegen` | neues Dokument mit Titel, wahlweise mit erstem Text |
| `dokument_gliederung` | je Abschnitt Kennung, Überschrift, Wortzahl |
| `abschnitt_lesen` | Text eines Abschnitts |
| `abschnitt_ersetzen` | ersetzt den Text eines Abschnitts; die Überschrift bleibt |
| `text_ersetzen` | ersetzt eine wörtliche Textstelle in einem Abschnitt (für ein Wort, eine Zahl, einen Satz) |
| `abschnitt_einfuegen` | neuer Abschnitt nach einem vorhandenen |
| `abschnitt_loeschen` | löscht einen Abschnitt |
| `ueberschrift_aendern` | ändert nur die Überschrift |
| `abschnitt_verschieben` | setzt einen Abschnitt hinter einen anderen |

Regeln:

- Es gibt **kein** Werkzeug, das ein vorhandenes Dokument als Ganzes ersetzt. Unbeteiligte Abschnitte kann das Modell
  dadurch gar nicht verändern.
- Ein Werkzeug antwortet mit einer kurzen Bestätigung, nie mit dem Dokument.
- Fehlermeldungen sagen dem Modell, was zu tun ist: unbekannte Kennung nennt die vorhandenen; eine Textstelle, die
  nicht oder mehrfach vorkommt, verlangt neues Lesen oder mehr Umgebung.
- Liefert das Modell bei `abschnitt_ersetzen` die Überschrift des Abschnitts versehentlich mit, wird sie
  herausgenommen.
- Lange Texte entstehen Abschnitt für Abschnitt: erst Gliederung anlegen, dann je Abschnitt ein Aufruf. So ist jeder
  Schritt sofort sichtbar und keine Antwort läuft in die Ausgabegrenze.
- Die Schleife endet wie im übrigen Chat bei Stillstand, ohne Rundengrenze.
- Was der Benutzer sieht, sieht der Bot (ADR 0033): Die Gliederung des gerade geöffneten Artefakts geht mit jeder
  Nachricht mit, solange der Schalter an ist.
- Ändert der Benutzer zwischendurch selbst, gilt für das nächste Werkzeug der neue Stand; Kennungen bleiben gültig.

### 4. Schalter im Eingabefeld

Ein dritter Schalter „Schreiben“ neben „Websuche“ und „Interne Suche“, gleiche Form, standardmäßig aus, jederzeit
mitten im Chat umschaltbar. Nur bei eingeschaltetem Schalter bekommt das Modell die Werkzeuge aus Abschnitt 3 und 6.
Ist er aus, bleiben die Artefakte sichtbar, bearbeitbar und exportierbar; die KI ändert dann nichts daran.

### 5. Ort: Artefakt in der Mitte, Chat rechts

Ein Artefakt öffnet sich in der Mitte als schließbarer Reiter (`ClosableTab`); der Chat bleibt in der rechten
Seitenleiste. Mehrere Artefakte sind mehrere Reiter. Im Chat steht unter der Antwort, die ein Artefakt angelegt oder
geändert hat, eine Zeile mit dessen Titel; ein Klick öffnet den Reiter. Welche Reiter offen waren, merkt sich der Chat.

Die Ansicht zeigt das Dokument gerendert mit der vorhandenen Markdown-Anzeige. Ändert die KI einen Abschnitt, rollt die
Ansicht dorthin und hebt ihn hervor, bis die nächste Änderung kommt. Der Benutzer bearbeitet selbst je Abschnitt
(Stift am Abschnitt öffnet dessen Markdown). Steuerelemente und Statusmeldungen folgen den Vorgaben der übrigen App,
nichts wird neu erfunden.

### 6. Grafiken sind eigene Artefakte

- **SVG direkt in der Chat-Antwort** bleibt, wie es ist: Der Chat erkennt und rendert es (`SvgMarkdownBlocks`). Dafür
  braucht es kein Werkzeug.
- **Grafiken für Dokumente** sind Artefakte der Art Grafik. Sie entstehen auf zwei Wegen: `grafik_zeichnen` (Thema und
  Inhalt, gezeichnet vom Modell der Bildgenerierung über `DiagramComposer`, wie im Podcast und bei
  `schaubild_zeichnen`) oder `grafik_anlegen` (das Chat-Modell liefert das SVG selbst).
- **Einbinden per Verweis:** `grafik_einbinden` setzt in einen Abschnitt einen Verweis auf die Grafik, keine Kopie des
  SVG. Wird die Grafik später korrigiert, stimmt das Dokument von selbst. Anzeige und Export lösen den Verweis auf.
- Ergebnisse eines Bild-Werkzeugs erscheinen im Chat über dieselbe Anzeige wie direktes SVG, es gibt nur einen
  Renderweg.
- **Eine Einstellung für Bilder:** Podcast, Chat-Schaubilder und Schreibumgebung nutzen dieselben Zeilen
  „Bildgenerierung“. Der Podcast und der Chat tun das bereits; es kommt keine neue Zeile hinzu. Die Vorgabe bleibt
  „wie Chat-Modell“, weil der Modellname vom Dienst abhängt.
- Mermaid und draw.io sind als Blöcke im Dokument erlaubt und werden von der vorhandenen Anzeige und den vorhandenen
  Exporten gerendert. Eine Leinwand bekommen nur SVG-Grafiken.
- **Leinwand (späterer Schritt):** Elemente einer Grafik haben feste Kennungen. Der Benutzer kann Elemente auswählen,
  verschieben, in der Größe ändern, löschen und einfache Formen und Text ergänzen. Für die KI gibt es dazu
  `grafik_elemente` (Liste mit Kennungen) und `grafik_element_ersetzen`, damit auch hier nur der gemeinte Teil
  geändert wird.

### 7. Markieren und zitieren

Markiert der Benutzer eine Stelle im Dokument oder ein Element in der Grafik, hängt die vorhandene Zitat-Funktion sie
an den Chat. Es bleibt ein Mechanismus. Das Zitat trägt zusätzlich den Bezug: Artefakt und Abschnitt, bei Grafiken das
Element. Die KI bekommt diesen Bezug mit und ändert gezielt dort.

### 8. Export, Import und Formatvorlagen

- **Export:** Knopf am Reiter, Formate Markdown, Word und PDF über die vorhandenen Schreiber, Ablage im
  Ausgabeordner, benannt nach dem Titel. Grafiken zusätzlich als SVG.
- **PowerPoint und Excel** gehören nicht zum ersten Ausbau. Der vorhandene PowerPoint-Schreiber setzt Folien aus
  Bildern zusammen (Podcast), ein Excel-Schreiber fehlt. Beides sind eigene spätere Schritte.
- **Word-Import (späterer Schritt):** Ein Word-Dokument wird zu einem Dokument-Artefakt in Markdown, ohne
  Formatierung. Enthaltene Bilder werden Grafik-Artefakte. Rasterbilder bleiben zunächst Bilder; die Umwandlung in SVG
  über das Vision-Modell geschieht nur auf Klick je Bild.
- **Formatvorlagen (späterer Schritt):** Eine Formatvorlage ist eine Word-Datei, aus der Formatvorlagen, Seitenränder,
  Kopf- und Fußzeilen, Seitenzahlen und Logos stammen. Beim Import merkt sich das Dokument seine Vorlage. Beim Export
  nach Word ist eine Vorlage wählbar, für jedes Markdown, nicht nur für importierte Dokumente. Der Inhalt wird in eine
  Kopie der Vorlage geschrieben und auf deren Überschriften-, Text-, Listen- und Tabellenformate abgebildet.

### 9. Schutz

Artefakte sind KI-Ergebnisse wie Chats und liegen nur im Datenordner der App. In Logs stehen nie Texte aus
Artefakten, nur Quelle und Ausnahmeklasse. Nichts läuft ohne Klick: kein KI-Lauf beim Öffnen, Importieren oder
Exportieren.

## Messung

Geprüft mit zwei lokalen Modellen (gemma4:12b, gpt-oss:20b) an erfundenen Dokumenten, vorab festgelegt. Weg A: die
sieben Kernwerkzeuge aus Abschnitt 3. Weg B: ganzes Dokument im Prompt, ganzes Dokument zurück.

| Dokument | Modell | Weg | Aufträge erfüllt | Nebenschäden | Zeit |
|---|---|---|---|---|---|
| kurz, rund 330 Wörter | gemma4:12b | A | 6 von 6 | 0 | 28 s für alle |
| kurz | gemma4:12b | B | 6 von 6 | 0 | 46 s für alle |
| kurz | gpt-oss:20b | A | 6 von 6 | 0 | 11 s für fünf Aufträge, der sechste enthielt den Modellstart |
| kurz | gpt-oss:20b | B | 6 von 6 | 1 (Tabelle anders gesetzt) | 22 s für alle |
| lang, 2.561 Wörter | gemma4:12b | A | 3 von 3 | 0 | 3 bis 7 s je Auftrag ohne Modellstart |
| lang | gemma4:12b | B | 3 von 3 | 0 | 28 bis 45 s je Auftrag |
| lang | gpt-oss:20b | A | 3 von 3 | 0 | rund 2 s je Auftrag |
| lang | gpt-oss:20b | B | 1 von 3 | 0 | rund 28 s je Auftrag |

„Erfüllt“ ist hier gelesen gezählt; das Prüfprogramm wertete zwei Läufe falsch, weil es „z. B.“ und „am 10.“ als
Satzende zählte. Auf Weg A fielen 50 Werkzeugaufrufe an, davon ein Fehler (Einfügen ohne vorher gelesene Gliederung),
den das Modell nach der Fehlermeldung selbst behob.

Ergebnis: Die Modelle können mit den Werkzeugen umgehen. Der Gewinn gegenüber „alles neu“ lag in dieser Messung bei
Zeit und Genauigkeit; unbeteiligte Abschnitte hat auch der alte Weg bei diesen Längen nicht beschädigt. Die Zeit des
alten Wegs wächst mit der Länge des Dokuments, die des Werkzeugwegs nicht. Nicht gemessen sind Dokumente in
Buchlänge, das Schreiben eines langen Textes von Grund auf, Grafiken und gpt-oss-120b. Das Protokoll liegt außerhalb
des Repositorys.

## Umsetzung in kleinen Schritten

1. Ablage der Artefakte am Chat und Abschnittsmodell mit allen Operationen und Revisionen, ohne Oberfläche.
2. Werkzeuggruppe, Schalter „Schreiben“, Anzeige in der Mitte, Zeile im Chat. Echter Test mit einem lokalen Modell.
3. Export-Knopf, Rückgängig, Hervorhebung der letzten Änderung.
4. Bearbeiten durch den Benutzer je Abschnitt; Zitat mit Bezug auf Abschnitt.
5. Grafik-Artefakte, Zeichnen über die Bildgenerierung, Einbinden per Verweis.
6. Später, je eigener Schritt: SVG-Leinwand, Word-Import, Formatvorlagen, PowerPoint, Excel.

## Offen

- Formatvorlagen: welche Teile einer Word-Vorlage sich mit der vorhandenen Bibliothek verlässlich übernehmen lassen
  (Kopf- und Fußzeilen mit Bildern, Abschnittswechsel), ist vor dem Bau zu prüfen.
- Leinwand: welche vorhandene Java-Bibliothek die Bearbeitung von SVG-Elementen trägt.
- Mit ADR 0043 gehören Artefakte künftig zum Projekt; die Ablage am Chat ist so zu schneiden, dass sie dorthin
  umziehen kann.
