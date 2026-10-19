# ADR 0019: Begriffsindex und Begriffsbeziehungen

- Status: **Angenommen, Begriffsindex umgesetzt** (Strang B, 19.10.2026; Festlegung von Angelo, 19.10. 00:33).
  Ergänzt [ADR 0003](0003-lucene-cache.md) (Lucene-Cache) und [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md)
  Punkt 4 (Rechte je Seite).
- Umgesetzt (#119): Prozessor `terms`, Begriffsdokumente in `LuceneEnrichmentStore`, `TermQueries`
  (`harvester.terms()`), `PageTerms.read` für die Leseansicht, REST `GET /api/v1/terms`,
  `GET /api/v1/terms/occurrences`, `GET /api/v1/terms/snippet`.
- Geplant (#120): Begriffsbeziehungen als Dateien außerhalb des Cache-Verzeichnisses, Punkt 5.
- Bezüge: #119, #120, #122, #336, #339 (Oberfläche, Strang L); `AcronymProcessor`, `EntityProcessor`, `PageAcronyms`.

## Kontext

Space Harvester soll Akronyme und Bezeichner (Nomen, Nominalgruppen, Kennungen) über alle Quellen zeigen: global im
Hauptbereich „Begriffe“ (Akronyme | Bezeichner | Beziehungen | Fachglossar) und je Seite im Reiter „Begriffe“ der
Leseansicht. Erkannt wurde bisher je Seite in `acronyms` (Version 2, bis 5 000 Fundstellen) und `entities` (nur erste
Fundstelle, 500 Begriffe); beide Ergebnisse liegen als gespeichertes JSON in der Anreicherungsablage, nicht als
durchsuchbare Felder. Eine globale Sicht ginge so nur über einen Lauf über alle Seiten je Anfrage.

Angelo hat festgelegt: ein gemeinsamer Lucene-Index, der vorhandene `LuceneEnrichmentStore` wird erweitert, keine vier
physischen Indizes und keine zweite Begriffsdatenbank; Begriffe werden einmal je Seite beim Herunterladen oder
Aktualisieren ermittelt und mit Seiten-ID, Quell-ID, Revision, Prozessorversion und Fundstellen indexiert; der globale
Bereich aggregiert über alle berechtigten Seiten, die Leseansicht zeigt nur die aktuelle Seite. Manuelle Zuordnungen
und Sperrlisten überleben einen Neuaufbau des Caches.

## Entscheidung

1. **Ein Prozessor `terms` (Version 1) als einziger Eingang des Index.** Er sammelt Akronyme nach den Regeln von
   `AcronymProcessor` über den ganzen Text und die Begriffe aller Nominalgruppen deutscher Sätze wie `EntityProcessor`,
   gruppiert nach Art (`noun`, `acronym`, `identifier`) und Suchwert, mit **jeder** Fundstelle (Anfang, Ende in
   UTF-16 im projizierten Text). Dieselbe Stelle zählt einmal: Hat der Akronymprozessor sie, entfällt die gleich
   beginnende Nominalgruppe der Art `acronym`. Zwei echte Vorkommen bleiben zwei. `acronyms` und `entities` bleiben
   unverändert, damit `view=extended`, die Akronymmarkierung der Leseansicht (#115) und andere Verbraucher nichts
   umstellen müssen. Kosten: die deutsche Satzanalyse läuft zweimal je Seite; das ist lokal, deterministisch und ohne
   Netz.
2. **Suchwert statt Grundform.** `TermText.normalize`: NFC, Kleinbuchstaben, Leerraum zusammengefasst. Kein
   Lemmatisieren, kein Umlaut- oder Bindestrich-Falten: `Beleg` und `Belege` bleiben zwei Begriffe; Gleichsetzungen
   sind Beziehungen (#120), nie Indexfakten.
3. **Abgeleitete Begriffsdokumente in derselben Ablage**, nach dem Vorbild der Vektordokumente (#89): eines je Seite,
   Art und Suchwert, mit allen Fundstellen gepackt (je acht Bytes) und den Feldern Quelle, Art, Suchwert, Wörter des
   Suchwerts, Prozessorversion und Sortierschlüssel (`Suchwert U+0000 Art U+0000 Quelle U+0000 Seite`, als DocValues
   sortiert). Ein Dokument je Vorkommen wäre bei großen Seiten um Größenordnungen mehr Dokumente, ohne dass eine
   Abfrage je Vorkommen filtern müsste; die Positionsliste genügt, weil Fundstellen immer zu einer Seite gelesen
   werden. Die Dokumente entstehen und vergehen mit dem `terms`-Ergebnis: Neuberechnung ersetzt sie im selben
   Schreibvorgang, ein fehlgeschlagenes Ergebnis hinterlässt keine, Entfernen der Seite oder Quelle löscht sie.
   `find(PageKey)` sieht sie nie. Das Schema bleibt `enrichment-store/v2`: Ein vorhandener Index hat nur noch keine
   `terms`-Ergebnisse, und die berechnet der nächste Abgleich (`EnrichmentsAfterSync`) oder Abruf aus den gecachten
   Snapshots, ohne Confluence erneut zu fragen; Embeddings und andere Ergebnisse bleiben unberührt.
4. **Abfragen über `TermQueries`, Rechte wie die Suche.** Die Quellen bestimmt `metadata` auf Quelle oder Space wie
   `SearchScope` (mit `sourceId` 403, global still beschnitten). Danach zählt jede Seite nur, wenn sie im Cache liegt,
   der Aufrufer ihren Inhalt lesen darf (`AccessGuard.canRead`: Korpusfreigabe bei technischen Clients, Prüfung bei
   der Quelle bei delegierter Anmeldung) und ihr Ergebnis zur aktuellen Revision und Prozessorversion gehört. Zähler,
   Schreibweisen und Ausschreibungen entstehen nur aus sichtbaren Seiten; Cursor nennen nur Schlüssel, die der
   Aufrufer gesehen hat. Die Begriffsliste ist alphabetisch nach Suchwert, dann Art, und endet an einer
   Begriffsgrenze; nach 50 000 gelesenen Einträgen kommt der Rest im nächsten Stapel. Snippets entstehen nur auf Abruf
   aus dem gecachten Text und nur, wenn dessen `inputSha256` noch der des Eintrags ist; gespeichert wird kein Kontext.
   `spaceKey` filtert über die Kopfdaten des Caches, nicht im Index.
5. **Begriffsbeziehungen (#120) sind Nutzerdaten, kein Cache.** Bestätigte und manuelle Beziehungen sowie Sperren
   (`REJECTED`) liegen unter `<dataDirectory>/term-relations/` als Dateien (atomar ersetzt, mit Änderungsprotokoll),
   außerhalb von `<dataDirectory>/cache/`. Ein Neuaufbau oder Löschen des Caches berührt sie nicht; der Core bleibt
   bei Lucene und Dateien, ohne Datenbank. Herkunft (`MANUAL`, `RULE`, `AI`) und Prüfstatus (`PROPOSED`,
   `CONFIRMED`, `REJECTED`) sind getrennte Felder; Vorschläge aus #122 und #336 ersetzen nur unbestätigte Vorschläge
   ihrer eigenen Herkunft.
6. **Leseansicht und globale Sicht teilen den Datensatz.** `PageTerms.read(key, views, text)` liest dasselbe
   `terms`-Ergebnis für genau den angezeigten Text wie `PageAcronyms.read` und meldet `CURRENT`, `STALE`, `FAILED`
   oder `MISSING`. Positionen der globalen Fundstellen und der Leseansicht sind dieselben.

## Grenzen

- Je Seite höchstens 50 000 Fundstellen und 10 000 Begriffe; `count` zählt weiter alles, eine Warnung nennt die
  Kürzung.
- Nominalgruppen nur aus Sätzen, die die Satzanalyse als deutsch erkennt; Akronyme aus jedem Text.
- Ausschreibungen nur nach der Anfangsbuchstabenregel direkt daneben (`RSL` → `Rückmeldeschlüssel` ist keine
  Ausschreibung, sondern eine Beziehung aus #120 oder ein Vorschlag aus #122).
- Kein Fachglossar und keine KI in Index und Abfragen.

## Folgen

- Die App (#339) liest die globale Sicht über `harvester.terms()`, den Reiter je Seite über `PageTerms.read`.
- Eine spätere Änderung des Dokumentaufbaus hebt die Version des Prozessors `terms`; alte Einträge zählen dann nicht
  mehr und werden beim nächsten Lauf ersetzt.
