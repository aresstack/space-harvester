# ADR 0024: Aussagenanalyse auf Knopfdruck und globaler Knowledge Graph

- Status: **Angenommen, Umsetzung läuft** (Strang G, 09.11.2026; Issue #499, Produktentscheidung Angelo 09.11.2026:
  „Der Knowledge Graph wird bereits in dieser ersten Ausbaustufe global und persistent angelegt. Er wird allerdings
  ausschließlich durch manuell gestartete Analysen befüllt.“).
- Baut auf: [ADR 0005](0005-deutsches-ud-parsing.md) (`:nlp-parser`, MaltParser, Modell aus #99),
  [ADR 0019](0019-begriffsindex-und-begriffsbeziehungen.md) (Begriffsindex, bestätigte Begriffsbeziehungen),
  [ADR 0021](0021-bildanalyse.md) (Analyse nur auf Abruf).
- Umsetzung in Schritten: Core (Vertrag, Ablage, Graph-Abfragen), Desktop-Adapter für MaltParser, Reiter
  „Analysen → Aussagen“, Abnahmetests.

## Kontext

`:nlp-parser` liefert seit #98 für deutschen Text Erwähnungen, Aussagen mit Rollen, nominale Bezüge und eine
Kernsicht; das Modell `maltparser-de-gsd-v1.0.0` ist seit #99 veröffentlicht und gepinnt. Eingebunden war das Modul
nirgends. #499 verlangt, eine geöffnete Seite in der Desktop-App auf Knopfdruck zu analysieren, das Ergebnis
dauerhaft zu speichern und die Aussagen aller so analysierten Seiten quellenübergreifend recherchierbar zu machen –
ohne Parser im Dienst, ohne Hintergrundanalyse, ohne KI-Dienst und ohne automatische Entitätsverschmelzung.

## Entscheidung

1. **Port im Core, Parser nur in der Desktop-App.** Der Core kennt nur `StatementAnalyzer` (Paket
   `application.knowledge`) mit einem parserneutralen Ergebnis `ParsedStatements` (Sätze, `Mention`, `Statement`,
   `Argument`, `Attachment`, Kernsicht-Kennzeichen, Komponenten, CoNLL-U). Die Desktop-App belegt den Port mit
   MaltParser aus `:nlp-parser` über `Harvester.Builder#statementAnalyzer`; Core und Dienst hängen nicht an
   `:nlp-parser` (ArchUnit-Regel `noParserModelInTheCore` in `CoreModuleTest`). Ohne Analysator ist
   `StatementAnalysis#available()` `false`, gespeicherte Analysen bleiben lesbar, ein Klick meldet `NOT_CONFIGURED`.
2. **Nur ausdrücklich.** `StatementAnalysis#analyze` ist der einzige Weg, der den Analysator ruft, für genau eine
   Seite. Der Analysator ist **kein** `EnrichmentProcessor`: weder Abgleich, Cache-Füllung, Hintergrundindizierung,
   Suche, Export, Versions- oder Freigabe-Neuberechnung noch „Analysen neu berechnen“ können ihn starten, weil er in
   keiner Prozessorliste steht. Lesen (`read`, Graph-Abfragen) rechnet nie.
3. **Eingabe ist der Lesetext.** Analysiert wird genau die Textprojektion, die der Reiter „Text“ zeigt
   (`Harvester#pageText`, `PageText`). Spannen von Erwähnungen und Prädikaten beziehen sich darauf, sodass der Sprung
   zur Fundstelle ohne Umrechnung funktioniert. Ein Ergebnis mit Spannen außerhalb des Texts oder Beteiligten ohne
   Erwähnung wird verworfen.
4. **Eigene Ablage je Seite außerhalb des Caches, nicht die Anreicherungsablage.**
   `FileKnowledgeGraphStore` legt je Seite eine Datei `<dataDirectory>/knowledge-graph/pages/<sha256(quelle, seite)>.json`
   (Schema `page-statements/v1`) mit Seite, Space, Titel, Revision, `inputSha256`, Analysator-ID und -Version
   (Parser, Modell samt Prüfsumme, Regeln), Zeitstempel, Ergebnis und CoNLL-U ab, atomar ersetzt (temporäre Datei,
   `ATOMIC_MOVE`). Gründe gegen die Lucene-Anreicherungsablage: Sie liegt im Cache und verschwindet mit dessen
   Neuaufbau, eine manuelle Analyse ist aber Nutzerarbeit (wie Begriffsbeziehungen und geprüfte Fakten); sie würde den
   Prozessor in die automatischen Ketten ziehen (Punkt 2); und ein Schritt „Ergebnis in die Ablage, Projektion in den
   Graphen“ über zwei Ablagen wäre nicht atomar. Das Schema der Anreicherungsablage bleibt unverändert.
5. **Der Graph ist eine Projektion, kein zweiter Speicher.** `PageGraph.of(analysis)` leitet deterministisch Knoten
   (`DOCUMENT` = Seite in der analysierten Fassung, `MENTION`, `STATEMENT`) und typisierte Kanten ab: Dokument →
   Aussage/Erwähnung (`CONTAINS`), Aussage → Erwähnung je Rolle (`ACTOR`, `AFFECTED_OBJECT`, `RECIPIENT`,
   `PREPOSITIONAL_OBJECT`, `INSTRUMENT`, `TIME`, `CIRCUMSTANCE`), Erwähnung → Erwähnung (`NOMINAL`, kein Besitz). Jede
   Kante trägt Herkunft (`PARSER`, `COORDINATION`, `CASE_HEURISTIC`, `LEXICON`, `STRUCTURE`), UD-Relation,
   Präposition, Beleg (Satz) und Sicherheit. IDs enthalten Quelle, Seite und die ersten 12 Zeichen des Eingabe-Hashs
   (`quelle/seite@fassung#s<Satz>.t<Token>` bzw. `.v<Verb>`). Weil eine neue Analyse die Datei der Seite ersetzt,
   ersetzt sie genau deren Projektion: keine verwaisten oder doppelten Knoten, andere Seiten unberührt, Wiederholung
   idempotent. Fehler und Abbruch schreiben nichts.
6. **Global, mit Rechten je Seite.** `KnowledgeGraph` fragt über alle gespeicherten Analysen aller Quellen und Spaces:
   `pages`, `page`, `search` (Begriff, normalisiert wie der Begriffsindex) und `links` (Verbindungen einer Seite zu
   anderen analysierten Seiten). Eine Seite zählt nur, wenn der Aufrufer ihren Inhalt lesen darf
   (`AccessGuard#canRead`, aktueller Space aus dem Bestand, sonst der beim Analysieren). Treffer, Verbindungen und
   Zähler entstehen ausschließlich aus sichtbaren Seiten.
7. **Keine Identität aus Wortgleichheit.** Gleiche normalisierte Grundform oder Wortform ist eine
   *lexikalische Kandidatenverbindung* (`Match.LEXICAL`); eine bestätigte, sichtbare Begriffsbeziehung (ADR 0019,
   `TermRelations#expansions`, nicht `RELATED`) ist eine *begriffliche* Verbindung (`Match.CONFIRMED_TERM_RELATION`).
   Beides bleibt eine Verbindung zwischen eigenständigen Erwähnungsknoten; es entsteht nie ein gemeinsamer
   Entitätsknoten. Pronomen bleiben `unresolved` und verbinden nichts. Begriffsbeziehungen werden nur gelesen.
8. **Stand statt stiller Neuberechnung.** `CURRENT` nur, wenn Eingabe-Hash und Analysator (ID und Version) zum
   aktuellen Stand passen; sonst `STALE` mit Grund `text-changed`, `analyzer-changed`, `no-text` oder (im Graphen)
   `page-missing`. Ohne eingerichteten Analysator zählt nur der Text. Veraltete Seiten erscheinen gekennzeichnet,
   nicht als aktuelle Aussagen; ersetzt werden sie erst durch den nächsten Klick.
9. **Ausdrücklicher Auftrag für einen Umfang (#499 Abschnitt E).** `StatementAnalysisJobs` analysiert eine Seite,
   eine Seite mit allen Nachfahren, einen Space oder eine Quelle als Auftrag `STATEMENT_ANALYSIS` über `Jobs`
   (Fortschritt, Abbruch, Diagnose). Er ermittelt die Seiten mit `PageNavigation#children` samt aller Cursor, nie
   über den sichtbar geladenen Baum der App und ohne dessen Grenze; Zyklen und doppelte Seiten zählen einmal. Seiten
   ohne Inhaltsrecht werden weder geladen noch gezählt noch in Ereignissen genannt, Spaces ohne Freigabe still
   übergangen. Erst nach vollständiger Ermittlung lädt der Auftrag je Seite den Inhalt über den geschützten
   Seitenabruf (`PageQueries#page`, Cache sonst Quelle) und ruft `StatementAnalysis#analyze` – jede Seite für sich
   atomar; ein Abbruch behält fertige Seiten. Aktuelle Seiten werden übersprungen, außer bei „Neu analysieren“.
   `estimate` zählt für den Bestätigungsdialog bis zur Sicherheitsgrenze, ohne Inhalte zu laden; überschreitet der
   Umfang die bestätigte Grenze, scheitert der Auftrag vor der ersten Analyse (`SCOPE_TOO_LARGE`), nie still gekürzt.
   Nicht ermittelbare Zweige melden `INCOMPLETE`, nachdem die gefundenen Seiten verarbeitet sind. Seiten laufen
   nacheinander; das Modell wird einmal geladen und geteilt. Kein Abgleich, Zeitplan oder Folgeauftrag startet ihn.
10. **Datenschutz.** Kein Text und keine Aussage geht an einen externen Dienst; der Analysator rechnet lokal. Dateinamen
   enthalten keine Kennungen im Klartext. Protokolle nennen bei unlesbaren Dateien nur die Ausnahmeklasse, nie Inhalt,
   Suchbegriffe oder Seitentitel.

## Alternativen

| Option | Bewertung |
|---|---|
| Ergebnis als `EnrichmentProcessor` in der Lucene-Anreicherungsablage | Verworfen: im Cache (Neuaufbau löscht Nutzerarbeit), Gefahr automatischer Läufe, keine Atomarität mit dem Graphen (Punkt 4). |
| Graphdatenbank (Neo4j, JanusGraph) oder RDF-Store | Nicht Teil des MVP (#499); zusätzliche Infrastruktur und Abhängigkeiten ohne Not. |
| Graph-Kanten separat in einer Datei für alle Seiten | Verworfen: jede Analyse müsste die Gesamtdatei umschreiben; Projektion je Seite reicht und ist atomar. |
| Gleichlautende Erwähnungen zu einem Entitätsknoten zusammenführen | Ausdrücklich ausgeschlossen (#499); Entitäts- und Koreferenzauflösung sind spätere Schritte. |
| MaltParser im Dienst laden | Ausgeschlossen (#499): keine Parser-Initialisierung im Microservice. |

## Folgen

- Speicher: Im Speicher hält der Core nur die Analysen ohne CoNLL-U, erst beim ersten Zugriff gelesen; die
  Syntaxdetails liest `KnowledgeGraphStore#conllu` aus der Datei. Abfragen gehen linear über die analysierten Seiten –
  für manuell befüllte Graphen ausreichend; ein Index wird nachgezogen, wenn Messungen es verlangen.
- Der Stand einer Seite braucht ihren aktuellen Eingabe-Hash; `KnowledgeGraph` merkt ihn je Seite und Payload
  (`rawSha256`), damit nicht jede Abfrage jede Seite neu projiziert.
- Modellwechsel (#99) und Regeländerungen an `:nlp-parser` heben die Analysator-Version; gespeicherte Analysen werden
  `STALE`, nichts rechnet von selbst nach.
- Der Analyse-Export (W) und die technische Inspektion unter „Erweitert → Analyse“ enthalten die Aussagenanalyse
  nicht, weil sie keine Anreicherung ist; ein eigener Export kann über `KnowledgeGraphStore#all` folgen.

- Darstellung (#499 Abschnitt F): `GraphNeighbourhood` liefert fokussierte Teilgraphen (Seite, Knoten oder Begriff,
  ein Schritt, standardmäßig höchstens 50 Knoten und 100 Kanten, mit Kennzeichen „gekürzt“) ausschließlich über
  `KnowledgeGraph`, also mit denselben Rechten je Seite. Seitenverbindungen sind Kanten zwischen eigenständigen
  Erwähnungen (`LEXICAL_CANDIDATE`, `CONFIRMED_TERM_RELATION`). Der Reiter „Analysen → Graph“ zeichnet sie mit Java2D
  (Form statt nur Farbe, gestrichelte Kandidaten, Zoom, Verschieben, Einpassen, „Nachbarn erweitern“, „Zurück“) und
  bietet dieselben Angaben als Tabelle; er lädt den Teilgraphen erst, wenn er sichtbar ist, und rechnet nie.

## Nicht Teil dieser Entscheidung

Wissensgraphgestützter Chat, Entitäts- und Koreferenzauflösung, Ontologie, grafisches Layout, KI-Verifikation,
automatische (nicht ausdrücklich gestartete) Analyse ganzer Spaces, MaltParser-Neutraining, REST-Endpunkte für den Graphen im Dienst.
