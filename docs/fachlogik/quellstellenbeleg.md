# Belegart „Quellstelle“ – Umsetzung

Grundlage: ADR 0032, Fortschreibung vom 26.11.2026, und ADR 0041, Punkt 7 (Entscheidung F41-5 vom 26.11.2026,
14:41). Es gibt **einen** Fachlogik-Bestand; Aussagen aus Programmcode bekommen eine zweite Belegart, keinen zweiten
Bestand.

## Teil 1: Beleg-Abstraktion im Core (`application.evidence`)

Reine Werttypen und Funktionen, ohne Ablage, Parser oder KI (`EvidenceLayeringTest`).

| Typ | Inhalt |
| --- | --- |
| `Evidence` (sealed) | gemeinsame Abstraktion: Art, Dokument, Revision, ob der Beleg Ist/Soll festlegt |
| `TextEvidence` | Textbeleg: Dokument, Fassung (aus der Aussagen-ID), Satzindex, Spanne. Geprüft wie bisher (Wortabgleich, Prüfschritt, W3). `TextEvidence.of(PageRef, Statement)` |
| `SourceLocationEvidence` | Quellstellenbeleg: Programm und Revision, Codestellen (je Dokument, Revision, Zeilen; mehrere Paragraphen, Programme, Copybooks), Parserfakten (Art, Wortlaut, Codestelle, Zeilen), Abhängigkeiten (aufgelöst mit Revision oder negative Probe), Analyseversion |
| `SourceLocationCheck` | technische Belegprüfung gegen den Port `CurrentSources` |
| `SourceInsight` | Erkenntnisebene einer Aussage mit Quellstellenbeleg aus W3-Prüfung und technischer Prüfung |
| `InsightLevel` | Parserfakt, Hypothese, bestätigt, verworfen |
| `Bearing` | Ist, Soll |

### Technische Prüfung

| Ergebnis | Gründe | Bedeutung |
| --- | --- | --- |
| aktuell | keine | Stellen vorhanden, nichts geändert |
| möglicherweise veraltet | Programm geändert, anderes Dokument einer Codestelle geändert, Abhängigkeit geändert oder auf anderes Dokument aufgelöst, Abhängigkeit nicht mehr gefunden, negative Probe jetzt gefunden, Analyseversion anders oder keine Analyse | Regel kann weiter gelten, muss neu bestätigt werden |
| nicht prüfbar | Dokument fehlt oder ist für den Aufrufer nicht lesbar, Codestelle reicht in der unveränderten Revision über das Dokumentende | Beleg trägt nicht |

Die Prüfung bestätigt nie etwas fachlich. Zeilen werden nur in der unveränderten Revision geprüft; in einer neuen
Revision ist der Beleg ohnehin möglicherweise veraltet.

### Bestätigung und Erkenntnisebene

- Die Prüfung durch einen Menschen ist die vorhandene aus W3 (`ParsedStatements.Review`, Ablage in
  `StandingDecision`), keine zweite.
- Bestätigt ist eine Aussage nur mit technisch aktuellem Beleg. Sonst fällt sie auf „Hypothese“ zurück und trägt
  `confirmationOutdated`: Die Bestätigung wird nie ungeprüft auf eine neue Revision übertragen.
- `SourceLocationEvidence#fingerprint()` fasst Programm, Revisionen aller Codestellen und Abhängigkeiten (auch
  negative Proben) und die Analyseversion zusammen. Er soll in die ID der Aussage eingehen, wie die Fassung in der ID
  einer Aussage aus Text; eine W3-Entscheidung hängt dann an genau diesem Stand.
- Ein Quellstellenbeleg legt immer Ist fest (`Bearing.IST`), auch bestätigt. Ein Textbeleg legt nichts fest; das Soll
  kommt aus Vorschriften und Fachtexten. Befunde des Soll-Ist-Vergleichs (FL8) bleiben Hinweise.

### Revisionen

Revisionen und Analyseversion kommen aus der Identität der gespeicherten Programmanalyse
(`ProgramAnalysisAccess`: Prozessor, Version, Inhalts-Hash je Dokument), nie aus einer zweiten Revisionsquelle.
Programmanalyse wird nur über dieses Tor gelesen (ADR 0041, Punkt 11).

## Grenzen und Limits

Teil 1 führt keine Grenze ein: keine Höchstzahl an Codestellen, Parserfakten oder Abhängigkeiten.

## Teil 2: Hypothesen der Programmanalyse als Aussagen im Bestand

- Herkunft `ParsedStatements.Origin.PROGRAMM`: Die Aussage stammt aus einer KI-Hypothese; `Derivation#source()` trägt
  den Quellstellenbeleg, `simplifiedSentence` den Hypothesensatz zur Anzeige. Der Hypothesensatz ist nie Beleg.
  `Evidence.of(PageRef, Statement)` liefert den Beleg jeder gespeicherten Aussage.
- `StatementAnalysis#analyzeProgram`: zerlegt jede Hypothese wie eine Zeile der Vorstufe mit dem Satzanalysator und
  speichert die Aussagen in der Analyse des Programms (derselbe Bestand, dieselbe W3-Prüfung). Nur Hypothesen, deren
  Beleg zu diesem Programm gehört und technisch aktuell ist; die übrigen werden gezählt. Die Fassung in den
  Aussagen-IDs fasst Programmtext und Fingerabdrücke aller Belege zusammen, eine Bestätigung wandert also nie auf einen
  neuen Stand. Veraltet ist die Analyse, sobald sich Programmtext oder Satzanalysator ändern.
- Programmcode ist kein Satztext: Die Satzanalyse überschreibt Programmaussagen nie, der Prüfschritt nimmt sie nicht
  auf (`CheckScope`).
- Ablage: `FileKnowledgeGraphStore` schreibt den Beleg unter `source`; ohne lesbaren Beleg wird die Aussage übergangen.
  Ältere Fassungen der App übergehen die unbekannte Herkunft.
- App: Nach „Fachliche KI-Analyse“ eines COBOL-Programms baut `ProgramHypothesisEvidence` die Belege aus den
  gespeicherten Analysen (Programm- und Copybook-Stellen, alle Proben, Identität des Parserergebnisses als
  Analyseversion), gelesen nur über `ProgramAnalysisAccess`; `ProgramCurrentSources` liefert den jetzigen Stand. Das
  Auftragsereignis nennt übernommene und ausgelassene Hypothesen.

## Teil 3 (offen)

Anzeige von Erkenntnisebene (Hypothese, bestätigt, Bestätigung veraltet) und Quellstelle in Aussagen-Reiter und Belege-
Overlay; Zählung der Programmaussagen in Fachsicht, Glossar und Stand als Ist.
