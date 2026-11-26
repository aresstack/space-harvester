# ADR 0039: Austauschbare Originalspeicherung und portable Wissensbestände

- Status: **Angenommen, auf main umgesetzt** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in #1437 samt
  Kommentaren). Die Abnahme mit echten Nutzerdateien und OneNote steht aus, #1437 ist deshalb offen. Die Antworten auf die
  Rückfragen vom 26.11.2026 (14:21 und 14:41) sind eingearbeitet.
- Quellen: #1437 (Zielarchitektur, Architekturleitplanken, sechs Fortschrittskommentare), PRs #1439, #1442, #1444,
  #1445, #1450, #1452, #1453, #1463, #1472; Angelos Zusammenfassung vom 26.11.2026 (Abschnitte 7 bis 9) und Klärung vom 26.11.2026, 14:12 (Punkt 4).
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

   **Die Strategie ergibt sich vorerst aus der Art der Quelle, ohne eigene Einstellung für den Benutzer.** Die
   Architektur lässt verschiedene Strategien je Quelle zu.

   | Quelle | Strategie |
   | --- | --- |
   | Lokale Dateiquelle | Originalreferenz |
   | Künftige Netzlaufwerkquelle | Originalcache |
   | SharePoint, BSCW | Originalcache |
   | Confluence | bestehender Snapshot |

   **Die Strategie wird nie selbsttätig anhand des Pfades umgeschaltet.** Ein Netzlaufwerk ist nicht in jeder
   Umgebung verlässlich zu erkennen, und eine stille Umschaltung könnte unbemerkt große Datenmengen kopieren. Eine
   lokale Dateiquelle auf einem Netzwerkpfad behält die Originalreferenz und bekommt die Warnung aus Punkt 11.
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
   - **Auf Dauer gilt dieselbe Bedeutung für alle Quellen.** Heute gilt sie für lokale Dateien.
   - **Im Baum steht der Volltextstatus.** „Semantisch erschlossen“ und „fachlich analysiert“
     erscheinen im Tooltip, in der Detailansicht und in der Quellenverwaltung, nicht als weitere Zeichen im Baum.
   - **Rechnung für das Ordnerzeichen:** Geeignet sind alle Dateien außer „nicht anwendbar“ und
     „gesperrt“. Grün nur, wenn jede geeignete Datei indexiert und aktuell ist. Gekürzt, veraltet, fehlgeschlagen
     und noch nicht indexiert verhindern Grün. Ein Unterordner, der noch nicht inventarisiert ist, verhindert Grün,
     weil sein Inhalt unbekannt ist. Ein Ordner ohne eine geeignete Datei bekommt ein neutrales Zeichen.
   - **Gesperrte Dateien** zählen nicht in den Nenner und werden eigens ausgewiesen („3 gesperrt“).
   - **Dateien ohne Text** (gescanntes PDF, Bild): Ist für die Quelle ein geeignetes Verfahren zur Texterschließung
     eingeschaltet, zählen sie als noch nicht indexiert. Sonst sind sie neutral („ohne Text“). Eine reine
     Bildklassifikation ist kein solches Verfahren.
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
    werden nicht automatisch gelöscht. Es gibt weder eine Migration noch eine Aufräumfunktion für alte Kopien
    (beschlossen): Eine neu angelegte Quelle nutzt die neue Ablage, eine entfernte Quelle nimmt ihre alten Daten
    mit.
11. **Netzlaufwerke sind kein lokaler Ordner.** Sie werden nicht stillschweigend auf die kopielose Semantik
    umgestellt. SharePoint ist eine eigene, native Quelle (#1438,
    [ADR 0042](0042-native-sharepoint-quelle.md)) und nutzt zunächst den Originalcache. Eine generische
    Netzlaufwerkquelle ist zurückgestellt. Beim Einrichten einer lokalen Quelle warnt die Oberfläche vor einem
    Netzwerkpfad (UNC, WebDAV, SMB), soweit er sich verlässlich erkennen lässt: Die Originale werden nicht lokal
    gespeichert, und solange der Pfad nicht erreichbar ist, gibt es weder Originalansicht noch regulären
    Originalexport. Vorerst genügt diese Warnung.
