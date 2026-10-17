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
`src/main/resources/com/aresstack/spaceharvester/nlp/malt/german-parser-model.properties`; hinterlegt ist
`maltparser-de-gsd-v1.0.0` (CC BY-SA 4.0, freigegeben in #99, `docs/NLP_MODEL_LICENSE.md`; `LICENSE-MODEL.md` und
`ATTRIBUTION.md` aus der ZIP gehören zu jeder Auslieferung des Modells). `MaltModelLoader` prüft vor dem Laden:

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
./gradlew :nlp-parser:downloadGermanParserModel
./gradlew :nlp-parser:test -PnlpModelDir=nlp-parser/build/nlp-model/maltparser-de-gsd-v1.0.0   # zusätzlich Rauchtest mit echtem Modell, Bericht in build/reports/nlp-parser/smoke.txt
```

- `ObjectCentricExtractionTest`: Regeln an handgeschriebenen Gold-Analysen (`src/test/resources/…/extraction`):
  Referenzsatz, Aktiv/Passiv, Genitiv, Dativ gegen Zeitangabe, ohne Akkusativobjekt, Koordination und trennbare
  Verben, gleiche Wörter in mehreren Aussagen, offene Pronomen, Kasus-Rückfall, eine echte Fehlanalyse und die echte
  Ausgabe des Modells v1.0.0 zum Referenzsatz (`time-on-nominal.conllu`).
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

## Messung mit dem veröffentlichten Modell (17.10.2026, Container)

`maltparser-de-gsd-v1.0.0` aus #99, Rauchtest `RealModelSmokeTest`:

| Messgröße | Wert |
|---|---|
| Laden `.mco` | ~7–7,5 s, ~225 MB Heap (Dienst mit mindestens 512 MB Heap planen) |
| OpenNLP-Modelle laden | ~0,2–0,3 s |
| Rohtext → Baum | ~5 ms je Satz einzeln, ~1,5 ms je Satz mit 4 Threads auf einem Modell |
| Gold-Sätze dieses Moduls (78 Wörter) | UAS 94,9 % / LAS 92,3 %, mit Gold- und mit OpenNLP-Wortarten gleich |
| GSD test laut Manifest (#99) | UAS 84,4 / LAS 79,4 (Gold-Wortarten), 77,7 / 71,6 (vorhergesagte Wortarten) |

Referenzsatz aus Rohtext: Kern `Der Sachbearbeiter → übermitteln → den Beleg`, Empfänger `dem Vorgesetzten`
(`obl:arg`, Dativ bestätigt), Genitiv `des Kunden` am Beleg. `am Montag` hängt das Modell als `nmod` an
`dem Vorgesetzten`; die Auswertung führt es zusätzlich als unsichere Zeitrolle der Aussage
(`Finding.TIME_FROM_NOMINAL`, Fixture `time-on-nominal.conllu`).

Vorversuch mit lokal trainierten, nicht veröffentlichten GSD-r2.16-Modellen (Gold-UPOS, GSD dev ohne Satzzeichen):

| Featuremodell | GSD dev UAS / LAS | obj | obl:arg | obl:agent | Referenzsatz |
|---|---|---|---|---|---|
| MaltParser-Standard (nur FORM, UPOS) | 84,7 / 77,8 | 63 % | 39 % | 0 % | Dativ als `obj`, Kern falsch |
| + Artikelformen (`FORM` von `ldep`) | 85,8 / 79,7 | 74 % | 51 % | 92 % | richtig |

Mit dem Standardmodell würde die Auswertung den Fehler im Referenzsatz als `CASE_CONFLICT` markieren und keine
Kernrelation daraus machen (`misparse.conllu`).
