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

10. **Selbst anhalten statt Fehler zählen** (Angelo 10.11.2026, großer Space: 5535 „Fehler“ nach abgelaufener Code-Abfrage).
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

## Nachtrag 21.11.2026: Hintergrundaufträge

Angelo 21.11.2026: Was die App von sich aus anstößt (fehlende Seiten nachladen, etwa Nachbarseiten eines Suchtreffers;
Bilder automatisch beschreiben; künftig weitere Folgeschritte nach dem Abgleich), läuft als eigene Auftragsart und
wird automatisch verwaltet.

1. **Eigene Auftragsart** `JobType.BACKGROUND` (`JobType#background()`). Welche Arbeit, sagt die Angabe
   `background.kind`; Arten melden sich bei `BackgroundJobs#register(BackgroundKind)` an (`application.background`).
   Eingebaut ist „Seiten nachladen“ (`PageLoads`, Art `pages`, `Harvester#pageLoads()`); die Bildbeschreibung meldet
   sich als Art `images` an. Fahrplan D3: „Aussagen analysieren“ über Teilbaum, Space oder Quelle ist die Art
   `statements` (`StatementAnalysisJobs#background`); eine einzelne Seite bleibt ein normaler Auftrag
   `STATEMENT_ANALYSIS`, als Klick über `Jobs#submitInteractive` (ebenso „Prüfen“ einer Seite), derselbe Code rechnet
   beide. Der Reiter „Aussagen“ rechnet und prüft dabei die gezeigte Fassung (`startShown`, `startCheckShown`), ohne
   die Seite erneut abzurufen; mit „mit Unterseiten“ zählt und fragt er wie der Baum (Sicherheitsgrenze, „wirklich“),
   verfolgt die Gruppe über `BackgroundJobs#watch` (Fortschritt und Abbrechen im Reiter, nach dem Anhalten weiter, bis
   sie fertig ist) und liest ihr Ergebnis abseits des EDT (`Workspace#statementGroupOmitted`, mit dem Recht
   `metadata` auf den Space statt `sync`). Eine Quelle mit Grenze: Die Grenze gilt für die Summe aller Spaces. Jeder
   Teil zählt nach seiner eigenen Ermittlung die schon ermittelten Seiten der übrigen Teile aus dem Auftragsspeicher
   dazu, ohne fremde Spaces abzugehen; wer die Grenze damit überschreitet, scheitert vor seiner ersten Analyse
   (`SCOPE_TOO_LARGE`), fertige Teile behalten ihre Analysen. Die ermittelte Zahl steht zusätzlich als Ereignis
   `SCOPE_COUNTED` am Teil, denn Fortsetzen leert seine Zähler; Geschwister zählen das Größere von beidem.
2. **Gruppe statt Oberauftrag mit eigenem Platz.** Ein Start (`BackgroundJobs#start`) legt je Space einen Unterauftrag
   an; alle tragen dieselbe `background.group` und `background.title`. Die Gruppe (`BackgroundGroup`) ist kein eigener
   Auftrag, belegt nichts und hat ihren Zustand aus den Unteraufträgen. Jeder Unterauftrag hat einen eigenen Platz und
   damit ein eigenes Kontingent (etwa `ai.parallel` je Unterauftrag), statt dass sich alle Spaces eines teilen.
3. **Nachrangig.** `Jobs#next` startet einen Hintergrundauftrag nur, wenn kein anderer Auftrag auf einen Platz wartet,
   und Hintergrund- und Folgeaufträge zusammen belegen nie alle Plätze: Hintergrund höchstens „Vorbereitung“ minus
   eins, ohne Mindestplatz. Bei nur einem Platz läuft kein Hintergrund; der wartende Teil meldet gelb „Wartet, nur ein
   Platz eingestellt“ (`Jobs.SINGLE_SLOT`). Ein Auftrag des Benutzers startet immer sofort. Ein Klick, der einen Folgeauftrag anstößt (`Jobs#submitInteractive`, Angabe `jobs.interactive`), wartet nie hinter Hintergrund: laufende Hintergrundteile zählen für ihn nicht gegen die Plätze der Folgeaufträge; unterbrochen wird keiner. Hintergrundaufträge haben einen eigenen Vorrat
   (`Jobs.BACKGROUND_CAPACITY`) ohne Grenze je Auslöser und füllen nie die Warteschlange des Benutzers.
4. **Fortsetzen nach Neustart.** Die Arbeit eines Unterauftrags baut seine Art allein aus Quelle, Space und Angaben
   (`BackgroundPart`, mit `resumed()`). `BackgroundJobs#resumeInterrupted` setzt am Ende des Harvester-Starts jeden
   Unterauftrag im selben Eintrag fort (`Jobs#rerun`), den das Beenden des Dienstes (`INTERRUPTED`) oder eine verlorene
   Verbindung unterbrochen hat, sofern seine Art angemeldet ist und `BackgroundKind#autoResume` zustimmt (etwa nur bei
   eingeschalteter Automatik und eingestelltem Modell). Vom Benutzer Angehaltenes oder Abgebrochenes läuft nie von
   selbst weiter, auch nicht, wenn das Anhalten oder der Abbruch kurz vor dem Ende oder einem Absturz kam: der nächste
   Start macht daraus `PAUSED` (`USER`) bzw. `CANCELLED`. Beim Beenden markiert `Harvester#close` laufende Teile als
   unterbrochen (`Jobs#interruptBackground`), bevor die übrigen Bausteine schließen; scheitert ein Teil danach, etwa
   weil der Planer seine Abrufe abwirft, endet er trotzdem als `INTERRUPTED`. Fehlt die Anmeldung (auch ein offener
   Einmalcode) oder antwortet die Quelle nicht, hält sich ein Teil an (`SIGN_IN_REQUIRED` bzw. `CONNECTION_LOST`,
   gelb), statt zu scheitern; die Ursache reicht der Planer über `Ticket#failure` durch. „Fortsetzen“ in der Liste geht über `JobActions` an `BackgroundJobs#resumeJob`.
