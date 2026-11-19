# BSCW Classic als Wissensquelle (WebDAV)

Space Harvester unterstützt BSCW Classic 5.3 über WebDAV, ohne dass in Windows ein Netzlaufwerk
eingebunden werden muss. Der Adapter nutzt die vorhandene Quellenverwaltung sowie die gemeinsame
Datei-, Tika-, Speicher- und Index-Pipeline.

## Einrichtung

In der Quellenverwaltung **BSCW Classic (WebDAV)** auswählen. Als Quelle für die Zugangsdaten
wie bei Confluence **KeePass** oder **Manuell** wählen. Bei KeePass stammen Benutzername,
Passwort und nach Möglichkeit die Ordner-URL aus dem ausgewählten Eintrag; eigene Felder
für Anmeldung, Benutzer und Passwort sind dann nicht nötig. Die Anmeldung erfolgt intern
per HTTP Basic. Bei manueller Anmeldung werden die benötigten Felder angezeigt.

| Option | Beispiel | Bedeutung |
|---|---|---|
| `base-url` | `https://bscw.example.org/sec/bscw.cgi/123456789` | URL eines echten, lesbaren BSCW-Ordners |
| `maxDepth` | `20` (Standard) | Unterordner bis zu 20 Ebenen durchsuchen; maximal 50 |
| `maxFileMegabytes` | `64` (Standard) | Maximale Größe eines einzelnen Downloads, maximal 64 MiB |

Wichtig: Die persönliche BSCW-Startseite `/sec/bscw.cgi/home` oder der persönliche
Arbeitsbereich kann im WebDAV eine leere Liste zurückgeben. Als `base-url` deshalb den
numerischen Objektlink eines tatsächlich geöffneten Ordners verwenden.

Die URL kann beim Auswählen eines KeePass-Eintrags automatisch aus dessen URL-Feld
übernommen werden. Eine bereits manuell eingetragene Ordner-URL wird nicht überschrieben.
Enthält KeePass nur die Startseite statt der numerischen Ordner-URL, muss der Startordner
einmal im Quellenformular ergänzt werden.

Die globale Netzwerkkonfiguration (Windows/PAC-Proxy oder manueller HTTP-Proxy) gilt
unverändert. Der WebDAV-Transport verwendet **Reactor Netty mit derselben Proxy-Route,
Proxy-BASIC-Anmeldung, TLS-Vertrauensregel und expliziter DIRECT-Behandlung wie der
Confluence-WebClient-Transport**. Er unterstützt zusätzlich die für WebDAV notwendige
HTTP-Methode `PROPFIND`. Die HTTP-Basic-Anmeldung am BSCW-Server ist von der separaten
Proxy-Anmeldung zu unterscheiden.

### Proxy-Route ohne PAC/WPAD

Ein erfolgreicher WebDAV-Zugriff im Windows-Explorer bedeutet nicht automatisch, dass der
Java-Prozess dieselbe Proxy-Route verwendet. Kann die automatische Proxy-Ermittlung keine
PAC-/WPAD-Adresse finden, bricht der Harvester die Verbindung bewusst ab, statt ungefragt
direkt zu verbinden.

Ist der BSCW-Host **nachweislich direkt erreichbar**, unter **Einstellungen → Netzwerk →
Erweitert → Proxy-Ausnahmen** den Hostnamen eintragen, beispielsweise
`bscw.example.org`. Das setzt nur für diesen Host die Route auf `DIRECT`;
andere Quellen behalten ihre Proxy-Einstellungen. Ist direkter Zugang nicht möglich,
stattdessen den korrekten manuellen Proxy oder die gültige PAC-Konfiguration einrichten.

Die BSCW-Verbindungsprüfung führt einen echten `PROPFIND Depth: 0` am Startordner aus,
statt lediglich die intern definierte Partition zurückzugeben. HTTP 401/403 zeigt
BSCW-Anmeldeprobleme, HTTP 407 eine gescheiterte Proxy-Anmeldung an.
Die stufenweise Netzwerkdiagnose prüft ebenfalls die `base-url` der BSCW-Quelle und
zeigt bei HTTP-Fehlern den tatsächlichen HTTP-Status. Die Proxy-BASIC-Authentifizierung
von `PROPFIND` über HTTPS-CONNECT wird durch einen Integrationstest geprüft.

