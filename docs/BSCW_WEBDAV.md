# BSCW Classic als Wissensquelle (WebDAV)

Space Harvester unterstützt BSCW Classic 5.3 über WebDAV, ohne dass in Windows ein Netzlaufwerk
eingebunden werden muss. Der Adapter nutzt die vorhandene Quellenverwaltung sowie die gemeinsame
Datei-, Tika-, Speicher- und Index-Pipeline.

## Einrichtung

In der Quellenverwaltung **BSCW Classic (WebDAV)** auswählen. Anmeldung **Basic** wählen und
die BSCW-Benutzerdaten über den vorhandenen Anmelde-/Secret-Provider hinterlegen.

| Option | Beispiel | Bedeutung |
|---|---|---|
| `rootUrl` | `https://bscw.example.org/sec/bscw.cgi/123456789` | URL eines echten, lesbaren BSCW-Ordners |
| `maxDepth` | `20` (Standard) | Unterordner bis zu 20 Ebenen durchsuchen; maximal 50 |
| `maxFileMegabytes` | `64` (Standard) | Maximale Größe eines einzelnen Downloads, maximal 64 MiB |

Wichtig: Die persönliche BSCW-Startseite `/sec/bscw.cgi/home` oder der persönliche
Arbeitsbereich kann im WebDAV eine leere Liste zurückgeben. Als `rootUrl` deshalb den
numerischen Objektlink eines tatsächlich geöffneten Ordners verwenden.

Die URL kann beim Auswählen eines KeePass-Eintrags automatisch aus dessen URL-Feld
übernommen werden. Eine bereits manuell eingetragene Ordner-URL wird nicht überschrieben.
Enthält KeePass nur die Startseite statt der numerischen Ordner-URL, muss der Startordner
einmal im Quellenformular ergänzt werden.

Die globale Netzwerkkonfiguration (Windows/PAC-Proxy oder manueller HTTP-Proxy) gilt
unverändert. Die HTTP-Basic-Anmeldung am BSCW-Server ist von einer eventuellen separaten
Proxy-Anmeldung zu unterscheiden. Die bestehenden TLS-Vertrauensregeln gelten auch für BSCW.

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

Die BSCW-Verbindungsprüfung führt jetzt einen echten `PROPFIND Depth: 0` am Startordner aus,
statt lediglich die intern definierte Partition zurückzugeben. HTTP 401/403 zeigt
BSCW-Anmeldeprobleme, HTTP 407 eine gescheiterte Proxy-Anmeldung an.
Die stufenweise Netzwerkdiagnose prüft ebenfalls die `rootUrl` der BSCW-Quelle.

## Funktionsumfang

- Im Explorer echte Ordnerhierarchie anzeigen: `PROPFIND Depth: 1` lädt **nur die direkten Kinder**
  beim Aufklappen eines Ordners; Unterordner und Dateien werden nicht vorab heruntergeladen
- Für einen ausdrücklich gestarteten Synchronisationslauf weiterhin den vollständigen Dateibestand
  rekursiv über Metadaten inventarisieren, ohne Dokumentinhalte während der Inventur zu laden
- Dokumente erst bei ihrem Abruf per `GET` als Originalbytes laden und in der bestehenden
  Leseransicht als escaped HTML-Text anzeigen, sofern Text extrahiert werden kann
- CSV und TSV anhand des Dateinamens als Text erkennen, auch wenn WebDAV einen generischen
  oder Excel-Medientyp meldet
- Änderungszeit, ETag, Dateigröße und Content-Type aus WebDAV übernehmen, soweit bereitgestellt
- Dokumente und mögliche Archive mit der bestehenden Extraktions- und Index-Pipeline verarbeiten
- Die für die Quelle verfügbaren Rechte des angemeldeten BSCW-Benutzers beachten

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

## Technischer Aufbau

`BscwSourceAdapter` registriert den neuen Quellentyp `bscw`. Der transportseitige
`BscwWebDavTransport` übernimmt nur authentifizierte HTTP-/WebDAV-Anfragen einschließlich
Proxy-Route. `BscwWebDavKnowledgeSource` implementiert die bestehenden Ports für
Partitionen, Metadaten, Originaldateien, Leseransicht und `PageNavigationSource`. Die
Live-Navigation ist getrennt von der rekursiven `PartitionListingSource`-Inventur; nur
der ausdrücklich gestartete Synchronisationslauf benötigt eine vollständige Metadatenliste.
Die Datei- und Indexlogik verbleibt im Core und wird nicht in die WebDAV-Klasse kopiert.
