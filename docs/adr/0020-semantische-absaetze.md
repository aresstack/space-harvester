# ADR 0020: Semantische Absätze

- Status: **Angenommen, im Core umgesetzt** (Strang V, 19.10.2026; Produktentscheidung von Angelo, 19.10. 01:39).
  Ergänzt [ADR 0003](0003-lucene-cache.md) (Lucene-Cache), [ADR 0011](0011-embeddings-ueber-externen-ki-dienst.md)
  (Embeddings nur über den externen Dienst) und [ADR 0012](0012-core-als-gemeinsame-bibliothek.md) (Parität).
- Umgesetzt (Teil von #181): Prozessor `passages`, `PassageSegmentation`, `OpenNlpSentenceSegmenter`,
  `PagePassages.read` für die Leseansicht, `PassageQueries` (`harvester.passages()`), REST
  `GET /api/v1/sources/{sourceId}/pages/{pageId}/passages`; Suchschnipsel aus Absätzen (`PassageSnippets`).
- Embeddings je Absatz: `EmbeddingProcessor.withPassages(PassageChunks)` (Punkt 8).
- Re-Ranking auf Absatzebene: der Reranker der semantischen Suche bekommt genau den ähnlichsten Absatz (Punkt 9).
- Offen: Reiter „Absätze“ in der App (#354, Strang L).

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
8. **Ein Embedding je Absatz** wie `IndexKnowledgeUseCase.withPassages` im Client: Mit Modell zerlegt der Prozessor
   `embedding` die Seite mit derselben `PassageSegmentation` statt nach Token-Budget und bettet jeden Absatz ohne
   Vorspann ein; ein Absatz über `embedding-chunk-chars` wird wie bisher geteilt. Format der Vektoren, semantische
   Suche und Freigabe bleiben; die Version nennt die Zerlegung (`+passages:<Fingerabdruck>`), vorhandene Embeddings
   veralten einmal und werden aus dem Cache neu berechnet. Die Satzvektoren der Themengrenzen kosten den Prozessor
   `embedding` wie `passages` je einen Aufruf je 32 Sätze.
9. **Re-Ranking auf Absatzebene**: Der Reranker der semantischen Suche (`ModelReranker`, `/rerank`) bekommt je Treffer
   den Titel und genau den ähnlichsten Absatz, wenn die Embeddings aktuell je Absatz sind (Punkt 8), statt eines auf
   2 000 Zeichen aufgefüllten Fensters; ohne aktuelle Absätze (veraltete Revision, Zerlegung ohne Absätze) bleibt das
   Fenster; die Ladereihenfolge der Schlagwortsuche (`ExcerptPriority`) bewertet über Punkt 7 schon den Absatz.
   Reihenfolge nur umordnen, nie verwerfen, und Rechte (nur mit `content`) bleiben.
10. **Stabile Passage-IDs** (EAC-Lücke 5, Kopie von `KnowledgePassageId`): Jeder Absatz trägt
   `<sourceId>:<pageId>@<capture>#p<position>@<zerlegung>-<modell>`. `capture` ist der volle SHA-256 des angezeigten
   Textes, `position` zählt ab 0, `zerlegung` und `modell` sind 8 Hex aus SHA-256 über `PassageSegmentation.fingerprint()`
   und das Embedding-Modell (ohne Modell `structure`). Ändert sich Text, Zerlegung oder Modell, ändert sich die ID.
   Berechnet beim Lesen aus dem gespeicherten Ergebnis, ohne Schemaänderung. `PassageQueries.resolve` und
   `GET /api/v1/passages?id=` lösen sie wie `KnowledgeResourceReader` auf: gefunden mit je einem Nachbarn
   (`found`), sonst alle aktuellen Absätze mit `older-version` (andere Capture) oder `changed` (Zerlegung oder Modell
   gewechselt); eine unbekannte oder nicht sichtbare Seite antwortet gleich mit 404. Abweichungen: Die Seite steht als `sourceId:pageId` statt der Resource-ID des Clients; der Modellteil ist der Modellname aus dem Ergebnis statt des Fingerabdrucks des Embedding-Raums (zwei Dienste mit gleichem Modellnamen geben dieselbe ID, ein Dienstwechsel rechnet über die Prozessorversion ohnehin neu); eine unbekannte Quelle antwortet wie eine unsichtbare Seite; die Versionsteile
   stammen aus dem gespeicherten Ergebnis statt aus der aktuellen Einstellung, ein veraltetes Ergebnis desselben
   Textes behält also seine IDs bis zur Neuberechnung; ohne Absätze gibt es zusätzlich `unavailable`; die ID steht im
   Parameter statt im Pfad, weil sie `#` und Seiten-IDs `/` enthalten.

## Dokumentnavigation

Kopie der Dokumentnavigation des Clients (FA-01 bis FA-05, `BuildDocumentNavigationUseCase`,
`DocumentNavigationBuilder`, `TopicClusterer`) in `application/passages`:

1. **Überschriften zuerst** (FA-01, FA-02): Die Überschriften der Textprojektion bilden die Kapitelhierarchie und
   bleiben unverändert; ein einziges oberstes Kapitel, das die Seite umschließt, wird aufgelöst.
2. **Themenabschnitte nur im Fließtext** (FA-03, FA-04): Hat der eigene Fließtext eines Kapitels (oder die Seite ohne
   Überschriften) mindestens drei Absätze, gruppiert `TopicClusterer` deren Vektoren (Average Linkage, Distanz
   1 − Kosinus, Grenze 0,55, zwei Repräsentanten, wie im Client); aufeinanderfolgende Absätze eines Themas bilden
   einen Abschnitt `origin=topic` unter dem Kapitel, ein einziger Lauf unterteilt nichts. Text vor der ersten
   Überschrift wird immer anspringbar. Bezeichnung ist der erste Satz des Repräsentanten (höchstens 80 Zeichen).
3. **Stellen statt Wiederfinden** (FA-05): Jeder Abschnitt trägt `start`, `end` und einen Anker (60 Zeichen bis zum
   ersten Zeilenumbruch) im angezeigten Text; `TextLocator` entfällt, weil die Absätze schon Stellen sind.
4. **Prozessor `navigation`** nach `passages`, im vorhandenen `LuceneEnrichmentStore`, ohne Schemaänderung. Er bettet
   nichts ein, sondern liest die aktuellen Embeddings je Absatz des Prozessors `embedding` (Punkt 8) für genau diesen
   Text, wie der Client die indexierten Passagen der Fassung. Ohne sie bleiben die Überschriften mit
   `analysis=headings` und Grund: `no-model`, `no-sentence-model` (kein OpenNLP-Satzmodell, wie `NO_SENTENCE_MODEL`
   im Client: kein Clustern über Absätze aus dem Regel-Splitter), `source-not-approved`, `not-embedded` (wird
   wiederholt, sobald die Embeddings da sind), `indexing-failed` (Embeddings dieses Textes gescheitert, wie
   `INDEXING_FAILED`; gilt bis zum Wechsel von Text oder Modell), `no-topic-change`, `model-unavailable`. Bei `analysis=semantic` heißt
   `model-unavailable`, dass das Chat-Modell nicht benennen konnte (Punkt 6). Ein nicht erreichbares Modell ist nie
   ein Fehler der Anfrage.
5. **Lesen** über `Harvester.documentNavigation().page(caller, sourceId, pageId)` → `DocumentNavigationView(state,
   analysis, reason, chapters, title, inputSha256, textSha256)` mit `flatten()` und `find(sectionId)` wie
   `DocumentNavigation` im Client; `inputSha256` und `textSha256` nennen den Text, zu dem die Stellen gehören, damit
   der Reader wie mit der Capture-ID im Client eine verspätete Antwort gegen den gezeigten Text prüft, und `GET /api/v1/sources/{sourceId}/pages/{pageId}/navigation`, Rechte wie
   `…/passages`. Fehlt ein Ergebnis für diesen Text, zeigt die Antwort sofort die Überschriften und stößt die
   Berechnung an.

6. **Benennung über das Chat-Modell** wie im Client: Die unveränderte `ChatSectionLabeler` (Kopie in
   `enterprise-ai-sources`) bekommt über den vorhandenen Chat-Port mit dem Titel der Seite (nur auf diesem Weg, also
   nur für freigegebene Quellen, nie geloggt) je 40 Themenabschnitte eine Anfrage mit höchstens 700 Zeichen je
   Abschnitt und liefert Bezeichnungen aus zwei bis fünf Wörtern; ohne Chat-Modell bleibt der erste Satz. Nur in der
   Anreicherung (Prozessor `navigation`, im Hintergrund nach den Embeddings), nie bei einer Anfrage; eigene Frist
   (60 s) und höchstens zwei Benennungen zugleich; nur für Quellen in `external-processing-sources`. Antwortet das
   Modell nicht, bleiben die Bezeichnungen der Analyse mit `reason=model-unavailable`, und der nächste Lauf versucht
   es wieder. Nach einem Fehler gehen die übrigen Pakete der Seite nicht mehr ans Netz, und fünf Minuten lang benennt
   keine Seite (gleiches Modell, gleicher Dienst): Die Hintergrund-Warteschlange, die Navigation und Embeddings
   teilen, wartet so höchstens eine Frist je Pause. Die Verfahrensversion enthält Modell und einen Fingerabdruck der
   Dienstadresse; ein Wechsel berechnet neu. Abweichung: Der Titel gehört nicht zum Eingabe-Hash des Textes (sonst
   veralteten alle Prozessoren); ändert sich nur der Titel, bleiben die Bezeichnungen bis zur nächsten Text- oder
   Versionsänderung. Bezeichnungen und Texte sind Inhalt und werden nie geloggt, auch nicht die Antwort bei einem
   Lesefehler. Zur Frage „kein KI-Chat im Core“: Entschieden, das ist interne Verarbeitung wie progressive Suche
   (#337) und Bildbeschreibung (#362), kein Chat-Angebot an Benutzer; die EAC-Kopie ChatSectionLabeler ist
   Paritätspflicht.

## Abweichungen vom Client

- Passagen sind Ausschnitte des Textes statt neu zusammengefügter Sätze, damit die Leseansicht zu ihnen springen
  kann. Die Blöcke einer Confluence-Projektion sind ihre Zeilen, wie die Strukturblöcke aus dem Dokumentbaum im Client.
- Die Sätze aller Absätze gehen in Stapeln an das Modell statt je Absatz (gleiche Vektoren), höchstens 2 000 je
  Seite, danach nur Struktur mit Warnung.
- Die Embeddings je Absatz liegen im Prozessor `embedding` statt in einer eigenen Ablage; `TopicClusterer` nutzt
  nur die Dokumentnavigation (unten), die Absatzgrenzen messen weiter die Satzfenster.

## Folgen

- Die Navigation ist ein gespeichertes Ergebnis der Anreicherung statt einer Berechnung je Aufruf; ändert sich das
  Verfahren oder das Modell, wird sie neu berechnet. Labeler-Wechsel löst Neuberechnung aus: Ein anderes Chat-Modell
  (`ai.chat-model`) macht alle gespeicherten Navigationen alt.
- Den Dokumenttitel bekommt das Chat-Modell nicht mit, denn der Prozessor kennt nur Seite und Text.
- Der Core hängt an `opennlp-tools` und dem Satzmodell (zusammen rund 1,5 MB, Apache-2.0).
- Mit Modell kostet jede Seite einen Embedding-Aufruf je 32 Sätze zusätzlich zum Prozessor `embedding`.
