# ADR 0032: Fachlogik aus Aussagen – Prüfstand, Vorstufe „Sätze vereinfachen“, Merkmale und Auswertung

- Status: Angenommen (17.11.2026). Entwurf des Architekten (Strang AT), Nummer vom Koordinator.
  **Fortgeschrieben** am 26.11.2026 um die Belegart „Quellstelle“ (siehe am Ende).
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
Vorstufe, vom Modell korrigiert. Getrennt davon kann ein Mensch eine einzelne Aussage bestätigen oder verwerfen
(Prüfung durch Menschen, Umsetzung W3); das überschreibt weder die Stufe des Parsers noch das Urteil des Modells.

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

## Umsetzung FL5b-1 (Ablage im Glossar)

- `GlossaryEntry` trägt zusätzlich `classification` (Kategorie, Urheber `RULE`/`AI`/`MANUAL`, Grund nur als
  Kennung wie `TECHNICAL_HEAD`, nie freier Text eines Modells), `definitionProposal` (Belegposition, nie Text; bleibt
  leer, bis das Artikel-Merkmal auf main ist) und `supportedBy` (welche Läufe den Begriff zuletzt nannten). Wichtigkeit
  wird nicht gespeichert. Alte Ablagen bleiben lesbar (fehlende Felder: keine Einordnung, gestützt vom anlegenden Lauf).
- Herkunft `RULE` ist zugelassen und verhält sich wie `AI`: ohne lesbaren Beleg unsichtbar, Belege und
  Definitionsvorschlag nach Leserecht gefiltert. Die Herkunft ändert sich nie; Regel und KI teilen die Kennung je
  Begriff und Geltungsbereich.
- Stützung: Jede Phase trägt nur sich ein oder aus. Ein ungeprüfter Vorschlag fällt erst, wenn ihn kein Lauf mehr
  stützt; ein bestätigter wird erst dann „Beleg veraltet“. Ein Lauf, der nicht stattfindet, löscht nichts.
- Einordnung: Mensch vor KI vor Regel; die KI ersetzt eine Regel-Einordnung nur bei `UNKLAR`, die Regel nie die KI.
  `Glossary.categorize` setzt die Einordnung des Menschen (Protokoll `categorize`).
- `Glossary.Search` filtert nach Kategorien (ohne Einordnung zählt `UNKLAR`).
- `GlossaryRuleProposals` macht aus `GlossaryDerivation` die Vorschläge eines Geltungsbereichs: nur Seitenbelege
  (keine Anhänge), bis zu 3 aus dem Original, höchstens `maxTerms` (Standard 500) nach Wichtigkeit, der Rest bleibt
  unverändert. Belege der Regel dürfen von Seiten ohne KI-Freigabe stammen; FL5c zeigt dem Modell nur Belege
  freigegebener Seiten.

## Umsetzung FL5b-2a (Regelphase im Auftrag, Einstellungen)

- Auftrag `GLOSSARY` je Quelle mit zwei Phasen: zuerst „Begriffe ableiten“ (ohne Modell, abbrechbar, Teilergebnisse
  je 50 Vorschläge, Abgleich am Ende mit Herkunft `RULE`), dann wie bisher „Fachbegriffe ermitteln“ mit Modell. Der
  Auftrag startet auch ohne Modell und ohne KI-Freigabe; die Modellphase fällt dann aus, löscht nichts, und ein
  Ereignis des Auftrags nennt Grund und Zahlen der Regelphase. Im Dienst gibt es keinen Wissensgraphen, dort bleibt es
  beim Start nur mit Modell (503 wie bisher).
- Einstellungen unter `space-harvester.glossary.rules.*` (beide Loader, Paritätstest): `max-terms` und je Art
  (`technical-heads`, `technical-words`, `role-heads`, `role-words`) zusätzliche (`add`) und ausgeschlossene
  (`exclude`) Wörter; Köpfe brauchen wie in der festen Liste mindestens 5 Buchstaben. Die feste Liste bleibt die
  Ressource `glossar-regeln.tsv`.

## Umsetzung FL5b-2b (Teilzahlen, REST, Schnittstelle)

- `GlossaryOverview`: Liste der Teilzahlen je Geltungsbereich (ohne Belege, nach Wichtigkeit) und Einzelheiten je
  Begriff, je Aufrufer gerechnet, nichts gespeichert (`Harvester#glossaryOverview`).
- Ereignisse des Auftrags mit fester Kennung: `GLOSSARY_RULES_DONE` (Zahlen der Regelphase) und
  `GLOSSARY_MODEL_SKIPPED:NO_MODEL` bzw. `:NOT_RELEASED` (bewusst ausgelassen, gelb).
