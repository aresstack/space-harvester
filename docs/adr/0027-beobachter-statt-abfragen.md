# ADR 0027: Beobachter statt Abfragen

- Status: **Vorgeschlagen**. Vorab entschieden (Angelo, 13.11.2026): „Also Polling ist wirklich ein ziemlicher Müll.
  [...] Also ich würde jedwedes Polling ablehnen.“ Kein Zeitgeber, keine wiederholte Abfrage, auch nicht als
  Übergang. Die Baum-Symbole nach dem Vorbild OneDrive (Wolke, Haken, Kreispfeile, veraltet, Schloss) entstehen erst
  mit diesem Dienst.
- Bezüge: `/mnt/project-files/export-ansicht/entwurf-export-ansicht.md` (Nachträge 2 und 3),
  `architektur-bestand-und-ausgaben.md` (Abschnitte 4 und 7.4). Berührt [ADR 0016](0016-auswahl-beschaffung-hintergrund.md)
  (Hintergrundfüllung), [ADR 0017](0017-progressive-suche.md) (progressive Suche) und
  [ADR 0025](0025-fortsetzbares-einlesen.md) (Aufträge). Keine neue Bibliothek.

## Kontext

Die Desktop-App erfährt Änderungen im Core heute, indem sie in festen Abständen nachfragt. Sechs Stellen:

| Stelle der App | fragt alle | was | Meldung im Core heute |
|---|---|---|---|
| Aufträge und Statuszeile (`ActivitiesView`) | 1,5 s | `jobs()`, Auftrag, Ereignisse, Abgleichstand | keine |
| Suche (`SearchView`) | 0,4 s | `keywordSearchProgress(id)`: geladene Treffer der Suche | keine |
| Glossar (`GlossaryTab`) | 1 s | Stand der Einordnungsaufträge | keine (Änderungen des Glossars: `Glossary.subscribe`) |
| Synonymsuche (`TermsView`) | 1 s | Stand des Synonym-Auftrags | keine |
| Hintergrundladen (`BackgroundPlate`) | 2 s | Stand des Beschaffungsplaners | keine |
| Diagnose (`StatusView`) | 3 s | jüngste Ereignisse aller oder eines Auftrags | keine |

Dazu kommt Neues: Die Symbole im Baum zeigen je Seite den tatsächlichen Bestand und je Knoten oder Space die
Zusammenfassung des Teilbaums. Der Bestand ändert sich durch Laden, Lesen, Suche und Hintergrundfüllung, durch das
Bestätigen einer Generation und durch die Änderungssuche. Der Core kennt bisher nur Rückrufe je Lauf
(`SyncListener`, `ProgressiveSearchListener`), Konfigurationsänderungen (`ConfigurationListener`) und
`Glossary.subscribe`.

## Entscheidung

### 1. Grundsatz

Der Core meldet Änderungen; die App fragt nicht in Abständen. Einmaliges Lesen eines Standes, etwa beim Öffnen einer
Ansicht oder beim Aufklappen eines Space, bleibt erlaubt; danach kommen nur Änderungen. Zeitgeber für Animation,
Entprellen von Eingaben und das Ausblenden von Hinweisen sind keine Abfragen und bleiben.

### 2. Gemeinsame Regeln für jeden Beobachter

Paket `com.aresstack.spaceharvester.application.observe`:

- **Anmelden und Abmelden.** Jede Anmeldung liefert eine `Subscription` (`AutoCloseable`, `close()` mehrfach
  aufrufbar). Eine Ansicht meldet sich an, wenn sie sichtbar wird, und ab, wenn sie verschwindet.
- **Erst anmelden, dann lesen.** Wer nach der Anmeldung den Stand liest, verpasst nichts. Eine Meldung, die schon im
  gelesenen Stand enthalten ist, darf noch kommen; Empfänger vertragen doppelte Meldungen.
- **Nie den Schreiber aufhalten.** Meldungen entstehen auf Arbeits-Threads, die oft Schreibsperren halten (Lucene,
  Auftragsmonitor). Dort wird nur ein Schlüssel als geändert vorgemerkt. Zugestellt wird auf einem eigenen Thread des
  Core, nie auf dem schreibenden und nie auf dem EDT. Die App wechselt selbst auf den EDT.
