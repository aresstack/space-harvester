# ADR 0020: Semantische Absätze

- Status: **Angenommen, im Core umgesetzt** (Strang V, 19.10.2026; Produktentscheidung von Angelo, 19.10. 01:39).
  Ergänzt [ADR 0003](0003-lucene-cache.md) (Lucene-Cache), [ADR 0011](0011-embeddings-ueber-externen-ki-dienst.md)
  (Embeddings nur über den externen Dienst) und [ADR 0012](0012-core-als-gemeinsame-bibliothek.md) (Parität).
- Umgesetzt (Teil von #181): Prozessor `passages`, `PassageSegmentation`, `OpenNlpSentenceSegmenter`,
  `PagePassages.read` für die Leseansicht, `PassageQueries` (`harvester.passages()`), REST
  `GET /api/v1/sources/{sourceId}/pages/{pageId}/passages`; Suchschnipsel aus Absätzen (`PassageSnippets`).
- Offen: Reiter „Absätze“ in der App (#354, Strang L); Passage-Embeddings und Re-Ranking (#181, eigene PRs).

## Kontext

Angelo will die Absätze einer Seite sehen, deren Grenzen dort liegen, wo sich das Thema ändert, nicht nur die
Absätze des HTML. Sie sollen später als Einheiten für Snippets, Embeddings und Re-Ranking dienen. Das Verfahren gibt
es schon: askai-java8, Branch `arch` (`research-knowledge-pipeline/…/PassageSegmentation.java`,
`BoundaryMode.SEMANTIC`, Satzgrenzen aus `research-text-opennlp/…/OpenNlpSentenceSegmenter`), unverändert übernommen
in den Enterprise AI Client (`astu/acropolis/…/passage/PassageSegmentation`, Satzgrenzen aus
`IndexSentenceSegmenter`, verdrahtet in `CompositionRoot` über `SemanticPassageSplitter`).

## Entscheidung

1. **Kopie, keine Neuentwicklung.** Ablauf und Werte wie im Client: Struktur zuerst (Überschriften, Absätze,
   Listenpunkte, Tabellenzeilen, Zitate und Code sind harte Grenzen); nur in Absätzen mit mehr als zwei Sätzen misst
   die Zerlegung den Themenwechsel als Kosinus der mittleren Satzvektoren eines linken und eines rechten Fensters
   (3 Sätze, Schwelle 0,35, mindestens 2 und höchstens 8 Sätze je Passage, über 350 Token an Wortgrenzen geteilt).
2. **Satzgrenzen mit OpenNLP**, nur dem Satzdetektor und dem deutschen Satzmodell (`opennlp-models-sentdetect-de`
   1.3.0, SHA-256 geprüft, dieselben Versionen wie `:nlp-parser`). Ohne ladbares Modell gilt der regelbasierte
   `SentenceSplitter` des Client. Das Verfahren steht in der Prozessorversion.
3. **Satzvektoren vom globalen Embedding-Modell** des Prozessors `embedding`, also vom externen KI-Dienst, nur für
   Quellen in `external-processing-sources`. Kein lokales Modell im Core.
4. **Rückfallebene nach Struktur, kein Fehler.** Ohne Modell (`no-model`), für nicht freigegebene Quellen
   (`source-not-approved`) oder bei nicht erreichbarem Modell (`model-unavailable`) entstehen die Passagen nur aus
   der Struktur, gekennzeichnet mit `mode=structure` und dem Grund. Ein nicht erreichbares Modell macht das
   Ergebnis sofort veraltet, es wird beim nächsten Abgleich oder Abruf wiederholt.
5. **Teil der vorhandenen Anreicherung, kein zweiter Suchmechanismus.** Ein Prozessor je Seite, gerechnet beim
   Laden und nach jedem Abgleich (mit Modell im Hintergrund), abgelegt im vorhandenen `LuceneEnrichmentStore` mit
   Revision und `inputSha256`. Gespeichert werden nur Stellen im projizierten Text (UTF-16), Satzbereich,
   Überschriftenkette, Art des Blocks, Grund der Grenze und bei Themenwechseln die gemessene Ähnlichkeit, nie der
   Text selbst und keine erfundene Konfidenz. Den Wortlaut liefert `PagePassages.read` aus dem Text mit demselben
   Hash. Ändern sich Text, Modell, Satzverfahren oder Zerlegung, veraltet das Ergebnis.
6. **Rechte wie der Abruf der Seite** (`GET …/pages/{pageId}`, ADR 0014 Punkt 4): ohne `content` keine Absätze.
7. **Suchschnipsel aus Absätzen** wie im Client, wo ein Schlagworttreffer seine Passage als Auszug bringt
   (`RetrieveKnowledgeUseCase`): `PassageSnippets` legt sich um den Volltextindex; Treffer und Rangfolge bleiben die
   des Index, nur der Auszug ist der Absatz mit den meisten Suchwörtern (höchstens 480 Zeichen um die erste
   Fundstelle). Das Ergebnis trägt dafür `textSha256` des Textes, zu dem seine Stellen gehören (Format 2); passt er
   nicht zum abgelegten Text, fehlen Absätze oder enthält keiner ein Suchwort, bleibt das Fenster des Index. Die
   Schlagwortsuche liefert Auszüge weiter nur mit `content`.

## Abweichungen vom Client

- Passagen sind Ausschnitte des Textes statt neu zusammengefügter Sätze, damit die Leseansicht zu ihnen springen
  kann. Die Blöcke einer Confluence-Projektion sind ihre Zeilen, wie die Strukturblöcke aus dem Dokumentbaum im Client.
- Die Sätze aller Absätze gehen in Stapeln an das Modell statt je Absatz (gleiche Vektoren), höchstens 2 000 je
  Seite, danach nur Struktur mit Warnung.
- Kein eigenes Embedding je Passage und kein `TopicClusterer` in diesem Schritt: Passage-Embeddings gehören in den
  Prozessor `embedding`, sobald er nach Passagen statt nach Token-Budget zerlegt (#181).

## Folgen

- Der Core hängt an `opennlp-tools` und dem Satzmodell (zusammen rund 1,5 MB, Apache-2.0).
- Mit Modell kostet jede Seite einen Embedding-Aufruf je 32 Sätze zusätzlich zum Prozessor `embedding`.