- Wortlisten der Einstellung: ein falsches Wort ist ein Problem, die übrigen gelten; eine Normalisierung
  (`GlossaryRuleSettings.normalize` = `TermText.normalize`).
- REST: Felder `category`, `categoryBy`, `categoryReason`, `definitionProposal`, `supportedBy`, Filter `category`,
  `PUT /{id}/category`. Der Dienst hat keinen Wissensgraphen: keine Regelphase, keine Teilzahlen, 503 ohne Modell.
- Schnittstelle für den Glossar-Reiter: `docs/fachlogik/glossar-schnittstelle.md`.

## Umsetzung FL5b-2c (fertige Sicht des Glossar-Reiters)

- `GlossaryOverview#view(caller, PageSelection, Query, cancelled)`: Einträge des Geltungsbereichs, in dem die Auswahl
  liegt, mit der Wichtigkeit über die Seiten der Auswahl verknüpft, sortiert (ohne Wichtigkeit hinten, alphabetisch),
  nach Kategorie, Status und Suchtext gefiltert, mit Zahlen je Kategorie und je Status für die Chips, `pages` und
  `stalePages`. Die Sicht hält das Ergebnis der Ableitung und beantwortet `term(normalized)` ohne neues Rechnen.
- Nichts gespeichert, abbrechbar; 5000 Seiten mit 500 Einträgen rund 0,4 s im Container. Der Reiter rechnet nichts
  selbst; neu geholt wird bei Glossar-Änderung, Auswahlwechsel und Ende von `STATEMENT_ANALYSIS`, `STATEMENT_CHECK` (sobald auf main),
  `GLOSSARY`.
- Verfeinern: `view(caller, vorigeSicht, query, cancelled)` nutzt das gehaltene Ergebnis und liest nur die Einträge
  neu; neue Auswahl oder Auftragsende = volle Sicht, Filter und Suche = Verfeinern.

## Umsetzung FL5b-3 (Art der Ist-Beziehung aus dem Artikel)

- `GlossaryDerivation.Relation` aus `Features.article`: UNBESTIMMT = BESTIMMUNG, OHNE = ROLLE, BESTIMMT =
  GLEICHSETZUNG, KEIN und SONSTIGER = keine Beziehung (zählt unter `notPresent`), ohne Merkmal = UNBEKANNT bis zur
  neuen Analyse. Tor unverändert (Original, bejaht, ohne Modalverb, GILT, kein Zustandswechsel).
- GLEICHSETZUNG wird nur gezeigt, nie automatisch Synonym oder Zusammenführung.
- `definitionProposal` der Regelphase: BESTIMMUNG mit dem Begriff als Träger, meiste Seiten, bei Gleichstand die
  erste; nur Seitenbelege aus dem Original.
- Gemessen in `GlossarBerichtTest` gegen `beziehungen.tsv`: Entwicklungssatz „foerderung“ festgehalten in
  `beziehungen-stand.tsv` (falsche Art und KEINE verletzt sind Fehler, nicht gefunden ist eine Lücke); Kontrollsatz
  „beschaffung“ nur berichtet.

## Umsetzung FL5c (Phase mit Modell, Freigabe je Seite)

- `GlossaryModelPhase`: fragt nur ungeprüfte, nicht manuelle Einträge ohne Einordnung oder mit `UNKLAR`, 25 je
  Anfrage. Alles, was hinausgeht, wird nur über jetzt freigegebene Seiten gerechnet (`AiRelease#mayProcess`), vor jeder
  Anfrage neu geprüft; Belege nur aus gezählten Aussagen des Originals, Zahlen grob gestuft.
- Das Modell nennt nur eine Kennung; die Kategorie folgt aus einer festen Tabelle (`glossar-schnittstelle.md`).
- Die KI-Einordnung trägt die Seiten ihrer Belege; Entzug (`ReleaseRecomputation`) setzt sie auf den Regelstand
  zurück (`Glossary#withdrawAi`), der nächste Lauf fragt neu. ADR 0028 entsprechend angepasst.
- Vorrang unabhängig von der Reihenfolge: Mensch, bestimmte Regel, KI, `UNKLAR` der Regel. Frühere Vorschläge der
  Herkunft `AI` bleiben stehen; die App schlägt über die KI keine neuen Begriffe mehr vor.
- Die Anweisung ist ungemessen. Die Tests nutzen nur ein Fake-Modell; Handantworten prüfen Verarbeitung und Vorrang,
  nie Qualität.

## Umsetzung FL5d (Rolle einer Person)