- **Bündeln.** Je Schlüssel (Auftrag, Space, Suche, Planer) höchstens eine Zustellung je 250 ms, mit dem jüngsten
  Stand bzw. der Vereinigung der geänderten Seiten. Reihenfolge je Schlüssel bleibt erhalten. Ohne Änderung kommt
  nichts.
- **Fehler des Empfängers.** Eine Ausnahme wird mit Beobachter und Ausnahmeklasse protokolliert, der Empfänger bleibt
  angemeldet, die übrigen Empfänger bekommen ihre Meldung trotzdem, und der Zustell-Thread läuft weiter. Kein Protokoll nennt Seiten-IDs, Titel, Suchtext oder Inhalte; Seiten-IDs in Meldungen bleiben im
  Prozess.
- **Rechte.** Wer sich anmeldet, bekommt nur, was er auch abfragen dürfte (Eigentümer bei Aufträgen, Quellenrecht bei
  Bestand).

### 3. Die Beobachter

| Bereich | Anmeldung | Meldung | ersetzt |
|---|---|---|---|
| Aufträge | `Jobs.subscribe(owner, JobListener)` | `changed(JobStatus)` bei Zustand, Zählern, Phase und Grund einer Pause; `removed(JobStatus)` mit dem letzten Stand (für die Rechteprüfung); `event(JobEvent)` je neuem Diagnoseereignis | Aufträge, Statuszeile, Glossar, Synonymsuche, Diagnose |
| Bestand | `Holdings.subscribe(sourceId, spaceKey, HoldingsListener)`, `spaceKey` leer = alle Spaces der Quelle | `pagesChanged(sourceId, spaceKey, pageIds)`; `spaceChanged(sourceId, spaceKey)`; `loading(sourceId, spaceKey, pageIds)` | neu: Baum-Symbole, Export-Reiter |
| Beschaffungsplaner | `AcquisitionScheduler.subscribe(listener)`; mit Hintergrundfüllung `BackgroundFill.subscribe(listener)`, für die App `Harvester.subscribeAcquisition` bzw. `Workspace.subscribeAcquisition` | jüngster `Status` bzw. `AcquisitionStatus`, auch das Ende der Wartezeit nach dem Vordergrund | Hintergrundladen |
| Suchfortschritt | `KeywordSearch.subscribe(progressId, listener)`, für die App `Workspace.subscribeSearchProgress` | jüngster `Progress` nach jeder Seite und nach einem Abbruch, zuletzt mit Ende | Suche |

Das Glossar meldet seine eigenen Änderungen schon (`Glossary.subscribe`) und bleibt so. Die Verbindungsprüfungen in
der Diagnose sind Zustand der App (`AppContext.Connection`) und werden dort gemeldet, nicht im Core.

### 4. Bestand: Meldungen und Abfrage

- **Meldungen.** Jedes Schreiben in den Seiten-Cache meldet die betroffenen Seiten: Speichern einer Seite,
  Katalogeintrag, Kopfdaten, Entfernen, Stapel samt Bestätigen und Commit einer Generation. Ebenso das Ändern der
  Baseline, denn „veraltet“ entsteht ohne Schreiben einer Seite (Bestätigen einer Generation, Änderungssuche,
  `keepLoaded`). Wo ein Weg keine Einzelliste hat (Quelle entfernt, Generation als Ganzes ersetzt), kommt
  `spaceChanged`, ebenso, wenn eine Zustellung mehr als 1000 Seiten eines Space nennen würde (etwa nach einer großen
  Generation); die App liest dann einmal neu. Wird eine ganze Quelle entfernt oder geleert, bekommt jeder Empfänger
  der Quelle `spaceChanged` für seinen Space, ein Empfänger für alle Spaces mit leerem `spaceKey`. Eine verschobene
  oder entfernte Seite meldet auch ihren bisherigen Space und ihre bisherige Elternseite, damit die Teilübersicht den
  alten Pfad neu zählt. Beginnt ein Inhaltsabruf bei der Quelle (Beschaffungsplaner, Lesen, Abgleich, Export), kommt
  `loading` für die Seite in ihrem Space laut Katalog; das Ende des Abrufs kommt als `pagesChanged`, auch wenn er
  scheitert und nichts schreibt, bei kurzen Abrufen in derselben Zustellung nach `loading`.
