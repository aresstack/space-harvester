# ADR 0030: Änderungsprotokoll und Update-Zusammenfassung je Quelle

- Status: **Angenommen** (Entwurf des Architekten, Strang AT, 16.11.2026; von Angelo am 16.11.2026 zum Bau bestellt).
  Vorab entschieden (Angelo, 16.11.2026):
  - Ein Änderungsprotokoll hält fest, was sich geändert hat; daraus fasst eine KI zusammen, „was seit dem letzten
    Update passiert ist“.
  - Auch was das Programm von selbst im Hintergrund holt, wird festgehalten und dem nächsten **manuellen** Update
    zugerechnet: Die Zusammenfassung umfasst alles seit der letzten manuellen Synchronisierung.
  - Aufbewahrung einstellbar, Standard ein Jahr, auch unbegrenzt; die Einstellung steht unter Einstellungen › Cache.
  - Fürs Erste genügt es, das Protokoll unter Einstellungen › Cache einzusehen, in einem eigenen Dialog mit allen
    Einträgen und Suche (reine Überprüfung).
  - Zusammenfassungen gibt es **je Wissensquelle getrennt**, auch wenn alle Quellen auf einmal aktualisiert werden.
  - Ort: Die Auswahl einer Quelle öffnet eine Quellenseite mit den Reitern der Seitenansicht; freie Reiter dort zeigen
    Angaben zur Quelle, unter anderem die Update-Zusammenfassungen. Frühere Zusammenfassungen bleiben nachlesbar, noch
    nicht erstellte entstehen auf Wunsch. Niemand muss nach einem Update sofort zusammenfassen lassen.
  - Ein Pop-up nach dem Update ist allenfalls eine Einstellung.
  Alle übrigen Punkte sind Vorschlag.
- Bezüge: Konzept https://claude.ai/artifact/5icjevP94x4X3kyLn3Dv65. Baut auf
  [ADR 0027](0027-beobachter-statt-abfragen.md) (Meldungen statt Abfragen), [ADR 0028](0028-ki-freigabe-je-seite.md)
  (KI-Freigabe je Seite) und [ADR 0029](0029-auswahl-und-ausgabe-aus-dem-bestand.md) (Auswahl) auf. Berührt
  [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md) (Abgleich), [ADR 0016](0016-auswahl-beschaffung-hintergrund.md)
  (Hintergrundfüllung), [ADR 0024](0024-aussagenanalyse-und-globaler-knowledge-graph.md) (Analyse mit dem Chat-Modell nur
  auf Auftrag) und [ADR 0025](0025-fortsetzbares-einlesen.md) (Aufträge). Keine neue Bibliothek, keine zweite Datenbank.

## Kontext

Nach einem Update zeigt die Auftragsliste nur Aufträge mit Zählern. Was sich in den Quellen geändert hat, ist nirgends
nachzulesen:

- `SyncSummary` hält je Space nur Zähler (neu, geändert, umbenannt, verschoben, entfernt, gesperrt), keine Seiten.
- Aufträge verfallen nach 7 Tagen (`JobLimits.DEFAULT_RETENTION`) und lassen sich in der Liste löschen; `JobEvent`
  ist Diagnose (Phase, Netzwerk, Fehler), kein Inhalt.
- Der Abgleich kennt je Seite die Art der Änderung samt altem und neuem Stand (`SyncPlan`, `PageChange` mit
  `BaselineEntry`), bewahrt das aber nicht auf.
- Der Cache führt je Seite den aktuellen Stand und einen Verlauf der Revisionen (`LucenePageCache#revisions`).
  Entfernte Seiten sind weg; Umbenennen und Verschieben ohne neue Version hinterlassen keine Spur.

Der Bestand ändert sich außerdem ohne manuellen Abgleich: Hintergrundfüllung (`BackgroundFill`, ADR 0016, Standard
an), Nachholen beim Start und nach Zeitplan (`ChangeSync`, Inventur, meist `CATALOG_ONLY`), Nachholen vor einer Suche
(`SyncBeforeSearch`), Versionsprüfung beim Lesen und in der Suche (`PageFreshness`, Beschaffungsplaner), Ereignisse
der Quelle (`EventSync`, Standard aus) und „Nur Fehler neu versuchen“ (`FailedPageRetry`). Die blauen Kreispfeile im
Baum zeigen jeden Seitenabruf mit Inhalt, gleich welcher Weg ihn auslöst.

## Entscheidung

### 1. Änderungsprotokoll im Core

- Der Core führt je Quelle ein dauerhaftes Protokoll der Änderungen an Seiten. Es liegt neben dem Cache, nicht in
  der YAML.
