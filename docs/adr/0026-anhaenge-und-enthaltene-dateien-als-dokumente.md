# ADR 0026: Anhänge und enthaltene Dateien als eigene Dokumente

- Status: **Entwurf zur Abnahme durch Angelo** (Strang „Anhänge für Analyse und KI aufbereiten“, 13.11.2026).
  Schon entschieden (Angelo, 13.11.2026): „Anhänge sollen auch in die Analyse“; die Lösung gilt für alle Quellen
  („Dieses Programm ist nicht nur auf Confluence als Quelle beschränkt“); Mails, deren Anhänge nicht gelesen werden,
  sind „eine gravierende Lücke“. Alle übrigen Punkte, auch der Weg für die Outlook-Quelle, sind Vorschlag und gelten
  mit der Abnahme.
- Ergänzt [ADR 0023](0023-dateianhaenge-im-export.md) und hebt dessen Folge „Absätze und Fakten lesen Anhangstexte
  vorerst nicht“ auf. Berührt [ADR 0019](0019-begriffsindex-und-begriffsbeziehungen.md) (Begriffe),
  [ADR 0020](0020-semantische-absaetze.md) (Absätze) und
  [ADR 0024](0024-aussagenanalyse-und-globaler-knowledge-graph.md) (Aussagen). Bilder bleiben bei
  [ADR 0021](0021-bildanalyse.md).
- Umsetzung offen, in sechs Stufen (unten), jede in kleinen Pull Requests und erst auf Bestellung.
- Bezüge: #183, #328, #385; corenth #36 (Mail-Anhänge als Kindressourcen) und #42 (Extraktoren je Format vor Tika);
  Messungen vom 13.11.2026 mit Apache Tika 3.3.2 (unten); Gegenprüfung der Analyse durch GPT. Keine neue Bibliothek.

## Kontext

