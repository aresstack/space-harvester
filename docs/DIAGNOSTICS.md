# Auftragsdiagnose

Jeder Auftrag (Export, Abgleich, Ernte) hält Diagnoseereignisse fest, damit ein gescheiterter Lauf ohne Logdatei
erklärbar ist. Die Status-Ansicht der App und die REST-API zeigen sie an.

## Ereignis

`JobEvent` (`com.aresstack.spaceharvester.application.jobs`):

| Feld | Inhalt |
| --- | --- |
| `jobId`, `sequence` | Auftrag und laufende Nummer ab 1 |
| `at` | Zeitpunkt |
| `type`, `sourceId`, `partitionKey` | Art, Quelle und Space des Auftrags |
| `severity` | `INFO`, `WARNING`, `ERROR` |
| `category` | `JOB` (Lebenslauf, Phasen), `NETWORK` (Verbindung, Proxy, TLS), `SOURCE` (Antworten der Quelle), `ACCESS` (Rechte des Auftraggebers), `STORAGE` (Cache, Ablage, Konvertierung) |
| `component` | beteiligte Komponente, etwa `Exports`, `SpaceSync`, beim Scheitern die Klasse, die der Ursache am nächsten ist |
| `phase` | Phase beim Ereignis, etwa `Bestand prüfen`, `Einlesen`, `Inventur`, `Seiten abrufen`, `Seiten exportieren`, `Konvertieren (docx)`, `Ablegen`; im eigenen Auftrag `ENRICHMENT` `Anreichern: <Prozessor> (i/n)` |
| `code` | maschinenlesbarer Code, beim Scheitern der Fehlercode des Auftrags (`SOURCE_UNAVAILABLE`, `SYNC_INCOMPLETE` …) |
| `message` | Meldung für Menschen |
| `httpStatus` | HTTP-Status der Quelle, wenn die Ausnahmekette ihn nennt |
| `proxyRoute` | Route und Transport genau des gescheiterten Abrufs (`PROXY host:port (pac:resolved) · WebClient`, `DIRECT · WebClient`, `UNAVAILABLE (…) · WebClient`); wo der Abruf keine eigene Route mitbringt (synchroner Transport), die Route zur Basisadresse der Quelle ohne Transport |
| `tlsFinding` | TLS- oder Zertifikatsbefund, etwa `SSLHandshakeException: PKIX path building failed` |
| `detail` | Ausnahmekette mit Stacktrace, höchstens 16 000 Zeichen; fehlt bei gewollten Abbrüchen ohne Ursache |

Ein Auftrag meldet immer `Auftrag angenommen`, `Auftrag gestartet`, jeden Phasenwechsel und sein Ende. Exporte melden
dazu, ob sie eine Generation wiederverwenden oder den Space einlesen, und ihre Warnungen. Scheitert ein Auftrag, trägt
das letzte Ereignis Komponente, Phase, Code, HTTP-Status, Route, TLS-Befund und Detail.

## Maskierung und Sichtbarkeit

Alle Texte durchlaufen `Redaction`, bevor sie gespeichert werden:

- Werte nach `Bearer`, `Basic`, `Token`, `Negotiate` und `NTLM` werden maskiert, ebenso Werte von `password=`, `token:`,
  `apiKey`, `client_secret`, `Authorization`, `Cookie` und ähnlichen Namen.
- URLs behalten nur Schema, Host und Port. Benutzerangaben, Pfade und Abfrageparameter fallen weg, weil Pfade Seiten-IDs
  tragen können.
- Lange, zufällig aussehende Zeichenketten (Tokens, Schlüssel) werden maskiert; Auftrags-IDs bleiben lesbar.

Ereignisse sieht nur der Auftraggeber, genau wie den Auftrag selbst. Sie nennen nichts, was er nicht lesen darf: Das
Einlesen eines Exports meldet nur Phasen, keine Seiten, und Warnungen nennen nur Seiten aus seiner Sicht
([ADR 0010](adr/0010-export-ohne-vorbereiteten-bestand.md)). Tokens delegierter Nutzer erscheinen nie.

## Ablage

Die Ereignisse liegen in einem eigenen Lucene-Index unter `cache/job-events` (Schema `job-events/v1`), neben der
Auftragsablage. Ohne Cache-Verzeichnis liegen sie im Speicher. Je Auftrag werden höchstens 500 Ereignisse gehalten.
Sie verschwinden mit ihrem Auftrag nach `space-harvester.jobs.retention` oder wenn der Auftraggeber sie löscht
(siehe „Löschen“). Ein Fehler der Ablage lässt keinen Auftrag
scheitern.

## Zugriff

- Bibliothek: `Harvester.events(jobId)` und `Harvester.recentEvents(limit)`; allgemein `Jobs.events(jobId, owner)` und
  `Jobs.recentEvents(owner, limit)`, für Abgleiche auch `SpaceSyncJobs.events(syncId, owner)`.
