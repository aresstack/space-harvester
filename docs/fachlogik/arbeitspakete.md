# Fachlogik aus dem Wiki: Arbeitspakete FL0–FL8 (Strang AT, 17.11.2026)

Konzept und Messbericht für Angelo: https://claude.ai/artifact/HedyiZRKJCJpAAVt4modMH
Messdaten: [messung-2026-11-17.md](messung-2026-11-17.md) (jeder Fehler mit Ursache); Texte, Vorgabe und Ausgaben im Prüfstand `nlp-parser/src/test/resources/com/aresstack/spaceharvester/nlp/pruefstand/`.
Der Architekt baut nicht; er prüft Absichten und PRs. ADR-Nummer vergibt der Koordinator.

## Vorgaben Angelos (17.11.2026, bindend)
- Erst messen, wie gut MaltParser und Wissensgraph arbeiten; mit dem klassischen Weg (Verb-Nomen-Analyse auf dem
  Vorhandenen) starten, dann stufenweise um moderne Verfahren erweitern; nichts Vorhandenes wegwerfen.
- Ziel: automatisierte objektorientierte Analyse (Nomen → Struktur, Verben → Verhalten), Fachlogik als roter Faden;
  Ist-Stand vor Beweggründen („Schaubild“); Leser soll erkennen, was er lesen muss; Fachglossar (eigener Reiter,
  „lebendes Objekt“) nur mit Fachlichem, Technisches höchstens als nicht funktionale Anforderung; Soll-Ist-Vergleich.
- Idee Angelos: Ein Chat-Modell vereinfacht den Text VOR dem Parser zu reinen Hauptsätzen („wie für einen Ausländer“),
  glättet Stichpunkte/Wiki-Stil; Abschnitte parallel; Modellgröße ausprobieren (Gemma, GPT 120b, ggf. reine
  Umform-Modelle). Auf seinem Rechner mit gemma4:12b gemessen: 64 % → 82 % (von Hand: 88 %); gemma4:e2b reicht nicht.

## Feste Regeln für alle Pakete
- Keine neuen Abhängigkeiten, kein Modell im Core: Chat-Modell nur über den eingestellten KI-Dienst (Muster
  `UpdateSummarizer`), nur Seiten mit `AiRelease.mayProcess`, Aufträge auf Wunsch und anhaltbar (ADR 0024, ADR 0025),
  kein Polling (ADR 0027). MaltParser bleibt in der Desktop-App (`MaltStatementAnalyzer`).
- Beleg jeder Aussage bleibt der ORIGINALSATZ. Keine stille Verschmelzung von Begriffen (Vorschläge, Mensch bestätigt).
- Im Repo nur erfundene Texte. Echte Seiten nur in lokalen Läufen, die ausschließlich Zahlen ausgeben.
- Jedes Paket weist seine Wirkung am Prüfstand (FL0) nach: Zahlen vorher/nachher in die PR-Beschreibung.

## Pakete

**FL0 Prüfstand (zuerst auf main).** Texte A–E samt Handvorgabe (aus `bewertung.md` als maschinenlesbare Vorgabe:
je Satz erwartete Aussagen mit Verb-Grundform, Handelndem, Betroffenem, Verneinung, Verbindlichkeit, Zeitbezug) in
`nlp-parser` (Test-Ressourcen). Kennzahlen: Verb gefunden, vollständig richtig, falsch trotz PLAUSIBLE, je Textart.
Varianten: heute / mit Vorstufe aus abgelegten Ausgaben (die Gemma-Ausgaben liegen bei) / lokal mit lebendem Modell
(nicht in CI). Ein Test hält den Stand fest (darf nur besser werden). Zusätzlich lokaler Lauf über echte Seiten mit
reiner Zahlenausgabe (Anteile PLAUSIBLE/UNCERTAIN/INVALID, Aussagen je Satz), ohne Inhalte.

**FL1 Regeln.** Die 13 Regel-Lücken aus `bewertung.md`: `csubj`/`ccomp`/`advcl`-Sätze als Subjekt bzw. Objekt
anbinden (Aussage verweist auf Aussage), „durch“+Passiv als Handelnder, gemeinsames Objekt bei gereihten Verben
(als übernommen markiert wie SUBJECT_FROM_COORDINATION), Relativpronomen und Pronomen im selben Satz auflösen,
`compound`/`flat` zu einem Namen zusammenführen, „ist … zu + Infinitiv“ als Pflicht mit von-Gruppe als Handelndem.

