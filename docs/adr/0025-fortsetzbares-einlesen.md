# ADR 0025: Abgebrochenes Einlesen setzt fort statt neu zu beginnen

- Status: **Angenommen** (Strang Q, 10.11.2026; Wunsch Angelo 10.11.2026: „eine Art Download-Manager, der nicht jeden
  Download immer wieder von vorne beginnt“).
- Baut auf: [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md) (Export liest selbst ein),
  [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md) (Cache kennt Seiten auch ohne Generation, `knownPages`).

## Kontext

Ein Abgleich oder ein Export mit Einlesen merkt alle Schreibvorgänge in einer `StagedGeneration` vor und macht sie
erst mit der neuen Generation sichtbar. Bricht der Lauf ab (Abbruch, Laufzeitgrenze, Absturz), wird alles verworfen,
auch die schon geladenen Seiten. Bei einer Erstbefüllung mit Tausenden Seiten beginnt „Erneut versuchen“ dann von vorn.
Zudem hält der Lauf die Schreibsperre des Caches über den ganzen Abruf, sodass Beschaffung und Suche warten.

## Entscheidung

1. **Neue Seiten sofort und dauerhaft.** Seiten, die weder Generation noch Cache kennen (`PageChange.Kind#NEW`), lädt
   `PlanApplier#loadNew` vor `SyncBaselineStore#begin` und legt sie mit `SyncBaselineStore#keepLoaded` ab, wie es die
   Beschaffung tut (`LucenePageCache#saveWaiting`: begrenzte Wartezeit, überschreibt nichts Neueres). Sie sind damit
   vor der Generation sichtbar, genau wie Seiten aus Suche oder Abruf (ADR 0014, Punkt 2).
2. **Fortsetzen ist Wiederholen.** Der nächste Lauf vergleicht die Inventur mit `knownPages`; schon abgelegte Seiten
   derselben Version sind `UNCHANGED` und werden nicht erneut geladen. Ein eigener zweiter Download-Weg entsteht
   nicht.
3. **Änderungen bekannter Seiten bleiben atomar.** Neue Versionen, Kopfdaten, Entfernen und Sperren laufen weiter
   über die `StagedGeneration` und werden nur mit der Generation sichtbar.
4. **Umsetzungen ohne diese Fähigkeit** (`keepsLoadedPages() == false`, etwa Testfakes) verhalten sich wie bisher.
5. **Anhalten und Fortsetzen im selben Auftrag** (Wunsch Angelo 10.11.2026: Play-/Pause-Knopf). Neuer Zustand
   `JobState.PAUSED` („Angehalten“), getrennt von Abgebrochen und Fehlgeschlagen. `Jobs#pause` nutzt den Abbruch-
   Prüfpunkt: der laufende Schritt endet sauber, ein wartender Auftrag ist sofort angehalten. `Jobs#resume` reiht
   einen angehaltenen, gescheiterten (etwa Verbindungsabbruch) oder abgebrochenen Auftrag unter derselben ID wieder
   ein; er läuft von vorn, holt nach Punkt 2 aber nur, was fehlt, und seine Zähler beginnen neu. Nur Abgleich und
   Export (`JobType#resumable`); ein Abgleich setzt nicht fort, solange für den Space ein anderer läuft.
6. **Nach einem Neustart** ist die Arbeit eines Auftrags nicht mehr im Speicher. Seit Punkt 8 läuft er trotzdem im
   selben Eintrag weiter (`Jobs#rerun`): `JobStatus#parameters` legt ab, was ein gleichwertiger Lauf braucht
   (Export: Tiefe, Formate, Aktualität; Abgleich: Betriebsart, Nachholen ab; Bilder: Export-ID), und der zuständige
   Anwendungsfall baut daraus dieselbe Arbeit. `job-not-resumable` (409) kommt nur noch für Aufträge, die noch laufen
   oder fertig sind, für andere Arten und für alte Bilderaufträge ohne Export-ID.
7. **Ein Teilbaum-Export lädt nur seinen Teilbaum.** Die Inventur listet weiter den ganzen Space (für die
   Elternkette), Inhalte lädt das Einlesen aber nur für Startseite und Unterseiten (`SyncPlan#withLoadingLimitedTo`).
   Der übrige Space wird wie bei `CATALOG_ONLY` behandelt: unbekannte Seiten kommen nur als Kopfdaten in den Katalog,
   geänderte bleiben veraltet. Die Generation ist damit nicht `CONFIRMED`; der Export gilt trotzdem als vollständig,
   wenn ihre Lücken (`SpaceProvisioning#unloaded`) alle außerhalb des Teilbaums liegen. Ein späterer Space-Export
   liest dann neu ein und lädt nur das Fehlende.

