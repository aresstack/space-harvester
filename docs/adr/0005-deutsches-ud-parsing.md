# ADR 0005: Deutsches Dependency Parsing nach Universal Dependencies mit MaltParser

- Status: **Angenommen** (Strang I, 17.10.2026) als Laufzeitparser von `:nlp-parser`. Vorgeschlagen am 17.10.2026;
  der reale Laufzeit-Spike ist mit dem veröffentlichten Modell `maltparser-de-gsd-v1.0.0` aus #99 abgeschlossen
  (Rauchtest unten). Das Modul ist weiterhin nicht in die Anwendung eingebunden; das braucht ein eigenes Issue.
- Entscheidung zur Parserwahl: Angelo, 17.10.2026 („Für das Dependency Parsing mussten wir aus Lizenzgründen auf den
  MaltParser ausweichen“), Issue #98 in der Fassung vom 17.10.2026.
- Hängt ab von: #99 (`:nlp-model-training`: Training, Evaluation, Lizenzprüfung und Veröffentlichung des Modells).

## Kontext

Space Harvester soll später Wissen objektzentriert erschließen: von Nomen ausgehend, wer was mit wem tut. Dafür
braucht es eine verlässliche Satzstruktur. Die regelbasierten Anreicherungen aus #29
(`application.enrichment.language`) arbeiten mit Satzstellungsregeln ohne Parser; sie sind schnell und ohne Modell,
stoßen aber bei freier Wortstellung, Koordination und Nebensätzen an Grenzen. Gesucht ist ein Dependency Parser nach
Universal Dependencies (UD) für Deutsch, vollständig in Java 21, lokal, ohne Python, JNI oder externen Dienst, und
lizenzverträglich mit der MIT-lizenzierten Anwendung.

## Entscheidung

### 1. MaltParser 1.9.2 ist der Laufzeitparser

`org.maltparser:maltparser:1.9.2` (BSD-3-Clause) aus Maven Central, im eigenen Gradle-Subprojekt `:nlp-parser`. Das
deutsche Modell (`.mco`) wird in #99 aus UD German GSD trainiert und als GitHub-Release veröffentlicht. `:nlp-parser`
trainiert nie; es lädt eine fest hinterlegte Version (`german-parser-model.properties`: Tag, Asset, SHA-256 der ZIP,
Modell-ID, Version, SHA-256 der `.mco`) aus einem Verzeichnis und prüft vor dem Laden Manifest-Schema
(`maltparser-model-manifest/v1`), Modell-ID, Version, Konfigurationsname, MaltParser-Version, Eingabeschema und
beide Prüfsummen. Jede Abweichung ist ein klarer Fehler (`ModelUnavailableException`), kein Rückfall auf eine andere
Version, kein Download zur Laufzeit.

Den Download übernimmt nur der ausdrücklich aufgerufene Gradle-Task `:nlp-parser:downloadGermanParserModel`
(Release-URL aus Tag und Asset, nie `latest`, SHA-256 der ZIP gegen den festen Wert, sonst wird nichts entpackt). Kein
Task des normalen Builds hängt davon ab. Im Betrieb liegt das Modell als Verzeichnis vor (vom Betrieb bereitgestellt
oder aus dem Download-Task), offline nutzbar.

### 2. Parserneutraler Vertrag, Corenth-Schnitt

- `nlp.syntax`: Vertrag ohne fremde Typen: `Token` (ID, Form, Spanne im Originaltext, Lemma, UPOS, XPOS, FEATS,
  HEAD, DEPREL), `MultiwordToken` (`am` = `an` + `dem`), `Sentence`, `ParsedDocument` mit `DocumentRef` (Quelle,
  Dokument, Version) und `Component` (Werkzeug, Modell, SHA-256 je Schicht); Ports `GermanPreprocessor` und
  `DependencyParser`, Anwendungsfall `DependencyAnalysis`; `ConllU` zum Austausch und Prüfen.
- `nlp.extraction`: objektzentrierte Regeln auf dem Vertrag (Abschnitt 4).
- `nlp.opennlp`, `nlp.malt`: Adapter. Nur sie kennen OpenNLP bzw. MaltParser.

