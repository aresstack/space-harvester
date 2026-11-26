# ADR 0041: Dokumentbasierte Programmanalyse und fachliches Reverse Engineering

- Status: **Angenommen als Zielbild** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in Epic #1447,
  #1470 samt Kommentaren und Epic #1461 Abschnitt G). Der COBOL-Parser ist auf main. Die gespeicherten Analysen,
  die Programmsuche und die Analyse-Reiter liegen in **PR #1488 (offen, gegen develop)**; solange er nicht gemergt
  ist, beschreibt diese ADR dort den Entwurfsstand. Was im Text mit *vorgeschlagen* gekennzeichnet ist, stammt aus
  den Antworten des ursprünglichen Chats vom 26.11.2026, 14:21, und gilt erst mit Angelos Bestätigung.
- Quellen: Epic #1447, #1470 (sechs Kommentare, darunter die Architekturentscheidungen vom 26.11., 02:05 und 02:08),
  Epic #1461 (G), #1486 (Review-Nacharbeiten) samt Kommentar, #1422, #1473, PRs #1423, #1471, #1479, #1481, #1488;
  Angelos Zusammenfassung vom 26.11.2026 (Abschnitte 13 bis 15).
- Bezüge: [ADR 0012](0012-core-als-gemeinsame-bibliothek.md) (Core als gemeinsame Bibliothek),
  [ADR 0026](0026-anhaenge-und-enthaltene-dateien-als-dokumente.md) (Dokumente),
  [ADR 0028](0028-ki-freigabe-je-seite.md) (KI-Freigabe), [ADR 0032](0032-fachlogik-aus-aussagen.md) (Fachlogik aus
  Aussagen), [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md) (Werkzeuge des Chats),
  [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md) (gemeinsamer Bestand, Identität),
  [ADR 0037](0037-quellenumfang-nutzungssteuerung-beschaffung.md) (FTP- und NDV-Quellen).

## Kontext

Ziel ist eine **lesende** Analyse-Umgebung für bestehende Mainframe-Anwendungen (COBOL, JCL, Natural), aus der sich
Geschäftsprozesse, Geschäftsregeln und Geschäftsobjekte nachvollziehbar rekonstruieren lassen. Der Quellcode ist die
technische Evidenz, nicht das Arbeitsergebnis.

