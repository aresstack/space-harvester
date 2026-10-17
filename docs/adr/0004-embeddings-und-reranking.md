# ADR 0004: Embeddings und Reranking als zweite Ausbaustufe der Suche

- Status: **Vorgeschlagen** (Strang D, 17.10.2026; Ausbaurichtung von Angelo: „Embeddings und Reranking ggf.
  ergänzen, um die Suchergebnisse zu verbessern“). Schnittstellen und Fakes liegen auf main, ein Modell ist nicht
  angebunden.
- Baut auf: [ADR 0003](0003-lucene-cache.md) (Lucene als einziger Speicher), #28 (Titel- und Metadatensuche), #29
  (Anreicherungen).

## Kontext

Die Suche aus #28 findet Seiten über Titelwörter und Metadaten. Inhaltliche Fragen („Wie läuft der nächtliche
Betrieb?“) findet sie nicht, und ihre Reihenfolge ist eine feste Sortierung, keine Relevanz. Angelo plant dafür
Embeddings und Reranking. Beides braucht ein Modell; welches, und ob lokal oder entfernt, ist offen. Unverrückbar
bleiben: Raw-First, Lucene als einziger Speicher, kein Datenbankserver, Rechte auf jeder Ebene, keine Inhalte in Logs,
Netzwerkzugriffe nur über den vorhandenen Stack (PAC, Proxy, mTLS), headless und als Bibliothek einbindbar.

## Entscheidung

### 1. Embeddings sind eine Anreicherung

Ein Embedding ist abgeleitet wie jedes Ergebnis aus #29, also gelten dieselben Regeln: `EmbeddingProcessor`
(`application.enrichment.embedding`) ist ein `EnrichmentProcessor` mit der Kennung `embedding`.

- Eingabe ist die sichere Textprojektion (`confluence-storage-text/v1`), nie Makroinhalte, Anhänge oder Bilder.
- Die Projektion wird an Abschnittsgrenzen in Stücke zerlegt (höchstens `maxChunkChars` Zeichen, sonst an Zeilen- oder
  Wortgrenzen, höchstens 200 Stücke je Seite mit Warnung darüber).
- Ergebnis: `{"model","dimensions","chunks":[{"charStart","charEnd","section","vector":[…]}]}`.
- Die Prozessorversion enthält Format, Modellkennung, Dimension und Stückgröße. Ein Modellwechsel macht damit genau
  die Embeddings veraltet; die Invalidierung über `inputSha256` und Version aus #29 gilt unverändert.
- Ein nicht erreichbares Modell oder falsche Vektoren (Dimension, NaN, Anzahl) ergeben ein `failed`-Ergebnis dieses
  Prozessors. Abgleich, Export und andere Prozessoren laufen weiter.

### 2. Das Modell steckt hinter einem Port

`EmbeddingProvider` (`modelId`, `dimensions`, `embed(texts)`) ist der einzige Weg zum Modell. Umsetzungen:

- **lokal**: ein Modell im Prozess (z. B. ONNX Runtime mit einem kleinen mehrsprachigen Satzmodell). Kein Netz,
  aber eine Modelldatei und eine native Bibliothek als neue Abhängigkeit.
- **entfernt**: ein HTTP-Endpunkt (z. B. Azure OpenAI oder ein eigener Dienst) über denselben ausgehenden
  Netzwerk-Stack wie die Confluence-Abrufe (PAC-Route, Proxy, Zertifikate, Zugangsdaten aus Key Vault). Die
  eingebetteten Texte verlassen dann das Haus; das muss je Quelle freigegeben sein.

Ohne konfigurierten Provider wird der Prozessor nicht registriert. Texte erscheinen nie in Logs, Fehlermeldungen
oder Ergebnissen außer als Positionen.

### 3. Vektoren liegen im Index `enrichments/`

Heute legt `LuceneEnrichmentStore` das Ergebnis als JSON ab. Für die Ähnlichkeitssuche kommt je Stück ein
`KnnFloatVectorField` hinzu, im selben Index, als eigenes Dokument je Stück mit Seitenschlüssel (`sourceId`,
`pageId`, Prozessorversion). Lucene bringt HNSW mit; ein Vektorspeicher außerhalb von Lucene ist ausgeschlossen. Das
neue Schema heißt `enrichment-store/v2`; ein Index im alten Schema wird wie bisher nicht still ersetzt, sondern beim
Start abgelehnt (Ergebnisse lassen sich neu berechnen).

### 4. Reranking ordnet nur um

`SearchReranker` (`application.ranking`) bekommt die Suchanfrage und die freigegebenen Treffer und gibt sie in neuer
Reihenfolge zurück. `Reranking` setzt durch:

- nur eine Umordnung derselben Trefferobjekte; fehlt einer, kommt einer hinzu oder doppelt sich einer, gilt die
  Reihenfolge des Index;
- ein scheiternder Reranker lässt die Suche nie scheitern;
- höchstens 100 Treffer je Aufruf (`MAX_WINDOW`).

Standard ist `SearchReranker.NONE`. Weil der Reranker nur sieht, was die Rechteprüfung schon durchgelassen hat, kann
er keine Rechte umgehen.

Eingehängt wird er hinter `GET /api/v1/search` als neue Sortierung `sort=relevance`. Sie ist nicht cursorfähig wie
die festen Sortierungen: Sie liefert genau ein Fenster (die ersten bis zu 100 freigegebenen Kandidaten aus Titel-,
Metadaten- und später Vektorsuche) und `complete=true`. Die bestehenden Sortierungen und ihre Cursor bleiben
unverändert.