## Funktionsumfang

- Im Explorer echte Ordnerhierarchie anzeigen: `PROPFIND Depth: 1` lädt **nur die direkten Kinder**
  beim Aufklappen eines Ordners; Unterordner und Dateien werden nicht vorab heruntergeladen
- Das Feld **„Titel filtern“** durchsucht ausschließlich bereits durch die Navigation bekannte
  Dateinamen im Speicher. Jede weitere geöffnete Ordnerebene erweitert die sichtbaren Treffer.
  Es löst **keine rekursive Inventur** aus; die Suche ist vor einer Synchronisation nicht vollständig.
- BSCW-Objekt-URLs sind keine hierarchischen Dateipfade. Die Navigation merkt deshalb
  Elternbeziehungen schon beim Öffnen von Ordnern, **auch bei leerem Dokument-Cache**.
  Bei früher heruntergeladenen Dokumenten kann sie stattdessen die gespeicherten
  Elternbeziehungen verwenden; der Explorer klappt die bekannten Ordner auf.
  Es werden keine unbekannten Elternordner erfunden.
- Nach **Daten bereinigen** ist die globale Volltextsuche zunächst leer, weil sie den
  lokalen Dokumentindex verwendet; BSCW WebDAV besitzt hier keinen Live-Volltextsuchport.
  Die Dokumente müssen gezielt erneut gelesen oder synchronisiert werden, bevor ihre
  Textinhalte wieder durchsuchbar sind. Das Aufklappen von Ordnern allein lädt nur Metadaten.
- Die Leseransicht verwendet als Basisadresse die URL **der geöffneten BSCW-Ressource**,
  nicht die in der Quelle konfigurierte Startordner-URL.
- Für einen ausdrücklich gestarteten Synchronisations- oder Exportlauf den WebDAV-Baum
  **ordnerweise mit Cursor** inventarisieren. Die erste Metadatenseite wird ausgegeben, bevor
  weitere Unterordner gelesen werden; Dokumentinhalte bleiben während der Inventur unangetastet.
  Inventuren prüfen zwischen ihren Stapeln Pausieren/Abbrechen. Ein 403, Timeout oder anderer
  Fehler eines Unterordners markiert die Inventur als unvollständig: keine neue bestätigte
  Generation, keine fälschlichen Löschungen und kein als vollständig ausgegebener Export
- Jede BSCW-Datei ist **eine eigenständige Dateiseite**: Ihr nativer Seitensnapshot enthält
  die Originalbytes (`nativeFormat=file`). Die Datei wird **nicht zusätzlich als Anhang
  ihrer eigenen Seite** geführt. Dadurch lädt der Export dieselbe Datei nicht ein zweites
  Mal in den Anhangsspeicher. Der Rohdatenexport enthält die Originalbytes weiterhin als
  Seitenpayload; Titel, Dateityp und Revision stehen in den Seitenmetadaten.
- Dokumente erst bei ihrem Abruf per `GET` als Originalbytes laden. Die Desktop-Leseransicht
  zeigt die Datei über den vorhandenen Originaldatei-Viewer; extrahierter Text steht
  separat für Volltextsuche und KI-Auswertung bereit
- CSV und TSV anhand des Dateinamens als Text erkennen, auch wenn WebDAV einen generischen
  oder Excel-Medientyp meldet
- Änderungszeit, ETag, Dateigröße und Content-Type aus WebDAV übernehmen, soweit bereitgestellt
- Dokumente und mögliche Archive mit der bestehenden Extraktions- und Index-Pipeline verarbeiten
- Die für die Quelle verfügbaren Rechte des angemeldeten BSCW-Benutzers beachten