- **Eine Schreibstelle.** Geschrieben wird dort, wo der Cache eine Seite speichert, ihre Kopfdaten ändert, sie
  entfernt oder sperrt, also an der Stelle, die auch die Beobachter aus ADR 0027 speist. So schreibt jeder Weg,
  auch ein künftiger, ohne eigenen Code. Eine Zeile entsteht mit dem Schreiben in den Cache. Ein Abgleich legt
  Seiten vor dem Bestätigen ab; deren Zeilen tragen keine Generation und bleiben auch stehen, wenn der Lauf danach
  abbricht. Entfernen und Sperren geschehen mit dem Bestätigen und tragen die Generation. Ein verworfener
  Schreiblauf schreibt nichts. Der Bericht eines Updates stützt sich deshalb nur auf Zeitpunkt und Marke, nie auf
  die Generation.
- **Zeile je Änderung:** Quelle, Space, Seiten-ID, Zeitpunkt, Art, Auslöser, alte und neue Version, alter und neuer
  Titel, alte und neue Elternseite, alter und neuer Space, Generation, falls vorhanden. Kein Inhalt.
- **Arten.** Die Quelle hat sich geändert: `NEW`, `CONTENT_CHANGED`, `RENAMED`, `MOVED`, `LABELS_CHANGED`, `REMOVED`,
  `WITHHELD` (gesperrt), `MOVED_OUT`. Davon getrennt, nur der Bestand hat sich geändert: `LOADED` (Inhalt erstmals
  lokal) und `RELOADED` (Inhalt einer schon als geändert bekannten Version jetzt lokal). Nur die erste Gruppe ist
  „was hat sich geändert“; die zweite steht im Bericht höchstens als Zahl.
- **Auslöser:** `MANUAL`, `BACKGROUND`, `STARTUP`, `SCHEDULE`, `SEARCH`, `READ`, `EVENT`, `RETRY`, `EXPORT`.
- Erfährt der Katalog von einer neuen Version, bevor der Inhalt da ist, entsteht `CONTENT_CHANGED` mit dem Vermerk
  „Inhalt noch nicht geladen“; das spätere Laden ist `RELOADED` und keine zweite Änderung.
- Das Protokoll hat die Leserechte der Seiten. Wird eine Quelle entfernt oder geleert, verschwindet ihr Protokoll.
  Texte im Protokoll sind Titel aus der Quelle; sie erscheinen in keinem Log.

### 2. Aufbewahrung

- `space-harvester.cache.change-log.retention`, Standard `P1Y`, `0` oder leer heißt unbegrenzt. In beiden Loadern und
  im `ConfigurationParityTest`. Aufgeräumt wird beim Start und nach einem Abgleich, nie nach Zeitgeber.
- Oberfläche: Einstellungen › Cache, Zahl mit Einheit wie die übrigen Zeitdauern, dazu die Wahl „unbegrenzt“.

### 3. Update = alles seit der letzten manuellen Synchronisierung, je Quelle

- Eine manuelle Synchronisierung setzt je betroffener Quelle eine **Marke** im Protokoll: Zeitpunkt (Ende des
  letzten zugehörigen Abgleichs), Benutzer, Umfang. Startet der Benutzer mehrere Abgleiche derselben Quelle auf einmal
  (alle Spaces), entsteht eine Marke, wenn der letzte fertig ist. Aktualisiert er alle Quellen auf einmal, bekommt
  jede Quelle ihre eigene Marke.
- Ein **Update** einer Quelle ist alles zwischen der vorigen und dieser Marke, gleich welcher Auslöser es geholt
  hat. Änderungen aus dem Hintergrund sammeln sich an und gehören zum nächsten manuellen Update.
- Die Marke ist die einzige Update-Kennung. Es gibt keine eigene Update-Datenbank.
- Was nach der letzten Marke kam, ist „seit dem letzten Update, noch nicht abgeschlossen“ und einsehbar, aber noch
  kein Update.
- Mehrere Änderungen derselben Seite innerhalb eines Updates werden im Bericht zu einer zusammengefasst (erster alter
  Stand, letzter neuer Stand). Neu und wieder entfernt im selben Update erscheint nicht.

### 4. Änderungsbericht

- `ChangeLog.updates(sourceId)` liefert die Updates einer Quelle (Marke, Zeitraum, Zähler je Art und je Space, ob
  eine Zusammenfassung vorliegt). `ChangeLog.report(sourceId, update)` liefert die Seiten des Updates nach Art, mit
  Titel, Pfad und Verweis. `ChangeLog.entries(filter)` liefert rohe Zeilen für die Einsicht, mit Suche nach Titel,
  Seiten-ID, Art, Auslöser, Zeitraum, seitenweise.
