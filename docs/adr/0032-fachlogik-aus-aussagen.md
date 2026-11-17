# ADR 0032: Fachlogik aus Aussagen – Prüfstand, Vorstufe „Sätze vereinfachen“, Merkmale und Auswertung

- Status: Angenommen (17.11.2026). Entwurf des Architekten (Strang AT), Nummer vom Koordinator.
- Konzept und Messbericht für Angelo: https://claude.ai/artifact/HedyiZRKJCJpAAVt4modMH
- Messbericht: [docs/fachlogik/messung-2026-11-17.md](../fachlogik/messung-2026-11-17.md), Pakete:
  [docs/fachlogik/arbeitspakete.md](../fachlogik/arbeitspakete.md); Daten im Prüfstand (siehe „Umsetzung FL0“)
- Baut auf: ADR 0005 (objektzentrierte Extraktion), ADR 0024 (Aussagenanalyse auf Wunsch, globaler Wissensgraph),
  ADR 0025 (Aufträge), ADR 0027 (kein Polling), ADR 0028 (KI-Freigabe je Seite), ADR 0030 (Änderungsprotokoll).

## Vorgaben Angelos (17.11.2026, bindend)

1. Erst messen, wie gut MaltParser und Wissensgraph arbeiten. Mit dem klassischen Weg starten (Verb-Nomen-Analyse auf
   dem Vorhandenen), dann stufenweise um moderne Verfahren erweitern. Nichts Vorhandenes wegwerfen.
2. Ziel ist eine automatisierte objektorientierte Analyse: Unrelevantes streichen, Nomen ergeben die Struktur, Verben
   das Verhalten; die Fachlogik ist der rote Faden.
3. Der Leser soll zuerst den Ist-Stand sehen („Schaubild“), erst danach die Beweggründe; er soll erkennen, was er
   lesen muss. Überholte Planungen und Archiv lenken ab, sind aber für die Beweggründe interessant.
4. Fachglossar (eigener Reiter, „lebendes Objekt“) enthält nur Fachliches; Technisches ist höchstens nicht
   funktionale Anforderung.
5. Soll-Ist-Vergleich zwischen geplantem IT-System und dem, was die Fachlogik verlangt.
6. Ein Chat-Modell darf die Kette flankieren. Idee Angelos: Es vereinfacht den Text VOR dem Parser zu reinen
   Hauptsätzen und glättet Stichpunkte/Wiki-Stil; Abschnitte parallel; Modellgröße ausprobieren.

Alles Übrige ist Vorschlag des Architekten und gilt, bis Angelo widerspricht.

## Befund (Messung 17.11.2026, 5 erfundene Texte, 67 von Hand vorgegebene Aussagen)

- Heute: Verb gefunden 66/67, vollständig richtig 43/67 (64 %); einfache Aktivsätze 12/12, Wiki-Stil 4/11.
- Von 24 Fehlern: 13 Regel-Lücken, 4 Stichpunkte ohne Vorstufe, 7 Parser-/Wortartfehler.
- Nicht erfasst: Verneinung, Verbindlichkeit (soll/kann/muss), Zeitbezug; Grundformen uneinheitlich.
- `StatementQuality`: von 51 PLAUSIBLE sind 39 richtig, 8 unvollständig, 4 falsch.
- Vorstufe „reine Hauptsätze“: von Hand 59/67 (88 %), gemma4:12b lokal 55/67 (82 %; 1–1,6 s je 10 Sätze), gemma4:e2b
  unbrauchbar. Gemma erfindet gelegentlich Handelnde und lässt Teilsätze weg.

## Entscheidung

### 1. Prüfstand zuerst
Ein fester Prüfstand mit erfundenen Texten und Handvorgabe liegt im Repo und gibt je Variante dieselben Kennzahlen aus
(Verb gefunden, vollständig richtig, falsch trotz PLAUSIBLE, je Textart). Jede Änderung an Regeln, Vorstufe, Modell
oder Anweisung weist ihre Wirkung daran nach. Echte Seiten werden nur lokal gemessen; der Lauf gibt nur Zahlen aus.

### 2. Klassische Kette bleibt und wird nachgebessert
Parser und Regeln bleiben der Weg, der die einheitliche, belegbare Form liefert. Nachgebessert werden die gemessenen
Regel-Lücken. Jede Aussage trägt künftig Verneinung, Verbindlichkeit und Zeitbezug; der Wissensgraph und sein Export
führen sie abwärtsverträglich. „Plausibel“ verlangt besetzte Kernrollen.

### 3. Vorstufe „Sätze vereinfachen“ (Chat-Modell davor)
- Auftrag auf Wunsch, anhaltbar, nur Seiten mit KI-Freigabe, Modell aus dem eingestellten KI-Dienst (kein Modell im Core).
- Das Modell vereinfacht nur den Satzbau: je Aussage ein Hauptsatz, Aktiv bei genanntem Handelnden, Nomen statt
  Pronomen, Stichpunkte mit Überschrift/Kennung zu Sätzen. Wörter bleiben (keine Leichte Sprache).
