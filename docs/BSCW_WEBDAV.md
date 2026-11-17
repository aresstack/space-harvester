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

Die globale Netzwerkkonfiguration (Windows/PAC-Proxy oder manueller HTTP-Proxy) gilt
unverändert. Die HTTP-Basic-Anmeldung am BSCW-Server ist von einer eventuellen separaten
Proxy-Anmeldung zu unterscheiden. Die bestehenden TLS-Vertrauensregeln gelten auch für BSCW.

## Funktionsumfang

- Ordner rekursiv per `PROPFIND` lesen und Dateien ohne vorherigen Download inventarisieren
- Dokumente per `GET` als Originalbytes laden
- Änderungszeit, ETag, Dateigröße und Content-Type aus WebDAV übernehmen, soweit bereitgestellt
- Dokumente und mögliche Archive mit der bestehenden Extraktions- und Index-Pipeline verarbeiten
- Die für die Quelle verfügbaren Rechte des angemeldeten BSCW-Benutzers beachten

Die BSCW-spezifische JSON-/XML-RPC-Schnittstelle, Ereignishistorie und Rechteverwaltung
werden derzeit nicht angebunden. Auch einen vorhandenen BSCW-Suchindex liest WebDAV nicht aus.

**Grenzen:** Der derzeitige `StoredContent`-Vertrag materialisiert ein ganzes Dokument im
Arbeitsspeicher; deshalb beträgt das Dateilimit höchstens 64 MiB. Die Aufnahme der Originalbytes
eines ZIP-Archivs bedeutet noch nicht, dass alle enthaltenen Dateien eigenständig indexiert sind:
Das ist durch die allgemeine Archiv-Extraktion des Space Harvesters sicherzustellen.
Die WebDAV-Integration ist mit einem lokalen Testserver geprüft, aber nicht gegen die
produktive Behördeninstallation.

## Technischer Aufbau

`BscwSourceAdapter` registriert den neuen Quellentyp `bscw`. Der transportseitige
`BscwWebDavTransport` übernimmt nur authentifizierte HTTP-/WebDAV-Anfragen einschließlich
Proxy-Route. `BscwWebDavKnowledgeSource` implementiert die bestehenden Ports für
Partitionen, Metadaten, Originaldateien und Dokumente. Die Datei- und Indexlogik verbleibt im
Core und wird nicht in die WebDAV-Klasse kopiert.