- `CategoryRule.ROLE_OF_PERSON`: Rollennomen einer Ist-Beziehung nur ohne Artikel (Art ROLLE; mit unbestimmtem Artikel steht bei Personen oft eine
  Bewertung, dort entscheidet die KI), deren Träger in
  `Mention.text` mit Frau, Herr oder Herrn und einem weiteren Wort beginnt; am Parser nichts geändert. Nur wenn keine
  Wortliste greift; bestimmte Regel (Rang 3).
- Gemessen: Kontrollsatz FALSCH bleibt 0 (das Plus bei richtig ist kein unabhängiger Beleg); Entwicklungssätze
  erfunden im Unit-Test.

## Umsetzung FL6 Teil 1 (Stand und Status-quo-Sicht, Core)
- Paket `application.standing`, erreichbar über `Harvester#statusQuo()`; Schnittstelle für die Anzeige:
  [docs/fachlogik/stand-schnittstelle.md](../fachlogik/stand-schnittstelle.md).
- Stand je Aussage: Entscheidung des Menschen > Vorstufe (KI), wenn die Merkmale aus dem vereinfachten Satz stammen und
  der Zeitbezug nichts Eigenes sagt > Zeitbezug (früher und verworfen beide überholt, Grund getrennt) > unklar. Die Seite
  (Label, eigener Titel, Titel eines Vorfahren, Entscheidung zur Seite) zieht nur Aussagen, deren „gilt“ der stille
  Vorgabewert ist (Zeitbezug GILT, Kennzeichen Tatsache oder Bedingung): überholt →
  überholt, Entwurf → unklar; Geplantes auf einer überholten Seite wird unklar. Verneinung und Verbindlichkeit ändern
  den Stand nie und gehen an jedem Eintrag mit.
- Ob eine Aussage zählt und mit welcher Stufe, beantwortet nur `StandingRules#effectiveQuality` (FL4 hängt dort ein):
  ungültige und abgelehnte fehlen, unsichere erscheinen gekennzeichnet und zählen nicht für die Seite. Stand je Seite =
  Zahlen je Stand, Etikett aus der Mehrheit der sicheren Aussagen, bei Gleichstand unklar.
- Entscheidungen des Menschen je Seite (über Fassungen hinweg) und je Aussage (genau diese Fassung, sonst „veraltet“) in
  `standing-decisions/v1` (seit W3 `v2`) mit Revision und Protokoll wie geprüfte Fakten; lesen wie die Seite, ändern mit `sync`;
  gelöscht mit der Quelle; Änderungen per Beobachter.
- Reine Auswertung, abbrechbar, speichert nichts; nur Kopfdaten aus dem Bestand. Laufzeit: 3000 erzeugte Seiten mit
  60 000 Aussagen rund 0,1 s im Container.
- Wortlisten (FL6 Teil 1b): einzige Stelle `StandingSettings` (`space-harvester.statements.standing.outdated-words`,
  `.draft-words`), beide Loader und `ConfigurationParityTest`; fehlend oder leer heißt Vorgabe, „-“ allein heißt keine Wörter. Entscheidungen bleiben
  beim Entfernen einer Seite aus dem Bestand stehen wie ihre Analyse und geprüfte Fakten, sie fallen mit der Quelle.

## Umsetzung FL6 Teil 2 (Leseempfehlung, Core)
- `Harvester#readingAdvice()`: Seiten einer Auswahl, wahlweise zu einem Thema, nach Summe offener Teilzahlen mit Grund
  (`ReadingReason`, Punkte als Konstanten in `ReadingAdvice`; Tabelle in
  [stand-schnittstelle.md](../fachlogik/stand-schnittstelle.md)). Keine verdeckte Formel, keine KI.
- Grundlage: Status-quo-Sicht (sichere Aussagen „gilt“/„überholt“, Seite überholt/Entwurf), Begriffe aus den
  gespeicherten Erwähnungen (Nomen und Eigennamen), Änderungsprotokoll nur gelesen (neu oder Inhalt geändert, 90 Tage).
- Verweise zwischen Seiten und Aufrufe zählen noch nicht, weil der Core dafür keinen Bestand hat.
- Das Protokoll liest nur die Quellen der Auswahl (bei genau einem Space nur diesen), je Quelle in einem Gang durch den
  Index (`ChangeLog#latestContentChanges`), ohne Zeilen vorzuhalten. Gemessen mit 72 000 Zeilen: 83 ms für einen Space,
  283 ms für eine ganze Quelle.

## Umsetzung FL6 Teil 3 (mögliche Widersprüche und Ablösungen, Core)
- `StatementConflicts.of(view)`: reine Funktion über die Status-quo-Sicht. Sie liefert Hinweise und ändert nie einen
  Stand. Schnittstelle: [stand-schnittstelle.md](../fachlogik/stand-schnittstelle.md).
