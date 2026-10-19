# ADR 0016: Auswahl der Beschaffung, Beschaffungsplaner und Hintergrundfüllung

- Status: **Angenommen, zweiter Schritt umgesetzt** (Strang Q, 18.10.2026; Entscheidungen von Angelo, 18.10.
  17:41–17:47). Ergänzt [ADR 0013](0013-suche-ueber-den-cache.md) bei der Beschaffung der Treffer.
- Umgesetzt mit #296: vorläufige Trefferliste, Laden in Rangfolge, Fortschritt und Abbruch je Suche.
- Umgesetzt im zweiten Schritt (Punkte 4 bis 6): austauschbare `CandidateSelectionStrategy`, `AcquisitionScheduler`
  mit Vorrang für den Vordergrund, dauerhafte Hintergrundfüllung `BackgroundFill`.
- Offen: Katalogisieren aller Funde als Kopfdaten (braucht den Space-Schlüssel im Fund, Änderung im Enterprise AI
  Client), Hintergrundfüllung und ihr Stand in der Oberfläche.
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

## Umsetzung (zweiter Schritt)

- **Auswahl** (Punkt 4): `CandidateSelectionStrategy` im Paket `application.query`, neben `KeywordSearch` und
  `ExcerptPriority`, weil sie über Funde einer Suche entscheidet. Eingebaut sind `SourceOrder` (`order`: alle
  fehlenden Funde in der Reihenfolge der Quellen), `TopN` (`top-n`: die besten N nach Titel und Auszug) und `Adaptive`
  (`adaptive`, Standard). Funde, deren Version schon im Cache liegt, zählen bei keiner mit. Die adaptive Regel: Fehlen
  höchstens 10 Seiten, laden alle; sonst `N = max(10, 3·F)` mit der Parallelität `F` des Vordergrunds (Standard 4,
  also 12); liegt mindestens die Hälfte des Stapels schon aktuell im Cache, halbiert sich `N`; was im Vordergrund
  schon wartet, geht ab, aber mindestens 5. Die Obergrenze ist `searchLoads`: `Harvester.Builder.searchLoads(n)` ohne
  `candidateSelection(...)` heißt wie bisher `top-n` mit `N = n`, mit ihr begrenzt es die gewählte Strategie. Server:
  `space-harvester.search.candidate-selection` = `order` | `top-n` | `adaptive`. Was nicht ausgewählt ist, bleibt
  vorläufiger Treffer; der Fortschritt (`total`) zählt nur die ausgewählten.
- **Planer** (Punkt 5 und 6): `AcquisitionScheduler` im neuen Paket `application.acquisition`, eine Warteschlange für
  `SearchHitPrefetch` (Vordergrund) und `BackgroundFill` (Hintergrund), beide über denselben Beschaffungsweg
  `PageQueries.page` (Version prüfen, laden, ablegen, Volltextindex, Anreicherungen samt Embeddings). Eine eigene
  `DocumentProcessingPipeline` gibt es deshalb nicht; die gemeinsame Pipeline ist dieser Weg. `Limits(foreground,
  background, perSource, capacity)`, Standard 4, 2, 4, 200: Der Vordergrund hat eigene Plätze; je Quelle laden
  höchstens `perSource` Seiten, der Hintergrund davon höchstens `perSource − 1`. Solange der Vordergrund wartet oder
  lädt oder jemand `pauseBackground()` hält, beginnt der Hintergrund keine neue Seite; sicherer Punkt ist die Grenze
  zwischen zwei Seiten, ein Abruf wird nie unterbrochen, auch beim Schließen nicht. Je Seite gibt es einen Eintrag:
  eine Anfrage des Vordergrunds stuft eine wartende Hintergrundseite hoch (`Status.promoted`), eine laufende teilt
  sie sich. Hintergrundanfragen mit Benutzeranmeldung lehnt der Planer ab; wartet eine Seite für beide, lädt sie mit
  dem technischen Aufrufer, und zieht ein delegierter Benutzer zurück, rückt sie ohne seine Anmeldung in den
  Hintergrund zurück.