12. **Der Lesetext hat keine Gesamtgrenze** (beschlossen am 26.11.2026, 14:41). Die feste Grenze von 1 000 000 Zeichen
    entfällt; sie wird nicht nur einstellbar. Die abschnittsweise Ablage und Indexierung trägt auch die
    Leseansicht:
    - Alle Abschnitte eines Dokuments bleiben erreichbar und werden nach Bedarf dargestellt.
    - Die Oberfläche muss nicht das ganze Dokument zugleich im Speicher halten. Abschnittsweises Laden,
      Virtualisierung oder Paginierung lösen das Ressourcenproblem, ohne Inhalte vorzuenthalten.
    - Fundstellennavigation, Textsuche, Markierungen und Analysebezüge arbeiten über Abschnittsgrenzen hinweg.

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
| Warnung vor erkennbaren Netzwerkpfaden beim Einrichten einer lokalen Quelle | **fehlt**, vorgesehen (F39-5) |
| Ordnerzeichen nach der Rechnung aus Punkt 8 | **weicht ab**, Korrektur beauftragt |
| Bestandszeichen mit derselben Bedeutung für alle Quellen | nur lokale Dateien |
| Leseansicht ohne Gesamtgrenze, abschnittsweise (Punkt 12) | **weicht ab:** feste Grenze von 1 000 000 Zeichen an vier Stellen |
| Abnahme mit echten Nutzerdateien und OneNote | offen |

## Bekannte Einschränkungen

- Die Hashprüfung ist kein atomarer Schnappschuss des Dateisystems. Eine Änderung bei gleicher Größe und
  unverändertem Zeitstempel erkennt die Inventur nicht; erst der Export bemerkt sie am Hash.
- Der Lesetext ist heute auf 1 Mio. Zeichen begrenzt, fest im Code; nach Punkt 12 entfällt das. Der Reranker sieht bei mehrteiligen Dokumenten nur einen Abschnitt.
- Lokale Embeddings sind nicht gegen einen echten KI-Dienst abgenommen.

## Widersprüche und Auffälligkeiten

- **Die Strategie fragt den Quellentyp ab.** `LocalOriginalStorage.supportsSource` prüft `"files".equals(type)`
  zusätzlich zur Fähigkeit `readsOriginalInPlace()`. Die Entscheidung „von der Quelle entkoppelt“ ist damit nur über
  die Fähigkeit erfüllt, nicht vollständig.
- **Der Schalter ist global, nicht je Quelle.** `localOriginalReferences` gilt für den ganzen Harvester. Eine
  lokale Quelle mit Kopie lässt sich damit nicht einzeln einstellen. Das ist
  nach Punkt 1 gewollt: Eine lokale Quelle auf einem Netzwerkpfad behält die Referenzstrategie, bis es eine eigene
  Netzlaufwerkquelle gibt.
- **Der Status deckt weniger ab als das Zielbild.** `IndexState` kennt `INDEXED`, `PARTIAL`, `OUTDATED`,
  `NOT_INDEXED`, `NO_TEXT`, `FAILED`, `LOCKED` und gilt für lokale Dateien. „Semantisch erschlossen“ und „fachlich
  analysiert“ sind dort keine eigenen Zustände, und andere Quellen zeigen weiter den Cachezustand.
- **Die Ordneraggregation weicht von Punkt 8 ab.** Nach der Klärung vom 26.11.2026 verhindert heute eine nicht
  indexierbare Datei das Grün des Ordners. `IndexState.NO_TEXT` fasst zudem zwei Fälle zusammen: nicht unterstützt
  oder ausgeschlossen (nicht anwendbar) und „extrahiert, aber ohne Text“ (etwa ein gescanntes PDF). Die Korrektur
  ist beauftragt.
- **Die Netzwerkpfad-Warnung** aus den Leitplanken von #1437 habe ich im Code nicht gefunden. Sie ist vorgesehen
  (Punkt 11).

## Geklärt am 26.11.2026

Alle Punkte sind entschieden. Die Antworten stammen vom 26.11.2026 (14:21, 14:38 und 14:41); Angelo hat sie aus
dem ursprünglichen Chat übermittelt und am 26.11.2026, 14:41, alle Empfehlungen bestätigt.

