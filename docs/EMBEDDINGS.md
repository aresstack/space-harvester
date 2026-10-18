# Embeddings über einen externen KI-Dienst

Embeddings sind eine optionale Anreicherung ([ADR 0004](adr/0004-embeddings-und-reranking.md)) und kommen nur von
einem externen, OpenAI-kompatiblen KI-Dienst ([ADR 0011](adr/0011-embeddings-ueber-externen-ki-dienst.md)). Ein
lokales Modell gibt es nicht mehr. Ohne Konfiguration gibt es keine Embeddings, und an Abgleich, Lesen, Export und
Schlagwortsuche ändert sich nichts; `sort=relevance` antwortet dann mit `semantic-search-unavailable`.

## Dienst

Der Dienst bietet `GET <base-url>/models` und `POST <base-url>/embeddings` im OpenAI-Format (`input`, `model`,
`data[].embedding`, Bearer-Token). Der Core nutzt dafür die unveränderten Adapter des Enterprise AI Client
(`OpenAiCompatibleEmbeddingAdapter`, `KipitzModelCatalogAdapter`). Die Verbindung läuft über dieselbe Proxy-Route je
Ziel wie die Quellen (PAC, Proxy, fail-closed) und über das Vertrauen aus `trust-bundle` oder der JVM.

## Einschalten

Server (`application.yml` oder jede andere Spring-Property-Quelle):

```yaml
space-harvester:
  ai:
    base-url: https://ki.example.org/v1
    api-key: ${KI_API_KEY}              # aus dem Secret-Speicher des Betriebs, nie im Klartext einchecken
    embedding-model: text-embedding-3-small
    embedding-dimensions: 1536          # Pflicht; Harvester.aiEmbeddingDimension misst sie
    timeout: PT30S                      # Standard
    trust-bundle: ki                    # optional, ein spring.ssl.bundle; sonst Vertrauen der JVM
    external-processing-sources: confluence,wiki2   # Standard: keine
    embedding-chunk-chars: 1500         # nur Server: Zeichen je eingebettetem Stück
    rerank-model: bge-reranker-v2-m3    # optional (#181): Cross-Encoder über POST /rerank
  relevance:                            # nur Server, optional (#181): Reciprocal Rank Fusion
    keyword-weight: 1.0
    semantic-weight: 1.0
    rank-constant: 60
```

Die Desktop-App liest dieselben Namen unter `space-harvester.ai` aus ihrer Datei, bis auf `embedding-chunk-chars`
und vorerst `rerank-model` (der Reiter „KI“ bekommt das Feld noch); den API-Key legt der Reiter „KI“ im Tresor ab und
gibt ihn nur zur Laufzeit an den Core. Als Bibliothek: `Harvester.builder().ai(AiServiceSettings)`,
etwa `AiServiceSettings.fromProperties(properties)`.

**Freigabe für externe Verarbeitung.** Das Leserecht eines Benutzers genügt nicht, um Seiteninhalte an den Dienst zu
schicken. Nur Seiten der Quellen in `external-processing-sources` gehen hinaus und bekommen Embeddings; Standard ist
keine. Suchanfragen gehen an den Dienst, sobald jemand `sort=relevance` benutzt.

Danach berechnet jeder bestätigte Abgleich die Anreicherung `embedding` für neue und geänderte Seiten im begrenzten
Hintergrund (ein Thread, höchstens 1000 wartende Seiten); Abgleich, Lesen und Export warten nicht darauf.
`POST …/pages/{pageId}/enrichments` rechnet eine Seite sofort. Ergebnis je Seite:
`{"model","dimensions","chunks":[{"charStart","charEnd","section","vector":[…]}]}` unter `enrichments` in
`view=extended`.

