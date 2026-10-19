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
    embedding-chunk-chars: 0            # nur Server: Zeichen je Stück; 0 (Standard) ohne Grenze wie im EAC, sonst ≥ 100
    embedding-chunk-tokens: 350         # nur Server: Budget je Stück (wie knowledge.chunk.maxTokens), mindestens 8
    embedding-chunk-overlap-sentences: 1  # nur Server: wiederholte Schlusssätze (wie knowledge.chunk.overlapSentences)
    rerank-model: bge-reranker-v2-m3    # optional (#181): Cross-Encoder über POST /rerank
```

Die Desktop-App liest dieselben Namen unter `space-harvester.ai` aus ihrer Datei, bis auf die `embedding-chunk-…`-Werte
(im Core `Harvester.builder().embeddingChunkChars(…)` und `.embeddingChunking(maxTokens, overlapSentences)`)
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
`view=extended`; `section` ist die Überschriftenkette des Abschnitts, z. B. `Handbuch > Betrieb`.

Zerlegung (#181, wie `KnowledgeChunker` im Enterprise AI Client): Abschnitte der Textprojektion sind harte Grenzen.
Innerhalb eines Abschnitts packt der Prozessor Sätze (`SentenceSplitter`) bis 350 Tokens (gezählt nach Wörtern und
Symbolen, `KnowledgeChunkingPolicy.defaults()`); das nächste Stück beginnt mit dem letzten Satz des vorigen. Ein Satz
über dem Budget wird an Wortgrenzen geteilt. Eine Zeichengrenze gibt es wie im Client nicht; nur wer einen Dienst mit
eigener Zeichengrenze anbindet, setzt `embedding-chunk-chars` (mindestens 100). Eingebettet wird jedes Stück mit der
Überschriftenkette seines Abschnitts davor (`Betrieb > Nachtlauf`), wenn es nicht selbst mit der Überschrift beginnt;
wie `KnowledgeChunker.fitHeading` belegt die Kette höchstens das halbe Token-Budget, äußere Überschriften entfallen
zuerst, dann wird die innerste an Wortgrenzen gekürzt (Version `+heading:eac`). `charStart`/`charEnd` bleiben Stellen in der Textprojektion, damit Treffer auf
ihre Stelle zeigen. Seit dieser Zerlegung trägt die Prozessorversion Format `2`; ältere Vektoren zählen nicht mehr und
werden beim nächsten Abgleich oder Öffnen der Seite neu berechnet. Ebenso beim Umstieg auf die Zerlegung ohne
Zeichengrenze und mit gekürzter Überschriftenkette: Vorhandene Embeddings werden beim nächsten Start einmal im gedrosselten Hintergrund aus
dem Cache neu eingebettet; bis dahin findet die Relevanzsuche eine noch nicht neu eingebettete Seite nur über ihre
Schlagworte.

Die Seiten kommen über die normale Beschaffung in den Cache: Jede gelesene Seite, auch ein vorgeladener Treffer der
nativen Schlagwortsuche, landet im Volltextindex und bekommt ihre Embeddings im Hintergrund. Für `sort=relevance` ist
also kein vollständiger Abgleich nötig (`HarvesterFullTextTest.aLiveHitLoadedIntoTheCacheIsFoundByTheSemanticSearchWithoutAnySync`).

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

- Erst in den Cache (ADR 0013, Punkt 3): Vor der Vektorsuche findet dieselbe Schlagwortsuche wie ohne Haken bis zu
  50 Seiten (die Anfrage, auf 200 Zeichen gekürzt, geht an die Suche der Quellen) und lädt sie mit den Rechten des
  Aufrufers in den Cache. Für diese Treffer berechnet die Suche fehlende oder veraltete Embeddings im Aufruf, vier
  zugleich, höchstens 10 s für Laden und Embeddings zusammen; was dann nicht fertig ist, macht die Antwort
  unvollständig (`complete: false`) und zählt beim nächsten Mal. Ohne Haken wird nie etwas eingebettet. Auch Seiten
  lokaler Dateien bekommen Embeddings.
- Die Anfrage geht über `POST /embeddings` an den Dienst (wie die Seiten, ohne Präfixe; das Modell bestimmt der Dienst).
- Kandidaten: bis zu 1000 ähnlichste Stücke der Quelle (ohne `sourceId`: aller freigegebenen Quellen zusammen, eine
  Einbettung der Anfrage und eine Vektorsuche, #114) unter genau der aktuellen Prozessorversion, zu Seiten
  (`sourceId + pageId`) zusammengefasst, höchstens 100 Seiten. Es gibt keine Volltext- oder Titelkandidaten und keine
  Fusion von Ergebnislisten (ADR 0013); `title` filtert nur die Kandidaten der Vektorsuche. Alle
  Filter (`spaceKey`, `title`, `parentPageId`, Zeitgrenzen …) gelten auch für Kandidaten der Vektorsuche.
- Rechte: wie die übrige Suche `metadata` für Quelle bzw. Spaces und jeden Treffer. Ähnlichkeiten sind aus dem Inhalt
  abgeleitet: Ein Kandidat der Vektorsuche braucht `content` für seine Seite, sonst fällt er ohne Spur weg. Der
  Reranker sieht nur diese freigegebenen Treffer und darf sie nur umordnen.
- Live-Prüfung je Treffer (ADR 0011, Punkt 7): Vor der Auslieferung liest die Suche die Kopfdaten jedes Treffers bei
  der Quelle und prüft an ihnen Recht (aktueller Space) und Version. Gesperrte oder verschwundene Seiten fallen weg,
  weitere Kandidaten rücken nach. Eine neuere Version wird im Hintergrund nachgeladen und neu eingebettet; der Treffer
  heißt bis dahin `stale`. Ein nicht prüfbarer Kandidat fehlt, und die Antwort trägt `complete: false`.
- Reihenfolge: Kosinusähnlichkeit der Anfrage zum ähnlichsten Abschnitt jeder Seite (`reranker:
  embedding-cosine@<Prozessorversion>`).
- Keine hybride Suche (ADR 0013): Die Schlagwortsuche läuft ohne Haken über den Volltextindex des Caches, die
  semantische Suche nur mit Haken über die Vektoren. Ergebnislisten werden nie zusammengeführt (keine Reciprocal Rank
  Fusion); das ist eine gewollte Abweichung vom Enterprise AI Client.
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
  Dazu `passageId`: die stabile Adresse des Absatzes, der dieses Stück ganz enthält (ADR 0020, Nachtrag „Stabile
  Passage-IDs“), lesbar über `GET /api/v1/passages?id=<passageId>` (ID URL-kodiert, sie enthält `#`). Ohne
  `content`-Freigabe gibt es weder `relevance` noch die ID. Sie fehlt (`null`), wenn die Absätze der Seite nicht aus
  derselben Fassung (Text und Gliederung) stammen wie das Embedding, kein Absatz das Stück ganz enthält oder sich
  Absätze im gespeicherten Ergebnis überlappen (kaputtes Ergebnis).