Wie in Corenth und enterprise-ai-client hängt der Kern (Vertrag, Regeln) an keinem Adapter; ein anderer Parser
(CoreNLP nach Freigabe, ein späteres eigenes Modell) ist ein weiterer Adapter hinter `DependencyParser`.

### 3. Vorverarbeitung mit Apache OpenNLP, Eingabevertrag FORM + UPOS

MaltParser parst nur; Sätze, Wörter und Wortarten müssen vorher feststehen. Dafür nutzt `:nlp-parser` Apache OpenNLP
2.5.12 (Apache 2.0) mit den deutschen Modellen `org.apache.opennlp:opennlp-models-{sentdetect,tokenizer,pos,lemmatizer}-de:1.3.0`
aus Maven Central (auf UD German GSD trainiert, SHA-256 je Modell geprüft). Verschmelzungen werden wie in GSD in
syntaktische Wörter geteilt (`im`, `zum`, `zur`, `am`, `vom`, `beim`, `ins`, `ans`, `ums`, `aufs`, `übers`: alle
Fälle im GSD-Trainingsteil). OpenNLP liefert UPOS und Grundformen, aber keine Kasusmerkmale (FEATS) und kein
verlässliches XPOS.

Mit #99 abgestimmt: Das Modell nutzt nur die Spalten **FORM und UPOS**; LEMMA, XPOS und FEATS sind bei Training und
Parsen `_`, Mehrwortzeilen und leere Knoten sind entfernt. Der Loader lehnt ein Modell ab, dessen Manifest weitere
Spalten verlangt.

### 4. Objektzentrierte Auswertung: Rohstruktur bleibt, Kernsicht ist abgeleitet

`ObjectCentricExtraction` macht aus einer Analyse Erwähnungen, Aussagen und nominale Bezüge; die vollständige
Rohanalyse bleibt im Ergebnis.

- **Erwähnung** (`Mention`): jede Nominalgruppe und jedes Pronomen mit eigener ID
  (`quelle/dokument@version#s<Satz>.t<Kopf>`), Spanne und Wortlaut im Original, Grundform, möglichen Kasus und deren
  Herkunft. Gleiche Wörter bleiben getrennte Erwähnungen; Pronomen sind `UNRESOLVED`.
