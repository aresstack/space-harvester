# ADR 0040: Zustandslose Navigation und revisionsgesicherte Paginierung

- Status: **Angenommen, auf main umgesetzt** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in #1438 und
  #1468). Die Abnahme an einem echten SharePoint steht aus, #1438 und #1468 sind deshalb offen. Was im Text
  mit *vorgeschlagen* gekennzeichnet ist, stammt aus den Antworten des ursprünglichen Chats vom 26.11.2026, 14:21,
  und gilt erst mit Angelos Bestätigung.
- Quellen: #1438, #1468 samt Kommentaren, PRs #1455, #1464, #1465, #1469, #1484, Enterprise AI Client #248 bis
  #250; Angelos Zusammenfassung vom 26.11.2026 (Abschnitte 10 und 11) und Klärung vom 26.11.2026, 14:12 (Punkte 5
  und 6).
- Bezüge: [ADR 0001](0001-ausgehender-http-transport.md) (HTTP-Transport), [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md)
  (Rechte), [ADR 0027](0027-beobachter-statt-abfragen.md) (kein Polling),
  [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md) (Fähigkeiten als Verträge),
  [ADR 0037](0037-quellenumfang-nutzungssteuerung-beschaffung.md) (strikte Inventur),
  [ADR 0039](0039-austauschbare-originalspeicherung-und-portable-wissensbestaende.md) (Originalspeicherung),
  [ADR 0042](0042-native-sharepoint-quelle.md) (SharePoint als erste Quelle dieser Navigation).

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
   - eine unvollständige Inventur Löschungen im Bestand auslöst (ADR 0037, Punkt 13).

   **Der vollständige Abruf als Rückfall läuft mit sichtbarem Fortschritt und Abbruch, ohne zusätzliche
   Rückfrage** (*vorgeschlagen*). Wird er abgebrochen oder scheitert er, zeigt der Ordner „unvollständig geladen“,
   nie „vollständig“, und es folgt keine Löschung aus einer unvollständigen Inventur.
9. **Keine zugesicherte Snapshot-Konsistenz.** Ändert sich der Ordner auf dem Server während des Blätterns und
   bietet die Quelle keinen Snapshot, ist eine lückenlose Sicht nicht garantiert.

10. **Der Cursor-Schlüssel lebt nur zur Laufzeit** (*vorgeschlagen*). Nach einem Neustart oder bei einer anderen
    Instanz ist ein ausgegebener Cursor ungültig; der Client beginnt neu (Punkte 6 und 7). Ein einstellbarer,
    dauerhafter Schlüssel kommt erst, wenn mehrere Server-Instanzen oder lange lebende Cursor gebraucht werden.