**FL2 Merkmale.** An `Statement`: Verneinung, Verbindlichkeit (soll/kann/muss/darf/ist zu), Zeitbezug (gilt jetzt,
geplant, früher, verworfen) aus Wörtern des Satzes; Grundformen vereinheitlichen. Durchreichen in den Wissensgraph
und den JSON-LD-Export (Schema-Erweiterung, abwärtsverträglich). `StatementQuality`: „plausibel“ nur, wenn auch die
Kernrollen besetzt sind, die das Verb erwarten lässt (Messung: 8 unvollständige als PLAUSIBLE).

**FL3 Vorstufe „Sätze vereinfachen“.** Core-Auftrag mit Chat-Modell. Je Originalsatz Liste einfacher Hauptsätze mit
Kennzeichen (Tatsache, Bedingung, Frage, Planung, verworfen) und Verweis auf den Originalsatz. Nur Satzbau, Wörter
bleiben (keine Leichte Sprache). Stichpunkte mit Überschrift/Kennung des Abschnitts zu Sätzen. Core gleicht ab:
Inhaltswörter (Grundform) müssen im Originalsatz oder in Überschrift/Kennung des Abschnitts stehen, sonst wird der
Satz verworfen bzw. als „ergänzt“ markiert (nur Handelnder aus dem Abschnitt). Weglassungen erkennen (Inhaltswörter
des Originals, die in keinem Ausgabesatz vorkommen → Originalsatz zusätzlich direkt parsen). Ablage je Satz (Hash aus
Text, Modell, Anweisungsversion), nur geänderte Sätze neu; Abschnitte parallel; Modell je Schritt einstellbar.
Startanweisung: `messung-2026-11-17/vorstufe-anweisung-v1.txt` (Schwächen stehen in `bewertung.md`).

**FL4 Prüfschritt.** Chat-Modell bekommt Originalsatz und Aussage: stimmt / unvollständig / falsch, Korrektur nur aus
Wörtern des Satzes (Core prüft). Herkunft je Aussage: Parser, Parser nach Vorstufe, vom Modell korrigiert. Nach FL2, FL3.

**FL5 Begriffe und Glossar.** Mehrteilige Fachbegriffe, Wichtigkeit (Seitenstreuung, Rolle als Handelnder),
Einordnung fachlich / technisch / Rolle-Stelle / unklar (erst Regeln, Rest Chat-Modell mit Begründung), Zustände
vorgeschlagen/bestätigt/abgelehnt, Definitionsvorschlag aus Belegsätzen, Vorschläge zum Zusammenlegen, „neu/geändert/
entfallen seit dem letzten Update“ (Änderungsprotokoll ADR 0030). Glossar-Reiter: nur Fachliches, Filter „Technik“.

**FL6 Stand und Leseempfehlung.** Stand je Aussage und Seite (gilt, geplant, überholt, unklar) aus Merkmalen (FL2),
Lage/Labels, Änderungsprotokoll, Verweisen, Analytics, Widersprüchen; Mensch überschreibt; Anzeige blass und nach
hinten, nie versteckt; Leseempfehlung je Space/Thema.

**FL7 Schaubild des Ist-Stands.** Aus geltenden fachlichen Aussagen: wichtigste Dinge als Kästen, Verben als Pfeile,
Klick zeigt Belegsätze und Seiten; zweite Ebene blendet Geplantes/Überholtes ein. Nach FL5, FL6.

**FL8 Soll-Ist.** Fachlogik gegen geplantes IT-System (Brücke: Sätze mit fachlichem und technischem Begriff);
Befunde: Ablauf ohne technische Entsprechung, Technik ohne fachlichen Bezug, Widerspruch. Zuletzt.

## Reihenfolge
FL0–FL3 sofort parallel (FL0 zuerst mergen). FL4–FL6, sobald FL2/FL3 auf main sind. FL7, FL8 danach.

## Vorschlag zur Verteilung (Koordinator entscheidet)
FL0, FL1, FL2: G (Aussagen/KG, `nlp-parser`). FL3, FL4: Q (Core/Aufträge) mit G für den Abgleich. FL5: B (Begriffe)
plus ein Fable-Strang für den Glossar-Reiter. FL6: Q/MX im Core, BS/AN für die Anzeige. FL7: Fable-Strang. FL8: später.