BSCW hat **keine Confluence-Spaces**. Der Startordner ist der navigierbare Einstiegspunkt
und zugleich die einzige technische Partition des bestehenden Synchronisationsports.
Die `base-url` ist wie bei Confluence eine gemeinsame Einstellung; die Ordnertiefe ist BSCW-spezifisch. Bestehende `options.rootUrl`-Einträge werden beim Bearbeiten auf `base-url` migriert.
Confluence-Optionen für Spaces gehören nicht in die BSCW-Konfiguration.

Die BSCW-spezifische JSON-/XML-RPC-Schnittstelle, Ereignishistorie und Rechteverwaltung
werden derzeit nicht angebunden. Auch einen vorhandenen BSCW-Suchindex liest WebDAV nicht aus.

**Grenzen:** Der derzeitige `StoredContent`-Vertrag materialisiert ein ganzes Dokument im
Arbeitsspeicher; deshalb beträgt das Dateilimit höchstens 64 MiB. Die Aufnahme der Originalbytes
eines ZIP-Archivs bedeutet noch nicht, dass alle enthaltenen Dateien eigenständig indexiert sind:
Das ist durch die allgemeine Archiv-Extraktion des Space Harvesters sicherzustellen.
Die WebDAV-Integration ist mit einem lokalen Testserver einschließlich Navigation, CSV-Lesen
und Lucene-Synchronisation geprüft. Eine vollständige Abnahme gegen die produktive
Behördeninstallation steht noch aus.

**Hinweis zur Leseransicht:** Der derzeitige Adapter rendert extrahierten Dokumententext
als `<pre>`-Darstellung und keine originalgetreue Office-/PDF-Vorschau. Der WebDAV-Dateiabruf
und die Textindizierung bleiben unabhängig von der Navigation.

## PowerPoint-Vorschau und Videostreaming

Die Desktop-Leseansicht rendert PowerPoint-Präsentationen (`.ppt` und `.pptx`) lokal mit Apache POI.
Die vorhandenen Seiten-, Zoom- und Speicherfunktionen des PDF-/Originaldatei-Viewers werden wiederverwendet.
Animationen, eingebettete Videos und komplexe Layouts müssen nicht pixelgenau wiedergegeben werden.

Word-Dokumente (`.doc`, `.docx`, `.docm`, `.dot`, `.dotx`) erhalten ebenfalls
eine lokale **Textvorschau** über Apache POI, ohne quellenabhängige Konvertierung.
Die Vorschau ist lesbar, aber nicht seitengenau formatiert; Tabellen und Schriftgestaltung
werden nicht wie in Word reproduziert. Originaldateien bleiben über Speichern/Öffnen verfügbar.
Word-Dateien ohne Erweiterung können anhand eines Word-Medientyps gelesen werden.
Ein 0-Byte-Snapshot einer BSCW-Datei wird beim Öffnen einmal live überprüft, bevor
die Anzeige eine tatsächlich leere Originaldatei meldet.

Excel-Dateien (`.xls`, `.xlsx`, `.xlsm`) und CSV-/TSV-Dateien erhalten eine
**lokale, read-only Tabellenansicht**. Für Excel verwendet der Viewer Apache POI und
zeigt mehrere Arbeitsblätter als Reiter; für CSV/TSV werden Trennzeichen,
Anführungszeichen und mehrzeilige Felder berücksichtigt. Ein von BSCW für CSV
gemeldeter Excel-Medientyp verhindert die CSV-Erkennung nicht mehr.
Die Anzeige ist auf 5.000 Zeilen und 96 Spalten je Blatt begrenzt, um große Dateien
ohne UI-Blockierung zu betrachten. Die Originaldatei wird nicht gekürzt und kann
unverändert gespeichert oder im zugeordneten Office-Programm geöffnet werden.
Für `.xlsb` und `.ods` besteht weiterhin keine lokale Tabellenvorschau.