- Jeder vereinfachte Satz trägt den Verweis auf seinen Originalsatz und ein Kennzeichen: Tatsache, Bedingung, Frage,
  Planung, verworfen. Der Zusammenhang der Teilsätze geht so nicht verloren. Beleg bleibt der Originalsatz.
- Abgleich im Core: Inhaltswörter müssen aus dem Originalsatz oder aus Überschrift/Kennung desselben Abschnitts
  stammen. Ein nur von dort ergänzter Handelnder wird als „ergänzt“ markiert; alles andere wird verworfen.
  Inhaltswörter des Originals, die in keinem Ausgabesatz vorkommen, gelten als Weglassung; der Originalsatz wird dann
  zusätzlich direkt verarbeitet.
- Ablage je Satz (Schlüssel: Text, Modell, Version der Anweisung); nur geänderte Sätze laufen neu; Abschnitte parallel.
  Löschen mit der Seite, mit der Quelle und bei Entzug der KI-Freigabe.
- Ohne KI-Freigabe oder ohne Dienst läuft die klassische Kette allein; das Ergebnis nennt das.

### 4. Prüfschritt (Chat-Modell danach)
Das Modell erhält Originalsatz und Aussage und antwortet mit stimmt, unvollständig oder falsch, gegebenenfalls mit
einer Korrektur aus Wörtern des Satzes; der Core prüft das. Jede Aussage nennt ihre Herkunft: Parser, Parser nach
Vorstufe, vom Modell korrigiert.

### 5. Auswertung über Seiten hinweg
- Begriffe: mehrteilige Fachbegriffe, Wichtigkeit, Einordnung fachlich / technisch / Rolle-Stelle / unklar (erst Regeln,
  Rest Chat-Modell mit Begründung). Keine stille Verschmelzung: Zusammenlegen nur als Vorschlag, Mensch bestätigt.
- Fachglossar: nur Fachliches, technische Begriffe unter eigenem Filter; Einträge vorgeschlagen / bestätigt /
  abgelehnt; Definitionsvorschlag aus Belegsätzen; neu, geändert, entfallen seit dem letzten Update (ADR 0030).
- Stand je Aussage und Seite: gilt, geplant, überholt, unklar; aus Merkmalen, Lage und Labels, Änderungsprotokoll,
  Verweisen, Aufrufen, Widersprüchen; Mensch überschreibt. Überholtes wird blass und nach hinten sortiert, nie versteckt.
- Leseempfehlung je Space/Thema; Schaubild des Ist-Stands aus geltenden fachlichen Aussagen mit Belegen; zweite Ebene
  blendet Geplantes und Überholtes ein.
- Soll-Ist: Fachlogik gegen geplantes IT-System; Befunde sind Hinweise, keine Feststellungen. Zuletzt.

## Nicht entschieden / später
- Welches Modell je Schritt Standard wird (Zahlen des Prüfstands; GPT 120b und reine Umform-Modelle ungemessen).
- Darstellung des Schaubilds in der App.

## Arbeitspakete
FL0 Prüfstand · FL1 Regeln · FL2 Merkmale · FL3 Vorstufe (sofort, parallel; FL0 zuerst auf main) · FL4 Prüfschritt ·
FL5 Begriffe und Glossar · FL6 Stand und Leseempfehlung · FL7 Schaubild · FL8 Soll-Ist.
Einzelheiten: [docs/fachlogik/arbeitspakete.md](../fachlogik/arbeitspakete.md).

## Umsetzung FL0 (Prüfstand)
- `nlp-parser/src/test/resources/com/aresstack/spaceharvester/nlp/pruefstand/`: `saetze.tsv` (Textsätze mit Rolle
  `entwicklung` oder `kontrolle`; Regeln werden an Entwicklungssätzen entwickelt, Kontrollsätze nur gemessen), je
  Textsatz `texte/`, `vorgabe.tsv` (Handvorgabe mit Schreibweisen `-`, `?X`, `§v`, `~X`, `A|B`, alternative Lesarten),
  `varianten/<v>/<T>.conllu` (gepinnte Ausgabe der echten Kette) und bei Vorstufen `<T>.vorstufe`
  (`<Originalsatz>|<Kennzeichen>|<Satz>`), `stand.tsv` (Untergrenzen richtig/streng, Obergrenze falsch trotz PLAUSIBLE).
- `PruefstandTest` (CI) bewertet alle Varianten, schreibt `build/reports/pruefstand/bericht.md` und hält den Stand.
  `PruefstandLiveTest` (mit `-PnlpModelDir`, auch im Workflow „Deutsches Parsermodell“) prüft die Pins gegen die echte
  Kette; mit `-PpruefstandSeiten=<Verzeichnis>` zählt er echte Seiten lokal, ohne Inhalte auszugeben.
- Stand bei Einführung: heute 43/67, Vorstufe von Hand 59/67 (streng 58), gemma4:12b 55/67 (streng 51); falsch trotz
  PLAUSIBLE 12 / 5 / 8.

