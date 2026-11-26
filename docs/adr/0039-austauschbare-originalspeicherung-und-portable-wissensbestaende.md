# ADR 0039: Austauschbare Originalspeicherung und portable Wissensbestände

- Status: **Angenommen, auf main umgesetzt** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in #1437 samt
  Kommentaren). Die Abnahme mit echten Nutzerdateien und OneNote steht aus, #1437 ist deshalb offen. Die Punkte unter
  „Offene Fragen“ sind nicht entschieden.
- Quellen: #1437 (Zielarchitektur, Architekturleitplanken, sechs Fortschrittskommentare), PRs #1439, #1442, #1444,
  #1445, #1450, #1452, #1453, #1463, #1472; Angelos Zusammenfassung vom 26.11.2026 (Abschnitte 7 bis 9) und Klärung vom 26.11.2026, 13:12 (Punkt 4).
- Bezüge: [ADR 0003](0003-lucene-cache.md) (Cache), [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md) (Export),
  [ADR 0026](0026-anhaenge-und-enthaltene-dateien-als-dokumente.md) (Dokumente und Abschnitte, große Dateien),
  [ADR 0029](0029-auswahl-und-ausgabe-aus-dem-bestand.md) (Ausgabe aus dem Bestand),
  [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md) (gemeinsamer Bestand).

## Kontext

Eine lokale Dateiquelle legte jede Datei noch einmal vollständig im Cache ab, obwohl das Original auf demselben
Rechner liegt. Angelo wollte das ausdrücklich nicht. Zugleich zeigte der grüne Haken im Baum nur, dass irgendeine
Repräsentation im Cache lag, nicht, dass die Datei durchsuchbar ist. Die Inventur durchlief je Stapel den ganzen
Baum erneut (bei 10.000 Dateien etwa 100 Vollscans).

## Entscheidung

1. **Wie das Original abgelegt wird, ist eine austauschbare Strategie**, getrennt von Quelle, Inventur,
   Textextraktion und Index. `SourceSnapshots` ist der eine Snapshot-Anwendungsfall,
   `SnapshotStorageStrategy` entscheidet, welche Repräsentation des Originals ein Snapshot behält.

   | Strategie | Behält | Für |
   | --- | --- | --- |
   | `LocalOriginalStorage` | Verweis auf das Original, SHA-256 des Originals, Extraktionsstatus, begrenzte Vorschau. **Keine dauerhafte Kopie**, auch nicht als Base64- oder JSON-Payload. | Lokale Dateien, die an Ort und Stelle lesbar sind |
   | `CachedOriginalStorage` | Kopie des Originals mit Revision (bisherige Semantik) | Alle anderen Quellen, auch SharePoint |

   Es gibt nur diese zwei Strategien. Bedarfscache, LRU und Quoten kommen erst, wenn SharePoint oder ein Netzlaufwerk
   sie brauchen (YAGNI).
2. **Lokale Originalreferenzen sind der Standard** (`Harvester.Builder.localOriginalReferences`, Vorgabe `true`).
   Das Referenzformat heißt `local-original-reference/v1`.
3. **Was bei einer lokalen Quelle dauerhaft bleibt:**

   | Inhalt | Abgelegt |
   | --- | --- |
   | Originaldatei | nein |
   | Verweis, Original-Hash, Metadaten | ja |
   | Strukturierte Textextraktion (Abschnitte im `ExtractionStore`) | ja |
   | Lucene-Volltextindex, abschnittsweise unter der echten Seiten-ID | ja |
   | Embeddings | bei entsprechender Verarbeitung |
   | Analysen, Passagen, Fundstellen | ja |

4. **Suche, Analyse und Viewer arbeiten unabhängig von der Strategie.** `ExtractionStore`, Lucene, Embeddings,
   Passagen, Analysen und der gemeinsame Viewer sind für beide Strategien dieselben. Lokale Abschnitte sind keine
   fiktiven Anhänge, sie haben eine eigene Dokumentidentität (`DocumentKey.localOriginal`).
5. **Das LargeFiles-Prinzip ist verallgemeinert, nicht umgedeutet.** Lokale Dateien werden nie als
   `large-file-text` oder `large-file-metadata` deklariert, sonst meldet die Oberfläche „zu groß“. Der Hash eines
   Text-Snapshots wird nie als Hash des Originals ausgegeben.
6. **Lesen an Ort und Stelle.** Lokale Originale werden nach erneuter Pfad- und Zugriffsprüfung direkt als Strom
   extrahiert und angezeigt, ohne temporäre Kopie. Eine Extraktion wird erst nach Revisions- und Zugriffsprüfung
   übernommen; ändert sich die Datei währenddessen, wird das Ergebnis verworfen.