Der Video-Player sucht zunächst eine vorhandene libVLC-Installation (u. a. die üblichen
Windows-VLC-Verzeichnisse), danach über die VLCJ-4-NativeDiscovery. Für portable
Installationen kann `spaceharvester.vlc.path` als JVM-Systemeigenschaft oder `VLC_HOME`
als Umgebungsvariable auf den Ordner mit `libvlc.dll` zeigen.
Nur ein tatsächliches `playing`-Ereignis schaltet die Bedienelemente auf Wiedergabe.
Fehlende Native-Bibliotheken oder Codec-/Netzwerkfehler werden sichtbar gemeldet;
ein akzeptierter Startaufruf allein gilt nicht als erfolgreiche Wiedergabe.
Die Bedienelemente bilden eine deckende, kompakte Steuerleiste **direkt unter der
nativen VLC-Zeichenfläche**. Ein transparentes Swing-Overlay über dem AWT-Canvas
wäre wegen Heavyweight-/Lightweight-Mischung fehleranfällig. Play, Pause und Stopp
sind als Icons ausgeführt, der Download ist separat bedienbar und sein Abbrechen
erscheint nur bei laufendem Download.

Die breite Zeitleiste folgt dem Bedienprinzip des Audio Studios: Klick oder
Ziehen zeigt die Zielposition sofort, **erst beim Loslassen** startet genau ein
neuer VLC-Sprung. Damit erzeugt das Ziehen keine Folge von WebDAV-Range-Abrufen.
Die Zeitleiste zeigt verstrichene Zeit und Gesamtdauer an und erlaubt
Links-/Rechtsnavigation per Tastatur. Wenn VLC die Dauer nicht kennt, wird
ersatzweise seine relative Position (Prozent) verwendet. Der native Sprung wird
auf einem Worker statt im Swing-Event-Thread ausgeführt, damit die Oberfläche
beim Nachladen der Position weiter reagiert.

**Vollbild:** Doppelklick auf das Videobild oder das Vier-Ecken-Symbol rechts
neben „Herunterladen“ öffnet ein randloses Vollbild auf demselben Monitor.
Esc, erneuter Doppelklick oder der Wiederherstellen-Button führen zurück
in die Leseansicht. Das vorhandene Video samt Transportleiste wird in das
Vollbildfenster und zurück umgesetzt; dieselbe VLCJ-Playerinstanz bleibt
erhalten. Da AWT bei einem Wechsel des Top-Level-Fensters das native
Canvas-Handle neu erzeugen kann, wird eine laufende Wiedergabe **vor dem
Umhängen kontrolliert gestoppt** und danach über die neu angebundene
Videooberfläche mit der vorherigen Position und dem Pausenzustand fortgesetzt.
Das kann eine kurze Unterbrechung und neue WebDAV-Range-Abrufe verursachen,
verhindert aber den dauerhaft schwarzen Bildschirm. Beim Wechsel von Vollbild
in den Audio-Only-Modus übernimmt dessen Medienwechsel den Neustart, damit
keine zweite Videowiedergabe ausgelöst wird. Ein bloßes Schließen des
Vollbildfensters während des Seitenwechsels startet die Wiedergabe nicht neu.
Das funktioniert mit der vorhandenen libVLC-Ausgabe (unter Windows auch
Direct3D); ein zusätzliches Direct3D-Modul oder Java-21-Upgrade ist nicht
notwendig.

Große Videoressourcen (`.mp4`, `.m4v`, `.webm`, `.mov`, `.mkv`) werden beim Auswählen im Explorer
**nicht vollständig heruntergeladen**: Der Snapshot enthält lediglich Metadaten
(`nativeFormat=remote-media-metadata`, `media.remote=true`).
Die ursprünglichen Videobytes sind nicht als Originaldatei im Seiten-Cache vorhanden.
Für Suche und KI liegt ohne Transkript auch kein gesprochener Videoinhalt vor.

