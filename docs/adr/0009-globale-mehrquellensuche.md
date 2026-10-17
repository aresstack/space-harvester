# ADR 0009: Globale Mehrquellensuche im Core

- Status: **Angenommen** (Strang M, 17.10.2026; Auftrag in #114).
- Bezüge: #28 (Titel-/Metadatensuche), #89 und [ADR 0004](0004-embeddings-und-reranking.md) (Relevanz),
  [ADR 0003](0003-lucene-cache.md) (ein Lucene-Index), [ADR 0006](0006-core-bibliothek-und-server.md) (Core ohne
  Spring), [ADR 0008](0008-desktop-app.md) (Desktop-App).

## Kontext

Bis #114 verlangte die Suche eine `sourceId`. Die Desktop-App bot zwar „Alle Quellen“, fragte aber jede Quelle einzeln
mit höchstens 100 Treffern ab, mischte die Listen und sortierte lokal: keine vollständige globale Trefferliste, kein
globaler Cursor, und bei Relevanz eine Mischung bereits vorselektierter Top-K je Quelle. Alle Seiten aller Quellen
liegen aber schon in **einem** Lucene-Index; `sourceId + pageId` (`PageKey`) ist die eindeutige Seitenidentität, die
`pageId` allein nicht.

## Entscheidung

1. **Ein Anwendungsfall für alle Aufrufer.** `PageSearch` und `RelevanceSearch` im Core bleiben die einzigen
   Einstiege; `PageSearchQuery.sourceId` ist optional (`null` = global). REST (`GET /api/v1/search` ohne `sourceId`)
   und Desktop-App rufen genau diese Methoden auf; die App hat keine eigene Suchschleife und keine eigene Sortierung.
2. **Suchumfang (`SearchScope`).** Mit `sourceId` unverändert: `metadata` für jeden genannten Space bzw. die Quelle,
   sonst 403; unbekannte Quelle 403, ohne Index 404 `cache-miss`. Ohne `sourceId`: alle konfigurierten Quellen, für die
   der Aufrufer `metadata` hat (mit `spaceKey` für mindestens einen der Spaces). Andere Quellen fallen still weg, ohne
   403, ohne Zähler, ohne Einfluss auf die Generation. Danach wird wie bisher jeder Treffer an seiner Seite geprüft.
   Filter gelten global in jeder Quelle (`spaceKey=DEV` = DEV in jeder freigegebenen Quelle).
3. **Eine Lucene-Abfrage über die ganze Menge.** Der Filter ist `sourceId ∈ Umfang` (`TermInSetQuery`); Sortierung und
   `searchAfter` laufen im Index über alle Quellen zusammen, nie „N je Quelle holen und mischen“. Jede Sortierung endet
   mit `pageId` und dann `sourceId` (SortedSet-DocValues des vorhandenen `KeywordField`, UTF-8; Quellen-IDs sind ASCII,
   also gleich `String.compareTo`). In einer Quelle bleibt die Reihenfolge wie vor #114. **Keine Schemaänderung**
   (`page-cache/v3` bleibt), kein Neuaufbau des Index nötig.
4. **Globale Generation und Cursor.** Für eine Quelle bleibt die Generation `s<n>`. Für mehrere ist sie `g<hex>`, ein
   SHA-256 über die sortierten Paare „Quelle = Suchgeneration“ der indexierten Quellen des Umfangs, berechnet im selben
   Lucene-Commit wie die Treffer. Sie ändert sich, sobald sich eine dieser Quellen ändert oder eine Quelle des Umfangs in
   den Index kommt, und auch, wenn sich der sichtbare Umfang des Aufrufers ändert. Ein Cursor (`s1.`) bindet
   Fingerabdruck der Anfrage (mit Scope: global und jede Einzelquelle unterscheiden sich), Generation und Position des
   letzten **ausgelieferten** Treffers; passt die Generation nicht mehr, endet die Suche mit 409
   `cache-generation-changed` und beginnt neu. Es gibt keinen Snapshot älterer Commits. Positionen enthalten nur die
   Sortierwerte des letzten sichtbaren Treffers; Positionen von vor #114 (ohne `sourceId`) gelten für Einzelquellen
   weiter.
5. **Leere globale Ergebnisse.** Keine Quelle konfiguriert, keine freigegeben oder keine im Index: 200 mit leerer
   Liste, `complete=true`, `index=null`, nie 404. Ein Cursor zu einem solchen Stand ergibt 409. Ein nicht lesbarer Index
   bleibt 503 `cache-unavailable`.
6. **Relevanz global, aber begrenzt.** Je Aufruf genau eine Einbettung der Anfrage und eine Vektorsuche über alle
   Quellen des Umfangs zusammen (`EmbeddingIndex.nearest(Set, …)`, höchstens 1000 Stücke, höchstens 300 Seiten
   nachgeschlagen), dazu die ersten 100 sichtbaren Titeltreffer in globaler Titelreihenfolge; Deduplikation nach
   `PageKey`. Das Fenster bleibt `Reranking.MAX_WINDOW` = 100: `sort=relevance` ist weiter **ein** begrenzter Block
   ohne Cursor und ohne Vollständigkeitsanspruch, global gerankt, keine Top-K je Quelle. Ähnlichkeiten stammen aus
   demselben Modell (gleiche Prozessorversion) und sind über Quellen vergleichbar. Vektorkandidaten und Ähnlichkeiten
   nur mit `content`, Titeltreffer mit `metadata`; ohne Modell bleiben Titeltreffer, ohne `title` 503
   `semantic-search-unavailable`.
7. **Antwort.** REST nennt `scope` (`source`/`global`), global `sourceId: null`, und jeder Treffer trägt `sourceId`;
   mit `page.pageId` ist er unter `/api/v1/sources/{sourceId}/…` adressierbar.

## Folgen

- Performance: global eine Abfrage je Stapel statt einer je Quelle; der Stand der Generation kostet eine Term-Abfrage je
  Quelle des Umfangs. Die Sortierung nach `sourceId` liest DocValues, die schon vorhanden sind.
- Konsistenz: Cursor sind an den Commit-Stand aller beteiligten Quellen gebunden; jeder Abgleich einer davon beendet
  eine laufende globale Pagination (409). Das ist bewusst strenger als ein scheinbar stabiles Weiterblättern.
- Berechtigung: Die Vorauswahl der Quellen ist nur eine Optimierung; maßgeblich bleibt die Prüfung jedes Treffers.
  Verweigerte Quellen erscheinen weder in Treffern noch in Generation, Zählern oder Fehlern.
- Index-Migration: keine. Ein späterer eigener Sortierschlüssel für `sourceId` in UTF-16 bräuchte ein neues Schema;
  nötig wird er erst, wenn Quellen-IDs Nicht-ASCII-Zeichen erlauben.
- Nicht Teil: Live-Suche bei den Quellen, Volltextindex, Hot-Reload der Quellen, eine Route
  `/sources/{id}/search`.
