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
- **Baseline**: linker Nachbar als Kopf, häufigstes Label je Wortart.
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
  Labelset ändern sich; MINOR: Neutraining bei gleichem Vertrag; PATCH: nur neu verpackt.
- Assets: `<tag>.zip` (Modell, Manifest, Evaluation, Lizenz, Namensnennung, Treebank-Lizenz), `<tag>.manifest.json`,
  `SHA256SUMS` (`<sha256>  <datei>`).
- Ein Tag wird nie ersetzt, es gibt kein `latest` für Verbraucher.

Workflow `.github/workflows/train-maltparser-model.yml`, nur von Hand (Actions → „MaltParser-Modell trainieren“):

- `publish: none` trainiert, evaluiert, verpackt, prüft und legt alles 30 Tage als Workflow-Artefakt ab
  (Rechte: nur lesen).
- `publish: draft` legt zusätzlich ein **Entwurfs-Release** an (eigener Job mit `contents: write`, Environment
  `maltparser-model-release`, Herkunftsnachweis per `attest-build-provenance`). Bricht ein Upload ab, wird der Entwurf
  gelöscht. Ein vorhandener Tag oder Release bricht vorher ab.
- **Veröffentlichen** (Entwurf → Release) geschieht nur von Hand, nach der Lizenzfreigabe in
  [docs/NLP_MODEL_LICENSE.md](../docs/NLP_MODEL_LICENSE.md). Solange das Rezept `license.approval=pending` enthält,
  trägt der Entwurf im Titel und in den Notizen einen Warnhinweis.

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

Reproduzierbar sind Eingaben (Commit, SHA-256), Rezept, Featuremodell und Messwerte. Die `.mco` selbst ist nicht
byte-gleich zwischen zwei Läufen (Zeitstempel im Archiv); die Messwerte eines zweiten Laufs müssen gleich sein.

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

<!-- RESULTS -->

## Fehlerfälle

- Falsche Prüfsumme einer Treebank-Datei: Abbruch, Datei wird verworfen, kein Wiederholen.
- Ungültiges CoNLL-U (Spalten, IDs, Zyklen, mehrere Wurzeln, unbekannte UPOS): Abbruch mit den ersten zehn Fehlern.
- Featuremodell liest andere Spalten als FORM und UPOS: Abbruch beim Laden des Rezepts.
- Lernlauf über dem Zeitlimit, mit Speichermangel oder ohne `.mco`: Abbruch, keine `.mco`, Log in `model/training.log`.
- `.mco` nach dem Training verändert: evaluate und package brechen ab.
- Qualitätsgrenze verfehlt: evaluate bricht ab, package verpackt nicht.
- Verbraucherprüfung: falsche ZIP- oder `.mco`-Prüfsumme, falsches Manifest-Schema, Spalten außerhalb des Vertrags,
  ungültiger Baum oder unbekanntes Label brechen ab.
