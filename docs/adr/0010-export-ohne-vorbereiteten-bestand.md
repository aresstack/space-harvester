# ADR 0010: Export ohne vorbereiteten Bestand; der Cache ist transparent

- Status: **Angenommen** (Strang Q, 17.10.2026; Auftrag von Angelo nach dem fehlgeschlagenen Word-Export der Beta).
- Bezüge: #11/#12 (Export), #16 (Abgleich, Generationen), #24 (delegierte Rechte), #27 (`refresh`),
  [ADR 0002](0002-native-confluence-snapshot.md) (Raw-First), [ADR 0003](0003-lucene-cache.md) (Lucene-Cache),
  [ADR 0006](0006-core-bibliothek-und-server.md) (Core und Server).

## Kontext

`Exports` exportierte nur aus einer vorhandenen Cache-Generation und brach sonst mit `NOT_CACHED` ab; ein Test hielt
das sogar als gewünschtes Verhalten fest. Wer in der Desktop-App oder über REST einen Space nach Word exportieren
wollte, musste vorher wissen, dass ein Abgleich nötig ist. Das widerspricht dem Projektziel: Space Harvester ist ein
erweiterter Suchcache vor Confluence. Ein Cache ist transparent, wird automatisch befüllt und ist keine Voraussetzung
für irgendetwas (Angelo, 17.10.). Dasselbe gilt für das Lesen einzelner Seiten: Was ein berechtigter Aufrufer
verlangt und nicht im Cache liegt, wird abgerufen und für alle im nicht benutzerspezifischen Storage-Format
zwischengespeichert.

## Entscheidung

1. **Der Export beschafft seine Daten selbst.** `Exports.start(ExportRequest)` nimmt Quelle, Space, Formate,
   `refresh` und die Leserechte des Auftraggebers je Seite. Im Auftrag gilt:
   - bestätigte Generation vorhanden und kein `refresh`: wiederverwenden, nichts von der Quelle lesen;
   - keine oder keine bestätigte Generation, oder `refresh`: den Space zuerst einlesen, dann exportieren.
2. **Einlesen = derselbe Weg wie der Abgleich, keine zweite Abruflogik.** Das Einlesen ist der Port
   `SpaceProvisioning`; `SpaceSyncJobs` setzt ihn um und ruft genau `SpaceSync.run` mit `PlanApplier` und dem
   konfigurierten Inhaltsabruf auf, samt Folgearbeit nach dem Bestätigen (Anreicherungen, Index). Raw-First bleibt:
   Originalpayload, Revision und Hashes landen wie beim Abgleich im Cache, und exportiert wird immer, was die
   bestätigte Generation hält.
3. **Ein Auftrag, ein Ausführungsplatz.** Das Einlesen läuft auf dem Thread des Exportauftrags; es gibt keinen zweiten
   Auftrag, auf den der Export wartet, also keinen Stillstand bei begrenzter Zahl gleichzeitiger Aufträge. Abbruch und
   Laufzeitgrenze greifen über dieselben Prüfpunkte; ein Abbruch verwirft das Vorgemerkte, die bisherige Generation
   bleibt gültig, nichts wird veröffentlicht.
4. **Unvollständig bleibt unvollständig.** Kann das Einlesen keine Generation bestätigen, scheitert der Export mit
   `SYNC_INCOMPLETE`, wenn es keinen Bestand gibt oder `refresh` verlangt war; sonst entsteht ein ausdrücklich
   unvollständiger Export aus dem bisherigen, unbestätigten Bestand. Scheitert der Abruf einzelner Seiten, ist das
   Artefakt nie `complete` und die Warnung nennt die Anzahl. Hat ein gleichzeitiger Abgleich zuerst bestätigt
   (`SyncConflictException`), wird dessen Generation exportiert.
5. **Gleiche Funktionen für jede Anmeldeart, Rechte je Seite.** PAT, Basic und delegierte Anmeldung sind nur
   austauschbare Arten der Authentifizierung; keine schließt eine Funktion aus (Angelo, 17.10.). Die frühere
   Beschränkung delegierter Endnutzer auf `metadata` und `content` in `DelegatedAccessPolicy` entfällt; ein
   Endnutzer bekommt, was die Korpusfreigabe des Clients erlaubt, und sieht, was die Quelle ihm zeigt. Der Export
   prüft jede Seite mit `AccessGuard.canRead`. Nicht lesbare Seiten gehören nicht zur Sicht des Auftraggebers: Sie
   fehlen im Artefakt und werden nirgends genannt, weder im Manifest noch in Warnungen, Fehlern oder Zählern; das
   Einlesen zählt deshalb nichts, gezählt wird erst je lesbarer Seite. `complete` bezieht sich auf diese Sicht. Für
   das Einlesen ist keine Freigabe `sync` nötig: Es ist ein interner Schritt der Datenbeschaffung mit der
   Quellenidentität, das Ergebnis liegt benutzerunabhängig im gemeinsamen Cache und wird bei jeder Ausgabe erneut
   gegen die Rechte des Aufrufers geprüft.
   Der Aufrufer (bei Delegation samt Token) lebt nur im Speicher des laufenden Auftrags. Die Freigabe für den Space
   wird zu Beginn und vor dem Veröffentlichen erneut geprüft; gilt sie nicht mehr (Token abgelaufen, Recht entzogen),
   scheitert der Export mit `ACCESS_REVOKED` ohne Artefakt. Entscheidungen der Quelle gelten wie bisher höchstens
   `decision-ttl`.
6. **Fehlercodes.** `NOT_CACHED` entfällt. Neu: `PROVISIONING_UNAVAILABLE` (Cache-Speicher oder Inhaltsabruf nicht
   angebunden), `UNKNOWN_SOURCE`, `SYNC_INCOMPLETE`, `ACCESS_REVOKED`; Quellfehler wie bisher `SOURCE_<Grund>`.
7. **Zähler.** Während des Einlesens bleiben die Zähler stehen (siehe 5.); danach zählt der Export jede lesbare Seite
   einmal. Die Phasen selbst macht die Auftragsdiagnose sichtbar (Folge-PR).

## Folgeschritte

- **Seite lesen** (`PageQueries.page`): Heute liefert sie ohne `refresh` bei fehlender Seite `cache-miss`, und
  `refresh=true` holt die Seite live, schreibt sie aber nicht in den Cache. Ziel: ein gemeinsamer Nachladeweg
  (Rechte zuerst an den Metadaten prüfen, dann Inhalt mit der Quellenidentität laden, im Cache ablegen, ausliefern),
  den Seite lesen, gezieltes Nachladen bei der Suche und der Export nutzen. Eigener PR.
- **Suche nach unbekannten Seiten:** Ein leerer Cache ist nicht „keine Treffer“. Eine ergänzende Live-Suche in den
  Quellen (Confluence CQL) ist möglich, wird aber gesondert entschieden.
- **Auftragsdiagnose:** strukturierte Ereignisse je Auftrag (Phase, Komponente, HTTP-Status, Proxy-Route, TLS-Befund).
