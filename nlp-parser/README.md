# nlp-parser: deutsches Dependency Parsing und objektzentrierte Auswertung (#98)

Eigenständiges Java-21-Modul. Entscheidung und Begründung: [ADR 0005](../docs/adr/0005-deutsches-ud-parsing.md).
Das deutsche Parsermodell trainiert und veröffentlicht #99 (`:nlp-model-training`); dieses Modul lädt es nur.

```
Rohtext ─► OpenNLP (Sätze, Wörter, UPOS, Grundform) ─► MaltParser (HEAD, DEPREL) ─► ParsedDocument (CoNLL-U)
                                                                                  └► ObjectCentricExtraction
                                                                                       ├ Erwähnungen, Aussagen, nominale Bezüge
                                                                                       ├ SalienceProjection (Subjekt → Prädikat → Objekt)
                                                                                       └ MentionStatistics
```

## Benutzen (ohne Spring)

```java
var parser = MaltModelLoader.loadPinned(Path.of("/opt/space-harvester/nlp-model/maltparser-de-gsd-v1.0.0"));
var analysis = new DependencyAnalysis(OpenNlpGermanPreprocessor.fromClasspath(), parser);
ParsedDocument document = analysis.analyze(new DocumentRef("confluence-prod", "123456", "7"), text);
ObjectCentricAnalysis result = new ObjectCentricExtraction(ExtractionSettings.defaults()).analyze(document);
SalienceProjection.Projection view = SalienceProjection.project(result);   // Kern zuerst
Statement details = result.statement(view.core().get(0).statement());      // Empfänger, Zeit, Beleg nachladen
String conllu = ConllU.write(document);                                    // vollständige Rohstruktur
```

Parser, Vorverarbeitung und Auswertung werden einmal erzeugt und von allen Threads geteilt; nichts lädt ein Modell
je Satz.

## Modell bereitstellen

Das Modell liegt im Betrieb als Verzeichnis mit `maltparser-de-gsd.mco` und `model-manifest.json` (Inhalt der
Release-ZIP aus #99). Version und Prüfsummen stehen in
`src/main/resources/com/aresstack/spaceharvester/nlp/malt/german-parser-model.properties`; leer, bis #99 ein
lizenzgeprüftes Modell veröffentlicht hat. `MaltModelLoader` prüft vor dem Laden:

| Prüfung | Fehler, wenn |
|---|---|
| Manifest `maltparser-model-manifest/v1` | fehlt oder anderes Schema |
| `modelId`, `modelVersion`, `configName` | weichen vom hinterlegten Wert ab |
| `maltParserVersion` | nicht 1.9.2 |
| `inputSchema.columns` | mehr als `FORM`, `UPOS` (die Vorverarbeitung liefert keine FEATS, kein XPOS) |
| SHA-256 der `.mco` | weicht vom hinterlegten Wert oder vom Manifest ab |

Herunterladen, nur auf ausdrücklichen Aufruf und nie zur Laufzeit:

```bash
./gradlew :nlp-parser:downloadGermanParserModel   # → nlp-parser/build/nlp-model/<tag>/, SHA-256 der ZIP geprüft
```

## Bauen und testen

```bash
./gradlew :nlp-parser:build     # Tests mit Gold-Analysen und kleinem Testmodell, Lizenz-Gate; ohne Netz, ohne Training
./gradlew :nlp-parser:test -PnlpModelDir=<Verzeichnis>   # zusätzlich Rauchtest mit echtem Modell, Bericht in build/reports/nlp-parser/smoke.txt
```

- `ObjectCentricExtractionTest`: Regeln an handgeschriebenen Gold-Analysen (`src/test/resources/…/extraction`):
  Referenzsatz, Aktiv/Passiv, Genitiv, Dativ gegen Zeitangabe, ohne Akkusativobjekt, Koordination und trennbare
  Verben, gleiche Wörter in mehreren Aussagen, offene Pronomen, Kasus-Rückfall und eine echte Fehlanalyse.
- `OpenNlpGermanPreprocessorTest`: vorhergesagte Wortarten (keine Gold-Daten), Verschmelzungen, Spannen.
- `MaltModelLoaderTest`: alle Prüfungen oben.
- `GermanPipelineTest`: Rohtext → Baum → Kernrelation mit dem Testmodell, 64 parallele Aufrufe auf einem Modell.
- `RealModelSmokeTest` (nur mit `-PnlpModelDir`): Gold- und OpenNLP-Wortarten getrennt bewertet, Kernrelation aus
  Rohtext, Laden, Speicher, Durchsatz. In CI: Workflow „Deutsches Parsermodell“, sobald ein Modell hinterlegt ist.

Das Testmodell `src/test/resources/…/malt/test-model/maltparser-de-test.mco` (29 KB) ist aus selbst geschriebenen
Sätzen (`src/test/malt/train-mini.conllu`, MIT) trainiert und taugt nur für die Verdrahtung. Neu erzeugen nur bei
Änderung der Sätze, mit `./gradlew :nlp-parser:trainTestParserModel`, danach Prüfsumme in `TestModel` und Manifest
nachziehen. Kein anderer Task trainiert.

## Was die Ausgabe enthält und was nicht

- `Token`: Form, Spanne im Original, Lemma (OpenNLP; klein geschrieben), UPOS, HEAD, DEPREL. **XPOS und FEATS sind
  leer**: OpenNLP liefert keine Kasusmerkmale. Kasus schließt `Mention` deshalb aus Artikeln und Pronomen und
  markiert ihn als `CASE_HEURISTIC`.
- Labels: die DEPREL-Liste des Modells steht im Manifest (`MaltDependencyParser#manifest().labels()`); GSD kennt
  u. a. `nsubj`, `nsubj:pass`, `obj`, `obl`, `obl:arg` (Dativ- und Präpositionalobjekte), `obl:agent`, `nmod`,
  `nmod:poss`, `compound:prt`, `conj`, aber kein `iobj`.
- Nicht aufgelöst, nur erkannt: Pronomen (`UNRESOLVED`), Relativpronomen, Koreferenz, gleiche Personen hinter
  gleichen Wörtern, Kopulasätze.

## Messung (17.10.2026, Container, lokal trainiertes GSD-Modell, nicht veröffentlicht)

Lokal trainiert auf UD German GSD r2.16 train (FORM + UPOS, nivreeager, liblinear, ~75–110 s), bewertet auf GSD dev
mit Gold-UPOS (ohne Satzzeichen) und auf den Gold-Sätzen dieses Moduls:

| Featuremodell | GSD dev UAS / LAS | obj | obl:arg | obl:agent | Referenzsatz | Gold-Sätze LAS (Gold-/OpenNLP-UPOS) |
|---|---|---|---|---|---|---|
| MaltParser-Standard (nur FORM, UPOS) | 84,7 / 77,8 | 63 % | 39 % | 0 % | Dativ als `obj`, Kern falsch | 82,1 / 82,1 |
| + Artikelformen (`FORM` von `ldep`) | 85,8 / 79,7 | 74 % | 51 % | 92 % | richtig | 94,9 / 93,6 |

Laufzeit mit dem zweiten Modell: Laden 1,3 s und ~64 MB Heap, OpenNLP-Modelle 0,3 s, Rohtext → Baum 4,7 ms je Satz,
1,4 ms je Satz mit 4 Threads. Mit dem Standardmodell würde die Auswertung den Fehler im Referenzsatz als
`CASE_CONFLICT` markieren und keine Kernrelation daraus machen (`misparse.conllu`). Die Wahl des Featuremodells und
die verbindlichen Zahlen liegen bei #99.