| Frage | Antwort | Stand |
| --- | --- | --- |
| F39-1: Neue Bestandszeichen für alle Quellen | Ja, auf Dauer dieselbe Bedeutung überall | **Beschlossen**, in Punkt 8 |
| F39-2: Weitere Zustände im Baum | Nein, im Baum genügt der Volltextstatus; der Rest steht im Tooltip, in der Detailansicht und in der Quellenverwaltung | **Beschlossen**, in Punkt 8 |
| F39-3: Speicherstrategie je Quelle wählbar | Die Architektur lässt es zu; vorerst ergibt sie sich aus der Art der Quelle | **Beschlossen**, in Punkt 1 |
| F39-4: Aufräumfunktion für alte Originalkopien | Nein, weder Migration noch Aufräumen | **Beschlossen**, in Punkt 10 |
| F39-5: Warnung vor Netzwerkpfaden | Ja, soweit verlässlich erkennbar | **Vorgesehen**, in Punkt 11 |
| F39-6: Rechnung für das Ordnerzeichen | Wie in Punkt 8 beschrieben | **Beschlossen**, in Punkt 8 |
| F39-7: Dateien ohne Text | Mit eingeschalteter Texterschließung „noch nicht indexiert“, sonst neutral | **Beschlossen**, in Punkt 8 |
| F39-8: Gesperrte Dateien | Nicht im Nenner, eigens ausgewiesen | **Beschlossen**, in Punkt 8 |
| F39-9: Strategie am erkannten Pfad festmachen | Nein. Vorerst genügt die Warnung; keine selbsttätige Umschaltung; die Netzlaufwerkquelle bleibt zurückgestellt | **Beschlossen**, in Punkt 1 und 11 |
| F39-10: Lesetext auf 1 000 000 Zeichen begrenzt | Die Grenze entfällt; die Leseansicht arbeitet abschnittsweise | **Beschlossen**, in Punkt 12 |

## Grenzen und Limits

Nach ADR 0036, Punkt 12. Stand main `69835ca8`. Dieser Bereich hat die meisten **Größenlimits**; nach Angelos
Vorgabe steht jedes davon zur Prüfung, ob es entfallen oder durch Strom und Abschnitte ersetzt werden kann.

| Grenze | Standard | Einstellung | In der Oberfläche |
| --- | --- | --- | --- |
| Größter Payload einer Seite im Cache (**Größenlimit**) | 20 MiB; größere werden abgewiesen, nie gekürzt | `space-harvester.cache.max-payload-size` (Server) | nein; die Desktop-App liest die Einstellung nicht |
| Textdateien, ab denen nur der Textauszug abgelegt wird (**Größenlimit**) | 200 MiB, `0` = nie | `space-harvester.cache.max-text-file-size` | ja; in `docs/CONFIGURATION.md` nicht gefunden |
| Lokale Dateien: größte Datei (**Größenlimit**) | 50 MB, 1 bis 2000 | Quellenoption `maxFileMegabytes` | ja, Quellenformular |
| Lesetext je Dokument (**Größenlimit**) | 1 000 000 Zeichen | **fest im Code**, an vier Stellen (`LucenePageTextIndex.MAX_TEXT_CHARS`, `DocumentExtractions.DEFAULT_MAX_TEXT_CHARS`, `TikaDocumentText.DEFAULT_MAX_CHARS`, `OneNotePreviewReader.MAX_CHARS`) | nein |
| Vorschau in der Originalreferenz (**Größenlimit**) | 8192 Zeichen | **fest im Code** | nein |
| Gelesener Dateianfang zur Erkennung (**Größenlimit**) | 8192 Bytes | **fest im Code** (`PREFIX_BYTES`) | nein |
| Extraktion: größte Datei, größter Text (**Größenlimit**) | `0` = ohne Grenze | `space-harvester.assets.extraction.max-file-size`, `max-chars` | nicht geprüft |
| Extraktion: Schachtelungstiefe, enthaltene Dateien | 10, 10 000 | `…extraction.max-depth`, `max-embedded-files` | nicht geprüft |
| Extraktion: Zeit ohne Fortschritt, Gesamtzeit, Parallelität | 30 s, 30 Minuten, 2 | `…extraction.*` | nicht geprüft |
| Extraktion: Speicherschranke je Medientyp (**Größenlimit**) | `*`: 20 MB, gängige Formate `0` | `…extraction.memory-limits` | nicht geprüft |
| Anhänge: größte Datei, Export von Anhängen (**Größenlimit**) | `0` = ohne Grenze | `space-harvester.assets.max-size`, `export.attachments.max-size` | nicht geprüft |
| Export: Stapelgröße | 100 | **fest im Code** (`Exports.BATCH_SIZE`) | nein |

„Nicht geprüft“ heißt: Die Einstellung ist dokumentiert und wird gelesen; ob sie im Einstellungsdialog der
Desktop-App steht, habe ich nicht für jede einzeln nachgesehen.

## Folgen

- Lokale Wissensbestände belegen nur noch den Platz für Extraktion, Index und Analysen.
- Ein Export bleibt ohne die ursprünglichen Dateien nutzbar; ein unvollständiger Export sagt das ausdrücklich.
- Eine weitere Speicherstrategie (Bedarfscache mit Quote) ist eine neue Implementierung von
  `SnapshotStorageStrategy`, kein Umbau von Suche oder Analyse.