Der Swing-Player ist über VLCJ eingebettet. Zur Wiedergabe muss auf dem Desktop-Rechner eine kompatible
VLC/libVLC-Installation vorhanden sein; ohne VLC erscheint ein Hinweis statt eines Fehlers.
Erst der Klick auf „Abspielen“ löst Abrufe aus: Ein zufällig geschützter Loopback-Endpunkt auf
`127.0.0.1` übersetzt lokale Streaming-Anfragen in authentifizierte WebDAV-`GET`-Anfragen mit
`Range` über die konfigurierten Proxy- und TLS-Routen. Pro Quellanforderung sind höchstens 256 KiB
zulässig. Nur `HTTP 206 Partial Content` wird akzeptiert. BSCW-Zugangsdaten werden niemals
an den Player oder einen anderen Host weitergegeben; der Endpunkt wird beim Seitenwechsel geschlossen.
Fehlen die Dateigröße oder serverseitige Range-Unterstützung, kann der Player nicht streamen und meldet
den Grund. Ein vollständiger Download erfolgt **nicht** automatisch als Ersatz.

**Video-Download auf Wunsch:** „Herunterladen“ fragt nach einer lokalen Zieldatei und lädt
erst danach die Originalbytes in begrenzten WebDAV-Range-Blöcken. Dies funktioniert auch ohne
installiertes VLC/libVLC. Die Datei entsteht zunächst als temporäre Datei im Zielordner und
wird erst bei vollständigem Erfolg sichtbar; Abbrechen und Seitenwechsel beenden den Vorgang.
Der Download erzeugt keinen automatischen Cache-Eintrag. Das BSCW-Exportartefakt enthält
standardmäßig nur die Metadaten und **nicht** das Videooriginal. Eine vollständige
Wiederherstellung aus einem solchen Export ist daher **nicht** möglich; dafür ist eine
separat aktivierte Sicherung mit Originaldateien erforderlich (noch nicht implementiert).

### Audio-only-Wiedergabe bei MP4

Der Umschalter **Nur Audio** verwendet für MP4 einen eigenen virtuellen MP4-Stream
anstatt lediglich das Bild in VLC zu verbergen. `Mp4AudioOnlySource` liest über
den vorhandenen authentifizierten Byte-Range-Port die MP4-Metadaten
(`moov`, `stco`/`co64`, `stsc` und `stsz`) und bietet nur die indexierten
Audio-Chunks an. Für fragmentierte MP4s verwendet
`FragmentedMp4AudioOnlySource` die Audio-`traf`-/`trun`-Indizes der
`moof`-Fragmente und baut daraus einen virtuellen audiobeschränkten MP4-Stream.
Die Videonutzdaten werden vom BSCW-Server dabei nicht angefordert.
Audio-only braucht weder eine vollständige Videodatei noch ein lokales Videooriginal.

Für den ersten Wechsel wird der Index im Hintergrund aufgebaut. Eine laufende
Videowiedergabe wird währenddessen angehalten; der Player verwendet danach
einen separaten Loopback-Stream für Audio. Ein erneuter Wechsel zum Bild
verwendet wieder den ursprünglichen Videostream. Die Wiedergabeposition und
der Pause-Zustand werden über den Medienwechsel wiederhergestellt.
Das vorbereitete Audio-Indexmodell bleibt während der aktuellen Ansicht erhalten.

Nicht unterstützte oder ungültige MP4-Strukturen werden ausdrücklich abgelehnt.
Ein Rückfall auf den vollständigen Videostream findet **nicht** statt.
Die Indexierung kann bei vielen Fragmenten zusätzliche kleine WebDAV-Anfragen
erfordern; sie ist kein Download der Videopakete. Andere Videoformate bleiben
vorerst im bisherigen Videomodus. Der Download-Button lädt weiterhin nur
auf ausdrücklichen Wunsch die vollständige Originaldatei herunter.

Der BSCW-Live-Test mit einer 161.804.927 Byte großen fragmentierten MP4
bestätigte für das erste Medienfragment: 276.244 Byte virtueller
Audiostream; 34.540 Byte über 28 Range-Anfragen von BSCW gelesen.
Für die gesamte Datei und die hörbare VLC-Wiedergabe steht eine
abschließende manuelle Abnahme noch aus.

### Lokale Whisper-Transkription aus der Textansicht

Für eine MP4 mit BSCW-Range-Unterstützung bleibt der Videoplayer in der
**Leseransicht**. Im benachbarten Reiter **Text** erscheint die Schaltfläche
**Transkript erzeugen**. Sie startet die lokale Whisper-Verarbeitung
ausschließlich auf ausdrücklichen Klick; die automatische Videoindexierung
bleibt ausgeschaltet.

