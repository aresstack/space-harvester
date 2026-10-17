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

8. **Seite lesen ist transparent gecacht.** `PageQueries.page` liefert einen Treffer aus dem Cache; fehlt die Seite,
   lädt sie sie über `PageFreshness` nach, wie bei `refresh=true`. Die Rechte prüft sie an den live gelesenen
   Kopfdaten, **bevor** der Inhalt geladen wird; eine nicht lesbare Seite wird gar nicht geladen und antwortet wie eine
   unbekannte. Einen neuen Inhalt (fehlende Seite oder neue Revision bei `refresh=true`) legt der Port
   `PageCacheFill` im gemeinsamen Cache ab und berechnet seine Anreicherungen (`EnrichingCacheFill`), danach ist die
   Seite auch in Suche und Export sichtbar. Abgelegt wird nur, was die Quelle mit der Quellenidentität geliefert hat,
   nie etwas Benutzerbezogenes. `LucenePageCache.trySave` wartet höchstens 500 ms auf die Schreibsperre, damit
   gleichzeitige Einzelablagen nacheinander zum Zug kommen: Schreibt gerade ein Abgleich, wird die Seite nur
   ausgeliefert (`origin: live`), und der Abgleich bzw. der nächste Abruf legt sie ab. Die Generation des Space
   ändert sich dadurch nicht; Löschungen leitet weiter nur ein bestätigter Abgleich ab. Eine nicht erreichbare Quelle
   ist `source-unavailable` und wird nie durch „nicht gefunden“ oder einen alten Stand ersetzt. Dasselbe gilt für die
   Confluence-kompatible Einzelseite, die denselben Anwendungsfall nutzt.

9. **Suche ist Beschaffung.** `ProvisioningSearch` ist die Suche für REST (`GET /api/v1/search`) und App. Mit
   Suchtext (ohne Cursor und ohne `pageId`) fragt sie jede Quelle des Umfangs, die selbst suchen kann
   (`SourceCapability.LIVE_SEARCH`, Confluence: CQL `siteSearch` wie die Websuche, also Titel **und** Inhalt), nach
   höchstens 25 Seiten. Jeder Treffer geht über den Seitenabruf aus 8.: fehlt er im Bestand, wird er geholt, abgelegt
   und indexiert, mit derselben Rechteprüfung wie beim Lesen (bei delegierten Benutzern je Seite über die Quelle).
   Ausgeliefert wird er aus dem Index, mit allen Filtern der Anfrage (Spaces, Eltern, Version, Zeit) und
   `metadata` an seinen aktuellen Kopfdaten; nicht lesbare Treffer fallen ohne Spur weg (auch nicht im Log).
   Die Suche in der Quelle läuft mit der Quellenidentität und liefert nur IDs; sichtbar wird davon nur, was der
   Aufrufer lesen darf. Die Antwort heißt `origin: source`, ist ein Stapel ohne Cursor, und `complete` heißt, dass
   keine Quelle mehr Treffer hatte als abgefragt. Ohne Suchtext, mit Cursor, für Quellen ohne eigene Suche, ohne
   `content` für die Quelle oder wenn die Quelle nicht antwortet, gilt die Titelsuche im Index (`origin: cache`);
   ist die einzige Quelle nicht erreichbar und nichts von ihr im Index, ist es `source-unavailable`. Global werden
   die Quellen in Konfigurationsreihenfolge abgefragt. Hinter den Treffern der Quellen folgen ohne Doppelte die
   Titeltreffer aus dem Index über alle Quellen des Umfangs (Quellen ohne eigene Suche, Seiten, die der Suchindex der
   Quelle noch nicht kennt); `sourceIds` gilt wie in ADR 0009.

## Folgeschritte

- **Volltext im Index:** Ohne erreichbare Quelle trifft die Suche nur Titel. Eine Volltextprojektion des Inhalts im
  Lucene-Index (aus der statischen Textprojektion der Anreicherungen) folgt gesondert.
- **Auftragsdiagnose:** umgesetzt, siehe `docs/DIAGNOSTICS.md`.