7. **Exporte sind eigenständig und portabel.** Die Ablage im Betrieb ist von der Materialisierung beim Export
   getrennt:
   - **Regulärer Export:** liest das Original bei Bedarf und prüft es gegen den gespeicherten Hash.
   - **Bestandsausgabe** (ADR 0029): nimmt die gespeicherte Textextraktion, ohne die Quelle zu berühren.
   - Das Exportpaket trägt Inhalte, Provenienz, Prüfsummen (Original-SHA-256, Referenz-SHA-256),
     Extraktionsstatus und Kürzungskennzeichen. Die Hashes beziehen sich auf die tatsächlich exportierten Bytes.
   - Ist das Original geändert oder nicht erreichbar, wird nie vorgetäuscht, die aktuelle Datei entspreche dem
     historischen Stand. Der Export weist das aus und gilt als unvollständig. Ohne gespeicherten Original-Hash wird
     kein angeblich historisches Original exportiert.
   - Die Größengrenze richtet sich nach dem Payload-Budget, nicht nach der Vorschau (8192 Zeichen) oder der
     Lesegrenze (1 Mio. Zeichen).
   - Ein Paket lässt sich in einen unabhängigen Harvester importieren, ohne Original und ohne `ExtractionStore`.
8. **Bestandsstatus ist nicht Cachezustand.** Ein grüner Haken heißt: durchsuchbar, und der Index gehört zur
   aktuellen Revision. Die Zustände einer Wissensressource sind getrennt: Metadaten vorhanden, Original erreichbar,
   Text extrahiert, Volltextindex aktuell, semantisch erschlossen, fachlich analysiert. Ordner zählen nicht als
   Dokumente. Extraktionsfehler, veraltete und unvollständige Bestände werden unterschieden.
   - **Ein Ordner ist grün, wenn alle dafür geeigneten Ressourcen vollständig und aktuell indexiert sind**
     (bestätigt am 26.11.2026). Ein Bild ohne indexierbaren Text verhindert nicht, dass ein Ordner mit 20
     indexierten PDFs als vollständig gilt: „20/20 indexiert, 1 nicht anwendbar“.
   - **„Nicht indexierbar“ und „Indexierung fehlgeschlagen“ sind verschiedene Zustände.** Ein PDF mit
     Extraktionsfehler fällt nie aus der Rechnung, es zählt als Lücke. Dasselbe gilt für ein Bild, sobald es über OCR
     erschlossen werden soll.
9. **Eine Inventur ist ein Verzeichnisdurchlauf**, nicht ein Vollscan je Stapel, mit Fortschritt schon beim Scan.
   Eine unvollständige Inventur löscht nie gültige Bestände. Eine Wurzel, die kein Verzeichnis mehr ist, gilt nie
   als vollständig bestätigt. Die lokale Version ist Größe plus Änderungszeit.
10. **Keine Bestandsmigration.** Angelo legt die Quellen neu an (Kommentar vom 25.11., 22:27). Bestehende Originale
    werden nicht automatisch gelöscht.