- REST: `GET /api/v1/exports/{exportId}/events` und `GET /api/v1/syncs/{syncId}/events`, mit denselben Freigaben wie
  der Status. Ein fremder oder unbekannter Auftrag antwortet mit 404.

## Löschen

Der Auftraggeber löscht beendete Aufträge (abgeschlossen, gescheitert, abgebrochen) und ihre Ereignisse selbst; die
Löschung ist dauerhaft und überdauert den Neustart. Wartende und laufende Aufträge lassen sich nicht löschen
(`JobStillRunningException`, REST 409 `job-running`): erst abbrechen, dann das Ende abwarten. Fremde Aufträge gelten
wie beim Lesen als unbekannt (404).

| Was | Bibliothek | REST |
|---|---|---|
| Ein Auftrag samt Ereignissen | `Harvester.deleteJob(jobId)` | `DELETE /api/v1/exports/{id}`, `DELETE /api/v1/syncs/{id}` (204) |
| Alle beendeten Aufträge | `Harvester.deleteFinishedJobs()` | `DELETE /api/v1/exports`, `DELETE /api/v1/syncs` (`{"deleted": n}`) |
| Ein Ereignis | `Harvester.deleteEvent(jobId, sequence)` | `DELETE …/{id}/events/{sequence}` (204, sonst 404 `event-not-found`) |
| Ereignisse vor einem Zeitpunkt | `Harvester.deleteEventsBefore(Instant)` (alle eigenen Aufträge) | `DELETE …/{id}/events?before=2026-10-17T12:00:00Z` (ohne `before` alle) |

Allgemein stehen dieselben Operationen an `Jobs` (`delete`, `deleteFinished`, `deleteEvent`, `deleteEventsBefore`)
und für Abgleiche an `SpaceSyncJobs`. Die übrigen Ereignisse behalten ihre Nummern, und keine Nummer wird wieder
vergeben. Gelöscht werden nur Einträge der Auftrags- und Diagnoseablage: Das Exportartefakt im Exportverzeichnis gehört
zum Auftrag und verschwindet beim nächsten Aufräumen mit ihm; Dateien, die der Benutzer anderswo gespeichert hat, und
der Seitenbestand eines Abgleichs bleiben unberührt.

## Welche Anfrage welchen Transport nimmt

Alle Anfragen an eine Quelle fragen dieselben `ProxyRoutes` (win-proxy-java) je Ziel-URL und je Weiterleitung.
Eine nicht verfügbare Route (PAC nicht ladbar, Auswertung gescheitert) beendet die Anfrage ohne Verbindung, nie
`DIRECT`. Über einen Proxy wird der Zielname nie lokal aufgelöst, sondern im `CONNECT` an den Proxy gegeben.

| Anfrage | Transport |
|---|---|
| Verbindungsprüfung, Space-Liste, Seitenliste, Seitenbaum, Metadaten | synchron (`HttpURLConnection` über `RouteConnections`) |
| Einzelseite mit Inhalt (Lesen, Nachladen, Sync-Seitenabruf, Export) | `WebClient` (`WebClientConfluenceTransport`) |
| Rechteprüfung delegierter Benutzer | `WebClient` (`DelegatedConfluenceHttp`) |

## Fortschritt eines Exports und der Anreicherung

Ein Export, der den Space selbst einliest, zählt in `discovered` die Seiten der Inventur und in `processed` die beim
Einlesen abgerufenen oder geprüften Seiten, jeweils nur solche, die der Auftraggeber lesen darf; `written` und `failed`
zählen die exportierten beziehungsweise fehlenden Seiten. Ohne Einlesen (bestätigter Bestand) zählt der Export alle vier
selbst. Nichts wird doppelt gezählt.

Die Anreicherungen nach einem Abgleich oder Einlesen laufen als eigener Auftrag `ENRICHMENT` desselben Auftraggebers;
Abgleich und Export warten nicht darauf. Die Phase nennt den aktiven Prozessor, `discovered` zählt Seiten mal
Prozessoren, `processed` erledigte Schritte, `written` neu berechnete und `failed` fehlgeschlagene Ergebnisse. Der
Auftrag lässt sich wie jeder andere abbrechen; Berechnetes bleibt, der Rest folgt beim nächsten Abgleich.

## Bilder beschaffen

Nach jedem veröffentlichten Export beschafft ein eigener Auftrag `ASSETS` („Bilder beschaffen“) die Bilder seiner Seiten:
Bild-, Vektor- und Diagramm-Anhänge, Bilder der Lese-Ansicht, die kein Anhang sind, und Mermaid-Diagramme als Text.
`discovered` zählt gefundene Bilder, `processed` geprüfte, `written` abgelegte (auch bereits vorhandene), `failed`
fehlende. Das Ergebnis steht in `assets.json` des Exports (Format `space-harvester/export-assets/v1`, je Eintrag Seite,
Referenz, Version, Größe, `sha256`, Status `STORED`/`MISSING`/`FORBIDDEN`/`TOO_LARGE`/`UNAVAILABLE` und Grund); die Bytes
liegen im Asset-Speicher. Der Export wartet nicht darauf.