- **Unterschied je Seite.** Für `CONTENT_CHANGED` bildet der Core lokal und ohne KI den Unterschied zwischen dem
  Lesetext der alten und der neuen Revision: hinzugekommene, entfallene und geänderte Absätze, in der Länge begrenzt.
  Fehlt die alte Revision im Cache, steht das als Daten im Bericht; es wird nichts geschätzt.
  - *Umgesetzt (CL5):* Der Verlauf im Cache hält keine alten Inhalte, nur Version und Hashes. Der Unterschied entsteht
    deshalb in dem Moment, in dem ein gespeicherter Inhalt durch eine andere Fassung ersetzt wird: im Commit des
    Schreiblaufs, in dem der bisherige Inhalt noch lesbar ist. Andere Fassung heißt andere Version, bei Inhalten ohne
    Version anderer Inhalts-Hash; dazu zählt das Nachladen einer erst im Katalog gesehenen Version. Andere Rohbytes
    derselben Version (dynamische Makros) ergeben keinen Unterschied. Abgelegt wird nur der Unterschied (Dokumentart
    `diff`, höchstens 50 Absätze, 500 Zeichen je Absatz, 8000 Zeichen gesamt, sonst `truncated`), nie eine alte
    Payload. Unterschiede gibt es erst für Ersetzungen ab Einführung von CL5.
  - Ein Schreiblauf hält für Unterschiede höchstens 500 Seiten und 64 MiB neuer Payload bis zum Commit vor; weitere
    Seiten des Laufs bekommen keinen Unterschied (`PREVIOUS_MISSING`), der Lauf selbst geht weiter.
  - Nur auf Wunsch (`report(…, withDiffs)`, REST `?diff=true`) trägt der Bericht je Seite mit `CONTENT_CHANGED` einen
    `diff` mit Zustand `AVAILABLE` (Schritte von der alten bis
    zur neuen Version), `CONTENT_PENDING`, `PREVIOUS_MISSING` oder `NOT_PERMITTED`. Ein Schritt ohne Absätze heißt:
    Der Lesetext ist gleich, geändert haben sich nur Unsichtbares oder Anhänge; Anhänge sind eigene Dokumente
    (ADR 0026) und nicht Teil des Unterschieds. Rechte: `METADATA` wie der Bericht und `CONTENT` an der Seite; die
    KI-Freigabe prüft erst die Zusammenfassung (Stufe 6).
  - Haltedauer = Aufbewahrung des Protokolls (Abschnitt 2), keine eigene Einstellung. Unterschiede verschwinden mit
    dem Seiteninhalt: Seite entfernt, gesperrt oder aus dem Umfang gefallen, Quelle entfernt, Cache geleert. Sie
    erscheinen in keiner Seitenabfrage, Suche, keinem Zähler, Export oder Embedding-Index; Inhalte und Titel stehen nie
    in Logs.
- Neue Einträge und neue Marken meldet ein Beobachter (ADR 0027). Niemand fragt in Abständen nach.

### 5. Zusammenfassung je Quelle und Update

- Eine Zusammenfassung gehört zu genau einer Quelle und einem Update. Sie entsteht **nur auf Wunsch** als Auftrag
  („Zusammenfassen“), nach dem Muster der Aussagenanalyse (ADR 0024): Der Core ruft das eingestellte Chat-Modell mit
  dem Änderungsbericht und legt das Ergebnis neben dem Protokoll ab. Das ist eine Auswertung, kein Chat; Gespräche,
  Agenten und Recherche bleiben außerhalb des Core.
- Das Modell bekommt nur den Bericht in fester, knapper Form: Zeitraum, Zähler, je Seite Art, Titel, Pfad und den
  Unterschied aus Punkt 4. Nie Auftragslogs, nie ganze Seiten. Bei vielen Änderungen wird je Space zusammengefasst
  und daraus die Fassung der Quelle gebildet.
- **KI-Freigabe (ADR 0028).** Titel und Unterschiede einer Seite gehen nur an das Modell, wenn
  `AiRelease.mayProcess(PageKey)` gilt. Für alle anderen Seiten bekommt das Modell nur Zahlen je Space. Die
  Zusammenfassung nennt, wie viele Änderungen sie nicht lesen durfte. Wird eine Seite von der Liste entfernt,
  verfallen die Zusammenfassungen, in die sie einging.
- Jede Aussage der Zusammenfassung verweist auf ihre Seiten. Abgelegt werden Text, Verweise, Modell, Zeitpunkt und
  der Stand des Berichts, aus dem sie entstand; ändert sich der Bericht nicht mehr (abgeschlossenes Update), bleibt
  sie gültig. Sie lässt sich neu erstellen.