## Umsetzung FL1 (Regeln, rules-7)
- Teilsätze als Beteiligte: Erwähnung der Art `CLAUSE` (Kopf = Prädikat des Teilsatzes, Finding
  `CLAUSE_PARTICIPANT`). `csubj` ist Subjekt, neben einem nominalen Subjekt Objekt; nachgestelltes `ccomp` ist Objekt;
  ein vorangestellter dass-/ob-Satz (`advcl`/`ccomp`) und eine Infinitivgruppe im Vorfeld sind abgeleitet
  (`HEURISTIC`, unsicher). wenn-/falls-/sofern-/sobald-/nachdem-Sätze bleiben eigene Aussagen. Im Wissensgraph sind
  `CLAUSE`-Erwähnungen nie Begriffe; der JSON-LD-Export führt sie mit `refersToStatement` als Kante zur Aussage.
- „durch“ im Passiv ohne von-Gruppe und die von-Gruppe bei „ist … zu + Infinitiv“ werden abgeleitete Handelnde
  (`AGENT_FROM_DURCH`, `AGENT_FROM_VON`, ergänzend, unsicher).
- Gemeinsames Objekt gereihter Verben nur bei reiner Verbreihung und nicht im eingeleiteten Nebensatz
  (`OBJECT_FROM_COORDINATION`, Basis `COORDINATION`, unsicher; `StatementQuality` stützt es nie).
- Relativpronomen am Anfang eines Relativsatzes werden auf ihr Bezugsnomen aufgelöst (`RELATIVE_PRONOUN_RESOLVED`);
  Personalpronomen als Objekt eines gereihten Verbs nur bei genau einem nominalen Objekt des Bezugsverbs
  (`PRONOUN_FROM_COORDINATION`, unsicher).
- zu-Infinitiv ohne Hilfsverb hat kein Subjekt (`SUBJECT_OF_INFINITIVE`): das vermeintliche Subjekt wird bei fehlendem
  Objekt unsicheres Objekt, sonst verworfen.
- Mehrteilige Namen mit Bindestrich (`compound`) und `flat` bilden Kopfform und Grundform („Batch-Job“, „Load Balancer“).
- Gespeicherte Analysen mit rules-6 gelten als veraltet (Analyzer-Version).

## Umsetzung FL5a (Begriffe ableiten, reine Auswertung)
- `application.glossary.GlossaryDerivation`: rechnet auf Anfrage über die lesbaren, aktuellen Analysen
  (`KnowledgeGraph#analysed`), abbrechbar, schreibt nichts. Je Begriff: Wichtigkeit als offene Teilzahlen (Seiten,
  Erwähnungen, als Handelnder, als Betroffenes, als Träger; nur Original), Verhalten (Verben als Handelnder und als
  Betroffenes, Vorstufe und verneinte getrennt), Eigenschaften und Ist-Beziehungen (nur Ist-Zustand: bejaht, ohne
  Modalverb, `GILT`, kein `STATE_CHANGE`, nur `ORIGINAL`, sicherer Träger), Belege als Position (Satzspanne,
  Eingabe-Hash), Einordnung nach Regeln.
- `application.knowledge.CountedStatements`: die eine Stelle, ob eine Aussage zählt und mit welcher Stufe (vom
  Menschen abgelehnte nie); FL4 (`Statement#effectiveQuality`) und FL6 hängen sich dort ein. Aussagen anderer Herkunft
  als das Original (Vorstufe, später Modell) zählen getrennt und sind nie Beleg.
- Schlüssel: `GlossaryDerivation#keyOf` = Grundform in der Schreibung des Textes (`spelling`: Großschreibung aus dem
  Text, „ss“ des Textes statt „ß“ der alten Rechtschreibung), normalisiert wie das Glossar.
- Einordnung: technisch (Schreibweise; Kopf-Wortliste), Rolle/Stelle (Kopf-Wortliste), sonst `UNKLAR`. Wortlisten an
  einer Stelle: `glossar-regeln.tsv`. Köpfe unter fünf Buchstaben und mehrdeutige Köpfe gelten nur als ganzes Wort oder
  nach Bindestrich/Leerzeichen (`technik-wort`, `rolle-wort`), nie als Ende eines zusammengeschriebenen Worts
  („Import“, „Rechtssystem“, „Leerstelle“); lieber `UNKLAR` als falsch. Belegt am Entwicklungstext W (trügerische
  Wortenden). Ist-Beziehungen ordnen noch nicht ein und geben keinen Definitionsvorschlag, bis
  der Parser den Artikel am Rollennomen festhält (Strang FL).
- Messung: `GlossarBerichtTest` gegen von Hand vorab festgelegte `begriffe.tsv`/`beziehungen.tsv` je Textsatz
  (Bericht `build/reports/pruefstand/glossar.md`, kein Pin; beim Kontrollsatz nur Zahlen und Kennungen).
