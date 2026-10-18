# ADR 0016: Auswahl der Beschaffung, Beschaffungsplaner und Hintergrundfüllung

- Status: **Angenommen, erster Schritt umgesetzt** (Strang Q, 18.10.2026; Entscheidungen von Angelo, 18.10.
  17:41–17:47). Ergänzt [ADR 0013](0013-suche-ueber-den-cache.md) bei der Beschaffung der Treffer.
- Umgesetzt mit #296: vorläufige Trefferliste, Laden in Rangfolge, Fortschritt und Abbruch je Suche.
- Offen: austauschbare `CandidateSelectionStrategy`, `AcquisitionScheduler` mit Vorrang für den Vordergrund,
  dauerhafte Hintergrundfüllung, gemeinsame `DocumentProcessingPipeline`, Katalogisieren aller Funde als Kopfdaten
  (braucht den Space-Schlüssel im Fund, Änderung im Enterprise AI Client).
- Bezüge: [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md) (Katalog und Inhaltsindex getrennt),
  [ADR 0017](0017-progressive-suche.md) (Suchanfragen), `KeywordSearch`, `SearchHitPrefetch`, `ExcerptPriority`.

## Kontext

Die Schlagwortsuche lud jeden Fund der Quelle in den Cache und wartete bis zu 5 s darauf; erst danach kamen Treffer.
Bei 84 Funden sah der Benutzer lange nichts, Fortschritt gab es nicht (Angelo 17:41). Die native Suche liefert aber
schon Seiten-ID, Adresse, Titel und Auszug. Damit lässt sich sofort eine brauchbare Liste zeigen und nach Titel und
Auszug bewerten, ohne einen Inhalt zu laden.

## Entscheidung

1. **Sofort anzeigen** (Angelo 17:44). Alle Funde der Quellen sind sofort vorläufige Treffer mit Titel und Auszug der
   Quelle. Ist ein Reranker eingerichtet, sind sie nach Titel und Auszug bewertet (`ExcerptRanking`-Parität), sonst
   bleibt die Reihenfolge der Quelle. Das Ergebnis ist nicht an das Laden gekoppelt.
2. **Einsortieren statt Ersetzen** (Angelo 17:45). Geladene Seiten kommen als Treffer aus dem Volltextindex des Caches
   und rücken an ihren Platz. Kein Treffer verschwindet; die Identität ist Quelle und Seiten-ID (`PageKey`). Das
   Signal heißt „Reihenfolge aktualisiert“, nicht „Liste ersetzt“.
3. **Laden in Rangfolge bis „Die Suche ist abgeschlossen“** (Angelo 17:47). Im Hintergrund wird in der Rangfolge
   geladen, die Liste wird dabei besser. Jede Suche hat einen Fortschritt (`total`, `loaded`, `failed`, `cancelled`,
   `done`) und lässt sich abbrechen.
4. **Was sofort geladen wird, ist eine eigene, austauschbare Strategie** (`CandidateSelectionStrategy`), getrennt vom
   Laden selbst: Reihenfolge der Quelle, die besten N, adaptiv.
5. **Hintergrundfüllung** ist standardmäßig an, solange das Programm läuft, mit begrenzter, einstellbarer
   Parallelität. Eine Aktion des Benutzers hat Vorrang. Der Hintergrund hält dann an sicheren Punkten an
   (kooperativ, nie mitten im Schreiben des Lucene-Index). Für den Vordergrund bleibt Kapazität reserviert. Eine
   Seite, die der Hintergrund gerade lädt und die jetzt gebraucht wird, wird hochgestuft, nicht doppelt geladen.
   Warteschlange und Stand überdauern das Beenden.
6. **Eine Pipeline für beide Wege**: Version prüfen, laden, extrahieren, Lucene, Embeddings.
7. **Rechte** wie bisher: Vorläufige Treffer brauchen die quellweite Freigabe für `metadata`, den Auszug zeigen sie
   nur mit quellweitem `content`. Ohne quellweite Freigabe wird je Seite live geprüft. Was der Aufrufer nicht sehen
   darf, erscheint nirgends, auch nicht in einer Zahl.

## Umsetzung (erster Schritt, #296)

- `KeywordSearch.Results`: `found` (vorläufig), `hits` (aus dem Cache bestätigt), `ranked()` (beides in einer Liste
  mit stabiler Identität), `progress`. Dazu `KeywordSearch.progress(id)` und `cancel(id)`.
- `SearchHitPrefetch` zählt je Stapel und bricht ab. Technische Aufrufer warten nicht (`Harvester.Builder.searchWait`,
  Standard 0). Delegierte Benutzer warten höchstens 5 s und laden nur während ihrer Anfrage.
- Ladeumfang: `Harvester.Builder.searchLoads` bzw. `space-harvester.search.loads` (Standard alle).
- REST: `KeywordResponse.found` und `progress`; `GET /api/v1/search/progress/{id}`, `DELETE` bricht ab.

## Folgen

- Die Oberfläche zeigt sofort eine Liste und fragt den Fortschritt ab; sobald `loaded` steigt, sucht sie erneut und
  ordnet die Liste neu.
- Komponentennamen (`CandidateDiscovery`, `CandidateSelectionStrategy`, `AcquisitionScheduler`,
  `DocumentProcessingPipeline`) sind Vorschläge; Abweichungen werden hier vermerkt, sobald sie umgesetzt sind.