Der Core liest den Text von Dokument-Anhängen schon (ADR 0023): Port `DocumentText`, Adapter `TikaDocumentText` (Tika
3.3.2 über die kopierte `TikaResourceExtractor`; 20 MiB, 30 s, 1 Mio. Zeichen), Ablage `AssetText` je SHA-256,
Extraktion und Version. Der Text dient der Volltextsuche (je Anhang ein eigenes Indexdokument unter seiner Seite, #385)
und dem Export (`<Datei>.txt`, Block `attachments`). Drei Dinge fehlen:

- **Struktur.** Die Kopie liest mit `BodyContentHandler`, also reinen Text. Überschriften, Listen, Tabellen und
  Seitengrenzen gehen verloren, obwohl Tika sie liefert.
- **Enthaltene Dateien.** Eine Mail samt Anhängen, auch ein ZIP oder eine weitergeleitete Mail darin, wird ein einziger
  Text ohne Dateinamen und Grenzen. Defekte oder leere Anhänge fehlen ohne Hinweis, und weder Treffer noch Zitat können
  sagen, aus welcher Datei sie stammen.
- **Analysen.** Absätze, Begriffe, Embeddings und Aussagen lesen nur den Seitentext.

Dateien erreichen den Core auf drei Wegen: als Anhang einer Seite (`Assets`; eine Anhangsliste liefert bisher nur
Confluence), als Seite der Quelle `files` (`LocalFilesTextProjector`, das Original liegt im Core) und in den kopierten
Quellen SharePoint, Git und BetaView, die in der Kopie selbst Text bilden, sodass der Core nur den Lesetext bekommt.
Die Outlook-Quelle (`OutlookKnowledgeSource`) liest nur `getBody()` und keine Anhänge.

### Messungen (Tika 3.3.2, 13.11.2026)

| Frage | Ergebnis |
|---|---|
| Taugt Tikas Namenspfad als Kennung? | Nein. Zwei Anhänge `notiz.txt` in einer Mail bekommen beide `/notiz.txt`, aus `ordner/unter.txt` wird `/unter.txt`, eine weitergeleitete Mail ohne Dateinamen heißt `/embedded-1`. Tika warnt selbst davor (`TikaCoreProperties.EMBEDDED_RESOURCE_PATH`). `EMBEDDED_ID_PATH` (`/1`, `/1/3`) ist ein Zähler in Lesereihenfolge. |
| Gibt es Hash und Originalbytes je enthaltener Datei? | Ja, mit tika-core allein: `DigestingParser` mit `InputStreamDigester` liefert SHA-256 je Teil, ein eigener `EmbeddedDocumentExtractor` die Bytes, auch verschachtelt (ZIP in Mail, Mail in Mail). Beide Hashes stimmen überein; auch defekte Teile haben einen Hash. |
| Wie wirkt Tikas Zeichengrenze bei enthaltenen Dateien? | Als ein gemeinsames Budget. Mail mit 66 000 Zeichen und drei Anhängen zu je 81 000 Zeichen, Grenze 100 000: Mit Abbruch erscheinen 2 von 4 Teilen; ohne Abbruch alle 4, zwei davon mit 0 Zeichen und ohne Kennzeichen am Teil. |
| Was passiert mit einem stark gepackten Archiv (327 KB, darin 150 MiB Text) in einer Mail? | Heute bricht Tikas Schutz das Archiv nach rund 1 Mio. Zeichen ab („Suspected zip bomb“, über 100 zu 1). Der Fehler steht nur in Tikas Metadaten, der Core meldet `truncated=false`. Bekommt jede enthaltene Datei ihren eigenen Text, greift dieser Schutz nicht mehr: 157 Mio. Zeichen im Speicher, kein Abbruch. |
| Welche Fundstellen liefert Tika? | PDF-Seite, PowerPoint-Folie, Excel-Blattname und Spalte. Die Excel-Zeile geht verloren: Die Zellen B3 und D17 erscheinen als erste und zweite Zeile. Word: Überschriften und Tabellen, keine Seiten, Listen nur als Absatzformat. |
| Was meldet Tika als enthaltene Datei? | Auch Inline-Bilder: Ein Word-Dokument mit 15 Bildern ergibt 15 Teile (`INLINE`). Anhänge einer `.eml` sind `ATTACHMENT`, bei `.msg` fehlt die Angabe. |
| Was passiert bei defekten Anhängen? | Der Fehler steht am Teil (`EMBEDDED_EXCEPTION`), die übrigen Teile bleiben lesbar. |

## Entscheidung

1. **Das Dokument ist die Einheit, nie der zusammengemischte Text.** Ein Dokument ist eine Seite, ein Anhang einer Seite
   oder eine darin enthaltene Datei (Mail-Anhang, Archiveintrag, in jeder Tiefe). Jedes hat eigenen Text, eigene
   Treffer, eigene Analyseergebnisse und einen eigenen Stand. Es kennt sein Elterndokument und seine Seite und gilt mit
   den Rechten dieser Seite. Der Text eines Anhangs steht nie im Text der Seite, der Text einer enthaltenen Datei nie im
   Text ihres Behälters. So arbeitet der Volltextindex bereits (`PageTextIndex.AttachmentEntry`).
2. **Für alle Quellen derselbe Weg: Die Quelle liefert das Original, der Core liest.** Eine Datei kommt als Seite
   (Quelle `files`), als Anhang einer Seite (`HarvestSource#attachments`, `#attachmentFile`) oder als enthaltene Datei,
   die der Core auspackt. Suche, Analysen und Export unterscheiden diese Wege nicht. Quellen, die in der Kopie selbst
   Text bilden, stellt ein Patch im Enterprise AI Client um: zuerst Outlook (Punkt 9), SharePoint, Git und BetaView bei
   Bedarf. Bis dahin liefern sie wie bisher flachen Text.
3. **Blöcke führen, Markdown und reiner Text sind Ansichten.** Neben `DocumentText` entsteht ein zweiter Port für die
   strukturierte Extraktion (Arbeitsname `DocumentStructure`, Paket `application.assets`, eigene Typen des Core, keine
   Typen der Kopie). Sein Ergebnis `StructuredDocument` enthält Kopfdaten (Titel, bei Mails Betreff, Absender, Empfänger
   und Datum), Blöcke in Lesereihenfolge, das Verzeichnis der enthaltenen Dateien und den Status. `DocumentText` bleibt
   als schmaler Port für reinen Text und wird aus derselben Extraktion bedient: einmal lesen, zwei Ansichten.

   | Block | Felder |
   |---|---|
   | `Heading` | `level` 1 bis 6, `text` |
   | `Paragraph` | `text` |
   | `ListItem` | `ordered`, `level` ab 1, `text` |
   | `Table` | Zeilen mit Zellen (`row` und `column` ab 0, `text`, `header`) |
   | `Code` | `text`, optional `language` |

   Jeder Block trägt seine Position (`index`) und optional eine Fundstelle. `text` ist reiner Text ohne Auszeichnung.
   Verweisziele stehen getrennt am Block, damit die Markdown-Ansicht sie zeigt und die Analysen sie nicht als Text
   lesen. Die Hierarchie ergibt sich aus den typisierten Feldern (Überschriftenebene, Listenebene); ein Dokumentbaum
   entsteht nicht. Kopf- und Fußzeilen, Notizen und Kommentare bleiben Absätze, bis ein Extraktor sie verlässlich
   unterscheidet. Das kopierte Modell `deigma` (`ExtractedBlock` mit `index`, `kind`, `text` und freien `attributes`)
   reicht dafür nicht und wird nicht über frei erfundene Schlüssel erweitert.
4. **Fundstellen sind typisiert und optional.** Ein Feld wird nur gesetzt, wenn der Extraktor den Wert aus der Datei
   liest. Sonst bleibt es leer; gezählt oder geschätzt wird nie, weil eine erfundene Angabe schlimmer ist als eine
   fehlende. Zeichenpositionen gehören zur Ansicht (wie `PageText.Section`), nicht zur Extraktion.

   | Fundstelle | Feld | Aus Tika 3.3.2 |
   |---|---|---|
   | PDF-Seite | `page` | ja |
   | PowerPoint-Folie | `slide` | ja |
   | Excel-Blatt | `sheet` | ja |
   | Excel-Zelle | `cellRef` an der Zelle, etwa `D17` | nein (Spalte ja, Zeile nicht); erst mit einem Extraktor über POI |
   | Word-Seite | keines | nein, Word speichert keinen festen Seitenumbruch |
   | Abschnitt | abgeleitet aus den Überschriften davor | ja, wo es Überschriften gibt |

5. **Kennung, Anzeigename und Fundstelle sind getrennt.** Die Kennung einer enthaltenen Datei ist der SHA-256 ihrer
   Bytes; liegt derselbe Inhalt im selben äußeren Behälter mehrfach, zählt eine laufende Nummer in Lesereihenfolge. Ein
   Dokument ist damit benannt durch Quelle, Seite, Anhang (wenn es einer ist) und diese Teilkennung; für Seiten bleibt
   alles, wie es ist. Der Dateiname ist nur Anzeige: Er kann fehlen, doppelt sein oder Pfadzeichen enthalten und wird
   nie als Kennung oder Dateipfad benutzt. Tikas Zählerpfad wird als technische Position dieser einen Extraktion
   mitgeschrieben, ebenso Elterndokument und Tiefe. Der Hash statt des Zählers, weil er einen Wechsel der Tika-Version
   übersteht, das Wiederfinden prüfbar macht und gleichen Inhalt nur einmal lesen lässt (Ablage nach Hash wie
   `AssetText`). Eine Datei, deren Bytes sich nicht auspacken lassen, hat keine Kennung; sie steht nur mit Status im
   Verzeichnis.
6. **Das Original bleibt abrufbar, ohne zweite Kopie.** Das Original einer enthaltenen Datei liefert der Core, indem er
   den Behälter erneut öffnet und die Datei mit passendem Hash herausliest: aus der Ablage (Anhänge je SHA-256, Seiten
   der Quelle `files`) oder, wo der Core kein Original hält, über die Quelle. Eine eigene Kopie je enthaltener Datei
   wird nicht abgelegt. Ist der Behälter weder abgelegt noch lieferbar, bleiben Text und Ergebnisse, und der Abruf des
   Originals meldet das mit festem Code.
7. **Status und Grenzen sind Daten, kein Text.** Jedes Dokument und jeder Eintrag im Verzeichnis trägt `status` (`OK`,
   `PARTIAL`, `UNSUPPORTED`, `FAILED`, `EXCLUDED`), das Kennzeichen `truncated` und einen festen Grund, etwa
   `encrypted`, `corrupt`, `empty`, `timeout`, `too-large`, `too-deep`, `too-many-files`, `unpacked-too-large`,
   `media-type-excluded`. `EXCLUDED` heißt wie im Manifest (ADR 0023): bewusst nicht gelesen. Hinweise wie „Anhang nicht
   lesbar“ oder Dateinamen als Überschrift stehen nie im Inhalt eines Dokuments, sonst hielte eine Analyse sie für eine
   Aussage der Quelle. Ansichten für Menschen dürfen den Status zeigen. Meldungen und Protokoll nennen weder Dateinamen
   noch Inhalte.
8. **Grenzen beim Auspacken gelten ab Stufe 2**, nicht erst mit Tika 4. Jede enthaltene Datei hat ihr eigenes Budget,
   der äußere Behälter ein Gesamtbudget. Erreicht ein Teil seine Grenze, werden die übrigen trotzdem gelesen; Tikas
   gemeinsame Zeichengrenze wird dafür nicht benutzt. Vorgeschlagene Standardwerte, einstellbar (beide Loader,
   `ConfigurationParityTest`):

   | Grenze | Standard |
   |---|---|
   | Größe je Datei für die Textextraktion | 20 MiB (wie heute) |
   | Zeichen je Dokument | 1 Mio. (wie heute) |
   | Verschachtelungstiefe | 5 |
   | Enthaltene Dateien je äußerem Behälter | 1 000 |
   | Ausgepackte Bytes je äußerem Behälter | 200 MiB |
   | Verhältnis ausgepackt zu gepackt je Datei | 100 zu 1, geprüft ab 1 MiB |
   | Laufzeit je Dokument und je äußerem Behälter | 30 s und 5 min |

   Jede erreichte Grenze ist ein Status nach Punkt 7. Die Texterkennung (OCR) wird ausdrücklich abgeschaltet; ihr Modul
   liegt im Klassenpfad. Gelesen wird weiter im Prozess des Dienstes; das Lesen in einem eigenen Prozess kommt erst mit
   Tika 4 (Stufe 6).
9. **Die Outlook-Quelle liefert ihre Anhänge.** Per Patch im Enterprise AI Client, nie in der Kopie: Die Quelle bekommt
   die Fähigkeiten `AttachmentListSource` und `PageViewSource#loadAttachmentFile`. Der Core bildet beide schon allgemein
   ab (`KnowledgeSourceHarvestSource`); ein Anhang aus einem Postfach läuft danach durch dieselbe Ablage und Extraktion
   wie ein Confluence-Anhang. Die Adresse eines Anhangs nennt Postfachdatei, Nachricht und Anhangsnummer. Die Bytes
   holt java-libpst (`PSTAttachment#getFileInputStream`), Tika liest nur. Danach wird die Kopie mit
   `sync-from-upstream.sh` abgeglichen.
10. **Analysen arbeiten je Dokument.** Eingabe ist der Lesetext des Dokuments (`PageText`, gebildet aus seinen Blöcken
    mit eigenem Schema, ohne Kopfdaten und ohne Status). Absätze (ADR 0020), Begriffe und Akronyme (ADR 0019),
    Embeddings und Navigation laufen für Anhänge und enthaltene Dateien wie für Seiten; Aussagen (ADR 0024) weiter nur
    auf ausdrücklichen Auftrag. Ergebnisse hängen am Dokument, nicht an der Seite: Die Ablagen bekommen neben `PageKey`
    die Teilkennung, ebenso die Kennungen, die heute Quelle und Seite tragen (Passage-ID, Knoten im Graphen). Für
    Seiten ändert sich nichts. Ansichten zeigen die Herkunft und können nach Seite und Anhang filtern; ihre Gestaltung
    bestellt Angelo bei den Oberflächen-Strängen. An einen KI-Dienst gehen Anhänge nur aus freigegebenen Quellen (ADR
    0011, ADR 0023 Punkt 5). Abschaltbar über eine Einstellung, Standard an.
11. **Vier Versionen, jede macht nur das Ihre alt.**

    | Version | Träger | Ändert sie sich, dann |
    |---|---|---|
    | Extraktor (`tika`, `3.3-1`) | `DocumentText#id` und `#version`, schon heute an `AssetText` | wird das Original neu gelesen |
    | Strukturmodell (`structured-document/v1`) | neu, an der abgelegten Extraktion | wird das Original neu gelesen |
    | Ansicht (Lesetext, Markdown) | `PageText.schema`, geht in `inputSha256` ein | veralten die Analysen der betroffenen Dokumente |
    | Analyse | `EnrichmentProcessor#version`, Analysator (ADR 0024) | veraltet nur dieser Prozessor |

    Jede Stufe merkt sich den Hash ihrer Eingabe. Liefert eine neue Tika-Version dieselben Blöcke, bleibt der Lesetext
    gleich, und keine Analyse veraltet. Der bisherige Stand gilt, bis der neue erfolgreich gelesen ist (wie ⟲ in
    [ADR 0025](0025-fortsetzbares-einlesen.md)): Ablage und Export geben bis dahin die letzte vorhandene Extraktion
    derselben Bytes als veraltet aus, der Indexeintrag wird erst danach ersetzt, und ein gescheitertes Neulesen lässt
    ihn stehen. Heute holt erst die nächste Ernte einer Seite den Text nach (`Assets#backfillText`), und der Export
    findet bis dahin keinen. Das Neulesen abgelegter Originale wird deshalb ein eigener, anhaltbarer Auftrag ohne
    Download.
12. **Suche und Export folgen dem Dokument.** Der Volltextindex führt je Dokument einen Eintrag unter seiner Seite. Ein
    Treffer bleibt ein Treffer der Seite und nennt Anhang und enthaltene Datei. Das JSON-Paket bekommt je Dokument
    `.md` neben `.txt` sowie enthaltene Dateien als eigene Einträge mit Verweis auf ihren Behälter, Status und Grund.
    Dateien im Paket heißen nach der Kennung, der Dateiname steht nur im Verzeichnis. Das Format steigt auf
    `attachments/v2`, `analysis-export/v1` bleibt additiv.
13. **Was zum Dokument selbst gehört, wird kein eigener Eintrag.** Inline-Bilder, Vorschaubilder und Makros eines
    Office-Dokuments sowie namenlose Textteile einer Mail gehören zu ihrem Dokument. Eigene Dokumente werden die
    übrigen Dateien, sofern ihr am Inhalt erkannter Typ zugelassen ist (`export.attachments.media-types`); Archive und
    Mails in einem Behälter werden wie heute geöffnet. Bilder als Anhang einer Mail stehen mit Name, Typ und Hash im
    Verzeichnis und bleiben bei ADR 0021 (Beschreibung nur auf Abruf).

## Stufen

| Stufe | Inhalt | Braucht |
|---|---|---|
| 1 | Vertrag: Typen, Port, Status und Kennung; Testkorpus im Repository aus selbst erzeugten Dateien. Kein geändertes Verhalten. | – |
| 2 | Adapter auf Tika 3.3.2: Blöcke und Fundstellen aus Tikas XHTML, Verzeichnis mit Hash, Grenzen und Status, OCR aus, `DocumentText` aus derselben Extraktion, neue Extraktor-Version, Neulesen als Auftrag. | 1 |
| 3 | Enthaltene Dateien als Dokumente in Ablage, Volltextindex und Export, für Seitenanhänge und die Quelle `files`. Dazu der Outlook-Patch im Enterprise AI Client, der parallel zu 1 und 2 vorbereitet werden kann. | 2 |
| 4 | Analysen je Dokument (Punkt 10). | 3 |
| 5 | Extraktoren je Format vor Tika (Word-Listen und Excel-Zellen über POI, HTML über jsoup), nur bei nachgewiesenem Gewinn am Testkorpus. | 2 |
| 6 | Tika in ein eigenes Modul und Umstieg auf Tika 4, als getrennte technische Umstellung. Braucht einen neuen Zuschnitt im Enterprise AI Client, weil `enterprise-ai-sources` Tika mitbringt. | – |

## Abnahmekriterien

1. **Suche.** Feste Suchanfragen über den Testkorpus finden vor und nach der Umstellung dieselben Seiten.
2. **Fundstellen.** Jede gesetzte Fundstelle ist gegen das Original geprüft (Text der PDF-Seite, Blattname,
   Foliennummer). Wo Tika keine liefert (Excel-Zeile, Word-Seite), ist das Feld leer.
3. **Kennung.** Zwei Anhänge mit gleichem Namen sind zwei Dokumente. Derselbe Inhalt zweimal ergibt zwei Einträge und
   eine Extraktion. Ein Dateiname mit Pfadzeichen wird nie ein Pfad.
4. **Grenzen.** Das gepackte Archiv aus der Messung endet innerhalb der Grenzen mit Status, der Speicher bleibt
   begrenzt, und die übrigen Anhänge derselben Mail werden gelesen. Ebenso ein Archiv mit 600 Einträgen und eines mit
   fünf Ebenen.
5. **Status.** Defekte, verschlüsselte und leere Anhänge tragen ihren Status am Teil. Kein Analyse-Eingang enthält
   erzeugten Text.
6. **Versionen.** Eine neue Extraktor-Version mit gleichen Blöcken lässt die Analysen aktuell. Eine geänderte Ansicht
   macht genau die betroffenen Dokumente alt. Der alte Text bleibt lieferbar, bis der neue gelesen ist.
7. **Original.** Eine enthaltene Datei ist aus dem abgelegten Behälter mit passendem Hash abrufbar.
8. **Mails.** Tests im Core decken Mails mit Anhängen ab (ZIP in Mail, Mail in Mail, Outlook-Mail in Mail, defekte
   Anhänge, doppelte Namen). Heute deckt kein Test im Core Mails ab.

## Folgen

- Ablage und Index wachsen. Embeddings für Anhänge kosten Aufrufe beim KI-Dienst, nur für freigegebene Quellen.
- Nach Stufe 2 werden alle abgelegten Originale einmal neu gelesen, ohne Download.
- Der Text eines Behälters wird kürzer, weil seine Dateien eigene Dokumente sind. Ein Treffer wandert von der Mail zu
  ihrem Anhang; die gefundene Seite bleibt dieselbe.
- PDFs bleiben ohne Überschriften und Listen, weil Tika dort keine Struktur liefert. Das ist eine benannte
  Qualitätsgrenze und kein Teil dieser Entscheidung.
- Gewollte Abweichung vom Enterprise AI Client (SH+), in PARITY.md durch Strang S nachzutragen.

## Verworfen

- **Anhang als künstliche Seite im Cache.** Die Inventur sähe sie als gelöscht, und Seitenzahlen, Baum und `pages.json`
  wären verfälscht.
- **Tikas Namenspfad oder Zähler als Kennung** (Messung, Punkt 5).
- **Eigene Bytekopie je enthaltener Datei** (Punkt 6).
- **Markdown als führendes Format.** Tabellen, Fundstellen und Status passen nicht verlustfrei hinein (Punkt 3).
- **Neue Bibliotheken** für PDF-Struktur oder Markdown (Docling, MarkItDown, OpenDataLoader PDF).

## Offen

- Welchen Medientyp das Confluence der HaVdB für `.msg` meldet. Bei `application/octet-stream` schließt die
  Standardliste die Mail aus.
- Ob Archive in die Standardliste von `export.attachments.media-types` kommen. Heute bleibt ein ZIP an einer Seite
  draußen, ein ZIP in einer Mail wird gelesen.
- Angehängte Outlook-Elemente ohne Dateibytes (`ATTACHMENT_METHOD_EMBEDDED`): Ihre Form legt der Patch fest.
- Die Standardwerte der Grenzen aus Punkt 8.
- Die Unterstützung für Tika 3 endet laut Apache je nach Stelle sechs Monate nach Tika 4.0.0 (erschienen am
  18.08.2026, also im Februar 2027) oder im März 2027. Stufe 6 sollte vorher erledigt sein.