- **Hintergrundfüllung** (Punkt 5): `BackgroundFill` sucht in Spaces mit bestätigter Generation (`LuceneCatalogGaps`)
  nach Seiten ohne Inhalt (`NOT_LOADED`) und mit neuerer Version in den Kopfdaten (`OUTDATED`) und lädt beide, in
  Rangfolge über alle Spaces: zuletzt geändert zuerst (Änderungsdatum aus den Kopfdaten), ohne Datum zuletzt.
  Gesperrte Seiten (`WITHHELD`) haben kein Seitendokument und erscheinen nie. **Die Füllung hängt nicht an der
  Betriebsart** (`sync.mode`, Entscheidung 18.10.): Die Betriebsart bestimmt weiter nur, was ein Abgleich spiegelt
  oder entfernt; die Füllung ist ein eigener Mechanismus mit eigenem Schalter. Die Warteschlange `LuceneFillQueue`
  (Lucene-Index `fill-queue/` neben dem Cache, wie die Auftragsablage) hält nur Quelle, Seiten-ID, Art und
  Änderungsdatum; ein Eintrag fällt erst nach seinem Abruf weg, was beim Beenden noch wartet oder lädt, kommt nach dem
  Neustart zuerst. Ist sie leer, sucht die Füllung den Katalog höchstens alle 10 min erneut ab (30 s nach dem Start
  zum ersten Mal). Geladen wird als technische Aktion: im Core mit `Harvester.caller()` für Quellen, die die
  Richtlinie quellweit für `content` freigibt, im Server mit einem eigenen Aufrufer, den nur ein interner
  `PageQueries` kennt, mit den Zugangsdaten der Quelle; nie mit dem Token eines Benutzers, und keines wird
  gespeichert. Was die Zugriffspolicy ausschließt, lädt sie nicht; Inhalte von Quellen ohne Freigabe an den
  KI-Dienst gehen wie immer nicht an ihn, Embeddings entstehen nur für freigegebene.
- **Einstellungen** (Laufzeitkonfiguration, ADR 0015, ohne Neustart): `sync.background-fill` (Standard `true`),
  `sync.background-parallelism` (Standard 2, 1 bis 16, für alle Quellen zusammen) und je Quelle
  `sync.sources.<Quelle>.background-fill`; ein eigener Wert je Quelle geht dem globalen vor
  (`HarvesterConfiguration.syncFor(id).backgroundFill()`). Abschalten zieht übergebene Anfragen zurück, eine laufende
  Seite endet regulär, die Warteschlange bleibt; eine abgeschaltete Quelle verliert ihre Einträge, die nächste Suche
  im Katalog findet sie wieder. Im Core folgt der `Harvester` damit `Builder.configuration(...)`; ohne sie, oder wenn
  nach ihr fest eingestellt, gelten `Builder.backgroundFill(boolean)` (Standard an) und `backgroundParallelism(int)`
  (Standard 2). `Harvester.acquisition()` gibt den Planer, `Harvester.backgroundFill()` die Füllung.
- **Stand**: `AcquisitionStatus` (`Harvester.acquisitionStatus()`, Server `GET /api/v1/acquisition` mit
  `manage-configuration`): `enabled`, `pause` (`NONE`, `FOREGROUND`, `USER_ACTION`, `COOLDOWN`, `DISABLED`, `IDLE`,
  `CLOSED`), Warteschlange und übergebene Einträge, wartende und ladende Seiten von Suche und Hintergrund, geladene
  und gescheiterte seit dem Start, `promoted`, `parallelism`, `lastScan` und Zähler je Quelle, nur für Quellen, die
  die Füllung lädt. Nur Zähler; Seiten-IDs und Suchtexte erscheinen weder dort noch im Protokoll.
- **Anhalten**: Der Hintergrund beginnt keine neue Seite, solange eine Seite der Suche wartet oder lädt, solange
  jemand `pauseBackground()` hält und bis 5 s nach der letzten Seite der Suche (`COOLDOWN`). Sicherer Punkt ist die
  Grenze zwischen zwei Seiten; ein laufender Abruf endet immer regulär, `Thread.interrupt` gibt es nicht, auch nicht
  beim Schließen.

## Folgen

- Die Oberfläche zeigt sofort eine Liste und fragt den Fortschritt ab; sobald `loaded` steigt, sucht sie erneut und
  ordnet die Liste neu.
- Komponentennamen (`CandidateDiscovery`, `CandidateSelectionStrategy`, `AcquisitionScheduler`,
  `DocumentProcessingPipeline`) waren Vorschläge. Umgesetzt sind `CandidateSelectionStrategy` und
  `AcquisitionScheduler` unter diesem Namen, dazu `BackgroundFill`; `DocumentProcessingPipeline` ist kein eigener
  Typ, die gemeinsame Pipeline ist `PageQueries.page` mit `EnrichingCacheFill`. `CandidateDiscovery` bleibt
  `KeywordSearch`.
- Die Hintergrundfüllung lädt mit den Standardwerten alle katalogisierten Seiten ohne oder mit veraltetem Inhalt,
  auch bei `sync.mode=catalog-only`. Wer das für eine Quelle nicht will, schaltet
  `sync.sources.<Quelle>.background-fill=false`.