- Aufbewahrt werden Zusammenfassungen so lange wie die Zeilen ihres Updates.

### 6. Oberfläche

- **Einstellungen › Cache:** die Aufbewahrung (Punkt 2) und ein Knopf, der einen Dialog „Änderungsprotokoll“ öffnet:
  alle Einträge als Tabelle (Zeit, Quelle, Space, Seite, Art, Auslöser), Suchfeld mit ✕, Filter nach Quelle, Art und
  Zeitraum. Reine Einsicht, keine Aktionen außer dem Öffnen der Seite.
- **Quellenseite:** Die Auswahl einer Quelle im Baum bestimmt Mitte und rechts wie jede Auswahl (ADR 0029). Reiter,
  die für eine Quelle nichts zeigen können, sind leer und zeigen nie die vorige Auswahl. Ein Reiter der Quellenseite
  zeigt die **Updates** der Quelle: Liste der Updates (Datum, Zähler), je Update die Änderungen nach Art mit Verweis
  auf die Seite und die Zusammenfassung, sofern erstellt, sonst die Aktion „Zusammenfassen“. Frühere Updates bleiben
  nachlesbar.
- **Nach einem Update** geschieht von selbst nichts. Eine Einstellung „Nach dem Update Änderungen anzeigen“
  (Standard aus) öffnet die Updates der Quelle; sie erstellt nie von selbst eine Zusammenfassung.
- Lange Vorgänge mit BusyOverlay, Teilergebnisse sofort, Abbrechen lässt Ergebnisse stehen.

### 7. Nicht Teil dieser Entscheidung

- Änderungen an Anhängen und enthaltenen Dateien als eigene Zeilen (die Kennungen aus ADR 0026 passen dazu).
- Zusammenfassung über mehrere Quellen hinweg.
- Benachrichtigungen außerhalb der App.
- Vergleich zweier beliebiger Fassungen einer Seite als eigene Ansicht.
- Welche weiteren Angaben die übrigen freien Reiter der Quellenseite zeigen.

## Umsetzung

1. **Protokoll schreiben** an der Schreibstelle des Cache, mit Art und Auslöser; Aufbewahrung; Marke je Quelle bei
   manueller Synchronisierung. Tests je Weg (Abgleich, Hintergrundfüllung, Nachholen, Versionsprüfung, Ereignis,
   Wiederholen): jede Änderung genau eine Zeile, erstes Laden ist `LOADED`.
2. **Abfragen und REST:** Updates, Bericht, rohe Einträge mit Suche; Beobachter.
3. **Einstellungen › Cache:** Aufbewahrung und Dialog „Änderungsprotokoll“.
4. **Quellenseite:** leere Reiter bei Quellenauswahl, Reiter „Updates“ mit Liste und Änderungen je Update.
5. **Unterschied je Seite** aus dem Revisionsverlauf.
6. **Zusammenfassung** als Auftrag mit Freigabeprüfung, Ablage und Anzeige; Einstellung für die Anzeige nach dem
   Update.

Die Stufen 1 bis 4 brauchen keine KI und sind für sich nützlich.

## Vor dem Bau zu klären

- ~~Wie lange der Cache alte Revisionen samt Inhalt hält~~ – geklärt mit CL5: gar nicht; der Unterschied entsteht
  beim Ersetzen und wird so lange aufbewahrt wie das Protokoll (Abschnitt 4).
- Ob es eine gemeinsame Schreibstelle für alle Wege schon gibt oder ADR 0027 sie erst schafft; wenn nicht, hängt
  Stufe 1 an der Umsetzung von ADR 0027, Punkt 4.
- Woran der Core eine manuelle Synchronisierung erkennt (Auslöser am Auftrag) und wie er mehrere gleichzeitig
  gestartete Abgleiche einer Quelle zu einer Marke bündelt.

## Folgen

- Der Bestand wächst um das Protokoll: je Änderung eine kleine Zeile, bei einem Jahr Aufbewahrung überschaubar; bei
  „unbegrenzt“ wächst es ohne Grenze, das sagt die Oberfläche.
- Die erste Inventur einer Quelle würde jede Seite als `NEW` schreiben. Das erste Update einer Quelle wird deshalb als
  „Erstbefüllung“ geführt, mit Zählern und ohne Seitenliste im Bericht.
- Die Zusammenfassung kostet Aufrufe beim Chat-Modell, nur auf Wunsch und nur für freigegebene Seiten.
- Gewollte Abweichung vom Enterprise AI Client (SH+), in PARITY.md nachzutragen.