Der Text-Reiter legt dafür bei der Dateiauswahl eine unabhängige
`VideoTranscriptSource` an. Diese erstellt beim Start der Transkription einen
virtuellen MP4-Stream **nur mit den indexierten Audiopaketen**; der normale
Videostream wird weder zum Transkribieren geöffnet noch als Fallback verwendet.
`VlcPcmAudioDecoder` wandelt das Audio-only-MP4 lokal in 16-kHz-Mono-PCM um.
`TranscribeAudioUseCase` führt die Erkennung mit der Maven-Central-Bibliothek
`com.aresstack:directml-inference:0.3.0` aus und entfernt die temporären
PCM-Daten nach Abschluss, Abbruch oder Fehler. Die Oberfläche zeigt grobe
30-Sekunden-Segmente mit Zeitmarken, noch keine wortgenaue Zeitzuordnung.
Die Vorschau wird **nicht** automatisch in den Suchindex aufgenommen.

**Ein einziges ausführbares JAR:** Das Gradle-Task `prepareWhisperModel`
beschafft **nur beim Build** vier Whisper-Tiny-Dateien aus der festgelegten
Hugging-Face-Revision
`169d4a4341b33bc18d8881c4b69c2e104e1cc0af`.
SHA-256 wird für jede Datei geprüft; ein vorhandener geprüfter Gradle-Cache
oder eine lokale Quelle kann wiederverwendet werden. Der App-Build bettet die
Dateien unter `BOOT-INF/classes/models/whisper-tiny/` in das **bestehende
ausführbare Spring-Boot-JAR** ein. MIT-, Apache-2.0- und Herkunftshinweise
liegen im JAR unter `BOOT-INF/classes/licenses/`.
`verifyWhisperJar` prüft deren Vollständigkeit bei jedem `build`.

Beim ersten Klick entpackt `WhisperModelLocation` die Modellgewichte aus dem
JAR in einen versionsgebundenen lokalen Cache unter
`%USERPROFILE%\\.space-harvester\\models` (erneut mit SHA-256-Prüfung).
Whisper benötigt diese Dateipfade; **ein Modelldownload zur Laufzeit ist
nicht nötig**. Ein separates ZIP, ein Modellinstallationsdialog oder eine
zweite Datei für den Anwender sind nicht erforderlich. Für lokale Tests kann
`-Dspaceharvester.whisper.model=<Modellverzeichnis>` den eingebetteten
Modellpfad übersteuern.

Bei sehr langen Aufnahmen kann die Indexierung der Audio-Chunks und die
Audio-Dekodierung einige Zeit dauern. Der erste Prototyp transkribiert nur
auf Anforderung; persistente Transkripte, Wort-Zeitmarken, Wiederaufnahme und
automatisierte Hintergrundjobs gehören nicht zu diesem Schritt.
Der opt-in-Live-Test `VlcPcmAudioDecoderLiveTest` benötigt eine vorhandene
libVLC-Installation (`-Dspaceharvester.test.vlcpcm=true`).

## Technischer Aufbau

`BscwSourceAdapter` registriert den neuen Quellentyp `bscw`. Der transportseitige
`BscwWebDavTransport` übernimmt nur authentifizierte HTTP-/WebDAV-Anfragen einschließlich
Proxy-Route. `RoutedWebDavHttp` verwendet den Reactor-Netty-Netzwerkweg mit expliziter
DIRECT-/PROXY-Entscheidung wie Confluence; `BscwWebDavKnowledgeSource` implementiert die bestehenden Ports für
Partitionen, Metadaten, Originaldateien, Leseransicht und `PageNavigationSource`. Die
Live-Navigation ist getrennt von der rekursiven `PartitionListingSource`-Inventur; nur
der ausdrücklich gestartete Synchronisationslauf benötigt eine vollständige Metadatenliste.
Die Datei- und Indexlogik verbleibt im Core und wird nicht in die WebDAV-Klasse kopiert.