- Möglicher Widerspruch: gleiche Sachlage (Art, Handelnder/Träger, Prädikat, alle übrigen Beteiligten mit Rolle,
  Präposition und Schlüssel aus `GlossaryDerivation#keyOf`; ohne Genus verbi), beide sicher und „gilt“, gleiche
  bestimmte Verbindlichkeit, andere Verneinung. Soll gegen Ist („muss“ gegen „nicht“) gehört zu FL8.
- Ablösung nur als Verweis: gleiche Sachlage mit anderer Verneinung („gilt“ gegen „überholt“) oder dieselbe Seite mit
  gleichem Prädikat und Betroffenem und anderem Handelnden.
- Nie verglichen: unsichere Aussagen, Zustandswechsel, Nebensätze, Vorstufen-Kennzeichen Bedingung, zwei Aussagen
  desselben Satzes derselben Seite.
- Laufzeit an 3000 erzeugten Seiten mit 60 000 Aussagen: rund 0,13 s im Container.
- Reine Auswertung, abbrechbar, speichert nichts. Laufzeit: 3000 erzeugte Seiten mit 60 000 Aussagen rund 0,4 s im
  Container, samt Begriffsableitung über `GlossaryDerivation`.

## Umsetzung W3: Prüfung durch Menschen (Core)
- Ein Eintrag je Ziel: Die Prüfung einer Aussage (`CONFIRMED`/`REJECTED`) ist ein zweites Feld im selben
  `StandingDecision` wie der Stand, keine zweite Ablage und nie in den Analysedateien. Prüfung nur an Einträgen zu
  Aussagen, nie an der Seite. Stand und Prüfung werden unabhängig gesetzt und zurückgenommen; ein Eintrag ohne beides
  wird gelöscht. Setzen des Stands behält die Prüfung und umgekehrt.
- Ablage eines Analysators: MaltParser und KI-Analysator bilden gleich lautende IDs (`…#s<n>.v<m>`), deshalb gehört der
  Analysator (`AnalyzerChoice`) bei Aussagen zum Schlüssel (`StandingDecision.Target`), bei der Seite `null`. Er kommt
  aus der Analyse, in der die Aussage gefunden wurde, nie aus der Einstellung. Einträge ohne Angabe gelten für den
  MaltParser. Eine Prüfung am MaltParser ändert die KI-Aussage mit derselben ID nicht und umgekehrt.
- Eine Stelle: `DecisionReviews` (Port `StatementReviews`) legt die Prüfung beim Lesen über die gespeicherte Analyse,
  angebunden an `StatementAnalysis` und von dort an `KnowledgeGraph` (alle Lesewege des Graphen, damit Stand, Begriffe,
  Glossar, Export) und an `StatementAnalysis#read` (Aussagen-Reiter). Nie auf einem Weg, der liest und
  zurückschreibt (Prüfschritt, Entzug von Vorstufe oder Urteilen): Diese lesen weiter die Ablage selbst.
- Vorrang: Die Prüfung aus der Entscheidung gilt; ohne sie bleibt der gespeicherte Wert der Aussage (in der Regel
  `UNREVIEWED`). Daraus folgen `CountedStatements` (verworfene zählen nicht), die Herkunftsanzeige und der JSON-LD-Wert
  `sh:verificationStatus`.
- Rechte wie beim Stand: sehen wie die Seite, setzen und zurücknehmen mit `sync`; eine im Bestand gesperrte Seite
  (ADR 0014, `HoldingState.LOCKED`) kann weder geprüft werden noch Stand annehmen: `review`, `withdrawReview`,
  `decide` und `clear` (Aussage wie Seite) lehnen mit `LockedPageException` ab, ihre Analyse und ihre Entscheidungen
  bleiben lesbar. Die Sicht nennt gesperrte Seiten (`StatusQuoView#lockedPages`), der Reiter „Stand“ sperrt Pillen und
  Löschen vorab („Seite ist gesperrt; der Stand lässt sich nicht ändern“); lehnt der Core ab, liest er neu und sagt „Stand
  nicht gesetzt: Die Seite ist gesperrt.“ Stand und Prüfung teilen sich die Revision des Eintrags (ein Zähler, ein
  Konflikt).
- Protokoll: Setzen ist `review`, Zurücknehmen eine eigene Zeile `unreview`, beide mit dem Wert der Prüfung; `remove`
  (Stand gelöscht, Quelle entfernt) trägt die zuletzt gültige Prüfung mit.
