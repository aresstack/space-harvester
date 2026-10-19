# ADR 0019: Begriffsindex und Begriffsbeziehungen

- Status: **Angenommen, Begriffsindex und Beziehungen umgesetzt** (Strang B, 19.10.2026; Festlegung von Angelo, 19.10. 00:33).
  Ergänzt [ADR 0003](0003-lucene-cache.md) (Lucene-Cache) und [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md)
  Punkt 4 (Rechte je Seite).
- Umgesetzt (#119): Prozessor `terms`, Begriffsdokumente in `LuceneEnrichmentStore`, `TermQueries`
  (`harvester.terms()`), `PageTerms.read` für die Leseansicht, REST `GET /api/v1/terms`,
  `GET /api/v1/terms/occurrences`, `GET /api/v1/terms/snippet`.
- Umgesetzt (#120): `TermRelations` (`harvester.termRelations()`), `FileTermRelationStore` unter
  `<dataDirectory>/term-relations` (Server: `space-harvester.term-relations.directory`), REST
  `/api/v1/term-relations` (Punkt 5 und 7); Regelvorschläge aus Ausschreibungen `AcronymRelationRule`
  (`harvester.acronymRelationRule()`, `POST /api/v1/term-relations/rules/acronyms?sourceId=`).
- Umgesetzt (#122, Stufe 1): KI-Akronymauflösung `AcronymResolution` (Punkt 8).
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
7. **Regeln der Beziehungen (#120).** Arten: `abbreviation` (gerichtet, Kurzform → Langform), `synonym`, `variant`
   (Schreibvariante), `related` (fachlich verwandt). Identität ist der Schlüssel aus normalisierten Endpunkten, Art und
   Geltungsbereich (global, Quelle oder Space); ungerichtete Arten ohne Reihenfolge. Eine Sperre gilt genau für
   diesen Schlüssel, nie für andere Bedeutungen derselben Abkürzung.
   - Manuell angelegt heißt `MANUAL` und `CONFIRMED`; Bestätigen ändert nur den Status, nie die Herkunft.
   - Ablehnen ist dauerhaft; Entfernen eines automatischen Eintrags lehnt ihn ab, damit der nächste Lauf ihn nicht
     wiederbringt; nur manuelle verschwinden ganz. Wiederherstellen macht aus der Sperre wieder einen Vorschlag
     (automatisch) oder eine bestätigte Beziehung (manuell). Jede Änderung trägt eine Revision; eine veraltete
     Revision ergibt 409.
   - Ein Vorschlagslauf (`propose`, für #122 und #336) gleicht in einem Schritt ab: neue Schlüssel werden
     `PROPOSED`, eigene Vorschläge bekommen neue Belege, abgelehnte und fremde Schlüssel bleiben unberührt, eigene
     unbestätigte, die er nicht mehr nennt, entfallen, eigene bestätigte werden nur als `evidenceStale` markiert.
     Unverändert belegte werden nicht neu geschrieben, damit ein Lauf je Abgleich weder Revisionen noch Protokoll
     aufbläht.
   - Die Akronym-Regel (`AcronymRelationRule`) startet der Core nach jeder Anreicherung einer Quelle selbst, als
     letzten Schritt des Auftrags `ENRICHMENT` (Phase „Folgeschritte“, nicht nach einem Abbruch); die App braucht
     dafür keinen Knopf. Der REST-Aufruf `POST …/rules/acronyms` bleibt für den Anstoß von Hand.
   - Lesen: Quelle oder Space braucht `metadata`; Belege zählen nur von Seiten mit Inhaltsrecht; ein automatischer
     Vorschlag ohne lesbaren Beleg ist verborgen, weil er aus deren Inhalt stammt. Ändern braucht `sync` auf den
     Geltungsbereich, global auf jede Quelle. Verborgenes gilt als unbekannt (404).
   - Suche darf über bestätigte Beziehungen außer `related` ausweiten (`expansions`), nie transitiv; ob die Suche das
     nutzt, entscheidet ADR 0013/0017.
8. **KI-Akronymauflösung (#122, Stufe 1).** `AcronymResolution` (`harvester.acronymResolution()`,
   `POST /api/v1/term-relations/refresh?sourceId=` → 202, Stand unter `…/refresh-jobs/{jobId}`) ist ein Vorschlagslauf
   je Quelle als Auftrag `ENRICHMENT` ohne Space (Phase „KI-Akronymauflösung“), kein eigener Auftragstyp.
   - Je Akronym höchstens 6 Fundstellen (2 je Seite) mit 160 Zeichen Kontext, nur aktuelle Seiten und nur aus dem Text
     mit passendem `inputSha256`; höchstens 200 Akronyme je Lauf (die mit den meisten Seiten), 20 je Anfrage. Akronyme
     über dem Budget behalten ihre Vorschläge.
   - Modell über den Port `AcronymExpansionModel`, umgesetzt mit `ChatAcronymExpansionModel` über
     `RemoteAi.acronymExpansions()` (Chat-Modell der wirksamen Konfiguration, eigene Frist 90 s). Version =
     Verfahren, Modell und Fingerabdruck der `base-url`, nie die Adresse. Ohne Modell `ai-unavailable` (503), ohne
     Freigabe der Quelle in `external-processing-sources` `external-processing-not-allowed` (409). Die Freigabe gilt
     bei Ausführung, nicht beim Einreihen: Sie wird vor jeder Anfrage erneut geprüft, und fällt sie oder das Modell
     während des Laufs weg, scheitert der Auftrag mit `ai-unavailable` ohne Abgleich (#425).
   - Die Antwort ist nicht vertrauenswürdig: Eine Ausschreibung zählt nur, wenn sie wörtlich in einer der genannten
     Fundstellen dieses Akronyms steht; Länge, Zeichen und Anzahl je Akronym sind begrenzt. Verschiedene Bedeutungen
     sind getrennte Vorschläge. Ergebnis nur `ai`/`proposed` über `propose`; Regel-, manuelle und bestätigte
     Beziehungen desselben Schlüssels haben Vorrang, Abgelehnte kommen nie wieder.
   - Scheitert eine Anfrage oder bricht der Auftrag ab, wird nichts abgeglichen. Ins Log kommen nur Quelle, Anzahlen
     und Ausnahmeklassen.
   - Ergebnis-Cache `ai-cache.json` neben der Beziehungsablage (Schema `term-relations-ai-cache/v1`): je Akronym ein
     SHA-256 über Verfahren, Modellversion, Akronym und Lage samt Texthash der Fundstellen, dazu nur die Kennungen der
     Beziehungen aus der Antwort. Ein unveränderter Stand geht nicht noch einmal an das Modell. Keine Begriffe, kein
     Text, keine Antworten. Geschrieben erst nach dem Abgleich, atomar ersetzt (#430).
   - Eine kaputte, unlesbare oder fremde Cache-Datei (anderes Schema, unerwartete Werte) wird verworfen: Der Start
     scheitert nicht, der Cache beginnt leer, der nächste Lauf fragt das Modell und schreibt die Datei neu. Ins Log
     kommt nur die Ausnahmeklasse. Ein neues Verfahren (`PROMPT_VERSION`) oder Modell ändert jeden Fingerabdruck und
     macht alte Einträge damit wirkungslos.

## Grenzen

- Je Seite höchstens 50 000 Fundstellen und 10 000 Begriffe; `count` zählt weiter alles, eine Warnung nennt die
  Kürzung.
- Nominalgruppen nur aus Sätzen, die die Satzanalyse als deutsch erkennt; Akronyme aus jedem Text.
- Ausschreibungen nur in Klammern direkt daneben, ausgerichtet wie im EAC (`LongFormMatcher`, Schwartz und Hearst):
  Buchstaben und Ziffern des Akronyms in Reihenfolge, das erste an einem Wortanfang, im selben Satz, Klammertext bis
  zum ersten Komma (seit `acronyms` 3, `terms` 2). So gilt auch `Zentrale Datenverarbeitung (ZDV)` oder
  `Rückmeldeschlüssel (RSL)`. Ohne Klammer bleibt `RSL` → `Rückmeldeschlüssel` eine Beziehung aus #120 oder ein
  Vorschlag aus #122.
- Gegenüber dem EAC noch offen (`AcronymExtractor`, `AcronymForms`, `TermScanner`): gemischte Schreibung (`GmbH`,
  `IoT`), Punkt-Akronyme (`U.S.A.`), Kennungen mit mehr als zwei Ziffern oder Unterstrich als eigene Art,
  Ausblenden von Versalzeilen, Code, URLs, Mailadressen und Dateinamen, mehrere Ausschreibungen je Akronym. Jede
  davon hebt die Version von `acronyms` und `terms`.
- Kein Fachglossar und keine KI in Index und Abfragen.

## Folgen

- Die App (#339) liest die globale Sicht über `harvester.terms()`, den Reiter je Seite über `PageTerms.read`.
- Eine spätere Änderung des Dokumentaufbaus hebt die Version des Prozessors `terms`; alte Einträge zählen dann nicht
  mehr und werden beim nächsten Lauf ersetzt.
