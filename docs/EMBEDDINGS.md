# Embeddings: lokales Modell (#89)

Embeddings sind eine optionale Anreicherung ([ADR 0004](adr/0004-embeddings-und-reranking.md)). Ohne Konfiguration
gibt es keine Embeddings, keine Modellbibliothek wird geladen, und am Abgleich, am Export und an der Suche ändert sich
nichts.

## Modell

| | |
|---|---|
| Modell | [`intfloat/multilingual-e5-small`](https://huggingface.co/intfloat/multilingual-e5-small), MIT-Lizenz |
| Revision | `614241f622f53c4eeff9890bdc4f31cfecc418b3` (Stand 2026-04-02) |
| Dateien | `onnx/model.onnx` (470 MB), SHA-256 `ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665`; `tokenizer.json` (17 MB), SHA-256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39` |
| Vektoren | 384 Dimensionen, Mean-Pooling über die Attention-Maske, L2-normiert |
| Eingabe | höchstens 512 Token je Text; längere Texte werden am Ende abgeschnitten. Dokumenttexte bekommen das Präfix `passage: `, Anfragen `query: ` |
| Kennung | `intfloat/multilingual-e5-small@614241f622f5`; sie steckt in der Prozessorversion, ein Wechsel macht alle Vektoren veraltet |

Ausgeführt wird im Prozess über ONNX Runtime und den Hugging-Face-Tokenizer (DJL); beide bringen ihre nativen
Bibliotheken für Linux, Windows und macOS im Jar mit. Kein Text verlässt den Prozess, und nichts wird nachgeladen: Die
Tokenizer-Bibliothek läuft im Offline-Modus und ohne Telemetrie.

## Bereitstellen

Die Dateien kommen vom Betrieb, nicht aus dem Repository und nicht zur Laufzeit aus dem Netz. Auf einem Rechner mit
Internetzugang:

```bash
rev=614241f622f53c4eeff9890bdc4f31cfecc418b3
base=https://huggingface.co/intfloat/multilingual-e5-small/resolve/$rev
mkdir -p e5/onnx
curl -L -o e5/onnx/model.onnx "$base/onnx/model.onnx"
curl -L -o e5/tokenizer.json "$base/tokenizer.json"
sha256sum e5/onnx/model.onnx e5/tokenizer.json
```

Das Verzeichnis `e5/` dann auf den Server kopieren, z. B. nach `/opt/space-harvester/models/e5`. Unter Windows
`Get-FileHash -Algorithm SHA256` statt `sha256sum`.

## Einschalten

```yaml
space-harvester:
  embedding:
    provider: onnx                 # Standard: none
    max-chunk-chars: 1500          # Zeichen je eingebettetem Stück
    onnx:
      model-dir: /opt/space-harvester/models/e5
      threads: 2                   # Rechenthreads je Modellaufruf
      batch-size: 16               # Texte je Modellaufruf
      max-concurrent: 1            # gleichzeitige Modellaufrufe
```

Weitere Schlüssel unter `space-harvester.embedding.onnx` mit ihren Standardwerten:

- `model-file` (`<model-dir>/onnx/model.onnx`) und `tokenizer-file` (`<model-dir>/tokenizer.json`);
- `model-sha256` und `tokenizer-sha256` (die Werte oben);
- `model-id`, `dimensions` (384) und `max-tokens` (512);
- `acquire-timeout` (`2m`): So lange wartet ein Aufruf auf das ausgelastete Modell;
- `retry-after` (`5m`): Frühestens so spät wird nach einem gescheiterten Laden erneut geladen.

Andere Dateien als die gepinnten lädt der Harvester nur, wenn auch Prüfsummen und `model-id` angegeben sind.

Danach berechnet jeder bestätigte Abgleich die Anreicherung `embedding` für neue und geänderte Seiten
(`POST …/pages/{pageId}/enrichments` für eine einzelne Seite). Ergebnis je Seite:
`{"model","dimensions","chunks":[{"charStart","charEnd","section","vector":[…]}]}` unter `enrichments` in
`view=extended`.

## Ablage der Vektoren

Jedes Stück liegt zusätzlich als eigenes Dokument mit einem Lucene-`KnnFloatVectorField` (HNSW, Skalarprodukt über
L2-normierte Vektoren, also Kosinus) im Index `enrichments/`, Schema `enrichment-store/v2`:

- Die Vektordokumente gehören zu ihrem Ergebnis. Ein neues Ergebnis derselben Seite ersetzt sie, ein `failed`-Ergebnis
  hat keine, eine entfernte Seite verliert sie mit ihren Anreicherungen. Der Seitenindex bleibt unberührt.
- Gesucht wird nur unter Vektoren derselben Quelle und genau derselben Prozessorversion. Die Version enthält
  Format, Modellkennung samt Revision, Dimension und Stückgröße; nach einem Modell- oder Chunker-Wechsel zählen alte
  Vektoren also nicht mit, bis die Seite neu berechnet ist. Jeder Treffer trägt den `inputSha256` seiner Eingabe, damit
  die Suche veraltete Treffer erkennt.
- Ein Index im Schema `enrichment-store/v1` wird beim Öffnen hochgestuft. Weil v1 keine Vektoren kannte, werden nur
  seine `embedding`-Ergebnisse gelöscht und beim nächsten Abgleich neu berechnet; alle übrigen Ergebnisse bleiben.
- Lucene nimmt höchstens 1024 Dimensionen je Vektor an; größere Modelle bekommen kein Vektordokument.

Die Ablage kennt keine Rechte: Die Vektorsuche liefert nur Kandidaten, die die Suche erst nach der `content`-Prüfung
ausgibt (#89 Teil 3).

## Fehlerverhalten

- Das Modell wird beim ersten Bedarf geladen, nicht beim Start. Fehlt eine Datei, stimmt eine Prüfsumme nicht oder
  passt die Ausgabe des Modells nicht zu 384 Dimensionen, steht nur die Anreicherung `embedding` auf `failed`, mit
  dem Grund in der Meldung. Abgleich, Export, Suche und die übrigen Anreicherungen laufen weiter. Der nächste
  Ladeversuch folgt nach `retry-after`.
- Fehlen die Dateien schon beim Start, steht eine Warnung im Log; der Dienst startet trotzdem.
- Ist das Modell länger als `acquire-timeout` ausgelastet, scheitert der Aufruf als „ausgelastet“ statt zu warten.
- Texte erscheinen nie in Logs oder Fehlermeldungen.

## Ressourcen

Grob, auf einem aktuellen x86-Server, zu messen auf eigenen Daten (#89 Punkt 4):

- Speicher: etwa 0,6 bis 1 GB für Modell und Laufzeit, dazu je Aufruf etwa `batch-size` × 512 × 384 × 4 Byte für die
  Ausgabe (16 Texte: rund 12 MB). `max-concurrent` multipliziert den Anteil je Aufruf.
- Rechenzeit: Ein Stück von 512 Token braucht auf zwei Threads einige zehn Millisekunden.

## Prüfen

- `OnnxEmbeddingProviderTest` (im Build) prüft Präfixe, Mean-Pooling, Normierung, Stapel, Abschneiden und das
  Verhalten bei fehlenden oder falschen Dateien mit einem Spielzeugmodell (`src/test/resources/embedding/tiny`).
- `E5ReferenceTest` vergleicht die Vektoren und Token-IDs des Harvesters mit Referenzvektoren aus der
  PyTorch-Implementierung der Modellkarte (`tools/embedding/e5_reference.py`,
  `src/test/resources/embedding/multilingual-e5-small/reference.json`; Kosinus > 0,9999). Er läuft im Workflow
  `.github/workflows/embedding-model.yml`, der die gepinnte Revision lädt und die Prüfsummen prüft, oder lokal:

  ```bash
  SPACE_HARVESTER_E5_DIR=/opt/space-harvester/models/e5 ./gradlew :test --tests '*E5ReferenceTest'
  ```

Eine neue Revision des Modells verlangt neue Werte in `E5Defaults`, neue Referenzvektoren und einen Eintrag in
ADR 0004.