SharePoint ist die erste Quelle dieser Navigation. Was nur SharePoint betrifft (native Quelle, Anmeldeart,
Änderungsabgleich), steht in [ADR 0042](0042-native-sharepoint-quelle.md).

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| `PageNavigation.listings` und `MAX_OPEN_LISTINGS` entfernt; `NavigationContinuation`, `NavigationOrder` | auf main (#1469) |
| SharePoint-Cursor ohne Sitzung und ohne Grenze (`SharePointNavigationCursor`, HMAC), `$top=100`, Überspringen leerer Seiten mit Zyklenerkennung | auf main (#1465, #1469, EAC #249, #250) |
| `InvalidNavigationCursorException`, REST 400 `invalid-navigation-cursor` | auf main (#1469) |
| Explorer: Abgleich nach ID, einmaliger Neuabruf, Erhalt der Ansicht | auf main (#1464, #1469) |
| Verhalten eines echten SharePoint bei mehr als 100 Dateien | **nicht nachgewiesen** (PowerShell-Test steht aus) |
| Vollständiger Abruf als Rückfall mit Fortschritt und Abbruch, Zustand „unvollständig geladen“ | nicht nachgesehen, vorgeschlagen (F40-3) |

## Widersprüche und Auffälligkeiten

- **Der Schlüssel der Cursor lebt nur im Prozess.** `NavigationContinuation` und `SharePointNavigationCursor`
  erzeugen ihren HMAC-Schlüssel beim Start zufällig. Hinter einem Lastverteiler versteht eine Server-Instanz die
  Cursor der anderen nicht. Der Explorer fängt das über Punkt 7 ab; ein REST-Client bekommt HTTP 400 und muss neu
  beginnen. „Zustandslos“ gilt damit je Prozess. Nach Punkt 10 genügt das vorerst.
- **Der Rückfall aus Punkt 8 ist am echten Server ungeprüft.** #1468 nennt einen „Offset-/Fingerprint-Refetch“ als
  Rückfall. Ob er bei einem Server ohne Fortsetzungsverweis alle Einträge liefert, ist nur gegen Attrappen getestet.
- **Die Vorschau aus dem Cache endet nach 1000 Kindern** (`PageNavigation.MAX_CACHED_CHILDREN`). Der Baum zeigt
  zuerst diesen Stand und fragt dann die Quelle. Ob er bei einem größeren Ordner bis dahin erkennbar macht, dass die
  Vorschau unvollständig ist, habe ich nicht nachgesehen (siehe F40-5).
- **Beim Änderungsabgleich von SharePoint gibt es noch ein Register mit fester Größe** (`SharePointChangePages`,
  vier Schnappschüsse). Das steht in ADR 0042.

## Geklärt am 26.11.2026

Stand der Antworten: **Beschlossen** gilt. *Vorgeschlagen* ist eine Empfehlung aus dem ursprünglichen Chat und gilt
erst, wenn Angelo sie bestätigt.

| Frage | Antwort | Stand |
| --- | --- | --- |
| F40-1: Lebensdauer des Cursor-Schlüssels | Ein Schlüssel nur zur Laufzeit genügt vorerst | *Vorgeschlagen*, in Punkt 10 |
| F40-2: Exaktes ChangeToken speichern | Ja, wie in #1438 vorgesehen | **Bestätigt** (ursprüngliches Ziel), in ADR 0042, Punkt 5 |
| F40-3: Vollständiger Abruf als Rückfall | Mit Fortschritt und Abbruch, ohne zusätzliche Rückfrage | *Vorgeschlagen*, in Punkt 8 |
| F40-4: Eigene ADR für SharePoint | Ja | *Vorgeschlagen*, umgesetzt als ADR 0042 |

## Grenzen und Limits

Nach ADR 0036, Punkt 12. Stand main `69835ca8`. Keine dieser Grenzen ist eine Einstellung.

| Grenze | Standard | Einstellung | In der Oberfläche |
| --- | --- | --- | --- |
| Offene Cursor | ohne Grenze | keine nötig (Punkt 1) | entfällt |
| Stapelgröße der Seitenliste je Anfrage | 100 (zugleich Höchstwert) | **fest im Code** (`PageQueries.MAX_PAGE_LIMIT`) | nein |
| Kinder je Knoten in der Vorschau aus dem Cache | 1000 | **fest im Code** (`PageNavigation.MAX_CACHED_CHILDREN`) | nein |
| Titelsuche: Treffer, Suchtiefe bei verdrängten Treffern | 100, 1000 | **fest im Code** (`PageNavigation.MAX_TITLE_LIMIT`, `MAX_TITLE_DEPTH`) | nein |
| Baum eines Space: Tiefe, Stapel beim Aufbau | 200 Ebenen, 1000 | **fest im Code** (`PageQueries.MAX_TREE_DEPTH`, `TREE_BATCH`) | nein |
| Gemerkte Versionsprüfungen | 10 000 | **fest im Code** (`PageQueries.MAX_CHECKED`) | nein |

Stapelgrößen sind nach Punkt 8 nie ein fachliches Limit. Die Tiefe von 200 Ebenen ist eines: Ein tieferer Baum
wird abgewiesen (`TreeTooLargeException`) und nur der Export liefert ihn.

## Offene Fragen an Angelo

- **F40-5 (zu Punkt 8):** Die Vorschau aus dem Cache zeigt höchstens 1000 Kinder eines Knotens, bevor die Quelle
  antwortet. Mein Vorschlag: Die Vorschau wird wie die Live-Auflistung paginiert, damit es dort keine feste Grenze
  mehr braucht. Einverstanden, oder genügt eine Einstellung?
- **F40-6 (zu „Grenzen und Limits“):** Ein Baum mit mehr als 200 Ebenen wird über REST abgewiesen. Soll das ein
  einstellbarer Wert werden, oder soll der Baum grundsätzlich ebenenweise geliefert werden, sodass die Grenze
  entfällt?

## Folgen

- Beliebig viele Clients blättern gleichzeitig, ohne einander zu verdrängen.
- Ein Cursor ist kurzlebig gedacht. Clients müssen mit `invalid-navigation-cursor` rechnen und neu beginnen können.
- Neue Quellen mit eigener Paginierung liefern ihren Cursor selbst; der Core verpackt ihn nur, wenn er eine
  Reihenfolge im Cache führt.