- Beobachter: Jede Änderung meldet `StatusQuo#subscribe` (ADR 0027, kein Polling). Gesetzte, zurückgenommene oder mit
  der Quelle entfernte Prüfungen melden zusätzlich `StatusQuo#subscribeReviews`; der Harvester gibt das an
  `KnowledgeGraph#analysesChanged` weiter, damit Graph, Aussagen und Verben ohne Klick nachziehen.
- Sicht: Eine verworfene Aussage fehlt in den Einträgen und Zahlen, ihre Entscheidung steht in
  `StatusQuoView#rejectedDecisions`, damit die Prüfung zurückgenommen werden kann; veraltet ist sie nicht. Veraltet
  (`staleDecisions`) sind auch reine Prüfungen verschwundener Aussagen, aber nur Einträge des gelesenen Analysators.
- Ablage `standing-decisions/v2`: Stand, Prüfung und Analysator je optional; `v1` wird weiter gelesen.
  Ein älterer Stand der Anwendung lehnt `v2` mit klarer Meldung ab.
- Bedienung (W3-Bedienung): Im Overlay „Belege“ der Fachsicht (Analysen und Seitenreiter „Graph“) stehen rechts in der
  Fußzeile jeder Belegzeile ✓ und ✕ (`app.ui.controls.ReviewToggle`, gezeichnet, Größe der Icon-Knöpfe der
  Reiterzeile, ohne Fachlogik und auch für die Fachbegriffe-Liste nutzbar). Ohne Rückfrage; ein Klick auf den gesetzten
  Knopf nimmt zurück. ✓/✕ setzen den festen Wert ohne Revisionsprüfung; Zurücknehmen übergibt die Revision der
  Entscheidung, die das Overlay gezeigt hat (beim Füllen abseits des EDT über `StatusQuo#reviews` gelesen, das auch das
  Recht nennt: `sync`, keine gesperrte Seite; nach eigenem Speichern die zurückgegebene). Bei Konflikt keine Rückfrage:
  Die Zeile zeigt den aktuellen Wert, der eine Statusbereich oben gelb „inzwischen geändert“. Lehnt der Core ab,
  springt der Knopf zurück, eine rote Meldung nennt die Aussage; ohne Recht sind beide Knöpfe gesperrt sichtbar.
  Geschrieben wird abseits des EDT; die Zeilen springen nicht, solange das Overlay offen ist (verworfen: Satz gedämpft,
  Pille „verworfen“ dunkel; bestätigt: „von Hand bestätigt“; zurückgenommen: die vorherige Stufe). Der Graph zieht über
  den Beobachter nach, sobald das Overlay zu ist. Der Analysator kommt aus der Analyse, in der die Aussage steht.
  Nichts scheitert still: Scheitert ein Schreiben erst, nachdem das Overlay zu (oder neu gefüllt) ist, meldet
  `EvidencePopup#onClosedFailure` es dem Reiter; dessen Statuszeile zeigt rot „Entscheidung zu „…“ nicht gespeichert“
  mit Kreispfeil, der dasselbe Schreiben wiederholt (derselbe Wert, beim Zurücknehmen dieselbe Revision), bei einem
  Konflikt beim Zurücknehmen gelb „… inzwischen geändert“ ohne Wiederholen. Der Seitenlink bleibt blau; Tastaturfokus
  zeigt der gelbe Ring wie bei den übrigen Knöpfen, der erste Fokus beim Öffnen nicht.
  Jeder Beleg trägt seinen Analysator (`Evidence#analyzerId`); Lesen, ✓/✕ und Zurücknehmen nennen ihn ausdrücklich
  (`StatusQuo#reviews/review/withdrawReview` mit `AnalyzerChoice`, die alten Aufrufe ohne ihn bleiben), damit ein
  Wechsel der Einstellung zwischen Anzeige und Klick nicht in der anderen Ablage landet. Eine im Bestand gesperrte Seite
  (ADR 0014) behält ihre lesbare Analyse, ist aber nicht prüfbar: `Reviews#locked`, gesperrte Knöpfe mit „Seite ist
  gesperrt; Aussagen lassen sich nicht prüfen“, der Core lehnt mit `LockedPageException` ab. Nur der Titel selbst öffnet
  die Seite, nicht der leere Platz bis ✓/✕; nach einer Meldung im Overlay (Neuaufbau) kehrt der Fokus dorthin zurück, wo
  er war. Der Seitenreiter „Graph“ hält eine Meldung des Core auch zurück, solange das Overlay noch lädt.

## Umsetzung W6: Begriffswissen für Chat und Suche (Core)