5. **Anhalten, Fortsetzen, Abbrechen** je Unterauftrag wie jeder Auftrag, je Gruppe über `BackgroundJobs#pause`,
   `#resume`, `#cancel`. Ein Start gilt ganz oder gar nicht: Lehnt `Jobs` einen Teil ab, werden die schon
   angenommenen abgebrochen. Beobachter (`BackgroundJobs#watch`) erfahren jede Änderung einer Gruppe über die
   Auftragsmeldungen (ADR 0027), ohne Abfragen. Wer nur das Ende braucht, abonniert es einmal mit
   `BackgroundJobs#whenFinished` (Angelo 21.11.2026) und zeigt bis dahin den drehenden Ring (`BusyOverlay`); ist die
   Gruppe schon fertig, kommt die Meldung sofort. Gegen doppelte Anläufe: `BackgroundJobs#running`.
6. **Rechte.** Der Hintergrund lädt nie mit der Anmeldung eines Benutzers (wie ADR 0016). `PageLoads` lädt mit dem
   technischen Aufrufer des Start-Aufrufs, nach einem Neustart mit dem technischen Aufrufer des Dienstes; was schon im
   Cache liegt, fällt vorher heraus, ein Fortsetzen holt also nur, was fehlt.
   `statements` legt den Eigentümer des Start-Aufrufers in den Angaben ab (`owner`, `AccessContext#jobOwner`) und
   setzt nach einem Neustart nur fort, wenn der wiederhergestellte Aufrufer derselbe ist (in der Desktop-App der
   App-Benutzer); sonst hält der Teil gelb an (`PauseReason#RESUME_BY_HAND`, „Angehalten – von Hand fortsetzen“),
   und `StatementAnalysisJobs#resume` setzt ihn mit den Rechten des Fortsetzenden fort. Die allgemeine
   Auftragsaktion (`BackgroundJobs#resumeJob`) kennt keinen Aufrufer; mit ihr hält ein fremder Teil erneut an, statt
   mit den Rechten des wiederhergestellten Aufrufers zu laufen. Der Analysator steht beim Start in
   den Angaben (`analyzer`) und wird nie neu aus `statements.analyzer` gelesen; beim Fortsetzen überspringt der Teil
   Seiten, die dieser Analysator schon aktuell hat (mit „erneut analysieren“ nur die seit dem Start, `since`).
   `BackgroundPart#automatic` sagt, ob `resumeInterrupted` fortsetzt oder ein Benutzer (für die Meldung).
7. **Desktop-App.** Die Auftragsverwaltung zeigt Hintergrundaufträge in einem eigenen Reiter „Hintergrund“, je Gruppe
   eine Zeile mit den Unteraufträgen darunter, farblich hervorgehoben (Form mit dem UX-Manager); die Liste der
   Benutzeraufträge bleibt frei davon.
