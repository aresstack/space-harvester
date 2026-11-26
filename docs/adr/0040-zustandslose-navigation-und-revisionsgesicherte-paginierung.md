# ADR 0040: Zustandslose Navigation und revisionsgesicherte Paginierung

- Status: **Angenommen, auf main umgesetzt** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in #1438 und
  #1468). Die Abnahme an einem echten SharePoint steht aus, #1438 und #1468 sind deshalb offen. Die Punkte unter
  „Offene Fragen“ sind nicht entschieden.
- Quellen: #1438, #1468 samt Kommentaren, PRs #1455, #1464, #1465, #1469, #1484, Enterprise AI Client #248 bis
  #250; Angelos Zusammenfassung vom 26.11.2026 (Abschnitte 10 und 11) und Klärung vom 26.11.2026, 13:12 (Punkte 5
  und 6).
- Bezüge: [ADR 0001](0001-ausgehender-http-transport.md) (HTTP-Transport), [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md)
  (Rechte), [ADR 0027](0027-beobachter-statt-abfragen.md) (kein Polling),
  [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md) (Fähigkeiten als Verträge),
  [ADR 0037](0037-quellenumfang-nutzungssteuerung-beschaffung.md) (strikte Inventur),
  [ADR 0039](0039-austauschbare-originalspeicherung-und-portable-wissensbestaende.md) (Originalspeicherung).

## Kontext

Der erste Ansatz für die SharePoint-Navigation hielt offene Auflistungen in einer zentralen Map mit höchstens 128
Cursorn. Beim Audit (#1468) fand sich im Core eine zweite solche Map (`PageNavigation.listings`,
`MAX_OPEN_LISTINGS = 256`, mit `clear()`). Solche Maps verdrängen alte Cursor, lassen parallele Clients einander
stören und setzen eine künstliche Grenze. Angelo hat beides verworfen.

## Entscheidung

1. **Keine serverseitige Sitzung für Auflistungen.** Es gibt keine zentrale Map, kein LRU, keine TTL und keine
   Grenze für die Zahl offener Cursor. Das gilt für die ganze Navigationsarchitektur, nicht nur für SharePoint.
2. **Der Cursor trägt seinen Fortsetzungszustand selbst.** Im Core ist er ein authentifizierter Umschlag
   (`NavigationContinuation`, HMAC-SHA256), gebunden an Quelle und Knoten, mit dem Cursor der Quelle, dem bestätigten
   Präfix-Zähler und der erwarteten Cache-Revision. Er enthält keine ID-Liste. Ein manipulierter, fremder oder
   beschädigter Cursor wird abgelehnt.
3. **Gruppenbasierte Quellen und Betrieb ohne Cache reichen den Cursor der Quelle unverändert durch.** SharePoint
   ist gruppenbasiert (`navigationUsesGroups`) und braucht keinen Cache für die Reihenfolge der Explorer-Knoten; für
   solche Quellen schreibt die Navigation nichts in den Cache.
4. **Die Reihenfolge im Cache wird atomar gegen die erwartete Revision fortgeschrieben** (`NavigationOrder`). Ein
   veralteter oder erneut gesendeter Cursor darf weiter navigieren, aber nie eine neuere Reihenfolge überschreiben
   oder eine neue Revision übernehmen. Die Revision wechselt auch bei unveränderter Reihenfolge.
5. **Der Cache ist für die Live-Navigation nie Voraussetzung.** Fällt er aus oder ist seine Schreibsperre belegt,
   wird der optionale Schreibvorgang übersprungen und die Navigation läuft weiter.
6. **Ein ungültiger Cursor hat einen eigenen Fehlertyp.** `InvalidNavigationCursorException` steht nur für echte
   Cursor-Invalidierung, nie für allgemeine Fehler der Quelle. REST antwortet mit HTTP 400 und dem Problem-Code
   `invalid-navigation-cursor`; `INVALID_RESPONSE` bleibt davon getrennt.
7. **Der Explorer erholt sich gezielt.** Alle Stapel werden über die stabile ID abgeglichen (keine Dubletten über
   Seitengrenzen). Ein vorübergehender Fehler behält den Cursor. Ein ungültiger Cursor führt einmal zu einem lokalen
   Neuabruf, nie zur endlosen Wiederholung desselben Fehlers; scheitert der Neuabruf, bleibt eine anklickbare
   Wiederholung ohne toten Cursor. Auswahl, Scrollposition und aufgeklappte gültige Knoten bleiben erhalten.
8. **Die Navigation macht den ganzen Inhalt eines Ordners zugänglich** (bestätigt am 26.11.2026). Eine Stapelgröße
   wie `$top=100` ist eine gewünschte Seitengröße, kein fachliches Limit. Liefert der Server keinen
   Fortsetzungsverweis, obwohl es weitere Einträge gibt, greift eine andere vom Server unterstützte Paginierung oder
   als Rückfall der vollständige Abruf. Es darf nie vorkommen, dass
   - Einträge nach dem hundertsten unsichtbar bleiben,
   - eine unvollständige Auflistung als vollständig gilt,
   - eine unvollständige Inventur Löschungen im Bestand auslöst (ADR 0037, Punkt 12).
9. **Keine zugesicherte Snapshot-Konsistenz.** Ändert sich der Ordner auf dem Server während des Blätterns und
   bietet die Quelle keinen Snapshot, ist eine lückenlose Sicht nicht garantiert.

### SharePoint als erste Quelle dieser Navigation