11. **Netzlaufwerke sind kein lokaler Ordner.** Sie werden nicht stillschweigend auf die kopielose Semantik
    umgestellt. SharePoint ist eine eigene, native Quelle (#1438) und nutzt zunächst den Originalcache. Eine
    generische Netzlaufwerkquelle ist zurückgestellt.

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| Inventur in einem Durchlauf, direkte Extraktion ohne temporäre Kopie | auf main (#1439, #1442) |
| `SnapshotStorageStrategy`, `LocalOriginalStorage`, `CachedOriginalStorage` | auf main (#1444) |
| Originalansicht für referenzierte Dateien im gemeinsamen Viewer | auf main (#1445) |
| Abschnittsweiser Volltext und abschnittsweise Analyse lokaler Originale | auf main (#1450, #1452, #1453) |
| Portabler Export, erneuter Import ohne Original | auf main (#1463) |
| Bestandszeichen aus tatsächlicher Volltextindexierung (`IndexState`, `LocalIndexStatus`, `IndexCoverage`) | auf main (#1472) |
| Referenzspeicherung als Vorgabe (`localOriginalReferences = true`) | auf main (`53d5098e`) |
| Warnung vor erkennbaren Netzwerkpfaden beim Einrichten einer lokalen Quelle | nicht gefunden |
| Abnahme mit echten Nutzerdateien und OneNote | offen |

## Bekannte Grenzen

- Die Hashprüfung ist kein atomarer Schnappschuss des Dateisystems. Eine Änderung bei gleicher Größe und
  unverändertem Zeitstempel erkennt die Inventur nicht; erst der Export bemerkt sie am Hash.
- Der Lesetext ist auf 1 Mio. Zeichen begrenzt. Der Reranker sieht bei mehrteiligen Dokumenten nur einen Abschnitt.
- Lokale Embeddings sind nicht gegen einen echten KI-Dienst abgenommen.

## Widersprüche und Auffälligkeiten

- **Die Strategie fragt den Quellentyp ab.** `LocalOriginalStorage.supportsSource` prüft `"files".equals(type)`
  zusätzlich zur Fähigkeit `readsOriginalInPlace()`. Die Entscheidung „von der Quelle entkoppelt“ ist damit nur über
  die Fähigkeit erfüllt, nicht vollständig.
- **Der Schalter ist global, nicht je Quelle.** `localOriginalReferences` gilt für den ganzen Harvester. Eine lokale
  Quelle, die bewusst Kopien halten soll (etwa ein Netzlaufwerk, #1437: „Originale lokal cachen“), lässt sich nicht
  einzeln einstellen.
- **Der Status deckt weniger ab als das Zielbild.** `IndexState` kennt `INDEXED`, `PARTIAL`, `OUTDATED`,
  `NOT_INDEXED`, `NO_TEXT`, `FAILED`, `LOCKED` und gilt für lokale Dateien. „Semantisch erschlossen“ und „fachlich
  analysiert“ sind dort keine eigenen Zustände, und andere Quellen zeigen weiter den Cachezustand.
- **Die Ordneraggregation weicht von Punkt 8 ab.** Nach der Klärung vom 26.11.2026 verhindert heute eine nicht
  indexierbare Datei das Grün des Ordners. `IndexState.NO_TEXT` fasst zudem zwei Fälle zusammen: nicht unterstützt
  oder ausgeschlossen (nicht anwendbar) und „extrahiert, aber ohne Text“ (etwa ein gescanntes PDF). Die Korrektur
  ist beauftragt.
- **Die Netzwerkpfad-Warnung** aus den Leitplanken von #1437 habe ich im Code nicht gefunden.

## Offene Fragen an Angelo

- **F39-1:** Sollen die neuen Bestandszeichen (durchsuchbar und aktuell statt „im Cache“) für alle Quellen gelten,
  oder bleiben sie auf lokale Dateien beschränkt?
- **F39-2:** Sollen „semantisch erschlossen“ und „fachlich analysiert“ eigene sichtbare Zustände im Baum werden,
  oder genügt dort der Volltextstatus?
- **F39-3:** Soll die Speicherstrategie je Quelle wählbar sein (zum Beispiel ein lokaler Pfad auf einem Netzlaufwerk
  mit Kopie), oder bleibt es bei einem globalen Schalter?
- **F39-4:** Alte Originalkopien bestehender lokaler Quellen bleiben liegen, bis du die Quelle entfernst. Ist das so
  gewollt, oder soll es eine Aufräumfunktion geben?
- **F39-6 (zum Ordnerzeichen):** Mein Vorschlag für die Rechnung: geeignet = alle Dateien ohne „nicht anwendbar“.
  Grün nur, wenn jede geeignete Datei `INDEXED` ist. `PARTIAL` (Extraktion gekürzt), `OUTDATED`, `FAILED` und
  `NOT_INDEXED` verhindern Grün. Ein Ordner ohne eine einzige geeignete Datei bekommt ein neutrales Zeichen, kein
  grünes. Ein Unterordner, der noch nicht inventarisiert ist, verhindert Grün ebenfalls, weil sein Inhalt
  unbekannt ist. Einverstanden?
- **F39-7 (zum Ordnerzeichen):** Ein gescanntes PDF ohne Textebene: „nicht anwendbar“ oder Lücke? Mein Vorschlag:
  Lücke, sobald für die Quelle OCR oder Bildanalyse ([ADR 0021](0021-bildanalyse.md)) eingeschaltet ist, sonst
  „nicht anwendbar“ mit eigenem Hinweis „ohne Text“. Dasselbe für Bilder.
- **F39-8 (zum Ordnerzeichen):** Gesperrte Dateien (`LOCKED`, kein Leserecht): aus der Rechnung nehmen oder Grün
  verhindern? Mein Vorschlag: Sie verhindern Grün nicht, werden aber eigens gezählt („3 gesperrt“).
- **F39-5:** Soll die Warnung vor Netzwerkpfaden (UNC, WebDAV, SMB) beim Einrichten einer lokalen Quelle noch
  kommen?

## Folgen

- Lokale Wissensbestände belegen nur noch den Platz für Extraktion, Index und Analysen.
- Ein Export bleibt ohne die ursprünglichen Dateien nutzbar; ein unvollständiger Export sagt das ausdrücklich.
- Eine weitere Speicherstrategie (Bedarfscache mit Quote) ist eine neue Implementierung von
  `SnapshotStorageStrategy`, kein Umbau von Suche oder Analyse.