8. **Bilder beschreiben** (`ImageDescriptionJobs`, Art `images`, `Harvester#imageDescriptions()`, D2). Ein Unterauftrag
   je Space; Angaben `root` (Startseite), `subtree`, `trigger` (`automatic` oder `user`). Die Automatik
   (`AutomaticImageDescriptions`) startet über die Art mit dem technischen Aufrufer und bleibt bei fehlender Freigabe
   still. Ein Lauf des Benutzers (Space, Seite, Seite samt Unterseiten über `Holdings#resolve`) trägt dessen
   Eigentümer (`principal`, `principal.kind`, `via`) und läuft nach einem Neustart nur mit dessen Rechten weiter; lässt
   sich daraus kein Aufrufer bauen (Endnutzer ohne Anmeldedaten), hält er an (gelb `IMAGES_RESUME_BY_HAND`), und ▶
   läuft mit dem Benutzer, der es drückt. Nicht freigegebene Seiten zählen bei Läufen des Benutzers gelb
   (`IMAGES_SKIPPED:NOT_RELEASED`). `autoResume` = ein Vision-Modell ist eingestellt. Jeder Unterauftrag beschreibt bis zu
   `ai.parallel` Bilder zugleich in eigener Spur, auch die Bilder einer Seite; die Obergrenze `images.max-per-run` gilt
   je Gruppe über alle Spaces. Der Knopf je Seite im Reiter „Bilder“ ist kein Hintergrundauftrag, sondern ein
   gewöhnlicher Auftrag (`ENRICHMENT`, `task=images`) über denselben Lauf; „Nur diese Seite einmalig“ bleibt ohne
   Auftrag, weil die Einmal-Erlaubnis keinen Neustart überdauern darf.
   Ein Lauf des Benutzers legt Teile nur für Spaces an, in denen er Seiten der Auswahl sieht, und prüft jeden Umfang
   (Space, Seite, Teilbaum) beim Arbeiten erneut mit seinen Rechten. Die Automatik hält sich nur hinter einem wartenden
   oder angehaltenen Teil desselben Space zurück; neben einem laufenden darf einer warten. Die Vormerkung einer Seite
   im Gruppenbudget geht bei Pause, Abbruch, Anmeldung und Fehler zurück. Beim Knopf bekommt jedes Bild seinen
   Versuch: Scheitert das Modell, stehen die Bilder als `FAILED` und die Seite wird gespeichert; ohne Modell oder
   Freigabe endet er gelb. `missing()` nennt dieselben Seiten, die ein Lauf an das Modell gäbe (auch gescheiterte und
   ohne gespeicherte Lese-Ansicht). Mit `jobs.parallel-preparations=1` läuft „Bilder beschreiben“ als
   Hintergrundauftrag nie (`SINGLE_SLOT`, gewollt seit #1030): Hintergrundaufträge brauchen mindestens zwei Plätze.
9. **Nichts läuft unsichtbar** (Angelo 21.11.2026: „volle Kontrolle über das, was passiert, beziehungsweise zumindest
   informiert“). Arbeit, die der Core von sich aus anstößt und die bisher an der Auftragsliste vorbeilief, meldet sich
   als eigene Art an. Gegen doppelte Anläufe gibt es neben `running` `BackgroundJobs#waiting` (ein noch nicht
   gestarteter oder angehaltener Teil), `#startUnlessWaiting` (für Arten, die ihre Arbeit erst beim Start aus dem
   Bestand bestimmen: ein laufender Teil sieht einen neuen Anlass nicht mehr) und `#startUnlessRunning` (für Arten,
   deren laufender Teil sich neue Arbeit selbst abholt). Ein angehaltener Teil zählt in diesen dreien mit (für
   `#startUnlessRunning` über `#open`): Er hält neue Anlässe zurück, beim Fortsetzen rechnet er sie mit (Abnahme
   #1052). `running` zählt ihn nicht, denn der Planer nimmt dessen Treffer als Vorgänger. Bisher umgestellt:
   - `synonyms` „Synonyme erkennen“ (`SynonymDiscovery#automatic`, ein Teil je Quelle; vorher ein Auftrag
     `ENRICHMENT` ohne Auftraggeber, den die App nie zeigte). Fortsetzen nach Neustart nur bei
     `terms.synonyms.automatic`.
   - `documents` „Anhänge analysieren“ (`DocumentEnrichments`, vorher ebenso ein Auftrag ohne Auftraggeber). Die
     Arbeit kommt allein aus Quelle, Space und Bestand: die Anhänge der Seiten des Space ohne aktuelle Analyse. Eine
     Meldung (`IndexedDocuments#indexed`) und die Anreicherung nach einem Abgleich (`DocumentEnrichments#afterSync`)
     vermerken nur, dass es im Space Arbeit gibt; gerechnet wird nur im Teil, Anhalten hält also wirklich an. Die
     Anreicherung vermerkt nur, wenn für einen Anhang der abgeglichenen Seiten laut Manifest etwas zu tun ist, ohne
     einen Abschnitt zu lesen; nach einem Abgleich ohne Arbeit erscheint keine Gruppe. Je Anlass eine Gruppe, je Space
     höchstens ein offener Teil (`BackgroundKind#exclusive`, unten); eine Meldung während des Laufs holt derselbe Teil
     in einem weiteren Durchgang ab. Gestartet wird erst, und eine weitere Runde beginnt erst, wenn für den Space kein
     Abgleich, kein Neulesen (es gilt für die ganze Quelle), kein Anhangsauftrag und keine Anreicherung nach dem
     Abgleich dieses Space (Angabe `task` = `after-sync`) mehr wartet oder läuft; sonst endet der Teil nach seiner
     Runde und vermerkt den Rest. Andere Anreicherungen (Bildbeschreibung, Abkürzungen, Synonyme) halten nicht auf,
     ebenso kein Auftrag, der auf seinen Vorgänger wartet (`AWAITING`, etwa die Anhänge eines Exports bis zu dessen
     Text). Das Ende solcher Aufträge kommt als Auftragsmeldung (ADR 0027, kein Abfragen). Außerhalb davon bündelt
     ein `Coalescer` die Meldungen zehn Sekunden lang. So geben die Anhänge eines Abgleichs eine einzige Gruppe. Nur
     ein fertiger Teil plant für Vermerke aus seiner Laufzeit einen neuen; ein abgebrochener (auch ein laufend
     gelöschter) verwirft sie einmal, erst ein neuer Anlass startet wieder. Wiederholte Meldungen desselben beendeten
     Teils verwerfen nichts mehr, ebenso wenig das Ende der noch rechnenden Arbeit eines gelöschten Teils: Was nach
     dem Löschen gemeldet wurde, bekommt einen neuen Teil. Rechnet beim Start eines Teils noch die abgekoppelte Arbeit eines anderen desselben
     Space, endet er sofort und vermerkt; deren Ende plant. Endet die Arbeit eines nach `STOP_GRACE` abgekoppelten Teils, plant sie selbst,
     falls seither etwas vermerkt ist und kein Teil des Space mehr offen ist.

   Höchstens ein offener Teil je Quelle und Space (`BackgroundKind#exclusive`, Abnahme #1052): `resumeJob` lehnt
   unter dem Startriegel der Art ab, solange ein anderer Teil wartet, angehalten ist oder läuft
   (`BackgroundPartOpenException`, in der App gelb „Läuft schon“), und `resumeInterrupted` setzt je Art, Quelle und
   Space nur den jüngsten unterbrochenen fort; dessen Arbeit aus dem Bestand deckt die der älteren. Die älteren
   schließt es als abgebrochen ab (`Jobs#retire`, Info „Durch jüngeren Teil ersetzt“), damit sie nach einem späteren
   Neustart nicht von selbst loslaufen, auch wenn der Benutzer den jüngeren inzwischen abgebrochen hat; ist schon ein
   anderer Teil offen, schließt es alle unterbrochenen so ab. Diese Prüfungen zählen einen angehaltenen Teil als offen
   (`BackgroundJobs#open`); `BackgroundJobs#running` zählt ihn nicht, denn der Planer nimmt einen solchen Teil als
   Vorgänger, und ein angehaltener Teil einer fremden Gruppe ließe sein Ziel warten. Nur für Arten,
   die neue Teile mit `startUnlessRunning` starten: Bei `startUnlessWaiting` (etwa `synonyms`) dürfen ein laufender
   und ein wartender Teil nebeneinander stehen, und nach „Alle anhalten“ sperrten sie sich beim Fortsetzen
   gegenseitig.

   Grenzen: Vermerke, aus denen noch kein Teil wurde (die zehn Sekunden Bündelung, ein noch laufender Abgleich),
   liegen nur im Speicher. Beim Beenden des Dienstes gehen sie verloren; der nächste Abgleich des Space holt sie über
   die Anreicherung nach. Ebenso ein Vermerk, der nach einem Abbruch eintrifft, solange die Arbeit des abgebrochenen
   Teils noch bis zu ihrem nächsten Prüfpunkt rechnet, höchstens `Jobs#STOP_GRACE` lang: Ihr Ende verwirft ihn mit. Nach
   einem Neustart setzt nur ein schon angelegter, unterbrochener Teil von selbst fort (nur bei
   `enrichment.documents.enabled`). Die Ablage der Anhänge kennt keinen Space: Ein Teil liest die Anhangseinträge der
   ganzen Quelle und behält die der Seiten seines Space.

   Abgleiche beim Start (vorher je ein eigener Thread ohne Eintrag in der Auftragsliste). Sie beginnen erst nach
   `resumeInterrupted`; eine Quelle mit schon offenem Teil derselben Art fällt heraus
   (`BackgroundJobs#startUnlessRunning` für mehrere Teile, eine Gruppe je Anlass):
   - `release` „KI-Freigabe prüfen“ (`ReleaseRecomputation#reconcileOnStart`, ein Teil je Quelle, nur mit Modell),
     „KI-Freigabe nachziehen“ (nach einem Wechsel der Freigabe einer Quelle, Angabe `mode` = `grant`, `withdraw`
     oder `images`) und `page-release` „Seitenfreigabe nachziehen“ (nach einer geänderten Liste freigegebener Seiten;
     die Seiten-IDs stehen als Angaben `pages` und, nur wenn es welche gibt, `removed` am Teil). Die Freigabe fragt
     jeder Teil wie bisher je Seite bei der Ausführung. Ein fortgesetzter Teil nach neuer Freigabe rechnet nur noch,
     was nicht zur Freigabe passt (wie der Abgleich beim Start); nach einem Entzug nimmt er die Rücknahme erneut
     vor. Nicht exklusiv: Prüfen und Nachziehen derselben Quelle sind verschiedene Arbeit; die Prüfung beim Start
     fällt daher nur weg, wenn schon ein Prüfteil der Quelle offen ist, ein angehaltener Teil „erteilen“ oder
     „entziehen“ hält sie nicht auf (`startUnlessRunning` mit Bedingung, Abnahme #1073).
     **Ein Entzug geht nie still verloren** (Abnahme #1073): Der Start setzt jeden abgebrochenen oder gescheiterten
     Teil, der zurücknimmt (`withdraw`, `images`, `page-release` mit `removed`), fort, auch nach
     Zeitüberschreitung und auch ohne Modell, denn Bildbeschreibungen nimmt nur ein Entzug zurück, nie die Prüfung.
     Das Ereignis des Abbruchs sagt das im Auftrag („der nächste Start führt den Entzug zu Ende“, Angabe
     `Jobs#CANCEL_NOTE`, ob der Teil wartete oder lief). Verwerfen heißt: den Eintrag löschen. Nimmt der Hintergrund
     die Gruppe eines Wechsels nicht an (voll, beim Beenden), läuft die Arbeit wie im Dienst im eigenen Thread.
   - `versions` „Analysen nach Update nachrechnen“ (`VersionRecomputation`, lokale Prozessoren einer älteren
     Version, ein Teil je Quelle, exklusiv).
   - `links` „Verweise abgleichen“ (`PageLinks#reconcileOnStart`, ein Teil je Quelle, exklusiv). Die Teile laufen
     nacheinander (`BackgroundJobs#startInSequenceUnlessRunning`, `Jobs#AFTER`, der erste hinter jedem schon offenen
     Teil der Art; Abnahme #1073), denn ein Teil hält den Riegel der Erfassung über seine ganze Quelle und ein
     zweiter belegte sonst nur einen Platz, um zu warten. Ein angehaltener Teil hält die folgenden auf („wartet auf
     …“); bricht einer ab, laufen die folgenden ohne gelben Hinweis, sie warten nur der Reihe wegen. Der Abgleich nach
     einer Änderung eines ganzen Space oder einer ganzen Quelle und einzelne Seiten bleiben beim Bündler, sonst gäbe
     jeder Abgleich eine eigene Gruppe. Bis der erste Abgleich einer Quelle seit dem Start endet, gilt sie als nicht
     vollständig; wird der Teil abgebrochen, holt das der nächste Abgleich eines Space nach.

   Jeder dieser Teile baut seine Arbeit allein aus Quelle, Angaben und Cache und meldet die bekannten Seiten der
   Quelle als Fortschritt. Ein Teil, den das Beenden des Dienstes unterbricht, gilt nicht als fertig und setzt nach
   dem Neustart von vorn fort; Aktuelles kostet dann nur den Blick in die Ablage. Grenzen: Mit nur einem Platz für
   Vorbereitungen laufen sie nicht und warten gelb (wie jeder Hintergrundauftrag); länger als die Laufzeitgrenze
   eines Auftrags (`JobLimits#maxRunTime`) endet ein Teil mit Zeitüberschreitung und lässt sich fortsetzen.
   Prozessoren, die über das Netz rechnen, reiht ein Teil wie bisher in die Warteschlange der Anreicherung ein
   (`Enrichments#recomputeInBackground`); fertig heißt hier: alle Seiten geprüft und eingereiht. Diese Warteschlange
   wird als eigene Art „KI-Anreicherung“ sichtbar (nächster Schritt). Beim Beenden wartet jede Komponente
   (`close()`) bis zu zehn Sekunden auf ihre laufenden Teile, bevor die Ablage schließt. Ein fortgesetzter Teil nach
   einem Wechsel der Freigabe gleicht gegen die Freigabe von jetzt ab, denn er ersetzt den Abgleich beim Start. Das
   Aufräumen des Änderungsprotokolls und das Nachtragen der Update-Marken beim Start bleiben ohne Auftrag: kurze
   Arbeit in der eigenen Ablage, ohne Netz und ohne Modell.

   Ohne Hintergrundaufträge (Dienst) bleibt es beim bisherigen Weg.

10. **KI-Anreicherung** (Angelo 21.11.2026, Fundstelle 5). Die Warteschlange der Prozessoren, die über das Netz
    rechnen (Embeddings, Absätze und Navigation mit Modell, Satzanalyse `statements`), lief bisher auf einem Thread
    mit 1.000 Plätzen und verwarf still, was nicht mehr hineinpasste. Mit Hintergrundaufträgen ist sie die Art
    `enrichment` „KI-Anreicherung“ (`Enrichments#background`):
    - Je Quelle ein Teil, der neue Berechnungen aufnimmt, solange er noch nicht angefangen hat (jede abgelegte
      Seite, Öffnen, Freigabe- und Update-Neuberechnung). Ab seinem Start rechnet er nur, was dann wartete; Späteres
      bekommt einen neuen Teil hinten in der Kette, so hält eine Quelle mit stetem Zufluss keine andere auf (Reihenfolge
      wie beim alten FIFO). Gewartet wird nur mit Prozessor und Seite, ohne Grenze und ohne Verwerfen; den Snapshot
      liest der Teil erst beim Rechnen, die KI-Freigabe prüft wie bisher jede Berechnung bei der Ausführung.
    - Die Teile laufen hintereinander (`BackgroundJobs#startBehindOpen`, `Jobs#AFTER`, nur der Reihe wegen), wie
      vorher der eine Thread; die Logik der Berechnung bleibt gleich.
    - Anhalten: Ein Teil, der noch nicht lief, sammelt weiter; ein angehaltener rechnet beim Fortsetzen weiter. Er hält
      nur Teile seiner eigenen Quelle auf, denn Anhalten von A darf B nicht stoppen (gilt für jeden Teil, der nur der
      Reihe wegen wartet). Abbrechen verwirft die wartenden Berechnungen des Teils, das Ereignis des Abbruchs sagt es
      (`Jobs#CANCEL_NOTE`): Fehlende oder veraltete Ergebnisse holt der nächste Abgleich oder Abruf nach, erzwungene
      Neuberechnungen nach einer Freigabe oder einem Update erst der Abgleich beim nächsten Start. Scheitert eine
      Berechnung (auch beim Lesen des Snapshots), meldet der Teil das gelb mit Quelle und Exception-Klasse und macht
      mit der nächsten weiter. Scheitert ein Teil (etwa Laufzeitgrenze), übernimmt ein neuer Teil, was liegen blieb.
    - Erzwungene Neuberechnungen (`recomputeInBackground`) warten nicht mehr auf einen der 50 Plätze: Ein Teil wie
      „KI-Freigabe nachziehen“, der dort wartete, hielte einen Platz des Hintergrunds, den die KI-Anreicherung zum
      Freigeben braucht.
    - Bisheriger Weg: Kann der Hintergrund nicht laufen (nur ein Platz unter „Vorbereitung“, `Jobs#SINGLE_SLOT`) oder
      nimmt er einen Teil nicht an (voll, beim Beenden, jeder andere Fehler), rechnet der bisherige eigene Thread.
    - Grenze: Die Warteschlange lebt im Speicher. Nach einem Neustart ist sie leer; Teile von vorher, die sich noch
      fortsetzen ließen, werden beim Start abgeschlossen (Info „Warteschlange nach Neustart leer“), damit kein
      angehaltener alle neuen aufhält. Fehlende oder veraltete Ergebnisse holt der nächste Abgleich oder Abruf nach,
      erzwungene der Abgleich beim Start.
    - In der Warteschlange ersetzt eine erzwungene Berechnung die gleiche ungezwungene an ihrer Stelle; eine
      ungezwungene neben der gleichen erzwungenen entfällt.
    - Semantische Suche (`SemanticPreparation`, Fundstelle 11): Die Suche ist ein Klick. Was nach der Frist von 10 s
      noch nicht angefangen hat, rechnet ein gewöhnlicher Auftrag `ENRICHMENT` des Aufrufers („Suchtreffer
      vorbereiten“, Angabe `task` `prepare-search-hits`, `Jobs#submitInteractive`) weiter: wie bisher bis zu vier
      parallel, sichtbar, abbrechbar, ungezwungen (nur, was dann noch nicht aktuell ist). Nur die höchstens vier schon
      laufenden Embeddings enden im Aufruf.
    - „Neu berechnen“ einer Seite (Fundstelle 21): ein gewöhnlicher Auftrag `ENRICHMENT` (Angabe `task`
      `recompute-page`, `root` = Seite) über `Jobs#submitInteractive` (`PageRecomputation`), sichtbar und abbrechbar
      vor jedem Prozessor; der Knopf wartet auf das Ende und sagt „Wartet auf freien Platz …“, solange der Auftrag
      hinter laufenden Folgeaufträgen wartet. Abgebrochen oder angehalten meldet er als Info, gescheitert rot mit Grund.

11. **Seiten nachladen** (Angelo 21.11.2026, Fundstellen 10 und 15, Entwurf vom Architekten bestätigt, Abnahme #1080).
    - Veraltete Treffer der semantischen Suche (`PageRenewal`) sammeln sich je Aufrufer kurz und gehen als eine
      Gruppe „Veraltete Treffer nachladen“ an `PageLoads#join(…, refresh, SEARCH)`: Mit der Angabe `refresh` lädt der
      Teil auch Seiten, die im Cache liegen, und zwar streng (nie aus dem Cache-Zeitfenster), auch im Vordergrund. Er
      schließt sich nur wartenden oder laufenden Teilen an, die ebenfalls nachladen, nie angehaltenen.
    - Der Anlass im Änderungsprotokoll bleibt `SEARCH` (ADR 0030), unabhängig von Aufrufer und Füllung: Er steht als
      Angabe `change-trigger` am Teil und geht über `AcquisitionScheduler.Mode` ausdrücklich bis zum Abruf.
    - Auch Benutzer mit Anmeldung laden sichtbar: Ihr Aufrufer liegt nur im Speicher (Angaben `caller`, `owner`), bis
      kein Teil der Gruppe mehr wartet, läuft oder angehalten ist; die Abrufe gehen über den Vordergrund des Planers.
      Nach einem Neustart endet so ein Teil gelb (`NOT_RESUMED`, unvollständig) statt mit technischen Rechten
      weiterzuladen; die nächste Suche holt nach.
    - Nachladen im Vordergrund (Benutzer mit Anmeldung, abgeschaltete Füllung) ist nachrangig: Seiten einer Suche
      gehen vor, und es belegt höchstens einen Platz des Vordergrunds zugleich.
    - Vorladen der Lese-Ansichten beim Aufklappen (`ReadingViews#prefetch`) kommt von einem Klick und ist darum ein
      gewöhnlicher Auftrag `JobType#READING_PREFETCH` „Lese-Ansichten vorladen“ über `Jobs#submitInteractive`, nie
      hinter Hintergrundteilen: höchstens ein wartender je Quelle und Auftraggeber, ein neuer Aufruf ersetzt ihn und
      nimmt seine Seiten hinter den eigenen mit, höchstens 25; was der Grenze wegen entfällt, nennt der ersetzte
      Auftrag mit Zahl. Innen mit allen übrigen Vorladungen höchstens zwei zugleich, nie mit Anmeldedialog. Ist die
      Quelle nicht erreichbar, verlangt sie eine Anmeldung oder drosselt sie, startet der Auftrag keine weitere Seite
      und endet gescheitert mit Grund (`SOURCE_UNAVAILABLE`), ohne automatische Wiederholung. Was ein Neustart
      unterbrach, schließt der Start ab; das Aufklappen lädt selbst.
    - Bei nur einem Platz unter „Vorbereitung“ (`BackgroundJobs#canRun()` falsch) nehmen Nachladen und Vorladen ihren
      bisherigen Weg.

12. **Funde katalogisieren** (Angelo 21.11.2026, Fundstellen 14 und 23, Entwurf vom Architekten bestätigt).
    - `FindingCataloging` ist die Art `catalog` „Funde katalogisieren“: je Angebot und Quelle ein Teil, die Funde als
      Seiten-ID und Version in den Angaben (`pages`), der technische Aufrufer als Kennung (`finder`) und der Anlass im
      Änderungsprotokoll (`change-trigger`). So setzt ein Teil auch nach einem Neustart mit denselben Rechten fort.
    - Höchstens ein wartender Teil je Quelle, Aufrufer und Anlass: Ein neues Angebot ersetzt ihn und nimmt seine Funde
      hinter den eigenen mit. Erst entsteht der Ersatz, dann wird der alte abgebrochen; scheitert das Anlegen, bleiben
      der alte Teil und die angenommenen Funde stehen und gehen mit dem nächsten Angebot mit (Abnahme #1082).
    - Angenommenes fällt nie weg: Ein Teil fasst höchstens 1000 Funde; ist er voll, nimmt er vom neuen Angebot nur, was
      passt. `FindingCatalog#accept` nennt die angenommenen Seiten; dazu zählen auch eben geprüfte (`RECHECK`, zehn
      Minuten) und solche, die ein offener Teil noch in derselben Fassung katalogisiert. Erledigte Seiten eines Teils
      zählen nicht mehr als offen; eine neue Fassung ist ein neuer Fund. Funde von Endnutzern und delegierten Aufrufern
      zählen wie bisher nicht.
    - `accept` kehrt sofort zurück: Die Teile legt ein eigener Arbeiter (`Coalescer`, gebündelt je Schlüssel) an. Die
      offenen Teile führt `FindingCataloging` im Speicher, gepflegt über Anlegen, Arbeit und die Auftragsmeldungen der
      Art (`BackgroundJobs#subscribe(kind, …)`); den ganzen Auftragsspeicher liest es nur einmal beim Start.
    - Die Trefferliste sortiert denselben Stapel bei jedem Ladefortschritt neu (`SearchView#refresh`). Sie nennt dabei
      ihre Suche (`KeywordSearch#search(…, searchId)`), und die Suche bietet nur Funde an, die in dieser Suche noch nicht
      angenommen sind: Neusortieren allein erzeugt keinen Teil, holt aber Abgewiesenes nach.
    - Bei nur einem Platz unter „Vorbereitung“ bleibt es beim bisherigen Weg im Speicher.

13. **Suchtreffer laden** (Angelo 21.11.2026, Fundstelle 13, Entwurf vom Architekten bestätigt, Abnahme #1085).
    - Kehrt die Suche eines technischen Aufrufers zurück, bevor die Treffer eines Stapels geladen sind
      (`technicalWait`, in der App null), wird, was noch lädt oder wartet, die Gruppe „Suchtreffer laden“ der eigenen
      Art `search-hits` (`SearchHitLoads`), je Quelle und Space ein Teil. Das gilt für jede Schlagwortsuche, auch aus
      Chat und progressiver Suche.
    - Die Suche übergibt nur und kehrt sofort zurück: Die Gruppe legt ein eigener Arbeiter an (`Coalescer`, gebündelt je
      Stapel). Welche Seiten offene Teile schon laden, führt `SearchHitLoads` im Speicher, gepflegt über Anlegen und die
      Auftragsmeldungen der Art (`BackgroundJobs#subscribe(kind, …)`); solche Seiten fallen aus neuen Teilen heraus.
    - Die Abrufe bleiben dieselben: Vorrang des Vordergrunds, Platz im Planer, kein zweiter Abruf. Der Teil wartet über
      Rückrufe auf sie (kein blockierter Arbeiter) und zählt sie; `progress()` der Suche zählt dieselben Abrufe, beide
      Anzeigen stimmen also überein.
    - Scheitert eine Seite, zählt sie rot mit Grund (Ereignis im Auftrag), die übrigen laden weiter; der Teil endet dann
      als gescheitert („… ließen sich nicht laden; die nächste Suche versucht es erneut“) und lässt sich einzeln
      wiederholen.
    - Die übernommenen Abrufe bricht nur der Benutzer ab: Abbrechen oder Anhalten in der Auftragsliste zieht sie
      zurück, Abbrechen in der Trefferliste bricht auch die Gruppe ab. Scheitert das Anlegen der Gruppe, laufen die
      Abrufe der Suche unverändert weiter.
    - Erst nach einem Neustart oder Fortsetzen fordert der Teil selbst an, was noch fehlt: höchstens 32 zugleich, wie
      die Suche als Vordergrund ohne `refresh` (zurückgestellt, Anlass `SEARCH`); ist der Planer voll, weckt ihn
      dessen Meldung.
    - Delegierte Benutzer laden wie bisher nur während ihres Aufrufs, ohne Auftrag. Bei nur einem Platz unter
      „Vorbereitung“ bleibt es beim bisherigen Weg.
    - **Eigene Spur** (Abnahme #1085, gilt auch für die Hintergrundfüllung): Teile, die nur auf Arbeit warten, die der
      Planer ohnehin begrenzt, belegen keinen Hintergrundplatz. `BackgroundKind#ownLane` ist nur für die genannten Arten
      erlaubt (`OWN_LANE_KINDS`); solche Teile starten sofort, zählen weder unter „läuft“ noch im Hintergrundanteil und
      haben keine Frist. Sie laufen nur mit mehr als einem Platz unter „Vorbereitung“ und ruhen mit „Hintergrund
      anhalten“ wie alle Hintergrundteile.

14. **Hintergrundfüllung** (Angelo 21.11.2026, Fundstelle 12, Variante A vom Architekten bestätigt).
    - Die Füllung selbst bleibt, wie sie ist (`BackgroundFill`, über den Planer). Sobald sie in einem Space Lücken
      übergibt, entsteht dort ein Teil der Gruppe „Hintergrundfüllung“ (Art `fill`, `FillJobs`), je Quelle und Space
      einer, solange dort Seiten warten oder laden. Der Teil rechnet nichts: Er zählt die Abrufe der Füllung (geladen,
      gescheitert, „Wartend“) und wartet über ihre Rückrufe, auf eigener Spur (Punkt 13).
    - Anhalten: Der Space ruht, bis der Teil fortgesetzt wird. Abbrechen: Der Space ruht bis zum nächsten
      abgeschlossenen Abgleich des Space oder der ganzen Quelle. Beides wird aus den Aufträgen abgeleitet, nicht im
      Speicher gehalten, und übersteht so den Neustart. Ruhende Spaces übergeht die Füllung beim Suchen, Anbieten und
      Nachschieben. Das gilt auch für einen noch wartenden Teil.
    - Ruhen räumt ab (`BackgroundFill#rest`): Was beim Planer noch wartet, wird sofort zurückgezogen, nur im Speicher.
      Die Einträge des Space verlassen die Warteschlange in einem Commit auf dem Arbeiter der Füllung, nie unter der
      Sperre der Aufträge. Laufende Abrufe enden regulär und werden gezählt, rot mit Grund, wenn sie scheitern.
    - Fortsetzen setzt denselben Teil fort, keine neue Gruppe, auch nach Abbrechen: Die Füllung sucht sofort im
      Katalog nach Lücken seines Space (`BackgroundFill#scanFor`), und der Teil endet erst, wenn sie dort nichts mehr
      findet; ohne Space meldet sie gleich „leer“. Was nach dem Abbrechen noch endet, zählt für keinen späteren Teil.
    - Die Sperre der Füllung gilt nur über ihre Karten im Speicher, nie über Zugriffe auf Warteschlange oder Cache.
    - Einreihen (erst spiegeln, dann hinzufügen) und Austragen (erst entfernen, dann Spiegel löschen) laufen unter einer
      eigenen Reihenfolge-Sperre nie verschränkt; ein Eintrag ohne Spiegel trägt die Füllung aus, statt ihn zu
      überspringen, damit er keinen Platz im Fenster dauerhaft belegt.
    - Wird ein Teil gelöscht, von Hand oder nach der Aufbewahrungsfrist, folgt sein Space den übrigen Teilen. „Daten
      bereinigen“ leert die Warteschlange über die Füllung (`BackgroundFill#removeSource`), damit auch ihr Spiegel
      bereinigt wird. „Wartend“ zeigt bei beendeten und angehaltenen Teilen 0.
    - Gescheiterte Abrufe stehen rot mit Grund im Teil; endet er mit Fehlern, scheitert er (`PAGES_MISSING`), und die
      Füllung versucht die Seiten nach ihrer Wartezeit erneut. Abrufe, die enden, bevor ihr Teil läuft, werden beim
      Start nachgemeldet.
    - Bei nur einem Platz unter „Vorbereitung“ bleibt die Füllung unsichtbar wie bisher.

15. **Automatische Abgleiche in der Auftragsliste** (Angelo 21.11.2026, Fundstelle 4). Die Auftragsliste der App
    liest alle Aufträge ihrer Eigentümer aus der Auftragsablage, jeder Art, nicht nur die gemerkten. So stehen auch
    Abgleiche und Neueinlesen dort, die der Core selbst anstößt: Nachholen beim Start, Abgleich vor der Suche,
    Inventur, Wiederaufnahme und Neueinlesen nach einem Versionswechsel. Löschen entfernt sie aus der Ablage.

16. **„Anhänge ablegen“ als Auftrag** (Angelo 21.11.2026, Fundstelle 24). Der Knopf in der Seitenansicht startet
    einen Auftrag „Anhänge beschaffen“ (`ATTACHMENTS`, `task=page-harvest`, `PageAssetHarvests`) als Klick des
    Benutzers (`submitInteractive`). Abgelegte Anhänge zählen als geschrieben, schon vorhandene als bewusst
    ausgelassen, gescheiterte rot mit Grund. Abbrechen geht nach jedem Anhang; das Abgelegte bleibt. Die Statuszeile
    des Reiters meldet „gestartet“ mit Link „Aufträge“, das Ergebnis ersetzt die Meldung (`Workspace#whenJobFinished`).
    Fortsetzen holt nur, was fehlt; die Anhangsliste samt Rechten liefert die App, nach einem Neustart setzt deshalb
    der Benutzer fort. „Neu erzwingen“ und „Nur Fehler“ der Anhangbeschaffung eines Exports gelten hier nicht.

17. **Quelle entfernen als Auftrag** (Angelo 21.11.2026, Fundstelle 22, Entwurf vom Architekten bestätigt). Entfernen
    und Umstellen auf ein neues Ziel löschen die Daten der Quelle nicht mehr im aufrufenden Thread, sondern als
    Auftrag „Daten bereinigen“ (`PURGE`, `purge.reason` = `removed` oder `moved`, `SourceDataPurge#startWithdrawal`)
    mit denselben Ablagen wie „Daten bereinigen“, KI-Ergebnisse und Caches eingeschlossen.
    - Der Auftrag trägt `Jobs.SURVIVES_SHUTDOWN`: Endet der Dienst, etwa weil die App nach dem Entfernen neu lädt, gilt
      er wie ein Hintergrundauftrag als unterbrochen, nicht als abgebrochen. Der nächste Start setzt ihn mit einem
      neuen Auftrag derselben Angaben fort (`SourceManagement#resumeWithdrawals`), außer die entfernte Quelle ist
      inzwischen wieder eingerichtet.
    - Bis er endet, lässt sich dieselbe Kennung nicht neu anlegen (`SourceRemovalPendingException`); das Formular zeigt
      unter der Kennung einen gelben Hinweis, der den Auftrag öffnet.
    - Beim Umstellen auf ein neues Ziel wartet `SourceManagement#change` wie bisher auf den Auftrag (höchstens fünf
      Minuten): Ein gleich danach gestarteter Abgleich der Quelle darf nicht vom Löschen der alten Daten beendet werden.

## Nachtrag 21.11.2026: Dienst-Planer (D1)

Analysedienste bauen aufeinander auf (etwa Wissensgraph nach Aussagenanalyse und Bildbeschreibung). Wer ein Ziel
ausdrücklich startet, soll nicht von Hand die Vorstufen anstoßen müssen; die Vorstufen verhalten sich wie
Windows-Dienste mit Starttyp (Angelo 21.11.2026, freigegeben vom Architekten).

1. **Beschreibung.** `AnalysisService` (`application.background.services`): Kennung, Titel, Starttyp
   (`ServiceStart` AUTOMATIC/MANUAL/DISABLED, zur Laufzeit gelesen), Vorstufen als Kennungen, `BackgroundKind` der
   Arbeit (oder noch keine) und `missing(caller, PageSet)` → `MissingPages` je Quelle und Space mit den Rechten des
   Aufrufers; Seiten ohne Zugriff nur als Zahl, nie mit Titeln. „Lokal“ heißt bestätigter Text in aktueller Fassung
   (Holdings `LOCAL`), `OUTDATED` zählt als fehlend.
2. **Planer.** `ServicePlanner#register` weist einen Zyklus mit `IllegalStateException` ab und lässt alles, wie es war.
   `ServicePlanner#start(caller, ziel, PageSet)` löst die Vorstufen topologisch auf und legt EINE Hintergrundgruppe an
   (`BackgroundJobs#startSteps`, ganz oder gar nicht, Gruppenart = Art des Ziels), je Stufe und Space ein Unterauftrag.
   Ein ausdrücklich gestartetes Ziel startet auch MANUAL-Vorstufen; DISABLED startet nie und wird gelb als „bewusst
   ausgelassen“ gemeldet. AUTOMATIC heißt zusätzlich: startet auf seinen bisherigen Auslösern. Die Vorgaben der
   Starttypen ändern am heutigen Verhalten nichts. Eine Vorstufe ohne fehlende Seiten legt nichts an; das Ziel legt
   seinen Teil je Space immer an, sobald die Seitenmenge für den Aufrufer nicht leer ist. Läuft für eine Vorstufe schon ein Teil
   gleicher Art für Quelle und Space (`BackgroundJobs#running`), wird er Vorgänger, auch aus einer fremden Gruppe
   (gezeigt werden nur Art und Space); deckt er nicht alle fehlenden Seiten ab, entsteht für den Rest ein eigener Teil.
   Das Ziel verwendet nie wieder, es legt seine Teile immer an.
3. **Warten je Space.** Ein Teil trägt die IDs seiner Vorgänger (`Jobs.AFTER`, Liste) und startet erst, wenn keiner ihn
   mehr hält (`Jobs.PredecessorGate`): offen, angehalten oder unterbrochen und fortsetzbar hält; fertig, gescheitert,
   abgebrochen, gelöscht oder unterbrochen ohne angemeldete, fortsetzbare Art gibt frei. Ein gesperrter Teil zählt für
   die Vorfahrt nicht als wartend und hält keinen anderen Auftrag auf; er meldet einmal „wartet auf „Vorstufe““
   (`Jobs.WAITING_FOR`). Teile anderer Spaces warten nie mit.
4. **Fehler und Pause.** Eine gescheiterte, abgebrochene oder gelöschte Vorstufe hält die Kette nicht auf: die
   Folgestufe läuft über das Vorhandene und meldet gelb mit Grund (`BackgroundJobs.PREDECESSOR_MISSING`). Eine
   angehaltene Vorstufe hält die Kette an. Abbruch der Gruppe bricht auch gesperrte Teile ab, Pause pausiert sie, und
   Fortsetzen setzt die Sperre wieder ein; lässt sich ein Vorgänger nicht fortsetzen, bleiben seine Nachfolger stehen.
   Jeder Übergang, der einen Nachfolger frei gibt (Abbruch eines wartenden, Löschen, Besen), weckt die Planung sofort.
   Die Aufbewahrungsfrist löscht keinen angehaltenen Vorgänger, auf den noch ein Nachfolger wartet. Der Stand
   beendeter Vorgänger liegt im Speicher, geprüft wird nur, solange der Hintergrund starten darf; ein Start gilt ganz
   oder gar nicht, zurückgenommen wird vom letzten Teil her.
5. **Neustart.** Die Vorgänger stehen in den Angaben; `resumeInterrupted` setzt die ältesten Teile zuerst fort, darum
   läuft eine unterbrochene Kette in derselben Reihenfolge weiter.
6. **Erste Dienste** (`Harvester#servicePlanner`): Indexieren (AUTOMATIC), Bilder beschreiben
   (wie `images.automatic`), Aussagenanalyse (MANUAL), Glossar (AUTOMATIC), Wissensgraph (MANUAL, nach Aussagenanalyse
   und Bildbeschreibung). Die Aussagenanalyse arbeitet über ihre Art `statements` (D3): `missing` aus
   `StatementAnalysisJobs#missing`, je Quelle und Space nach dem gespeicherten Kopf gruppiert. „Bilder beschreiben“
   arbeitet über seine Art `images` (D2): `missing` sind die freigegebenen Seiten, die ein Lauf an das Modell gäbe
   (`ImageDescriptionJobs#missing`), je Space und nur sichtbare. Die übrigen Arten bleiben Beschreibung.
7. **Angaben der Teile.** Jeder Teil trägt seine Seiten (`AnalysisService.PAGES`). Ein Dienst kann weitere Angaben
   beisteuern (`AnalysisService#parameters`), einmal je Start und für alle Spaces gleich; `pages` setzt immer der
   Planer. Die Aussagenanalyse legt so Eigentümer, Startzeit und den beim Start eingestellten Analysator fest
   (`StatementAnalysisJobs#plannerParameters`), dazu eine Kennung des Start-Aufrufers in diesem Prozess (`caller`).
   Ein Teil mit `pages` analysiert genau diese Seiten seines Space ohne Navigation, jede nur mit Leserecht; der erste
   Lauf rechnet mit den Rechten des Start-Aufrufers, nach einem Neustart gilt die Eigentümer-Regel aus Punkt 6 oben
   (gleicher Eigentümer läuft weiter, sonst gelb „von Hand fortsetzen“). Ohne „erneut analysieren“ und ohne Grenze;
   aktuelle Seiten überspringt der Teil.
   „Bilder beschreiben“ legt ebenso den Eigentümer (`principal`, `principal.kind`, `via`) und die Kennung `caller`
   fest (`ImageDescriptionJobs#plannerParameters`). Ein Teil mit `pages` beschreibt genau diese Seiten mit den
   Rechten des Start-Aufrufers, nach einem Neustart mit dem aus den Angaben gebauten Eigentümer; lässt der sich nicht
   bauen (Endnutzer ohne Anmeldedaten), hält er gelb mit `IMAGES_RESUME_BY_HAND` an, nie technisch. Je Seite gelten
   Inhaltsrecht und KI-Freigabe zum Zeitpunkt der Arbeit.