Die Entscheidungen sind gewachsen. Epic #1447 (25.11.) sah einen eigenen Analysebereich im Command Deck vor
(„COBOL-Home“, eigener Projektindex, #1454 bis #1458). Die erste Programmsuche (#1474) arbeitete auf einem
sitzungsgebundenen Snapshot im FTP-Bereich. Am 26.11. hat Angelo das ersetzt: Programme sind Dokumente der
Wissensquellen, und ihre Analysen liegen im gemeinsamen Bestand. **Maßgeblich ist der spätere Stand.**

## Entscheidung

1. **Programmcode ist eine Wissensressource wie jedes andere Dokument.** Es gibt keine zweite Anwendung im Space
   Harvester, die nur Programmcode verarbeitet. Sprachspezifisch sind die Analyseprozessoren und die Darstellung
   ihrer Ergebnisse, nicht Bestand, Suche, Viewer oder Chat.
2. **Die Quellen liefern den Bestand.** FTP-Wissensquellen halten COBOL und JCL samt Copybooks und Prozeduren,
   NDV-Wissensquellen halten Natural samt Datenobjekten. Beide Beschaffungswege (ausdrückliches Indexieren und
   navigationsgetriebene Befüllung, ADR 0037) schreiben in denselben Bestand. Eine Suchanfrage fragt nie erneut FTP
   oder NDV ab.
3. **Analysen sind Artefakte am Dokument.** Parserfakten und abgeleitete Ergebnisse sind an Quellen-ID,
   Dokument-ID, Revision und Analyseversion gebunden. Sie nutzen die vorhandene Infrastruktur für
   dokumentbezogene Analysen: `EnrichmentProcessor`, `EnrichmentStore` (`LuceneEnrichmentStore`),
   Hintergrundaufträge und Neuberechnung. Es gibt **keine eigene Programmanalyse-Datenbank**, keine
   Graphdatenbank und keinen exklusiven FTP-Suchsnapshot als Ziel.
4. **Ein gemeinsamer Viewer.** FTP- und NDV-Programme erscheinen als Dokumente im Viewer des Space Harvesters. Die
   Leseansicht zeigt den originalen Programmcode sprachgerecht. Der vorhandene Oberreiter „Analysen“ bleibt; seine
   Unterreiter richten sich nach dem Dokumenttyp (Programmstruktur, Datenstrukturen, Copybooks, Aufrufe,
   Kontrollfluss, Datenfluss, Diagnosen, fachliche Analyse). Die bestehende Textanalyse bleibt unverändert.
5. **Command Deck, Viewer, Chat und KI-Werkzeuge lesen dieselben Artefakte** über dieselben lesenden
   Anwendungsfälle. Es gibt keine zweite Analyseimplementierung für die KI.
   - **Analyse-Logik, Prozessoren und Zugriffsverträge gehören in den Core** (*vorgeschlagen*, nach ADR 0012).
     Eine REST-Schnittstelle muss nicht sofort entstehen; die fachliche Logik darf aber nicht dauerhaft nur in der
     Desktop-App liegen.
   - **Ein externer MCP-Zugriff ist zurückgestellt, nicht ausgeschlossen** (*vorgeschlagen*). Der eingebaute Chat
     nutzt die lesenden Werkzeuge direkt; jetzt entsteht kein zusätzlicher MCP-Server.
6. **Parser arbeiten auf dem Original.** Parserfakten werden nie in Markdown verwandelt, um sie wieder zu parsen.
   Quellpositionen (Zeile, Spalte) bleiben erhalten. Der Parser-Kern hängt nicht von Swing ab, ProLeap-Typen
   bleiben im Adapter. Für jede Sprache gibt es genau eine Parser-Implementierung.
7. **Deterministische Analyse und KI-Interpretation sind getrennt.**

   | Stufe | Inhalt | Herkunft |
   | --- | --- | --- |
   | Technische Fakten | AST, ASG, Symbole, `CALL`, `COPY`, `PERFORM`, Datenstrukturen, Kontrollfluss, mit Quellposition und Revision | Parser (ProLeap für COBOL) |
   | KI-Hypothesen | Geschäftsregeln, Geschäftsobjekte, Prozessschritte, jeweils mit Beleg (Quelle, Revision, Zeile) | Modell, auf Grundlage der Parserfakten |
   | Fachlich bestätigt | Von einem Menschen bestätigte oder korrigierte Erkenntnis | Fachliche Prüfung |

   Die KI bekommt strukturierte Parserfakten und konkrete Belege, nicht bloß tausende Zeilen Code und nicht bloß
   Embeddings. Eine technische Bedingung ist zunächst ein Regelkandidat, ein Copybook eine technische Struktur, ein
   Aufrufgraph eine technische Beziehung; nichts davon gilt selbsttätig als Fachregel, Geschäftsobjekt oder
   Prozess. Ein Modellaufruf wird immer ausdrücklich ausgelöst.

   **Bestätigte fachliche Erkenntnisse kommen in den vorhandenen Fachlogik-Bestand** (ADR 0032), mit Programm,
   Revision, Quellstelle, Parserfakten und Analyseversion als Beleg, sofern dessen Modell das trägt
   (*vorgeschlagen*). Technische Parserfakten bleiben Artefakte am Programm. So liegen Geschäftsregeln aus Erlassen,
   Confluence und Programmcode in einem Bestand und nicht nach Herkunftssystem getrennt. Was dafür am Modell von
   ADR 0032 fehlt, steht unter F41-5.
8. **Unsicheres bleibt als unsicher sichtbar.** Dynamische Aufrufziele, nicht auflösbare Verweise, fehlende Quellen
   und Teilanalysen werden gekennzeichnet. Es gibt keine Aussage „keine Aufrufer“ oder „keine Verwendungen“, solange
   der Suchbereich nicht vollständig analysiert ist. Bei mehreren Kandidaten wählt der Benutzer mit Herkunft; es
   wird nicht geraten.
9. **Diagramme zeigen nur Belegtes.** Sie entstehen aus den nachgewiesenen Fakten, nie aus der Reihenfolge im
   Quelltext; erfundene Kanten oder ein angehängtes `STOP RUN` sind ausgeschlossen. Ohne echten Kontrollfluss fällt
   die Ablaufdarstellung sichtbar auf die Struktur zurück. Zeichnen geht sofort und ohne KI; eine KI-Verdichtung
   wird ausdrücklich angefordert und bleibt bis zur Quellstelle rückverfolgbar. Mermaid ist der Renderer für
   technische Diagramme. Draw.io kommt erst auf der höheren Ebene (Geschäftsprozesse, fachliche Dokumentation);
   einen formatübergreifenden Umbau der Renderer gibt es vorerst nicht.
10. **Identität und Gültigkeit.** Für die Ressource gilt ADR 0036, Punkt 7. Die Gültigkeit einer Analyse hängt an:
    Programmrevision (Inhalts-Hash), Parser- und Analyseversion, SYSLIB- bzw. Bibliotheksreihenfolge, Revisionen
    der aufgelösten Copybooks und den **negativen Proben** (Copybooks, die gesucht und nicht gefunden wurden). Eine
    Änderung macht die betroffenen Artefakte ungültig, nicht den ganzen Bestand.
11. **Ein Tor für Berechtigung und Aktualität.** Eine Analyse wird nur ausgeliefert, wenn der Aufrufer **jedes**
    Dokument lesen darf, aus dem sie abgeleitet ist (Programm und alle aufgelösten Copybooks), und wenn sie aktuell
    ist. Zugriff auf das Hauptprogramm allein genügt nicht. Viewer, Suche, Chat-Werkzeuge und Export benutzen
    dieselbe Prüfung. Ändert sich die Basis während einer Anfrage, wird nichts ausgeliefert (fail-closed). Ein Export
    gibt Ergebnisse mit fremden Belegen ohne Inhalt aus (`evidence-withheld`), weil er keinen Leser kennt.
12. **Strikt lesend.** Keine Schreib-, Refactoring-, Compiler-, Debugger- oder Deployment-Funktionen, keine
    Job-Ausführung durch KI, keine ungefragte Übermittlung von Quellcode an externe Dienste.
13. **Reihenfolge.** Zuerst COBOL im FTP-Bereich; Natural ist keine Voraussetzung, JCL zunächst nur mit
    unmittelbarem Bezug zu COBOL. Fachlich: (1) Sprung zur Definition und Verwendungen, (2) Aufrufhierarchie,
    (3) Datenstrukturen, (4) Kontroll- und Datenfluss, (5) fachliche Rekonstruktion. Erst die für Menschen nutzbare
    Analyse, dann die KI-Werkzeuge.

## Stand (26.11.2026)

| Baustein | Stand |
| --- | --- |
| ProLeap-COBOL-Parser, `CobolAnalysis` mit Quellpositionen, COPY/REPLACE, SYSLIB | auf main (#1423) |
| FTP- und NDV-Wissensquellen als Bestand der Programme | auf main (#1477, #1482) |
| NDV-Identität mit Typ, DBID/FNR und Konto im Deck-Cache | auf main (#1471) |
| Gespeicherte COBOL-, JCL- und Natural-Analysen als Enrichments (`CobolProgramEnrichment`, `JclProgramEnrichment`, `NaturalProgramEnrichment`), Analyse-Reiter (`ProgramAnalysisTabs`) | PR #1488 offen |
| Abhängige Analysen nach Änderung eines Copybooks neu berechnen (`DependentEnrichments`) | PR #1488 offen |
| Tor für Berechtigung und Aktualität (`ProgramAnalysisAccess`) | PR #1488 offen |
| Programmsuche und Aufrufhierarchie auf dem gespeicherten Bestand, Chat-Werkzeuge | PR #1488 offen |
| Ablaufdiagramm aus dem statischen Kontrollfluss statt aus der Absatzfolge (#1473) | PR #1488 offen |
| Fachliche KI-Analyse als Hypothese (`CobolBusinessEnrichment`, ausdrücklich ausgelöst) | PR #1488 offen |
| Zustand „fachlich bestätigt“ mit Prüfung durch Menschen | nicht umgesetzt |
| Vollständiger Kontroll- und Datenfluss (`GO TO`, Fall-through, programmübergreifend), SQL/CICS-Semantik | offen |
| Abnahme gegen echtes z/OS-FTP, NDV und KI-Dienst | offen |

PR #1488 ersetzt die älteren, offenen PRs #1456, #1458, #1473, #1474, #1478, #1479 und #1481; sie werden nach dem
Merge mit Beleg geschlossen (#1486).

## Widersprüche und Auffälligkeiten

- **Epic #1447 gegen die Entscheidung vom 26.11.** Der Epic-Text verlangt in Abschnitt A einen eigenen, persistent
  konfigurierten Analysebereich („Home-Verzeichnis“) und in Abschnitt D die Oberfläche im Command Deck. Die spätere
  Entscheidung (#1470, 02:05 und 02:08) macht daraus die Startpunkte der FTP-Quelle und Reiter im gemeinsamen
  Viewer. Der Epic-Text ist nicht nachgezogen; er soll angepasst werden (F41-4).
- **MCP.** Epic #1447 (E) und Angelos Zusammenfassung sprechen von „lesenden MCP-Tools“ für den Chat, ein
  Kommentar zu #1470 nennt den „externen MCP-Anschluss“ als offen. PR #1488 hat den externen MCP-HTTP-Server
  dagegen entfernt; der Chat nutzt die Werkzeuge direkt (#1486, P2). Nach Punkt 5 ist der externe Zugriff
  zurückgestellt.
- **Analyseinfrastruktur.** Die Tickets (#1461 G, #1470 D) nennen `AnalysisService`, `ServicePlanner` und
  `BackgroundJobs`. Umgesetzt ist es mit `EnrichmentProcessor` und `EnrichmentStore`. Das deckt sich mit Angelos
  Zusammenfassung und der Vorgabe „vorhandene dokumentbezogene Analyse-Infrastruktur“, weicht aber vom Wortlaut der
  Tickets ab.
- **Lage im Modul `space-harvester-app`.** Die Programm-Enrichments und `ProgramAnalysisAccess` liegen in PR #1488
  unter `app.runtime.program`, nicht im Core. Der Server (REST) kann sie damit nicht anbieten. Nach Punkt 5 gehören
  sie in den Core (siehe F41-6).
- **Draw.io.** Die Zusammenfassung nennt „Mermaid- oder Draw.io-Diagramme“. Die Prioritätskorrektur in #1470
  (26.11., 01:25) stellt Draw.io ausdrücklich zurück. Punkt 9 folgt der Korrektur.

## Geklärt am 26.11.2026

Stand der Antworten: **Beschlossen** gilt. *Vorgeschlagen* ist eine Empfehlung aus dem ursprünglichen Chat und gilt
erst, wenn Angelo sie bestätigt.

| Frage | Antwort | Stand |
| --- | --- | --- |
| F41-1: Externer MCP-Zugriff | Zurückgestellt, nicht ausgeschlossen; jetzt kein zusätzlicher MCP-Server | *Vorgeschlagen*, in Punkt 5 |
| F41-2: Programmanalyse in den Core | Ja: Logik, Prozessoren und Zugriffsverträge; REST nicht sofort | *Vorgeschlagen*, in Punkt 5 |
| F41-3: Ablage bestätigter Erkenntnisse | Im Fachlogik-Bestand aus ADR 0032, sofern dessen Modell passt; Parserfakten bleiben am Programm | *Vorgeschlagen*, in Punkt 7 |
| F41-4: Epic #1447 anpassen | Ja, Abschnitte A und D an die Entscheidung vom 26.11. angleichen | Folgerung; der Epic-Text ist noch nicht geändert |

## Grenzen und Limits

Nach ADR 0036, Punkt 12. Stand develop vom 26.11.2026; was nur in PR #1488 steht, ist so gekennzeichnet.

| Grenze | Standard | Einstellung | In der Oberfläche |
| --- | --- | --- | --- |
| Antwort- und Kontextlänge der fachlichen KI-Analyse | 8192 Tokens | Modellzeile (`ai.chat-max-tokens`, Kontextlänge je Modell) | ja, Einstellungen › KI-Dienst › Modelle |
| Zeichen je Token zur Aufteilung des Programms | 3 | **fest im Code** (`CobolBusinessAnalysis.CHARACTERS_PER_TOKEN`, PR #1488) | nein |
| Tiefe der Abhängigkeiten: Natural, DDM | 5, 3 | **fest im Code** (`SourceRelations.NATURAL_DEPTH`, `DDM_DEPTH`) | nein |
| Diagramm: Zusammenfalten ab Knotenzahl, Häufigkeitsschwelle | 30, 3 | **fest im Code** (`OutlineToMermaidConverter.COLLAPSE_THRESHOLD`, `FREQ_THRESHOLD`) | nein |
| KI-Beschriftung von Diagrammen: Code je Anfrage (**Größenlimit**) | 4000 Zeichen, danach abgeschnitten | **fest im Code** (`DiagramLabels`) | nein |
| KI-Beschriftung von Diagrammen: Wartezeit je Anfrage | 30 s | **fest im Code** (`DiagramLabels.TIMEOUT_SECONDS`) | nein |

Die Programmanalyse selbst hat kein Größenlimit für Programme: Die KI-Stufe teilt nach dem Tokenbudget des
Modells auf (PR #1488, #1486). Die Beschriftung der Diagramme schneidet dagegen still nach 4000 Zeichen ab; das
widerspricht „Unsicheres bleibt sichtbar“ (Punkt 8), solange die Kürzung nicht angezeigt wird.

## Offene Fragen an Angelo

- **F41-5 (zu Punkt 7):** ADR 0032 belegt jede Aussage mit ihrem Originalsatz und prüft, dass die Inhaltswörter der
  Aussage aus diesem Satz stammen. Für Programmcode passt das nicht: Eine Bedingung wie `IF KZ-STATUS = 'A'`
  enthält die fachlichen Wörter der Regel nicht. Mein Vorschlag: Der Fachlogik-Bestand bekommt eine zweite
  Belegart „Quellstelle“ (Programm, Revision, Zeilenbereich, Parserfakt, Analyseversion). Der Wortabgleich entfällt
  dafür; an seine Stelle tritt, dass eine Aussage aus Code erst mit der Prüfung durch einen Menschen (W3) als
  geltend zählt. Einverstanden?
- **F41-6 (zu Punkt 5):** Der Umzug in den Core betrifft den offenen PR #1488, an dem ein anderer Strang arbeitet.
  Soll der Umzug noch in #1488 geschehen oder als eigener Schritt danach? Mein Vorschlag: danach, damit #1488
  nicht weiter wächst.
- **F41-7 (zu „Grenzen und Limits“):** Die Diagrammbeschriftung schneidet den Code nach 4000 Zeichen ab. Soll sie
  wie die fachliche Analyse nach dem Tokenbudget des Modells aufteilen, sodass die Grenze entfällt?

## Folgen

- Eine neue Sprache braucht einen Parser-Adapter, einen Prozessor und passende Unterreiter, keinen neuen Bestand.
- Jede Stelle, die Programmanalysen ausliefert, geht durch dasselbe Tor. Ein neuer Leseweg ohne dieses Tor ist ein
  Fehler.
- Fachliche Aussagen bleiben bis zur Quellzeile belegbar, und der Unterschied zwischen Fakt, Hypothese und
  bestätigter Erkenntnis geht in keiner Ansicht verloren.