- **Aussage** (`Statement`) je Vollverb, mit eigener ID (`…#s<Satz>.v<Verb>`): Prädikat im Wortlaut und als Grundform
  (trennbare Verben: `ruft … an` → `anrufen`), Aktiv/Passiv, Haupt-, koordinierter oder Nebensatz und Beteiligte mit
  semantischer Rolle (`ACTOR`, `AFFECTED_OBJECT`, `RECIPIENT`, `PREPOSITIONAL_OBJECT`, `INSTRUMENT`, `TIME`,
  `CIRCUMSTANCE`; wie im Aussagenmodell aus enterprise-ai-client und in #29), grammatischer Relation, Präposition,
  Rang (`CORE`, `SUPPLEMENTARY`, `CONTEXT`) und Grundlage (`PARSER`, `COORDINATION`, `CASE_HEURISTIC`, `LEXICON`).
  Empfänger und Zeit gehören zu ihrer Aussage, nie zum Verb allgemein.
- **Nominaler Bezug** (`NominalAttachment`): `nmod` an der Gruppe, an der der Parser ihn anhängt; Genitiv oder
  Präposition, ohne Besitz- oder Herkunftsaussage.
- **Regeln** einzeln: `PrimaryRelationExtraction` (Subjekt, Prädikat, Akkusativobjekt), `SupplementaryRoles`
  (`obl:agent`, `obl:arg`, `iobj`, `obl`; Dativargument gegen Zeitangabe nach der Kante, nicht nach dem Kasus),
  `CaseFallback` (abschaltbar; schlägt fehlendes Subjekt/Objekt nur aus eindeutigem Kasus einer funktionslosen
  `dep`-Gruppe vor und markiert Widersprüche zwischen Kasus und Kante als `CASE_CONFLICT`, ohne die Kante zu ändern),
  `NominalAttachments`.
- **Kernsicht** (`SalienceProjection`): Subjekt → Prädikat → Akkusativobjekt nur für aktive Haupt- oder koordinierte
  Sätze mit beiden Beteiligten aus eigenen, widerspruchsfreien Parserkanten. Alles andere (Passiv, Nebensatz,
  übernommenes Subjekt, Heuristik, fehlendes Objekt) ist nachrangig und über die Aussage-ID samt Beleg nachladbar.
- **Statistik** (`MentionStatistics`): wie oft und wo eine Grundform Subjekt, Passivsubjekt oder Objekt ist, nur
  Parserkanten, ohne Pronomen und ohne Zusammenführung; sprachliche Statistik, keine Wichtigkeit.

Erkannt, aber bewusst nicht aufgelöst: Koreferenz, Entitäten über Erwähnungen hinweg, Synonyme, Relativpronomen,
Sätze mit Kopula (`ist zuständig`; Eigenschaften liefert #29).

## Alternativen

| Option | Bewertung |
|---|---|
| **Stanford CoreNLP 4.6.0** mit `models-german` | Technisch gut: im Spike am 17.10. parste es den Referenzsatz richtig (nsubj, obl:arg, obj, nmod; Laden ~16 s, ~1,2 GB Heap, ~200 MB Modell). Aber GPLv3+ für Code und Modelle; eine Modulgrenze hebt das für eine gemeinsam ausgelieferte Anwendung nicht auf. Nur nach eigener Lizenzfreigabe als weiterer Adapter, nie im Standard. |
| **MaltParser 1.9.2** | Gewählt: BSD-3, reines Java, klein (Modell ~6 MB), schnell, threadsicher über `ConcurrentMaltParserModel`. Braucht eine Vorverarbeitung und ein eigenes deutsches Modell (#99); alt, mit Log4j 1.2.16 als Abhängigkeit (siehe Folgen). |
| UDPipe | Native C++-Bibliothek bzw. Python/REST-Dienst; widerspricht „rein Java, lokal“. |
| Nur Kasusregeln | Bleibt als gekennzeichneter Rückfall (`CaseFallback`) und als Plausibilitätsprüfung; allein zu schwach für Koordination, Nebensätze und Anbindung. |
| Regeln aus #29 erweitern | Bleiben die schnelle, modellfreie Anreicherung; für eine belastbare Satzstruktur nicht ausreichend. |

## Folgen

- **Lizenz:** `:nlp-parser` enthält nur MaltParser (BSD-3), liblinear-java (BSD), libsvm (BSD-3), reload4j (Apache
  2.0), OpenNLP mit Modellen (Apache 2.0; trainiert auf UD German GSD, dessen CC-BY-SA-Hinweise für die Trainingsdaten
  gelten) und jackson-jr mit jackson-core (Apache 2.0). Die Prüfung `:nlp-parser:licenseGate` (Teil von `check`) bricht ab, sobald
  CoreNLP (`edu.stanford.nlp`) oder Log4j 1.x im Laufzeit-Klassenpfad liegt. Die Lizenz des GSD-Modells klärt #99;
  sie wird nicht automatisch MIT.
- **Sicherheit:** MaltParser bringt `log4j:log4j:1.2.16` mit (u. a. CVE-2019-17571, CVE-2021-4104,
  CVE-2022-23302/23305/23307). Es ist ausgeschlossen und durch `ch.qos.reload4j:reload4j:1.2.26` (gleiche API, Lücken
  geschlossen) ersetzt; Laden und Parsen laufen damit unter Java 21 (Tests).
- **Build:** `:nlp-parser` ist Teil von `./gradlew build`; die Tests laufen ohne Netz und ohne GSD-Modell mit einem
  kleinen Testmodell aus selbst geschriebenen Sätzen (`src/test/malt/train-mini.conllu`, 29 KB, SHA-256 fest). Es wird
  nur mit `:nlp-parser:trainTestParserModel` neu erzeugt, das kein anderer Task aufruft. Die Anwendung bindet das
  Modul nicht ein; eine spätere Anbindung als Enrichment-Prozessor ist optional und braucht ein eigenes Issue.
- **Modell:** fest hinterlegt ist `maltparser-de-gsd-v1.0.0` (ZIP-SHA-256 `d779fb32…a959`, `.mco`-SHA-256
  `237885c2…6ea3`, UD German GSD r2.18, `stacklazy`/liblinear, Lizenz CC BY-SA 4.0, Freigabe in #99 und
  `docs/NLP_MODEL_LICENSE.md`). Wer das Modell mit ausliefert, gibt die Dateien `LICENSE-MODEL.md` und
  `ATTRIBUTION.md` aus der ZIP mit; der Code von `:nlp-parser` bleibt MIT.
- **Messung mit dem veröffentlichten Modell (17.10.2026, Container, Workflow „Deutsches Parsermodell“):** Laden
  der `.mco` ~7–7,5 s und ~225 MB Heap (JVM für den Dienst mit mindestens 512 MB planen), OpenNLP-Modelle
  ~0,2–0,3 s; Rohtext → Baum ~5 ms je Satz, ~1,5 ms je Satz mit 4 Threads auf einem geteilten Modell. Die
  Gold-Sätze dieses Moduls (78 Wörter ohne Satzzeichen): UAS 94,9 %, LAS 92,3 % mit Gold- und mit
  OpenNLP-Wortarten. GSD-Werte laut Manifest aus #99: test UAS 84,4 / LAS 79,4 mit Gold-Wortarten, 77,7 / 71,6
  mit vorhergesagten Wortarten. Das Labelset enthält `nsubj`, `nsubj:pass`, `obj`, `iobj`, `obl`, `obl:arg`,
  `obl:agent`, `obl:tmod`, `nmod`, `nmod:poss`, `compound:prt`, `conj` (vollständige Liste im Manifest).
- **Qualität am Referenzsatz:** aus Rohtext richtig `nsubj` (Sachbearbeiter), `obj` (Beleg), `obl:arg` (dem
  Vorgesetzten, Dativ bestätigt) und `nmod` (des Kunden am Beleg). `am Montag` hängt das Modell als `nmod` an
  `dem Vorgesetzten` statt als `obl` an das Prädikat. Die Auswertung führt eine solche Zeitangabe mit Präposition
  zusätzlich als unsichere Zeitrolle der Aussage (`TIME_FROM_NOMINAL`, Grundlage Wortliste) und lässt die Kante als
  nominalen Bezug stehen; die Kernrelation ist davon nicht betroffen. Im lokalen Vorversuch mit dem
  MaltParser-Standard-Featuremodell kippte Dativ gegen Akkusativ; das veröffentlichte Modell nutzt deshalb
  zusätzliche Artikelform-Merkmale (#99), und die Regeln fangen den Restfehler weiter ab (`CASE_CONFLICT`).

## Mess- und Wechselkriterien

- Ein neues Modell aus #99 wird nur hinterlegt, wenn der Rauchtest (`./gradlew :nlp-parser:test -PnlpModelDir=…`) die
  Kernrelation des Referenzsatzes aus Rohtext liefert und #99 die Lizenzprüfung abgeschlossen hat.
- Neu bewerten, wenn auf realen Confluence-Texten LAS für `nsubj`, `obj` oder `obl:arg` mit vorhergesagten Wortarten
  deutlich unter den GSD-dev-Werten liegt, oder wenn Kasusmerkmale (FEATS) gebraucht werden, die die Vorverarbeitung
  nicht liefert. Dann: besseres Featuremodell bzw. Morphologie-Tagger (#99), sonst CoreNLP nach Lizenzfreigabe oder
  ein anderer Java-Parser hinter demselben Port.

## Nicht Teil dieser Entscheidung

Training, Treebank-Beschaffung, Evaluation und Veröffentlichung des Modells (#99); Entitäts- und Begriffsauflösung,
Koreferenz, Knowledge Graph, Graphdatenbank, KI-Prüfung der Aussagen; Einbindung in Harvesting, Index oder REST-API.
