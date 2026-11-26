# ADR 0042: Native SharePoint-Quelle

- Status: **Angenommen, auf main umgesetzt** bis auf Punkt 5 (Entscheidungen von Angelo, 25./26.11.2026,
  festgehalten in #1438). Die Abnahme an einem echten SharePoint steht aus, #1438 ist deshalb offen. Diese ADR ist
  am 26.11.2026 aus [ADR 0040](0040-zustandslose-navigation-und-revisionsgesicherte-paginierung.md) herausgelöst
  worden; die Trennung ist beschlossen (F40-4, F42-2): ADR 0040 beschreibt die allgemeine Navigation, diese ADR
  die SharePoint-eigenen Entscheidungen.
- Quellen: #1438 samt Kommentaren, PRs #1455, #1465, #1484, Enterprise AI Client #248 bis #250; Angelos
  Zusammenfassung vom 26.11.2026 (Abschnitte 10 und 11), Klärung vom 26.11.2026, 14:12 (Punkte 5 und 6) und
  Antworten vom 26.11.2026, 14:21 und 14:41.
- Bezüge: [ADR 0001](0001-ausgehender-http-transport.md) (HTTP-Transport),
  [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md) (Fähigkeiten als Verträge, Grenzen als Einstellungen),
  [ADR 0037](0037-quellenumfang-nutzungssteuerung-beschaffung.md) (strikte Inventur),
  [ADR 0038](0038-indexbasierte-progressive-und-agentengestuetzte-suche.md) (Suchpipeline),
  [ADR 0039](0039-austauschbare-originalspeicherung-und-portable-wissensbestaende.md) (Originalcache),
  [ADR 0040](0040-zustandslose-navigation-und-revisionsgesicherte-paginierung.md) (Navigation und Cursor).

## Kontext

SharePoint-Bibliotheken ließen sich bisher nur als WebDAV-Laufwerk einbinden und wie ein lokaler Ordner
durchsuchen. Das bedeutet einen vollständigen Dateisystemscan über das Netz, eine Passwortpflicht und keine
Änderungserkennung.

## Entscheidung

1. **SharePoint ist eine eigenständige, native Wissensquelle**, kein eingebundenes Netzlaufwerk.

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
2. **Die Quelle bietet Suche, Navigation, Originalzugriff und Änderungsabgleich** als Fähigkeiten (ADR 0036,
   Punkt 4). Die Originale liegen zunächst im Originalcache (ADR 0039, Punkt 1).
3. **Die Anmeldeart wird ausdrücklich gewählt** (bestätigt am 26.11.2026): „Manuell“ (ausdrückliche Zugangsdaten),
   „KeePass“ oder „Windows-Anmeldung“ (integrierte Anmeldung des aktuellen Windows-Benutzers). Die
   Windows-Anmeldung wird nie selbsttätig aktiviert, weil die nötigen JDK-Einstellungen für den ganzen Prozess
   wirken und nicht nur für eine Quelle. Bestehende Quellen ohne Zugangsdaten werden nicht umgestellt. Bereits
   eingerichtete Quellen und von Hand gesetzte JVM-Parameter funktionieren weiter. Die Unterscheidung von Kerberos
   und NTLM ist bewusst zurückgestellt.
4. **Die Navigation folgt ADR 0040.** Der Cursor trägt seinen Zustand selbst, es gibt keine Grenze für offene
   Cursor, und `$top=100` ist eine Stapelgröße, kein fachliches Limit: Der ganze Inhalt eines Ordners ist
   erreichbar (ADR 0040, Punkt 8).
5. **Inkrementeller Abgleich über `GetChanges` mit exakten ChangeTokens** (ursprüngliches Ziel aus #1438,
   beschlossen am 26.11.2026).
   - Gespeichert wird das ChangeToken des letzten **erfolgreich verarbeiteten** Stands. Es wird erst nach
     erfolgreicher Verarbeitung dauerhaft fortgeschrieben. Scheitert die Verarbeitung, bleibt der letzte gültige
     Stand, und der Abruf wird sicher wiederholt.
   - Die Verarbeitung ist wiederholbar und idempotent. Ein verspäteter Auftrag überschreibt nie einen neueren
     Synchronisationsstand.
   - Zeitmarke und vollständige Inventur bleiben abgesicherte Rückfälle, etwa wenn der Server das Token nicht mehr
     kennt.
   - Nicht unterstützte oder strukturell heikle Änderungen führen zu einem vollständigen Inventurabgleich. Eine
     unvollständige Inventur ist nie ein Löschungsnachweis (ADR 0037, Punkt 13).
6. **Kein Register offener Änderungsabrufe** (beschlossen am 26.11.2026, 14:41). Die Map mit höchstens vier
   Änderungsschnappschüssen wird beseitigt, nicht einstellbar gemacht: Ein fünfter Abruf darf den ersten nie
   ungültig machen. Die Fortsetzung eines Änderungsabrufs trägt sich selbst.
   - Verwendet wird vorzugsweise der Fortsetzungsstand, den der Server tatsächlich liefert, gegebenenfalls mit einem
     stabil abgegrenzten Änderungsfenster.
   - Das Ausgangstoken mit einem bloßen Zähler zu verbinden genügt nicht, wenn sich die Ergebnismenge zwischen zwei
     Abrufen ändern kann.
   - Der Umbau geschieht zusammen mit Punkt 5 im Enterprise AI Client und kommt über den regulären
     Upstream-Abgleich in den Space Harvester.

7. **Bibliotheken ausblenden mit dem allgemeinen Ausschließen** (Angelo, 26.11.2026). Für SharePoint gibt es keine
   eigene Auswahl der Bibliotheken. Unerwünschte Bibliotheken auf oberster Ebene (etwa „Style Library“,
   „FormServerTemplates“, „SiteAssets“) und Ordner darin schließt man wie bei jeder Quelle aus: per Rechtsklick im
   Baum oder in der Übersicht „Ausgeschlossen“ der Quelle, die die Bibliotheken vom Server abfragt
   ([ADR 0037](0037-quellenumfang-nutzungssteuerung-beschaffung.md), Punkt 15). Neu angelegte Bibliotheken bleiben
   sichtbar. Ausschließen löscht nichts.

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| Native SharePoint-Quelle mit Suche, Navigation, Originalzugriff, `GetChanges` | auf main (#1455) |
| Cursor ohne Sitzung und ohne Grenze (`SharePointNavigationCursor`, HMAC), `$top=100`, Überspringen leerer Seiten mit Zyklenerkennung | auf main (#1465, #1469, EAC #249, #250) |
| Windows-Anmeldung als ausdrückliche Wahl im Quellenformular | auf main (#1484) |
| Exakte ChangeTokens speichern und erst nach Verarbeitung fortschreiben | umgesetzt (EAC #261, Space Harvester): Checkpoint je Bibliothek = Token der letzten verarbeiteten Änderung, gespeichert in der bestätigten Generation (`SyncBaseline.changeCheckpoint`); ohne Checkpoint oder bei abgelehntem Token gelten Zeitmarke bzw. Inventur |
| Änderungsabruf ohne Register im Prozess | umgesetzt (EAC #259): Cursor trägt Fenster und Position je Bibliothek, kein Register |
| Verhalten eines echten SharePoint bei mehr als 100 Dateien | **nicht nachgewiesen** (PowerShell-Test steht aus) |
| Punkt 7: Bibliotheken ausschließen (allgemeiner Mechanismus) | auf develop (#1504), Abgleich folgt (ADR 0037, Punkt 15) |

## Abweichungen des Codes vom Zielbild

- Keine mehr offen bei Punkt 5 und 6. Am echten SharePoint ist nicht geprüft, wie der Server ein zu altes Token
  ablehnt; jede Ablehnung beendet den nativen Abruf, und die Inventur übernimmt.

## Geklärt am 26.11.2026

Angelo hat die Antworten am 26.11.2026, 14:41, aus dem ursprünglichen Chat übermittelt.

| Frage | Antwort | Stand |
| --- | --- | --- |
| F40-2: Exaktes ChangeToken speichern | Ja, wie in #1438 vorgesehen | **Beschlossen**, in Punkt 5 |
| F42-1: Register der Änderungsschnappschüsse | Vollständig beseitigen, nicht einstellbar machen; Fortsetzung mit dem Stand des Servers | **Beschlossen**, in Punkt 6 |
| F42-2: Eigene ADR für SharePoint | Ja, beibehalten | **Beschlossen** |

## Grenzen und Limits

Nach ADR 0036, Punkt 12. Stand main `69835ca8`. Der Adapter liegt in der Upstream-Kopie; Änderungen gehen über den
Enterprise AI Client.

| Grenze | Standard | Einstellung | In der Oberfläche |
| --- | --- | --- | --- |
| Größte Datei (**Größenlimit**) | 50 MB, 1 bis 2000 | Quellenoption `maxFileMegabytes` | ja, Quellenformular |
| Tiefe der Inventur | ohne Grenze, höchstens 100 | Quellenoption `maxDepth` | ja, Quellenformular |
| Zahl der Ressourcen | ohne Grenze, höchstens 1 000 000 | Quellenoption `maxResources` | ja, Quellenformular |
| Verbindungs- und Lesezeit | 15 s, 60 s, höchstens 10 Minuten | Quellenoptionen `connectTimeoutMillis`, `readTimeoutMillis` | nein, kein Feld im Quellenformular |
| Stapelgröße der Navigation (`$top`) | 100 | **fest im Code** (`SharePointNavigationPages.BATCH_SIZE`) | nein |
| Stapelgröße des Änderungsabrufs | 500 | **fest im Code** (`SharePointChangeLog.PAGE_SIZE`) | nein |
| Größte JSON-Antwort (**Größenlimit**) | 32 MiB | **fest im Code** (`SharePointKnowledgeSource.MAX_JSON_BYTES`) | nein |
| Gelesener Dateianfang zur Erkennung (**Größenlimit**) | 8192 Bytes | **fest im Code** (`PREFIX_BYTES`) | nein |

## Folgen

- Der Adapter bleibt im Enterprise AI Client; der Space Harvester bekommt Änderungen über den Upstream-Abgleich.
- Mit gespeichertem ChangeToken wird der Änderungsabgleich unabhängig von Uhren und Zeitzonen.
- Weitere Quellen mit eigener Paginierung (etwa BSCW) folgen ADR 0040 und brauchen keine eigene ADR, solange sie
  keine eigenen Anmelde- oder Abgleichsregeln mitbringen.