Freigegeben vom Architekten am 22.11.2026 als Absicht für „Wissen in Chat und Suche“. Der Core liefert nur Port und
Tests; den Chat-Anschluss (Werkzeug, System-Prompt) baut der Chat-Strang, die zuschaltbare Suchmethode „Fachbegriffe“
(Standard aus) der Strang Suchqualität.

- `TermKnowledge#answer(caller, Begriff, Bereich, Delivery, Abbruch)` auf der Begriffsgruppe der Fachsicht
  (`DomainTermGraph#group`, derselbe Aufbau wie `around`: der Begriff, seine Synonyme und alle ihre Verb-Partner, die
  stärksten zuerst, ohne Nachbarn der Partner): der bestätigte Fachbegriff oder das Synonym, dazu die unmittelbar daran hängenden Synonyme, je Begriff die
  Aussagen (`Subjekt —Verb→ Objekt`) mit Vertrauensstufe, Zahl der Belege und Belegseiten als `PageKey` mit Space und
  Titel, außerdem die Nachbarbegriffe. Gezählt wird genau wie in der Fachsicht: Standardansicht, verworfene Aussagen
  nie, veraltete Seiten ausgelassen und gezählt. Nur lesen, kein KI-Aufruf, nichts gespeichert.
- Verneinung, Verbindlichkeit (Modalverb) und Zeitbezug trennen Aussagen: Seit W9 ist jede Verb-Kante der Fachsicht
  schon eine Aussage mit diesen Merkmalen (`DomainTermGraph.Qualifiers`); das Begriffswissen übernimmt sie Kante für
  Kante und teilt nicht mehr selbst. „Prüft“ und „prüft nicht“ sind nie eine Aussage. Die Kante faltet „nicht
  bestimmt“ in „schlicht“; dem Chat meldet `TermKnowledge` je Merkmal `UNKNOWN`, solange keine Belegstelle der
  Aussage es bestimmt; bei gemischten Belegen gilt der bestimmte Wert.
- Stufe je Aussage (aus ihren Belegen): `ABWEICHEND`, wenn ein Beleg abweicht und keiner geprüft ist, sonst die höchste Stufe der Belege.
  Reihenfolge geprüft, einig, ungeprüft, abweichend, innerhalb der Stufe nach Belegen. Ungeprüftes kommt mit, trägt
  aber seine Stufe („Was ich sehe, sieht der Bot“: Die Fachsicht zeigt es ebenfalls).
- Bereich im Core erzwungen: Gelesen werden nur die Analysen der Seiten des Bereichs (`KnowledgeGraph#analysed` mit
  Seitenauswahl, Rechte je Seite), nie die ganze Ablage, höchstens `MAX_PAGES` (500) Seiten in fester Ordnung (Quelle,
  Seite). Ein größerer Bereich wird gekappt und `Limits` nennt Seiten im Bereich und gelesene.
- KI-Freigabe im Core erzwungen (ADR 0028, ADR 0033 Punkt 8): Jede Seite, deren Aussagen in der Antwort stehen, geht
  an die `Delivery` des Aufrufers, bevor die Antwort herausgeht; genau diese, nicht der ganze Bereich und nicht die
  Seiten gekappter Aussagen. Wirft die Meldung, gibt es keine Antwort. Wer nichts an ein Modell gibt (Suche), übergibt
  ausdrücklich `Delivery.NONE`.
- Obergrenzen je Antwort: 5 Begriffe, 20 Aussagen, 3 Belegseiten je Aussage, 10 Nachbarbegriffe; was gekappt wurde,
  steht in `Limits` (auch gekappte Belegseiten, fehlende Kanten des Begriffs oder seiner Synonyme wegen der Knotengrenze
  der Fachsicht und ausgelassene veraltete Aussagen). Ist die Eingabe kein
  Fachbegriff, nennt die Antwort bis zu 5 ähnliche (`NOT_A_TERM`) und liefert keine Seite.
- Nachbarbegriffe nur aus den gelieferten Aussagen: Was die Grenze von 20 Aussagen kappt, erreicht den Aufrufer auch
  nicht als Nachbar.