### 5. Rechte und Aktualität

- Embeddings und Reranking-Ergebnisse sind aus dem Inhalt abgeleitet und verlangen `content`, wie die Payload. Eine
  Vektorsuche liefert nur Seiten, die der Aufrufer mit `content` sehen darf; Ähnlichkeitswerte werden nie für Seiten
  ohne Freigabe berechnet oder ausgegeben.
- Ein Treffer aus einem veralteten Embedding (`freshness=stale`) wird markiert, nie still als aktuell ausgegeben.
- Entfernte Seiten verlieren ihre Vektoren mit ihren übrigen Anreicherungen (`EnrichmentsAfterSync`).

## Folgen

- Bis ein Modell gewählt ist, ändert sich am Verhalten nichts: kein Provider, kein Embedding, Reranking `none`.
- Die Wahl lokal oder entfernt ist eine Betriebsentscheidung mit Datenschutzfolgen; sie gehört in ein eigenes Issue
  (siehe unten), nicht in den Code.
- Für die Tests gibt es `FakeEmbeddingProvider` (Vektoren aus SHA-256, ohne Netz und Modelldatei).

## Offen (eigenes Issue nötig)

1. Modell wählen (lokal oder entfernt; Sprache Deutsch und Englisch; Lizenz) und `EmbeddingProvider` umsetzen.
2. `enrichment-store/v2` mit `KnnFloatVectorField` und einem Such-Port für die Vektorsuche.
3. `sort=relevance` in `GET /api/v1/search` mit Kandidatenfenster und `Reranking`.
4. Einen Reranker umsetzen (Kosinus über Embeddings oder ein Cross-Encoder).

## Nachtrag 17.10.2026: Modell und Reihenfolge (#89)

Entscheidung Angelo ([#89](https://github.com/aresstack/space-harvester/issues/89#issuecomment-6231616582)):

- **Modell:** `intfloat/multilingual-e5-small`, lokal über ONNX Runtime im Prozess. 384 Dimensionen, 512 Token,
  Deutsch und Englisch, MIT-Lizenz. Keine externe Inferenz, kein Senden von Inhalten nach außen.
- **Gepinnt:** Revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`, `onnx/model.onnx` und `tokenizer.json` mit fester
  SHA-256 (`E5Defaults`). Die Dateien stellt der Betrieb bereit; sie liegen nie im Repository und werden nie zur
  Laufzeit geladen. Betrieb und Prüfung: [docs/EMBEDDINGS.md](../EMBEDDINGS.md).
- **Umsetzung:** `OnnxEmbeddingProvider` (Paket `embedding.onnx`) mit Hugging-Face-Tokenizer, Präfix `passage: ` für
  Dokumenttexte und `query: ` für Anfragen, Mean-Pooling über die Attention-Maske und L2-Normierung wie in der
  Modellkarte. Geprüft gegen Referenzvektoren der PyTorch-Implementierung.
- **Reihenfolge:** (1) Provider, (2) `enrichment-store/v2` mit `KnnFloatVectorField`, (3) Vektorsuche hinter der
  `content`-Prüfung und `sort=relevance`. Der erste Reranker nutzt die Kosinusähnlichkeit der vorhandenen Embeddings;
  ein Cross-Encoder ist keine Voraussetzung.
- Damit sind Punkt 1 der offenen Liste und die Modellwahl erledigt. Qualität, CPU- und Speicherbedarf auf echten Daten
  misst Angelo; erst danach wird über ein stärkeres oder entferntes Modell entschieden.

## Nachtrag 17.10.2026: Hochstufen statt Ablehnen von `enrichment-store/v1` (#89)

Abweichend von Abschnitt 3 wird ein Index im Schema `enrichment-store/v1` beim Öffnen hochgestuft statt abgelehnt.
Grund: v2 fügt nur Vektordokumente hinzu. Ein v1-Index enthält Ergebnisse aller übrigen Prozessoren, die gültig
bleiben; nur `embedding`-Ergebnisse haben in v1 keine Vektoren. Diese werden beim Hochstufen gelöscht und beim
nächsten Abgleich neu berechnet. Ein Index in einem unbekannten Schema wird weiter abgelehnt.

## Nachtrag 17.10.2026: `sort=relevance` (#89)

- `GET /api/v1/search` bekommt `q` und `sort=relevance`. Kandidaten sind bis zu 1000 ähnlichste Stücke der aktuellen
  Prozessorversion (zu Seiten zusammengefasst) und, mit `title`, die ersten Titeltreffer; höchstens 100 Seiten. Die
  übrigen Filter gelten für alle Kandidaten.
- Die Vektorsuche in Lucene (HNSW) läuft über alle Vektoren der Quelle, ihre Werte verlassen die Anwendung aber nie
  ungeprüft: Jeder Kandidat braucht `metadata` und `content` für seine Seite, sonst fällt er ohne Spur weg. Ähnlichkeiten
  an Titeltreffern gibt es nur mit `content`.
- Erster Reranker: Kosinus der Anfrage zum ähnlichsten Abschnitt jeder Seite, aus den gespeicherten Embeddings
  (`CosineReranker`, über `Reranking`). Aktualität: `stale`, wenn das Embedding aus einer anderen Revision stammt als
  die aktuellen Kopfdaten.
- Ohne Modell oder bei Modellausfall bleiben Titeltreffer in Titelreihenfolge; ohne `title` 503
  `semantic-search-unavailable`.
- Damit sind die Punkte 2 bis 4 der offenen Liste umgesetzt. Ein Cross-Encoder bleibt möglich, ist aber nicht nötig.