- Ohne eingerichteten Dienst, oder wenn er die Anfrage nicht einbetten kann, antwortet die Suche mit 503
  `semantic-search-unavailable`, auch mit `title`; kein stiller Rückfall auf die Titelsuche. Der Anfragetext steht nie
  im Log.

## Verwandte Abschnitte

`GET /api/v1/sources/{sourceId}/pages/{pageId}/related?chunk=<n>` liefert die Nachbarschaft eines eingebetteten Stücks,
wie `KnowledgeRelationsPort` mit `IndexKnowledgeRelations` im Enterprise AI Client (#181); im Core
`harvester.relevance().related(caller, sourceId, pageId, chunk, RelatedSections.Query.all())`. Die Kanten entstehen bei
der Abfrage aus Vektorablage und Kopfdaten; es gibt kein neues Embedding, und nichts geht an den KI-Dienst.

- `similar-to`: Kosinussuche mit dem gespeicherten Vektor des Stücks über alle lesbaren Quellen (oder
  `targetSourceId`), ab `minSimilarity` (Standard 0,80), ohne das Stück selbst und seine direkten Nachbarstücke, die
  sich durch die Überlappung Text teilen.
- `parent`: die Elternseite, mit ihrem ersten Stück, wenn sie eingebettet ist.
- `next`/`previous`: das folgende bzw. vorige Stück derselben Seite.
- `same-section`: weitere Stücke derselben Seite unter derselben Überschriftenkette, die nächstgelegenen zuerst.

Jedes Ziel erscheint einmal (die erste Kante in dieser Reihenfolge gewinnt), je Art höchstens `limit` (Standard 3,
höchstens 20). Die Ausgangsseite und jede Zielseite brauchen `metadata` und `content`; mit Live-Prüfung gilt das für den
heute bei der Quelle gemeldeten Space. Eine gesperrte Zielseite fehlt ohne Spur, eine nicht prüfbare ebenfalls, dann
mit `complete: false`. Ist die Seite nicht mit dem aktuellen Modell eingebettet, gibt es nur `parent`, und `origin`
fehlt. Verweise (`LINKS_TO`) kennt auch der Enterprise AI Client noch nicht.

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
