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
   Mit `ExportRequest.withRoot(rootPageId)` exportiert derselbe Auftrag nur diese Seite und ihre Unterseiten nach
   den Elternbeziehungen der Generation; Einlesen, Rechte und Ergebnis bleiben dieselben, und `JobStatus` nennt
   `rootPageId` und `rootTitle`. `withDepth(n)` begrenzt den Teilbaum auf `n` Ebenen unter der Startseite,
   `withPage(pageId)` (= Tiefe 0) exportiert die Seite allein ohne Unterseiten wie „Seite exportieren“ im Enterprise
   AI Client (#186). Das Manifest nennt die Startseite (`rootPageId`); die Formate beginnen dann wie im Enterprise AI
   Client mit ihr als Kapitel 1.
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

8. **Seite lesen prüft jedes Mal live, der Cache spart nur den Inhaltsabruf** (Angelo, 17.10.; für jede Anmeldeart
   gleich). `PageQueries.page` liest zuerst die Kopfdaten der Seite live und prüft an ihnen in einem Schritt Recht
   und Version (`PageFreshness`): gleiche Version im Cache ⇒ der Cache-Stand wird ausgeliefert (`current`), sonst
   wird die Seite geholt, abgelegt und ausgeliefert. Ohne `refresh` unterscheidet sich der Ablauf nur, wenn die Quelle
   für die Prüfung nicht erreichbar ist (`unavailable`, `rate-limited`): dann kommt der Cache-Stand ungeprüft, aber
   nur, wenn die Zugriffspolicy die Seite für den Aufrufer freigibt; sonst „nicht gefunden“. `refresh=true` liefert
   nie ungeprüft. Die Rechte gelten **vor** dem Inhaltsabruf; eine nicht lesbare Seite wird gar nicht geladen und antwortet wie eine
   unbekannte. Einen neuen Inhalt (fehlende Seite oder neue Revision bei `refresh=true`) legt der Port
   `PageCacheFill` im gemeinsamen Cache ab und berechnet seine Anreicherungen (`EnrichingCacheFill`), danach ist die
   Seite auch in Suche und Export sichtbar. Abgelegt wird nur, was die Quelle mit der Quellenidentität geliefert hat,
   nie etwas Benutzerbezogenes. `LucenePageCache.trySave` wartet höchstens 500 ms auf die Schreibsperre, damit
   gleichzeitige Einzelablagen nacheinander zum Zug kommen: Schreibt gerade ein Abgleich, wird die Seite nur
   ausgeliefert (`origin: live`), und der Abgleich bzw. der nächste Abruf legt sie ab. Die Generation des Space
   ändert sich dadurch nicht; Löschungen leitet weiter nur ein bestätigter Abgleich ab. Eine nicht erreichbare Quelle
   ist bei einer fehlenden Seite und bei `refresh=true` `source-unavailable` und wird nie durch „nicht gefunden“
   ersetzt. Dasselbe gilt für die
   Confluence-kompatible Einzelseite, die denselben Anwendungsfall nutzt.

9. **Suche ist Schlagwortsuche in den Quellen** (Angelo, 17.10. 23:41). `KeywordSearch` ist die Suche über Titel
   und Inhalt für REST (`GET /api/v1/search?text=…`) und App (`Harvester.keywordSearch()`). Sie fragt jede Quelle des
   Umfangs, die selbst suchen kann (`SourceCapability.LIVE_SEARCH`, Confluence: CQL `siteSearch` über
   `/rest/api/search` mit Textauszug, wie die Websuche), und führt die Treffer reihum zusammen. Sie lädt **keine**
   Seiteninhalte und legt nichts ab; den Inhalt beschafft erst das Öffnen einer Seite über 8. Vor jedem Treffer
   liest sie die Kopfdaten der Seite live und prüft an ihnen `metadata` für genau diese Seite (bei delegierten
   Benutzern über die Quelle); so gilt der Space-Filter auch global, und nicht freigegebene Seiten erscheinen weder
   als Titel noch als Auszug noch im Log. Der Cursor merkt sich je Quelle, wie weit sie gelesen ist (höchstens 1000
   Treffer tief). Eine nicht erreichbare Quelle steht als `unavailable` in `sources` und verwirft die anderen nicht
   (`complete=false`); Quellen ohne eigene Suche stehen dort als `not-supported`. Ohne `text` bleibt die Titel- und
   Metadatensuche im Index. Semantische Suche ist nicht Teil davon. Die frühere `ProvisioningSearch`, die die ersten
   25 Treffer je Quelle nachlud und indexierte, ist entfernt.

## Folgeschritte

- **Auftragsdiagnose:** umgesetzt, siehe `docs/DIAGNOSTICS.md`.