Zerlegung (#181, wie `KnowledgeChunker` im Enterprise AI Client): Abschnitte der Textprojektion sind harte Grenzen.
Innerhalb eines Abschnitts packt der Prozessor Sätze (`SentenceSplitter`) bis 350 Tokens (gezählt nach Wörtern und
Symbolen, `KnowledgeChunkingPolicy.defaults()`); das nächste Stück beginnt mit dem letzten Satz des vorigen. Ein Satz
über dem Budget wird an Wortgrenzen geteilt; zusätzlich hat kein Stück mehr als `embedding-chunk-chars` Zeichen.
Eingebettet wird jedes Stück mit der Überschriftenkette seines Abschnitts davor (`Betrieb > Nachtlauf`), wenn es nicht
selbst mit der Überschrift beginnt. `charStart`/`charEnd` bleiben Stellen in der Textprojektion, damit Treffer auf
ihre Stelle zeigen. Seit dieser Zerlegung trägt die Prozessorversion Format `2`; ältere Vektoren zählen nicht mehr und
werden beim nächsten Abgleich oder Öffnen der Seite neu berechnet.

Die Seiten kommen über die normale Beschaffung in den Cache: Jede gelesene Seite, auch ein vorgeladener Treffer der
nativen Schlagwortsuche, landet im Volltextindex und bekommt ihre Embeddings im Hintergrund. Für `sort=relevance` ist
also kein vollständiger Abgleich nötig (`HarvesterFullTextTest.aPageOpenedWithoutAnySyncIsFoundByTheHybridRelevanceSearch`).

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
ausgibt.

## Suchen

```sh
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/search?sourceId=confluence&sort=relevance&q=Wie+l%C3%A4uft+der+n%C3%A4chtliche+Betrieb%3F"
```

- Die Anfrage geht über `POST /embeddings` an den Dienst (wie die Seiten, ohne Präfixe; das Modell bestimmt der Dienst).
- Kandidaten: bis zu 1000 ähnlichste Stücke der Quelle (ohne `sourceId`: aller freigegebenen Quellen zusammen, eine
  Einbettung der Anfrage und eine Vektorsuche, #114) unter genau der aktuellen Prozessorversion, zu Seiten
  (`sourceId + pageId`) zusammengefasst, und mit `title` zusätzlich die ersten Titeltreffer; abwechselnd gemischt, höchstens 100 Seiten. Alle
  Filter (`spaceKey`, `title`, `parentPageId`, Zeitgrenzen …) gelten auch für Kandidaten der Vektorsuche.
- Rechte: wie die übrige Suche `metadata` für Quelle bzw. Spaces und jeden Treffer. Ähnlichkeiten sind aus dem Inhalt
  abgeleitet: Ein Kandidat der Vektorsuche braucht `content` für seine Seite, sonst fällt er ohne Spur weg. Ein
  Titeltreffer ohne `content` bleibt sichtbar, bekommt aber keine Ähnlichkeit und steht hinter allen anderen. Der
  Reranker sieht nur diese freigegebenen Treffer und darf sie nur umordnen.
- Live-Prüfung je Treffer (ADR 0011, Punkt 7): Vor der Auslieferung liest die Suche die Kopfdaten jedes Treffers bei
  der Quelle und prüft an ihnen Recht (aktueller Space) und Version. Gesperrte oder verschwundene Seiten fallen weg,
  weitere Kandidaten rücken nach. Eine neuere Version wird im Hintergrund nachgeladen und neu eingebettet; der Treffer
  heißt bis dahin `stale`. Ein nicht prüfbarer Kandidat fehlt, und die Antwort trägt `complete: false`.
- Reihenfolge: Kosinusähnlichkeit der Anfrage zum ähnlichsten Abschnitt jeder Seite (`reranker:
  embedding-cosine@<Prozessorversion>`); Treffer ohne Ähnlichkeit folgen in ihrer Reihenfolge.
- Hybrid (#181): Der Volltextindex über den Cache (#180, BM25 über Titel und Text) liefert weitere Kandidaten; sie
  brauchen `content`, denn ihr Rang stammt aus dem Inhalt. Gibt es Volltexttreffer im Fenster, entsteht die Reihenfolge
  per Reciprocal Rank Fusion (wie `ReciprocalRankFusion` mit `RetrievalSettings` im Enterprise AI Client) aus der
  Volltext- und der Kosinus-Rangfolge (`reranker: rrf(fulltext,embedding-cosine@<Prozessorversion>)`); Treffer ohne
  beide Ränge folgen. Fällt der Volltextindex aus, ist die Suche rein semantisch. Gewichte und `k` sind einstellbar,
  im Server unter `space-harvester.relevance` (`keyword-weight`, `semantic-weight` je 0 bis 10, Standard 1;
  `rank-constant` 1 bis 1000, Standard 60), im Core über `Harvester.builder().rankFusion(…)`; abweichende Werte stehen
  in der Kennung (`rrf@k30,w2.0/1.0(…)`). Anders als dort fusioniert der Core je Seite, nicht je Stück: Der
  Volltextindex kennt Seiten, und die Antwort listet Seiten; für die Kosinus-Rangfolge zählt das ähnlichste Stück.
- Cross-Encoder (#181, wie `KipitzRerankAdapter` und `CrossEncoderRanking` im Enterprise AI Client): Mit
  `rerank-model` bewertet der Dienst über `POST <base-url>/rerank` jeden Treffer einzeln gegen den Anfragetext und
  ordnet die Treffer der Antwort danach um (`reranker: …+cross-encoder@<Modell>`). Er kommt erst nach der
  Live-Prüfung und sieht den Titel und, nur mit `content`-Freigabe im heute gemeldeten Space, höchstens 2000 Zeichen
  des Lesetexts um das ähnlichste Stück, nie die Adresse. Es gehen nur
  Treffer aus Quellen in `external-processing-sources` hinaus; die übrigen behalten ihren Platz. Anders als im
  Enterprise AI Client verwirft er nichts, er ordnet nur um. Scheitert der Dienst, bleibt die Reihenfolge, und die Suche
  antwortet trotzdem. Ein eigener Reranker des Programms (`Harvester.builder().reranker(…)`) ersetzt ihn.
- Jeder Treffer mit Ähnlichkeit trägt `relevance`: `similarity` (-1 bis 1), `chunk` (`charStart`, `charEnd` in der
  Textprojektion) und `freshness`. `stale` heißt: Das Embedding stammt aus einer älteren Revision als die aktuellen
  Kopfdaten; es wird beim nächsten Abgleich neu berechnet.
- Ohne eingerichteten Dienst, oder wenn er die Anfrage nicht einbetten kann, antwortet die Suche mit 503
  `semantic-search-unavailable`, auch mit `title`; kein stiller Rückfall auf die Titelsuche. Der Anfragetext steht nie
  im Log.

## Fehlerverhalten

- Ist der Dienst nicht erreichbar, lehnt er den Key ab oder liefert er falsche Vektoren (Dimension, Anzahl), steht nur
  die Anreicherung `embedding` dieser Seite auf `failed`. Abgleich, Export, Suche und die übrigen Anreicherungen
  laufen weiter.
- Ist der Hintergrund voll, wird die Seite übersprungen und später nachgeholt.
- Fehlen `base-url` oder `embedding-dimensions` bei gesetztem `embedding-model`, startet der Server nicht.
- Texte und der API-Key erscheinen nie in Logs oder Fehlermeldungen; Meldungen nennen nur Art und Statuscode.

## Prüfen

- `RemoteAiTest` prüft `/models`, `/embeddings`, Bearer-Token, die Proxy-Route und die Fehler gegen einen lokalen
  OpenAI-kompatiblen Dienst; `AiServiceSettingsTest` die Properties und die Maskierung des Keys.
- `EnrichmentsTest` prüft Hintergrund und Freigabe je Quelle, `RelevanceSearchTest` und `RelevanceSearchApiTest` die
  Suche.