- Quellen mit offenem Löschen (#1090: entfernt oder umgestellt, auch wartend oder in Selbstheilung) liest der Knowledge
  Graph nicht mehr, an einer Stelle für alle Leser (`Harvester#analysedSources` als `activeSources`): Graph, Fachsicht,
  Begriffswissen, Chat und Suche. Damit meldet auch die `Delivery` keine solche Seite.
- Zugang: `Harvester#termKnowledge`, `Workspace#termKnowledge`.
- Nachtrag (Abnahme W7): Auch „ist ein Fachbegriff“, Synonyme und Vorschläge entscheidet der Kern nur nach dem
  Bereich (`DomainTermGraph#groupWithin`, die Seiten werden einmal gelesen): Ein Eintrag zählt nur, wenn einer seiner
  Geltungsbereiche global ist oder eine lesbare Seite des Bereichs abdeckt (Quelle, Space), auch eine noch nicht
  analysierte (Space aus den Kopfdaten des Bestands, `KnowledgeGraph#place`, nie der ganze Snapshot; nur
  nachgeschlagen wenn nötig, ein Abbruch wirkt schon dabei). Dazu die KI-Freigabe
  (`AiRelease`): Ein Name, der nur in Quellen ohne Freigabe gilt, geht nur mit, wenn eine schon freigegebene Seite des
  Bereichs darin liegt oder die Antwort eine Seite darin liefert und damit freigibt; global geltende Einträge bleiben.
  Mit `Delivery.NONE` (Suche, nichts geht an ein Modell) entfällt nur diese Freigabe-Prüfung, der Bereich gilt
  trotzdem. Ein Synonym zählt nach dem Geltungsbereich seiner Beziehung zum gesuchten Begriff, nicht nach seinen
  übrigen Einträgen. Ein Fachbegriff, der nur außerhalb des Bereichs gilt, wird auch über ein Synonym kein Knoten
  (gezählt wird wie in der Fachsicht). Erst filtern, dann auf fünf Begriffe kappen und Ausgelassenes zählen; als
  ausgelassen zählt nur, wer auch ohne Seiten unfreigegebener Quellen nennbar wäre. Für „schon freigegebene Seite des
  Bereichs“ prüft der Kern alle Bereichsseiten der Quelle (`Area#coveredReleased`), nicht nur die zufällig schon
  nachgeschlagenen. Fällt ein Name erst an den tatsächlich gelieferten Seiten durch (seine Seite fiel einer Grenze
  zum Opfer), wird ohne ihn neu gebaut; geliefert werden nur die Seiten des letzten Baus, und scheitert der gesuchte
  Begriff selbst, gibt es keine Antwort und keine Lieferung.

## Umsetzung W9: eine Kante je Aussage in der Fachsicht (Core und App)

Auftrag des Architekten, Form A des UX-Managers.

- Core: `DomainTermGraph#collect` trennt Verb-Kanten nach Verneinung, Modalverb und Zeitbezug
  (`DomainTermGraph.Qualifiers`); „prüfen“ und „nicht prüfen“ sind zwei Kanten mit eigenen Belegen und eigener Stufe,
  ein Beleg zählt nie für die Gegenaussage. „Nicht bestimmt“ zählt wie schlicht (gilt, ohne Modalverb). Der
  Wortlaut (`Edge#phrase`) nennt Modalverb, Verneinung und Zeitbezug in Grundform („muss nicht prüfen (geplant)“,
  „prüfen (früher)“), „gilt“ nie. Hat kein Beleg eine Grundform (nur die gebeugte Form aus dem Text), entfallen
  Modalverb und „nicht“ im Wortlaut (kein „muss nicht prüft“); der Zeitbezug bleibt, die Verneinung zeigt der Strich. Veraltete Belege werden mit denselben Merkmalen gezählt.
- Kappung: Die seitenweite Sicht zählt je Knoten alle Belege seiner Kanten (unverändert, Teilen ändert die Summe
  nicht). Die Begriffsgruppe (`group`, für W6) ordnet Partner jetzt nach allen Belegen zwischen Gruppe und Partner,
  nicht nach der stärksten Einzelkante; die Umgebung (`around`) nimmt Knoten wie bisher in Reihenfolge der Kanten.
- „Nur geprüft“ wirkt je Aussage: Eine verneinte Aussage mit geprüftem Beleg bleibt, die bejahte ohne fällt weg.
- App: Beschriftung „nicht prüfen · 2“, Tooltip „Plan – nicht prüfen – Ziel · 2 Belege“ (ohne Legende, ohne
  Hinweis). Linienart nach Vertrauen wie W2; Verneinung als schräger Querstrich (14 px, etwa 60° zur Linie, in
  Linienfarbe, mindestens 2 px stark) 8 px vor der Beschriftung auf der Seite des Startknotens, obenauf; auf
  gestrichelter oder gepunkteter Linie ist die Linie 12 px um ihn durchgezogen; „früher“ und „verworfen“ blasser (Linie und Schrift), nie farbig.
- Mehrere Aussagen eines Knotenpaars liegen nebeneinander gebogen, jede Beschriftung auf ihrem Bogen; der Abstand der
  Scheitel richtet sich nach der Lage des Paars (steil: Breite der Beschriftung, flach: ihre Höhe). Je Paar und
  Richtung höchstens drei Bögen (`GraphSource.MAX_ARCS`): Gibt es mehr, zeigen die zwei mit den meisten Belegen ihre
  Kante, der dritte Bogen heißt „+ n weitere“. Läge die gerade Verbindung zweier äußerer Knoten über der Mitte, biegen alle Bögen des Paars nach außen, weg
  von der Mitte. Diese Bögen nehmen keinen Umweg (er legte sie auf die Nachbarn); das
  Layout-Budget aus W4b bleibt unverändert und zählt sie wie jede Kante.
- Overlay „Belege“: Bei mehreren Aussagen des Paars in dieser Richtung je Aussage eine Überschrift mit Zahl und Stufe,
  nach Belegen geordnet, die geklickte oben und als einzige aufgeklappt (bei „+ n weitere“ die, für die er steht),
  die übrigen zugeklappt (Abschnittskopf `SectionHeader`); kein
  „dafür/dagegen“. Eine Aussage allein zeigt das Overlay wie bisher, Kopf „Plan – prüfen – Ziel“.
- Die Statuszeile zählt jede Aussage als Verbindung, auch die hinter „+ n weitere“.
- Verbraucher der Kante: nur Fachsicht (Übersicht und Reiter „Graph“), Overlay und Begriffswissen (W6: Verb und Merkmale je
  Aussage, den Wortlaut setzt der Chat selbst). Verben-Reiter, Glossar und Stand lesen `DomainTermGraph.Edge` nicht; der Export des
  Bestandsgraphen bleibt unberührt. Andere Listen oder Tabellen der Fachsicht gibt es nicht.

## Fortschreibung (26.11.2026): Belegart „Quellstelle“ für Aussagen aus Programmcode (ADR 0041)

Entscheidung vom 26.11.2026, 14:41 (Antwort auf F41-5, von Angelo aus dem ursprünglichen Chat übermittelt). Noch
nicht umgesetzt.

- **Ein Bestand.** Bestätigte fachliche Erkenntnisse aus Programmcode (COBOL, JCL, Natural) liegen in diesem
  Fachlogik-Bestand. Es entsteht kein zweiter Bestand für Regeln aus Code. Technische Parserfakten bleiben
  Artefakte am Programm (ADR 0041, Punkt 3).
- **Der Beleg wird eine gemeinsame Abstraktion mit zwei Arten.** Bisher gilt: „Beleg bleibt der Originalsatz“.
  Das gilt weiter für Aussagen aus Text und heißt jetzt Textbeleg. Neu ist der Quellstellenbeleg.

  | Belegart | Inhalt | Prüfung |
  | --- | --- | --- |
  | Textbeleg | Originalsatz, Dokumentrevision, Textposition | Wortabgleich im Core und die bestehenden Prüfverfahren, unverändert |
  | Quellstellenbeleg | Programmidentität, Quellrevision, Zeilenbereiche, Parserfakten, Analyseversion | technische Belegprüfung und Bestätigung durch einen Menschen (W3) |

- **Der Wortabgleich gilt nicht für Quellstellenbelege.** Eine Bedingung wie `IF KZ-STATUS = 'A'` enthält die
  Inhaltswörter der fachlichen Aussage nicht. An seine Stelle tritt: Die Quellstelle muss in der genannten
  Revision vorhanden sein und zum Parserfakt passen, und die Aussage zählt erst mit der Bestätigung durch einen
  Menschen als fachlich bestätigt.
- **Ein Quellstellenbeleg kann mehrere zusammengehörige Codestellen nennen** (mehrere Paragraphen, Programme oder
  Copybooks).
- **Ist und Soll.** Eine bestätigte Aussage über Programmcode beschreibt, was das Programm tut (Ist). Sie ist
  keine geltende Soll-Regel, denn ein Programm kann fehlerhaft sein. Das Soll kommt weiter aus Vorschriften und
  Fachtexten. Damit liefern Quellstellenbelege die Ist-Seite für den Soll-Ist-Vergleich (FL8); dessen Befunde
  bleiben Hinweise, keine Feststellungen.
- **Veraltete Belege.** Ändert sich der Programmcode oder eine maßgebliche Abhängigkeit (etwa ein Copybook), gilt
  der Beleg als möglicherweise veraltet. Eine frühere Bestätigung wird nie ungeprüft auf die neue Revision
  übertragen. Das entspricht „Beleg veraltet“ bei geprüften Aussagen aus Text.
- Die KI-Freigabe je Seite (ADR 0028) und die Rechteprüfung gelten für Programmcode wie für jede andere Seite.
