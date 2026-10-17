# nlp-model-training: deutsches MaltParser-Modell

Modellfabrik für `maltparser-de-gsd` (#99): holt die gepinnte Treebank UD German GSD, trainiert ein MaltParser-1.9.2-
Modell, evaluiert es, verpackt es mit Manifest, Prüfsummen, Lizenz und Namensnennung und lädt es zur Probe wie ein
Verbraucher. Das Sprachverarbeitungsmodul aus #98 (`:nlp-parser`) lädt nur eine fest gepinnte, veröffentlichte Version
und trainiert nie.

**Kein normaler Lauf trainiert.** `./gradlew build`, `test`, `check`, `assemble`, `bootJar` und jeder Pull Request
bauen und testen dieses Modul nur (mit einem Mini-Modell aus den eigenen Prüfsätzen, ohne Netz). Die Treebank wird nur
von den Tasks unten geladen. Kein anderes Projekt hängt von diesem Modul ab (`ModelRecipeTest`).

## Bestandteile

| Datei | Zweck |
|---|---|
| `model/maltparser-de-gsd.properties` | Rezept: Modellversion, Treebank-Commit und SHA-256 je Datei, Algorithmus, Heap, Zeitlimit, Lizenzstand, Qualitätsgrenzen |
| `model/feature-model.xml` | Featuremodell; liest nur FORM und UPOS (geprüft beim Laden des Rezepts) |
| `model/german-reference-sentences.conllu` | Eigene Prüfsätze mit erwarteten Kanten (Referenzsatz aus #98, Passiv, trennbare Verben, Dativ und Zeitangabe, Koordination, ohne Akkusativobjekt, Pronomen) |
| `model/LICENSE-MODEL.md`, `model/ATTRIBUTION.md` | Vorlagen für Lizenz und Namensnennung im Asset |
| `TreebankAcquisition`, `TreebankValidation`, `InputContract` | Laden mit Prüfsumme, CoNLL-U-Prüfung, Projektion auf den Eingabevertrag |
| `MaltModelTrainer` | Lernlauf in eigener JVM (Heap, Zeitlimit, Spitzenspeicher) |
| `MaltModel`, `ModelEvaluator`, `DependencyEvaluation`, `AdjacencyBaseline`, `OpenNlpPosTagger`, `ReferenceSentences` | Evaluation |
| `ModelManifest`, `ModelPackager`, `ModelConsumerCheck` | Verpacken und Verbraucherprüfung |

## Lokal trainieren

Java 21, etwa 8 GiB freier Speicher (Heap 6 GiB, `-PtrainingHeap=…` ändert ihn), Netz nur für den ersten Abruf:

```bash
./gradlew :nlp-model-training:buildGermanModel
```

Das sind nacheinander `prepareGermanTreebank` (Laden, SHA-256), `validateGermanTreebank` (CoNLL-U prüfen, normalisieren),
`trainGermanModel`, `evaluateGermanModel`, `packageGermanModel`, `verifyGermanModel`. Ergebnisse unter
`nlp-model-training/build/maltparser-de-gsd/`:

- `data/` normalisierte Train-/Dev-/Testdateien, `validation.json` Zählungen je Teil,
- `model/maltparser-de-gsd.mco`, `model/training.log`, `training.json` (Dauer, Heap, Spitzenspeicher, Labels),
- `evaluation.json`, `dist/` mit den drei Release-Assets, `consumer/` das entpackte Asset der Verbraucherprüfung.

Ohne Netz: die fünf Dateien aus dem Rezept vorab in ein Verzeichnis legen und `-PtreebankDir=<Verzeichnis>` angeben;
passende Prüfsummen werden nicht neu geladen, falsche brechen ab.

## Eingabevertrag (abgestimmt mit #98)

CoNLL-U, ein Satz je Block, nur syntaktische Wörter (ganzzahlige IDs). Mehrwort-Zeilen (`6-7 im`) und leere Knoten
(`3.1`) entfernt der Verbraucher vor dem Parsen, Verschmelzungen löst seine Tokenisierung auf (`im` → `in dem`). Das
Modell liest **nur FORM und UPOS**; LEMMA, XPOS und FEATS werden schon im Training geleert, weil die OpenNLP-
Vorverarbeitung keine verlässlichen XPOS und FEATS liefert. Das Manifest hält das in `inputSchema.columns` fest. Der
MaltParser-Konfigurationsname ist fest `maltparser-de-gsd`; die `.mco` darf nicht umbenannt werden.

## Evaluation

- **Gold** = UPOS aus der Treebank, **predicted** = UPOS von OpenNLP (`opennlp-models-pos-de` 1.3.0, dasselbe Modell
  wie in #98) auf Gold-Tokens. Gute Gold-Werte sind kein Nachweis für Rohtext; Tokenisierungsfehler misst keiner der
  beiden Werte.
- UAS/LAS über alle syntaktischen Wörter einschließlich Interpunktion (wie CoNLL 2018), zusätzlich ohne Interpunktion;
  LAS mit vollem Label einschließlich Subtyp. Je Relation Precision, Recall, F1 und die häufigsten Verwechslungen.
- **Baseline**: rechter Nachbar als Kopf, häufigstes Label je Wortart.
- **Prüfsätze**: Kanten aus `german-reference-sentences.conllu`, mit Gold- und vorhergesagten Wortarten. Ein Bericht,
  keine Grenze; er zeigt, wo die objektzentrierte Auswertung dem Parser trauen kann.
- **Trennung**: Train nur fürs Lernen (und Baseline, Labelset), Dev für die Wahl von Algorithmus und Featuremodell,
  Test nur für die abschließenden Werte.
- **Qualitätsgrenzen** (`quality.minimum.*` im Rezept): `evaluateGermanModel` bricht darunter ab, `packageGermanModel`
  verpackt dann nicht. Eine neue Version darf die Grenzen der vorigen nicht senken.

## Konfigurationswahl (Dev, Gold-UPOS)

Ergebnisse von Version 1.0.0 stehen unten unter „Messwerte“; die Wahl fiel auf Dev:

Alle Läufe auf GSD r2.18 Train, gemessen auf Dev mit Gold-UPOS, LIBLINEAR, Eingabe nur FORM und UPOS. LAS mit
Interpunktion; Relationen als Anteil mit richtigem Kopf und Label.

| Algorithmus | Featuremodell | Training | UAS | LAS | obj | obl:arg | obl:agent | nmod |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| nivreeager (projektiv) | MaltParser-Standard | 65 s | 83,86 | 78,08 | 62,4 | 38,6 | 0,0 | 64,0 |
| stacklazy (Swap) | MaltParser-Standard | 56 s | 84,56 | 79,29 | 67,1 | 42,9 | 0,0 | 64,0 |
| stackeager (Swap) | erweitert | 248 s | 84,57 | 79,89 | 69,5 | 48,6 | 0,0 | 63,3 |
| stacklazy | erweitert | 242 s | 85,58 | 80,95 | 72,3 | 45,7 | 0,0 | 64,0 |
| stacklazy | erweitert + Stack-Kontext | 587 s | 85,90 | 81,28 | 73,5 | 54,3 | 0,0 | 66,6 |
| **stacklazy** | **+ Wortformen linker Abhängiger (gewählt)** | 594 s | 85,87 | **81,33** | **73,7** | **67,1** | **61,5** | **66,8** |
| stacklazy | gewähltes ohne FORM (nur UPOS) | 42 s | 81,89 | 72,30 | 50,8 | 1,4 | 0,0 | 59,6 |

covnonproj brach mit 3 GiB Heap wegen Speichermangel ab und wurde nicht weiter verfolgt. Die letzte Zeile beziffert die
Option „ohne Wortformen“ aus docs/NLP_MODEL_LICENSE.md: rund 9 Punkte LAS weniger, Dativobjekte praktisch nicht mehr
erkannt.

## Release und Freigabe

GitHub Releases sind der einzige Verteilweg (öffentlich ohne Token, anders als GitHub Packages). Je Version:

- Tag `maltparser-de-gsd-vMAJOR.MINOR.PATCH`, unabhängig von der Anwendungsversion. MAJOR: Eingabevertrag oder
  Labelset ändern sich; MINOR: Neutraining mit anderen Daten, Algorithmus oder Featuremodell bei gleichem Vertrag;
  PATCH: Neutraining mit gleichem Rezeptinhalt (z. B. nach einem Abhängigkeitsupdate). Neu verpacken ohne Training
  ändert die Version nicht und geht nur, solange die Version nicht veröffentlicht ist.
- Assets: `<tag>.zip` (Modell, Manifest, Evaluation, Lizenz, Namensnennung, Treebank-Lizenz), `<tag>.manifest.json`,
  `SHA256SUMS` (`<sha256>  <datei>`).
- Ein Tag wird nie ersetzt, es gibt kein `latest` für Verbraucher.

Training und Bereitstellung sind getrennte Workflows, beide nur von Hand (Actions):

- **„MaltParser-Modell trainieren“** (`train-maltparser-model.yml`) trainiert, evaluiert, verpackt, prüft und legt
  alles 30 Tage als Workflow-Artefakt `<tag>` ab. Nur Leserechte, veröffentlicht nichts. Bricht ab, wenn es den Tag
  oder einen Entwurf dazu schon gibt.
- **„MaltParser-Modell bereitstellen“** (`publish-maltparser-model.yml`) trainiert nie. Quelle ist
  - mit `run_id`: das Artefakt eines erfolgreichen Trainingslaufs auf main (ID aus der URL `…/actions/runs/<id>`),
  - ohne `run_id`: die Assets des vorhandenen Entwurfs zum Tag des Rezepts.

  Er prüft die Prüfsummen, verpackt die Assets mit dem Lizenzstand des Rezepts neu (`repackageGermanModel`: Modell,
  Evaluation und Trainingsangaben bleiben, Lizenzangaben und -texte kommen aus dem Rezept; ein Rezept, das ein anderes
  Modell beschreibt, wird abgelehnt), lädt das Ergebnis wie ein Verbraucher (`verifyGermanModel`) und legt einen
  **Entwurf** an oder aktualisiert ihn. Eigener Job mit `contents: write`, Environment `maltparser-model-release`,
  Herkunftsnachweis per `attest-build-provenance`. Ein veröffentlichtes Release verändert er nie.
  Beim Aktualisieren eines Entwurfs sichert er dessen bisherige Assets vorher als Artefakt `<tag>-previous-draft`
  (90 Tage) und schreibt sie zurück, wenn das Hochladen scheitert.
- Ein Entwurf läuft nicht ab. Deshalb wird ein geprüftes Artefakt am besten gleich als Entwurf gesichert, auch vor der
  Lizenzfreigabe; der Entwurf ist nicht öffentlich und trägt bis zur Freigabe einen Warnhinweis im Titel und in den
  Notizen.
- Artefakt abgelaufen und kein Entwurf da: das heruntergeladene ZIP-Artefakt entpacken, unter Releases einen Entwurf
  mit dem Tag anlegen, `<tag>.zip`, `<tag>.manifest.json` und `SHA256SUMS` anhängen und „bereitstellen“ ohne `run_id`
  starten. Lokal geht dasselbe mit `./gradlew :nlp-model-training:repackageGermanModel
  :nlp-model-training:verifyGermanModel -PsourceDir=<Verzeichnis mit den drei Dateien>`.
- **Veröffentlichen** (Entwurf → Release) geschieht nur von Hand, nach der Lizenzfreigabe in
  [docs/NLP_MODEL_LICENSE.md](../docs/NLP_MODEL_LICENSE.md).

## Verbraucher (offline-fähig)

```bash
tag=maltparser-de-gsd-v1.0.0
curl -sSfLO "https://github.com/aresstack/space-harvester/releases/download/$tag/$tag.zip"
echo "<im Verbraucher gepinnte SHA-256>  $tag.zip" | sha256sum -c
unzip -d model "$tag.zip"   # danach mcoSha256 aus model/model-manifest.json prüfen
```

In Java: `ConcurrentMaltParserService.initializeParserModel(Path.of("model/maltparser-de-gsd.mco").toUri().toURL())`,
dann `parseTokens(String[])` mit CoNLL-U-Zeilen (ID, FORM, `_`, UPOS, `_` …). Das Modell lässt sich ohne Netz aus
einem Cache oder einem festen Pfad laden; der Dienst lädt nie selbst etwas herunter. `ModelConsumerCheck` zeigt die
Prüfschritte.

## Neutraining

Nur bei bewusster Änderung: neue Treebank-Revision, anderer Algorithmus oder anderes Featuremodell, MaltParser-
Wechsel, geänderter Eingabevertrag, deutlich bessere Qualität oder ein nachgewiesener Fehler. Dazu Rezept ändern,
`model.version` erhöhen, Qualitätsgrenzen beibehalten oder anheben, per PR auf main und dann den Workflow starten.

Reproduzierbar festgehalten sind Eingaben (Commit, SHA-256), Rezept, Featuremodell und Messwerte. Byte-gleiche `.mco`
sind nicht zugesichert (MaltParser schreibt Zeitstempel ins Archiv); ob ein zweiter Lauf dieselben Messwerte ergibt,
zeigt der Vergleich von `evaluation.json` zwischen lokalem Lauf und Workflow-Lauf.

## Abhängigkeiten

| Bibliothek | Version | Lizenz | Anmerkung |
|---|---|---|---|
| `org.maltparser:maltparser` | 1.9.2 | BSD-3-Clause | Training und Laufzeit |
| `de.bwaldvogel:liblinear` | 1.8 | BSD-3-Clause | Lerner, zur Laufzeit nötig |
| `tw.edu.ntu.csie:libsvm` | 3.1 | BSD-3-Clause | von MaltParser verlangt, hier nicht als Lerner genutzt |
| `log4j:log4j` 1.2.16 → `ch.qos.reload4j:reload4j` | 1.2.26 | Apache-2.0 | log4j 1.x hat bekannte Lücken (CVE-2019-17571, CVE-2021-4104, CVE-2022-23302, -23305, -23307), betroffen sind SocketServer, JMS-, JDBC- und Chainsaw-Klassen, die MaltParser nicht nutzt; reload4j ist der gepflegte, API-gleiche Ersatz, per Gradle-Substitution erzwungen und mit dem vollen Training geprüft |
| `org.apache.opennlp:opennlp-tools` / `opennlp-models-pos-de` | 2.5.12 / 1.3.0 | Apache-2.0 | nur Evaluation |
| `com.google.code.gson:gson` | 2.10.1 | Apache-2.0 | Manifest und Berichte |

Zur Laufzeit (#98) braucht ein Verbraucher nur MaltParser, LIBLINEAR, LIBSVM und reload4j; OpenNLP und Gson sind hier
nur Werkzeuge.

## Messwerte

Version 1.0.0, lokal trainiert am 2026-10-17 (Container, 4 Kerne, Java 21.0.12, Heap 6 GiB). Diese Werte stammen aus
einem lokalen Lauf; maßgeblich für ein Release sind `evaluation.json` und die Zusammenfassung des Workflow-Laufs.

| Satz | UAS | LAS | UAS ohne Interpunktion | LAS ohne Interpunktion |
|---|---:|---:|---:|---:|
| Dev, Gold-UPOS | 85,87 | 81,33 | 86,37 | 81,13 |
| **Test, Gold-UPOS** | **84,42** | **79,40** | 85,43 | 79,58 |
| Dev, OpenNLP-UPOS | 78,82 | 73,44 | 79,68 | 73,47 |
| **Test, OpenNLP-UPOS** | **77,67** | **71,60** | 78,93 | 71,85 |
| Test, Baseline (rechter Nachbar) | 28,32 | 22,44 | | |

OpenNLP trifft die UPOS zu 93,4 % (Dev) und 93,0 % (Test); jeder Wortartfehler kostet im Mittel rund eine Kante.

| Relation (Test) | Gold | Precision | Recall | F1 | F1 mit OpenNLP-UPOS |
|---|---:|---:|---:|---:|---:|
| nsubj | 1136 | 78,5 | 79,1 | 78,8 | 66,8 |
| nsubj:pass | 115 | 60,8 | 56,5 | 58,6 | 48,9 |
| obj | 584 | 73,9 | 73,6 | 73,8 | 63,9 |
| obl | 873 | 75,6 | 75,4 | 75,5 | 64,4 |
| obl:arg | 85 | 65,0 | 45,9 | 53,8 | 48,6 |
| obl:agent | 13 | 55,0 | 84,6 | 66,7 | 66,7 |
| nmod | 996 | 67,0 | 67,6 | 67,3 | 60,7 |
| compound:prt | 77 | 76,0 | 77,9 | 76,9 | 70,1 |
| conj | 525 | 63,0 | 68,0 | 65,4 | 58,0 |

Häufigste Verwechslungen auf Test: obl ↔ nmod (121 und 107, die Anbindung von Präpositionalgruppen), obj ↔ nsubj (69
und 51), obl:arg → obj (27). `iobj` und `nmod:poss` kommen in GSD praktisch nicht vor (Dativobjekte sind `obl:arg`).

Prüfsätze: 29 von 30 Kanten richtig, mit Gold- wie mit OpenNLP-Wortarten. Im Referenzsatz stimmen Subjekt
(Sachbearbeiter), Dativ (Vorgesetzten, `obl:arg`), Akkusativobjekt (Beleg) und Genitiv (des Kunden am Beleg); nur
„am Montag“ hängt als `nmod` am Vorgesetzten statt als `obl` am Verb. Die Kernrelation Sachbearbeiter → übermitteln →
Beleg ist damit ableitbar, die Zeitangabe nicht verlässlich.

Training 613 s, Spitzenspeicher des Lernprozesses 3,7 GiB, `.mco` 23 MB, ZIP 23 MB. Parsen in einem Thread rund 900
Sätze pro Sekunde (Gold-Tokens, ohne Wortartbestimmung).

Qualitätsgrenzen ab 1.0.0 (gemessen minus 1 Punkt): Test Gold UAS ≥ 83,4, LAS ≥ 78,4, Test OpenNLP LAS ≥ 70,6.

## Fehlerfälle

- Falsche Prüfsumme einer Treebank-Datei: Abbruch, Datei wird verworfen, kein Wiederholen.
- Ungültiges CoNLL-U (Spalten, IDs, Zyklen, mehrere Wurzeln, unbekannte UPOS): Abbruch mit den ersten zehn Fehlern.
- Featuremodell liest andere Spalten als FORM und UPOS: Abbruch beim Laden des Rezepts.
- Lernlauf über dem Zeitlimit, mit Speichermangel oder ohne `.mco`: Abbruch, keine `.mco`, Log in `model/training.log`.
- `.mco` nach dem Training verändert: evaluate und package brechen ab.
- Qualitätsgrenze verfehlt: evaluate bricht ab, package verpackt nicht.
- Verbraucherprüfung: falsche ZIP- oder `.mco`-Prüfsumme, falsches Manifest-Schema, Spalten außerhalb des Vertrags,
  ungültiger Baum oder unbekanntes Label brechen ab.