- **Abfrage „Bestandsübersicht je Space“.** `Holdings.overview(sourceId, spaceKey)` liefert je Seite des Katalogs
  den eigenen Zustand und die Zähler ihres Teilbaums, gerechnet im Core über den Katalogbaum, ohne Quellenzugriff:
  - Zustand `REMOTE` (nur Katalog, kein Inhalt lokal), `LOCAL` (Inhalt lokal und aktuell, gleich ob durch Laden,
    Lesen, Suche oder Hintergrundfüllung), `OUTDATED` (Inhalt lokal, laut Baseline neuere Version), `LOCKED` (für das
    Konto nicht sichtbar);
  - Zähler `pages`, `local`, `outdated`, `locked` je Teilbaum einschließlich der Seite selbst und für den Space, dazu
    Generation und Stand vom. Ohne bestätigte Inventur (Generation `g0`) kennt der Cache nur einzelne Seiten, deren
    Elternseiten oft fehlen; die Übersicht sagt das ausdrücklich („keine Inventur“, nicht „nichts lokal“).
  - Gerechnet über denselben Baum wie der Seitenbaum: Eine Seite, deren Elternseite der Cache nicht kennt, und die
    Seite, an der ein Zyklus aufgebrochen wird, sind Wurzeln. Gesperrte Seiten hängen an der Elternseite aus ihrem
    zurückgehaltenen Stand. Seiten ohne Leserecht fehlen in Übersicht und Meldungen.
  `Holdings.overview(sourceId, spaceKey, pageIds)` liefert nach einer Meldung nur die genannten Seiten samt ihrer
  Vorfahren, damit die App nur den Pfad nach oben neu zeichnet; eine genannte Seite, die es nicht mehr gibt, steht dort
  ausdrücklich als entfernt. Es gilt dieselbe Obergrenze wie für den Seitenbaum (`query.max-tree-nodes`).
  `Holdings.spaces(sourceId)` liefert je Space nur die Zähler und den Stand vom, ohne Seitenliste, für die Zeilen einer
  Quelle.
- **Kreispfeile an Knoten** kommen aus den Auftragsmeldungen: `JobStatus` nennt Quelle, Space (`partitionKey`),
  Startseite und Zustand.
- Der Umfang (was aktuell gehalten wird) gehört nicht zum Bestand und nicht zu den Symbolen.

### 5. Nicht Teil dieser Entscheidung

- **REST.** Meldungen gelten für die App im selben Prozess. Für REST-Clients bleiben die Abfragen; ein Gegenstück per
  Server-Sent Events ist ein eigener späterer Punkt (`docs/PARITY.md`).
- **Umfang, Ausgabe aus dem Bestand und Medien am Bestand** (Schritte B bis D der Architektur) sind eigene Pakete.

## Umsetzung

Je ein kleiner Pull Request, Core zuerst:

1. Dieses ADR.
2. Gemeinsame Zustellung und Auftragsmeldungen, Anmeldung über `Harvester` und `Workspace`.
3. Bestand: Abfrage und Meldungen.
4. Beschaffungsplaner und Suchfortschritt.
5. App: die sechs Zeitgeber durch Anmeldungen ersetzen (Oberflächen-Stränge), Baum-Symbole und Export-Stand
   (Strang Export-Ansicht).

## Folgen

- Die App zeigt Änderungen, sobald sie geschehen, statt bis zu drei Sekunden später, und fragt nichts mehr ab, wenn
  sich nichts ändert.
- Jede Ansicht muss sich zuverlässig abmelden; eine vergessene Anmeldung hält Empfänger am Leben. Tests prüfen das je
  Beobachter.
- Ein langsamer Empfänger verzögert nur seine eigenen Meldungen, nie den Core; durch das Bündeln wächst kein Stau.