10. **SharePoint ist eine eigenständige, native Wissensquelle**, kein eingebundenes Netzlaufwerk.

    | Verworfen oder zurückgestellt | Beschlossen |
    | --- | --- |
    | WebDAV-Mount als lokalen Ordner durchsuchen | Native SharePoint-REST-API |
    | Vollständiger Dateisystemscan | Navigation über Bibliotheken und Ordner |
    | Alle Ordnerinhalte sofort laden | Paginierung nach Bedarf |
    | Passwortpflicht | Integrierte Windows-Anmeldung möglich |
    | JNA oder eigener nativer Authentifizierungsclient | Bordmittel des JDK |
    | Eigener Viewer, eigene Suchmaschine | Gemeinsamer Dokumentviewer, vorhandene Suchpipeline (ADR 0038) |

    Der bestehende Adapter im Enterprise AI Client wird weiterentwickelt, es gibt keine parallele Implementierung.
    Eine generische Netzlaufwerkquelle ist zurückgestellt.
11. **Die Anmeldeart wird ausdrücklich gewählt** (bestätigt am 26.11.2026): „Manuell“ (ausdrückliche Zugangsdaten),
    „KeePass“ oder „Windows-Anmeldung“ (integrierte Anmeldung des aktuellen Windows-Benutzers). Die
    Windows-Anmeldung wird nie selbsttätig aktiviert, weil die nötigen JDK-Einstellungen für den ganzen Prozess
    wirken und nicht nur für eine Quelle. Bestehende Quellen ohne Zugangsdaten werden nicht umgestellt. Bereits
    eingerichtete Quellen und von Hand gesetzte JVM-Parameter funktionieren weiter. Die Unterscheidung von Kerberos
    und NTLM ist bewusst zurückgestellt.
12. **Inkrementelle Synchronisierung über `GetChanges`.** Nicht unterstützte oder strukturell heikle Änderungen
    führen zu einem vollständigen Inventurabgleich.

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| `PageNavigation.listings` und `MAX_OPEN_LISTINGS` entfernt; `NavigationContinuation`, `NavigationOrder` | auf main (#1469) |
| SharePoint-Cursor ohne Sitzung und ohne Grenze (`SharePointNavigationCursor`, HMAC), `$top=100`, Überspringen leerer Seiten mit Zyklenerkennung | auf main (#1465, #1469, EAC #249, #250) |
| `InvalidNavigationCursorException`, REST 400 `invalid-navigation-cursor` | auf main (#1469) |
| Explorer: Abgleich nach ID, einmaliger Neuabruf, Erhalt der Ansicht | auf main (#1464, #1469) |
| Native SharePoint-Quelle mit Suche, Navigation, Originalzugriff, `GetChanges` | auf main (#1455) |
| Windows-Anmeldung als ausdrückliche Wahl im Quellenformular | auf main (#1484) |
| Verhalten eines echten SharePoint bei mehr als 100 Dateien | **nicht nachgewiesen** (PowerShell-Test steht aus) |
| Exakte serverseitige ChangeTokens speichern | offen |

## Widersprüche und Auffälligkeiten

- **ChangeToken:** #1438 verlangt, das Token „erst nach bestätigtem Stand zu speichern“. Auf main dient die
  vorhandene Synchronisationszeitmarke als Ausgangspunkt (`SharePointChangeLog` bildet daraus `ChangeTokenStart`).
  Das exakte Token wird nicht gespeichert. Angelos Zusammenfassung führt das als offen.
- **Der Schlüssel der Cursor lebt nur im Prozess.** `NavigationContinuation` und `SharePointNavigationCursor`
  erzeugen ihren HMAC-Schlüssel beim Start zufällig. Nach einem Neustart ist jeder ausgegebene Cursor ungültig, und
  hinter einem Lastverteiler versteht eine Server-Instanz die Cursor der anderen nicht. Der Explorer fängt das über
  Punkt 7 ab; ein REST-Client bekommt HTTP 400 und muss neu beginnen. „Zustandslos“ gilt damit je Prozess.
- **Der Rückfall aus Punkt 8 ist am echten Server ungeprüft.** #1468 nennt einen „Offset-/Fingerprint-Refetch“ als
  Rückfall. Ob er bei einem Server ohne Fortsetzungsverweis alle Einträge liefert, ist nur gegen Attrappen getestet.

## Offene Fragen an Angelo

- **F40-1:** Der Cursor-Schlüssel gilt nur bis zum Neustart und nur für eine Instanz. Reicht das (Desktop-App, ein
  Server), oder soll der Server-Schlüssel einstellbar sein, damit Cursor einen Neustart und mehrere Instanzen
  überleben?
- **F40-2:** Soll das exakte SharePoint-ChangeToken gespeichert werden, wie #1438 es vorsieht, oder bleibt die
  Zeitmarke mit Inventur-Rückfall die Lösung?
- **F40-3 (zu Punkt 8):** Wenn weder ein Fortsetzungsverweis noch eine andere Paginierung des Servers greift: Soll
  der vollständige Abruf eines sehr großen Ordners ohne Rückfrage laufen, oder mit sichtbarem Fortschritt und
  Abbruch? Mein Vorschlag: mit Fortschritt und Abbruch, und ein abgebrochener Abruf zeigt den Ordner als
  „unvollständig geladen“, nie als vollständig.
- **F40-4:** SharePoint (Punkte 10 bis 12) steht hier als Abschnitt, weil keine eigene Nummer vergeben wurde. Soll
  die native SharePoint-Quelle eine eigene ADR bekommen?

## Folgen

- Beliebig viele Clients blättern gleichzeitig, ohne einander zu verdrängen.
- Ein Cursor ist kurzlebig gedacht. Clients müssen mit `invalid-navigation-cursor` rechnen und neu beginnen können.
- Neue Quellen mit eigener Paginierung liefern ihren Cursor selbst; der Core verpackt ihn nur, wenn er eine
  Reihenfolge im Cache führt.