8. **Zustandsobjekte und ein Einstieg für Aktionen** (Angelo 10.11.2026). `JobLifecycleState` leitet aus
   `JobState`, `JobStatus#incomplete` und `JobProgress#failed` genau einen wirksamen Zustand ab (Wartet, Läuft,
   Anhalten/Abbruch angefordert, Angehalten, Fertig, Fertig mit Fehlern, Fehlgeschlagen, Abgebrochen); jeder Zustand
   nennt seine erlaubten `JobAction`s in Anzeigereihenfolge. ▶ und ⏸ erscheinen nie zusammen, vor dem Start steht
   „Jetzt starten“ (`Jobs#prioritize`, an den Anfang der Warteschlange), gelöscht wird nur Beendetes, und Löschen
   lässt die Daten liegen. `JobActions#perform` prüft am Zustand und gibt an Abgleich, Export, Bilder oder `Jobs`
   weiter; keine Aktion legt einen neuen Auftrag an. `COMPLETED` heißt nur technisch beendet: unvollständige Exporte
   und Bilder melden `JobContext#incomplete`.
9. **Bilder sind fortsetzbar** (`JobType#ASSETS`). Beim Anhalten schreibt der Auftrag das bisherige Manifest; ein
   Fortsetzen übernimmt fertig geprüfte Seiten mit ihren Einträgen und Zählern ohne Abruf, schon abgelegte Dateien
   kommen aus dem Speicher.

10. **Selbst anhalten statt Fehler zählen** (Angelo 10.11.2026, CLUK: 5535 „Fehler“ nach abgelaufener Code-Abfrage).
    `JobContext#pause(PauseReason)` beendet einen Auftrag als `PAUSED` mit Grund (`JobStatus#pauseReason`):
    `SIGN_IN_REQUIRED`, wenn mit Oberfläche die Anmeldung nicht rechtzeitig kommt oder abgelehnt wird (vorher
    zählten alle übrigen Dateien sofort als „Anmeldung fehlt“), `CONNECTION_LOST`, wenn Bilder die Quelle nicht
    erreichen oder das Einlesen von Export und Abgleich `PlanApplier#CONNECTION_LOST_AFTER` (5) Inhaltsabrufe
    nacheinander an fehlender Verbindung oder Drosselung scheitert; jede Antwort der Quelle setzt die Zählung zurück.
    Ohne Oberfläche (Dienst) gilt beim Einmalcode das alte Verhalten. Der Zustand heißt dann `PAUSED_SIGN_IN_REQUIRED`
    bzw. `PAUSED_CONNECTION_LOST`, die Aktionen sind dieselben wie bei „Angehalten“.

11. **Anhänge als eigener Auftrag** (Angelo 10.11.2026). Bei eingeschalteten Anhängen (`export.attachments`) startet
    nach dem Export neben „Bilder beschaffen“ ein Auftrag `JobType#ATTACHMENTS` („Anhänge beschaffen“), ebenfalls
    Folgeauftrag, anhaltbar und fortsetzbar, mit denselben Zuständen und Aktionen. Beide sind derselbe Code
    (`ExportAssetCollection`, Art `MEDIA` oder `DOCUMENTS`) mit demselben Download, derselben Anmeldung und Ablage.
    Beide schreiben in `assets.json`, jeder nur seine eigenen Einträge, unter einem Schloss je Export;
    `imagesComplete` und `documentsComplete` vermerken die Vollständigkeit je Art, `complete` gilt für beide.
    `attachments.json` im Paket entsteht wie bisher erst beim Konvertieren aus den Dokument-Einträgen.

12. **Einstellbare Wiederholungen** (Angelo 10.11.2026). `space-harvester.jobs.retry.*` in Dienst und App
    (`RetrySettings`, in `JobLimits`): `max-attempts` (0–10, Standard 2 wie bisher im Dienst), `initial-delay` (Standard 1 s), `max-delay`
    (Standard 30 s), `pause-after-failures` (Standard 5). Es gibt eine Schleife, die des REST-Clients der Quelle
    (`maxRetries`, `retryBackoffMillis`, `maxRetryBackoffMillis`, exponentiell mit Zufall); eigene `options` einer Quelle
    gehen vor. Wiederholt werden nur Netzfehler, Zeitüberschreitungen, 429 und 5xx; nie 401, 403, 404, ungültige
    Antworten, Datei-Downloads und das Absenden eines Einmalcodes (keine neue Sitzung, keine neue Mail). Damit
    wiederholt auch die Desktop-App Seitenabrufe; die frühere Begründung für 0 (#571) betraf Dateien mit Code-Abfrage,
    die der Client nicht wiederholt. `pause-after-failures` ersetzt den festen Wert aus Punkt 10. Änderungen wirken
    nach einem Neustart. Offen: `Retry-After` (nur per Upstream-Patch) und das Abbrechen einer laufenden Wartezeit
    beim Anhalten; heute wirkt Anhalten nach dem laufenden Abruf, also nach höchstens `max-delay` je Versuch.

13. **↻ Nur Fehler neu versuchen** (Angelo 10.11.2026: „Die sechs fehlgeschlagenen Seiten gezielt nachladen, ohne
    erneute Inventur“; „der retry macht keinen neuen auftrag mehr daraus“). Ein mit Fehlern fertiger Download
    (`COMPLETED_WITH_ERRORS`) bietet `JobAction#RETRY_FAILED` an. Das Manifest nennt die lesbaren Seiten, deren Abruf
    beim Einlesen scheiterte (`unfetched`), neben den fehlenden (`failures`); genau sie lädt `Exports#retryFailed`
    im selben Eintrag nach (`Jobs#rerun`), ohne Inventur: `SpaceProvisioning#retry` bzw. `FailedPageRetry` fragt jede
    Seite einzeln nach, prüft Lesbarkeit und Umfang an den frischen Kopfdaten (sonst fällt sie still weg, ohne
    Zähler), lädt den Inhalt über denselben `PlanApplier` und bestätigt darauf die nächste Generation; alle übrigen
    Seiten bleiben, wie sie sind. Danach entsteht das Artefakt neu und ersetzt das bisherige erst nach Erfolg
    (`ExportArtifactWriter#replace`); Bilder-, Anhängemanifest und Lese-Ansichten wandern mit, das Manifest als
    unvollständig, und die vorhandenen Aufträge „Bilder beschaffen“ und „Anhänge beschaffen“ laufen im selben Eintrag
    erneut. Solange einer davon noch läuft, lehnt ↻ ab (das Artefakt wird gelesen). Zähler: alle exportierten Seiten
    plus die wieder gescheiterten; was die Quelle nicht nachprüfen konnte, bleibt gescheitert. Ohne bestätigte
    Generation liest der Lauf wie beim Fortsetzen ein. In der App führt der Pfeil im Kreis bei einem solchen Download
    ↻ aus, bei einem gescheiterten oder abgebrochenen Fortsetzen.

14. **⟲ Neu erzwingen** (Angelo 10.11.2026: „Den gesamten Datenbestand tatsächlich neu von Confluence abrufen, aber
    erst nach erfolgreichem Download den bisherigen Bestand ersetzen“). Ein beendeter Download (fertig, mit Fehlern,
    gescheitert oder abgebrochen) bietet `JobAction#FORCE_REFRESH` an. `Exports#forceRefresh` läuft im selben Eintrag
    erneut, mit Inventur und `reloadAll` (`SpaceProvisioning#provisionScoped(…, reloadAll)`): `SyncPlan#withContentReloaded`
    macht auch unveränderte Seiten im Ladeumfang zu `CONTENT_CHANGED`. Sie laufen über die `StagedGeneration`, ersetzen
    ihren bisherigen Stand also erst mit der neuen Generation; scheitert ein Abruf, bleibt der bisherige als veraltet.
    Ein Abbruch verwirft das Vorgemerkte, Bestand und Artefakt bleiben. Das neue Artefakt ersetzt das bisherige erst
    nach Erfolg (`ExportArtifactWriter#replace`), Bilder und Anhänge laufen danach in ihren Einträgen erneut; solange
    sie noch laufen, lehnt ⟲ ab. Ein Teilbaum-Export lädt nur seinen Teilbaum neu. In der App steht ⟲ als eigener
    Knopf neben ↻; ↻ lädt bei Downloads nur noch gescheiterte Seiten nach.

15. **↻ und ⟲ für Abgleiche.** Ein Abgleich, der seine Generation bestätigt, aber Seiten nicht geladen hat
    (`SYNC_PARTIAL`), ist `COMPLETED_WITH_ERRORS` und bietet `RETRY_FAILED`, `FORCE_REFRESH` und `DELETE` an.
    `SpaceSyncJobs#retryFailed` lädt im selben Eintrag genau die Seiten nach, die der letzte Lauf nicht laden oder
    prüfen konnte (nach einem Neustart: die veralteten der bestätigten Generation, bei einem vollständigen Abgleich
    auch die fehlenden), über `FailedPageRetry`, ohne Inventur; scheitern wieder welche, bleibt er `SYNC_PARTIAL`.
    `SpaceSyncJobs#forceRefresh` inventarisiert im selben Eintrag in seiner Betriebsart neu und lädt mit
    `SyncPlan#withContentReloaded` auch unveränderte Seiten neu; die bisherige Generation gilt, bis der Lauf
    bestätigt. Fertige, gescheiterte und abgebrochene Abgleiche bieten ⟲ an, ein reiner Katalogabgleich
    (`CATALOG_ONLY`) nicht. Ein Abgleich, der keine Generation bestätigt hat, setzt wie bisher fort (▶).

16. **Löschen in jedem Zustand, hängender Abbruch** (Angelo 10.11.2026: Mülltonne immer da, „Alle Aufträge löschen“
    löscht alles). Jeder Zustand bietet `DELETE` als letzte Aktion an. `Jobs#delete` bricht einen wartenden Auftrag
    ab und beendet einen laufenden sofort; dessen Arbeit wird abgekoppelt (`Job#detached`): sie meldet nichts mehr,
    legt den Eintrag nicht wieder an und endet an ihrem nächsten Prüfpunkt. `Jobs#deleteAll` (App:
    `Workspace#deleteAllJobs`) löscht alle eigenen Aufträge in jedem Zustand. Erreicht ein laufender Auftrag nach
    angefordertem Abbruch oder Anhalten `Jobs#STOP_GRACE` (30 s) lang keinen Prüfpunkt, weil ein Abruf der Quelle
    hängt, gilt er als abgebrochen bzw. angehalten und gibt seinen Platz frei; ▶ startet einen neuen Lauf desselben
    Eintrags, sobald die abgekoppelte Arbeit geendet hat (vorher lehnt `Jobs` ab, weil beide Läufe dieselben Dateien
    unter derselben ID schreiben würden). Löschen legt den Endzustand ab, bevor es Ereignisse löscht; scheitert das,
    bleibt ein abgebrochener Eintrag und der Platz ist trotzdem frei. Bilder und Anhänge, die unvollständig fertig
    sind, bieten `RETRY_FAILED` an: derselbe Eintrag läuft erneut und holt nur, was fehlt; abgelegte Dateien kommen
    aus dem Speicher. `FORCE_REFRESH` (⟲) gilt auch für Bilder und Anhänge: derselbe Eintrag holt jede Datei neu von
    der Quelle, am Speicher vorbei; der Speicher legt Inhalte nach Prüfsumme ab, der alte Stand bleibt also lesbar,
    bis der neue da ist.
17. **Wiederanlauf nach Verbindungsabbruch, ↻ in jedem Zustand** (Angelo 11.11.2026: WLAN; „der Button soll immer
    verfügbar sein“). Hält sich ein Auftrag wegen fehlender Verbindung an (`PAUSED_CONNECTION_LOST`), läuft er nach
    `jobs.retry.resume-initial-delay` (10 s) von selbst wieder an; jeder weitere vergebliche Anlauf verdoppelt die
    Wartezeit bis `jobs.retry.resume-max-delay` (10 min), aufgegeben wird nie. Hielt ein Lauf mindestens
    `Jobs#RECONNECT_RESET_AFTER` (2 min), beginnt die Wartezeit wieder von vorn. Der Anlauf geht über
    `JobActions#perform(RESUME)` (`Jobs#recovery`), also denselben Weg wie ein Klick; hat der Benutzer inzwischen selbst
    gehandelt, entfällt er. `Jobs#reconnectAt` nennt den nächsten Anlauf. Auch nach einem Neustart laufen so angehaltene
    Aufträge nach der ersten Wartezeit wieder an. `RETRY_FAILED` bietet jeder Zustand an, sobald etwas gescheitert ist:
    laufend hält der Durchgang am nächsten Prüfpunkt an und läuft sofort im selben Eintrag neu, Abgelegtes bleibt
    (`Jobs#requestRetryFailed`, Angelo 12.11.2026: nicht bis zum Ende warten). Bis dahin heißt der Zustand `RESTARTING`
    („Startet neu“, `PauseReason#RETRY`) und bietet ⏸ (dann ohne Neustart) und Abbrechen an; hält der Auftrag sich selbst
    an (Anmeldung, Verbindung), gilt das statt des Neustarts. Weiteres Drücken, solange der Neustart aussteht und bis
    `Jobs#RETRY_DEBOUNCE` (5 s) nach dem Start des neuen Laufs, löst keinen weiteren aus. Angehalten oder
    abgebrochen setzt es fort, und Fortsetzen holt das Gescheiterte mit. Bilder und Anhänge besuchen dabei die Seiten mit
    gescheiterten Dateien erneut (`ExportAssetCollection#retryFailed`). Lehnt der Anwendungsfall das nachgeholte
    `RETRY_FAILED` ab, etwa weil Bilder des Exports noch laufen, folgt es mit derselben wachsenden Wartezeit. Der
    Wiederanlauf prüft Lesbarkeit und Exportrecht wie beim Start; der Dienst setzt einen Export nur fort, solange er
    dessen Arbeit vom Start hält, sonst bleibt er angehalten, bis der Client ihn mit seinem Zugang fortsetzt.
18. **Vorbereiten und Laden in eigenen Pools** (Angelo 11.11.2026). Bilder- und Anhängeaufträge (`JobType#download`)
    belegen nicht die allgemeinen Plätze, sondern zwei eigene: höchstens `jobs.parallel-preparations` (Standard 2, 1 bis
    16) bereiten gleichzeitig vor, höchstens `jobs.parallel-downloads` (Standard 4, 0 bis 64) laden gleichzeitig. Die
    Vorbereitung nimmt für alle Seiten die Anhangsliste und zählt die Anhänge; die Leseansicht holt sie nie (Angelo 11.11.2026: „die
    Vorbereitung sollte gar keine Leseansicht holen“). Erst der Ladedurchgang holt sie, und nur für Seiten mit
    berechneten oder fremden Inhalten (`ForeignContent`: include, excerpt-include, multiexcerpt-include, children,
    Jira …) oder Erwähnungen von Personen, deren Inhalt allein die gerenderte Ansicht zeigt; ihre Bilder kommen dort zur Gesamtzahl hinzu. Alle
    übrigen Bilder kommen aus dem Anhangskatalog (Punkt 21), draw.io und Mermaid aus der Quelle. Dann wartet der Auftrag im Zustand `PREPARED` („Vorbereitet“) auf
    einen Ladeplatz (`JobContext#awaitDownloadSlot`; „Jetzt starten“ zieht ihn vor) und lädt im Zustand `RUNNING`. Bei 0 Downloads
    werden Aufträge angenommen und vorbereitet, laden aber nichts; das Einlesen der Seitentexte läuft weiter. Beide Werte
    gelten sofort (`Workspace#setParallelPreparations`, `#setParallelDownloads`) und stehen unter `jobs` in der
    gemeinsamen YAML (`ConfigFileEditor#withParallel`). Zustandsschlüssel: `PREPARING`, `PREPARED`.
19. **Bilder und Anhänge entstehen mit dem Export** (Angelo 11.11.2026: „Dann hat der User nämlich die Möglichkeit,
    den Auftrag zu löschen“). `Exports#onSubmitted` → `ExportAssetCollection#announce` legt „Bilder beschaffen“ und
    „Anhänge beschaffen“ beim Start des Exports an (`Jobs#submitAwaiting`). Sie warten im Zustand `AWAITING_EXPORT`
    („Wartet auf Text“, `JobStage#AWAITING`, einzige Aktion Löschen), belegen keinen Platz und zählen nicht gegen die
    Grenzen. Ist der Text veröffentlicht, gibt `ExportAssetCollection#start` sie frei (`Jobs#releaseAwaiting`).
    Gelöschte bleiben still weg: Der Export trägt `follow-ups=announced`, es entsteht nur der Text, ohne Fehler. Scheitert,
    endet oder hält der Text an, warten sie weiter, bis „Fortsetzen“ ihn veröffentlicht. Wird der Export gelöscht,
    verschwinden sie mit. Nach einem Neustart gelten sie wie jeder wartende Auftrag als unterbrochen und laufen beim
    nächsten Veröffentlichen im selben Eintrag. Die Verknüpfung zum Export steht in `exportId`.
20. **Restzeit aus globalen Download-Dauern** (Angelo 11.11.2026). `DownloadDurations` misst je tatsächlich von der
    Quelle geladener Datei die Dauer des Transfers, getrennt für Bilder und Dateianhänge (`Kind#of` wie die beiden
    Aufträge), über alle Exporte. Treffer aus dem Speicher, Warten auf einen Platz und Seitentexte zählen nicht. Der
    Durchschnitt beginnt mit einem Startwert (Bild 1 s, Anhang 3 s), den die erste Messung ersetzt, und mittelt danach
    über etwa die letzten 50 Dateien. Er liegt als Anwendungszustand in `download-durations.properties` im
    Datenverzeichnis, nicht in der YAML. Die App liest ihn über `Workspace#averageDownload(JobType)` und rechnet die
    Restzeit: Durchschnitt × offene Verweise ÷ gleichzeitig ladende Aufträge.
21. **Anhangskatalog über die Suche** (Angelo 11.11.2026: keine zusätzlichen Aufrufe). Die Vorbereitung von „Bilder
    beschaffen“ und „Anhänge beschaffen“ holt die Anhangslisten aller Seiten am Anfang über die Suche der Quelle
    (`HarvestSource#attachmentsOf`, bei Confluence `type=attachment AND container in (…) AND space="KEY"` in Stapeln zu
    50 Seiten, EAC `AttachmentSearchSource`, enterprise-ai-client#217) statt Seitendaten und Anhangsliste je Seite.
    Der Space-Filter ersetzt die frühere Space-Prüfung für Seiten mit Treffern: sie liegen nachweislich im Space. Eine
    leere Liste heißt ohne Anhänge oder verschoben; vor der Lese-Ansicht (nur der Bilderauftrag, im Ladedurchgang)
    prüft er bei diesen Seiten den aktuellen Space einzeln. Kann die Quelle nicht suchen oder scheitert eine Abfrage, fragt die Vorbereitung die
    betroffenen Seiten einzeln wie bisher. Die Rohantworten der Suche werden nicht je Seite gehalten.
22. **Verbindungen (gesamt) für Bilder und Anhänge** (Angelo 11.11.2026). Bis hier lud ein Auftrag im Ladedurchgang
    eine Datei nach der anderen. `jobs.download-connections` (Standard 8, 0 bis 64, beide Loader und
    `ConfigurationParityTest`) ist ein Gesamtbudget gleichzeitiger Dateiabrufe aller ladenden Bilder- und
    Anhängeaufträge (`ConnectionBudget`); Textdownloads bleiben bei einer Verbindung. Lädt nur ein Auftrag, bekommt er
    alle Verbindungen; laden mehrere, verteilt sich das Budget bei jeder Datei neu nach ihrer geschätzten Restzeit
    (offene Verweise × Durchschnittsdauer aus Punkt 20), damit möglichst alle zugleich fertig werden, jeder mit
    mindestens einer. Ein Auftrag lädt so viele Seiten zugleich, wie sein Anteil hergibt; jede Datei belegt eine
    Verbindung. Das Manifest behält die Reihenfolge der Seiten. Fordert die Quelle einen Code an, wartet nur eine
    Verbindung darauf, die anderen laden danach dieselbe Datei noch einmal (eine Mail, ein Code, wie #571). Bei 0
    werden keine Bilder und Anhänge geladen: die Aufträge warten in der Phase „Wartet auf Verbindungen“, ohne Fehler zu
    zählen, und laden weiter, sobald das Budget wieder steigt. Die App ändert es über
    `Workspace#setDownloadConnections` (Menüeintrag baut Strang OH).

REST: `POST /api/v1/exports/{id}/pause|resume` und `POST /api/v1/syncs/{id}/pause|resume`; die Knöpfe in der
Auftragszeile und im Overlay baut Strang OH über `Workspace#pauseJob`, `#resumeJob` und `#canResume`; künftig
zeichnet sie nur `Workspace#jobState(id).actions()` und ruft `Workspace#performJobAction`.

## Folgen

- Ein abgebrochener Lauf hinterlässt neue Seiten ohne Generation; Suche und Lesen finden sie, der Export erst mit
  einer bestätigten Generation.
- Die Schreibsperre ist während des Abrufs neuer Seiten nur je Seite belegt.
- Ein Teilbaum-Export lädt nur die Seiten seines Teilbaums statt des ganzen Space.
- Offen: Die Inventur selbst hält ihren Stand noch nicht fest und läuft sequenziell (eigener Schritt; der EAC listet
  ebenfalls sequenziell).
