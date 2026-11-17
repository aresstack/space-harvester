# ADR 0026: Anhänge und enthaltene Dateien als eigene Dokumente

- Status: **Angenommen** (Angelo, 13.11.2026, PR #696). Stufen 1 bis 5 umgesetzt, siehe Nachtrag. Stufe 6 nicht
  bestellt. Vor der Abnahme schon entschieden (Angelo, 13.11.2026): „Anhänge sollen auch in die Analyse“; die
  Lösung gilt für alle Quellen („Dieses Programm ist nicht nur auf Confluence als Quelle beschränkt“); Mails, deren Anhänge nicht gelesen werden,
  sind „eine gravierende Lücke“; Anhänge sollen nicht fehlen, weil sie zu groß sind (für den Download seit dem
  11.11.2026, ADR 0023). Alle übrigen Punkte, auch der Weg für die Outlook-Quelle, sind Vorschlag und gelten mit der
  Abnahme.
- Ergänzt [ADR 0023](0023-dateianhaenge-im-export.md) und hebt zwei seiner Aussagen auf: „Absätze und Fakten lesen
  Anhangstexte vorerst nicht“ und, aus dem Nachtrag, „Text gibt es weiter nur bis 20 MiB“. Berührt
  [ADR 0019](0019-begriffsindex-und-begriffsbeziehungen.md) (Begriffe), [ADR 0020](0020-semantische-absaetze.md)
  (Absätze) und [ADR 0024](0024-aussagenanalyse-und-globaler-knowledge-graph.md) (Aussagen). Bilder bleiben bei
  [ADR 0021](0021-bildanalyse.md).
- Umsetzung in sechs Stufen (unten), jede in kleinen Pull Requests und erst auf Bestellung; Stand im Nachtrag.
- Bezüge: #183, #328, #385; corenth #36 (Mail-Anhänge als Kindressourcen) und #42 (Extraktoren je Format vor
  Tika); Messungen vom 13.11.2026 mit Apache Tika 3.3.2 (unten); Gegenprüfung der Analyse und dieses Entwurfs durch
  GPT. Keine neue Bibliothek.

## Kontext

Der Core liest den Text von Dokument-Anhängen schon (ADR 0023): Port `DocumentText`, Adapter `TikaDocumentText` (Tika
3.3.2 über die kopierte `TikaResourceExtractor`), Ablage `AssetText` je SHA-256, Extraktion und Version. Der Text dient
der Volltextsuche (je Anhang ein eigenes Indexdokument unter seiner Seite, #385) und dem Export (`<Datei>.txt`, Block
`attachments`). Vier Dinge fehlen:

- **Struktur.** Die Kopie liest mit `BodyContentHandler`, also reinen Text. Überschriften, Listen, Tabellen und
  Seitengrenzen gehen verloren, obwohl Tika sie liefert.
- **Enthaltene Dateien.** Eine Mail samt Anhängen, auch ein ZIP oder eine weitergeleitete Mail darin, wird ein einziger
  Text ohne Dateinamen und Grenzen. Defekte oder leere Anhänge fehlen ohne Hinweis, und weder Treffer noch Zitat können
  sagen, aus welcher Datei sie stammen.
- **Analysen.** Absätze, Begriffe, Embeddings und Aussagen lesen nur den Seitentext.
- **Große Dateien.** Beschafft und abgelegt wird ohne Größengrenze (ADR 0023, Nachtrag). Text gibt es aber nur bis
  20 MiB, und er endet nach 1 Mio. Zeichen, weil der heutige Weg die ganze Datei als Byte-Array und den ganzen Text
  als eine Zeichenkette hält (`TikaDocumentText`, `Assets#IN_MEMORY_BYTES`). Der Export nennt dafür nur
  `text-unavailable`; dass ein Text gekürzt wurde, steht dort nicht.

Dateien erreichen den Core auf drei Wegen: als Anhang einer Seite (`Assets`; eine Anhangsliste liefert bisher nur
Confluence), als Seite der Quelle `files` (`LocalFilesTextProjector`, das Original liegt im Core) und in den kopierten
Quellen SharePoint, Git und BetaView, die in der Kopie selbst Text bilden, sodass der Core nur den Lesetext bekommt.
Die Outlook-Quelle (`OutlookKnowledgeSource`) liest nur `getBody()` und keine Anhänge.

### Messungen (Tika 3.3.2, 13.11.2026)

| Frage | Ergebnis |
|---|---|
| Taugt Tikas Namenspfad als Kennung? | Nein. Zwei Anhänge `notiz.txt` in einer Mail bekommen beide `/notiz.txt`, aus `ordner/unter.txt` wird `/unter.txt`, eine weitergeleitete Mail ohne Dateinamen heißt `/embedded-1`. Tika warnt selbst davor (`TikaCoreProperties.EMBEDDED_RESOURCE_PATH`). `EMBEDDED_ID_PATH` (`/1`, `/1/3`) ist ein Zähler in Lesereihenfolge. |
| Gibt es Hash und Originalbytes je enthaltener Datei? | Ja, mit tika-core allein: `DigestingParser` mit `InputStreamDigester` liefert SHA-256 je Teil, ein eigener `EmbeddedDocumentExtractor` die Bytes, auch verschachtelt (ZIP in Mail, Mail in Mail). Beide Hashes stimmen überein; auch defekte Teile haben einen Hash. |
| Bekommt jede enthaltene Datei einen eigenen Teil? | Nein. Einen verschlüsselten Eintrag in einem ZIP übergibt Tika nicht als Datei; am Behälter steht nur eine Ausnahme. |
| Wie wirkt Tikas Zeichengrenze bei enthaltenen Dateien? | Als ein gemeinsames Budget. Mail mit 66 000 Zeichen und drei Anhängen zu je 81 000 Zeichen, Grenze 100 000: Mit Abbruch erscheinen 2 von 4 Teilen; ohne Abbruch alle 4, zwei davon mit 0 Zeichen und ohne Kennzeichen am Teil. |
| Was passiert mit einem stark gepackten Archiv (327 KB, darin 150 MiB Text) in einer Mail? | Heute bricht Tikas Schutz das Archiv nach rund 1 Mio. Zeichen ab („Suspected zip bomb“, über 100 zu 1). Der Fehler steht nur in Tikas Metadaten, der Core meldet `truncated=false`. Bekommt jede enthaltene Datei ihren eigenen Text, greift dieser Schutz nicht mehr: 157 Mio. Zeichen im Speicher, kein Abbruch. |
| Lässt sich eine Grenze schon beim Auspacken durchsetzen? | Ja. Schreibt ein eigener `EmbeddedDocumentExtractor` jede enthaltene Datei zählend in eine temporäre Datei, endet dasselbe Archiv bei einer Grenze von 20 MiB nach 20 MiB und einem Puffer von 64 KiB; der Heap bleibt unter 64 MiB. |
| Braucht eine große Datei viel Speicher? | Nicht, wenn Tika aus der Datei liest und der Text fortlaufend abfließt. Mit 64 MiB Heap vollständig gelesen: PDF mit 309 MiB (300 Seiten mit Bildern) in 0,6 s, PDF mit 4 000 Seiten und 12,1 Mio. Zeichen in 4,5 s, Word mit 5,9 Mio. Zeichen in 0,5 s, Excel mit 1 Mio. Zeilen und 141,6 Mio. Zeichen in 21 s. Über ein Byte-Array, den heutigen Weg, scheitert dasselbe PDF mit 309 MiB bei 256 MiB Heap; mit 2 GiB Heap belegt es in der Spitze 827 MiB. |
| Und ein großer Anhang in einer Mail? | Tikas Mail-Parser kopiert jeden Anhang der ersten Ebene in ein Byte-Array (`MailContentHandler#body`): Eine Mail mit dem PDF von 309 MiB scheitert noch bei 1 GiB Heap. Reicht der Parser den Strom durch (`RFC822Parser#setExtractAllAlternatives`) und geht der Anhang in eine temporäre Datei, genügen 64 MiB. |
| Welche Fundstellen liefert Tika? | PDF-Seite, PowerPoint-Folie, Excel-Blattname und Spalte. Die Excel-Zeile geht verloren: Die Zellen B3 und D17 erscheinen als erste und zweite Zeile. Word: Überschriften und Tabellen, keine Seiten, Listen nur als Absatzformat. |
| Was meldet Tika als enthaltene Datei? | Auch Inline-Bilder: Ein Word-Dokument mit 15 Bildern ergibt 15 Teile (`INLINE`). Anhänge einer `.eml` sind `ATTACHMENT`, bei `.msg` fehlt die Angabe. |
| Was passiert bei defekten Anhängen? | Der Fehler steht am Teil (`EMBEDDED_EXCEPTION`), die übrigen Teile bleiben lesbar. |

Nicht gemessen sind alte Office-Formate (`.doc`, `.xls`, `.ppt`), PowerPoint, Outlook-`.msg` und `.pst` in großen
Dateien sowie verschlüsselte und gescannte Dokumente. Die großen Prüfdateien sind selbst erzeugt.

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
   unterscheidet. Blöcke kommen als Strom, nicht als Liste im Speicher; ein übergroßer Block, etwa eine Tabelle mit
   1 Mio. Zeilen, wird an Zeilen geteilt (Punkt 8). Das kopierte Modell `deigma` (`ExtractedBlock` mit `index`, `kind`,
   `text` und freien `attributes`) reicht dafür nicht und wird nicht über frei erfundene Schlüssel erweitert.
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

5. **Kennung, Inhalts-Hash, Anzeigename und Fundstelle sind getrennt.** Jede Datei, die der Extraktor aus einem
   Behälter übergeben bekommt, wird ein Eintrag mit eigener Dokumentkennung, auch wenn sie defekt, verschlüsselt, leer,
   zu tief verschachtelt oder ausgeschlossen ist. Nur so lässt sich ein Fehlschlag später wiederfinden, gezielt neu
   lesen und im Status fortschreiben.

   - **Dokumentkennung.** Quelle, Seite, Anhangskennung der Quelle (wenn es ein Anhang ist) und für enthaltene Dateien
     eine Teilkennung innerhalb des äußeren Behälters. Ein Anhang einer Seite behält die Kennung, die seine Quelle
     vergibt, auch wenn Download oder Extraktion scheitern. Für Seiten bleibt alles, wie es ist.
   - **Teilkennung.** Sind die Bytes einer enthaltenen Datei vollständig gelesen, ist es ihr SHA-256 samt laufender
     Nummer unter inhaltsgleichen Einträgen desselben äußeren Behälters. Sonst (Abbruch an einer Grenze, Lesefehler
     mitten in der Datei) ist es ihre Position in Lesereihenfolge (Zählerpfad wie `/1/3`), mit eigenem Präfix und gültig
     für genau diesen Stand des Behälters (dessen SHA-256).
   - **Inhalts-Hash.** Ein eigenes Feld am Eintrag. Er darf fehlen, die Kennung nie.
   - **Anzeigename.** Nur Anzeige: Er kann fehlen, doppelt sein oder Pfadzeichen enthalten und wird nie Kennung oder
     Dateipfad.
   - **Position, Elterndokument und Tiefe** stehen an jedem Eintrag, auch an einem mit Hash.

   Der Hash geht vor die Position, weil er einen Wechsel der Tika-Version und einen neuen Stand des Behälters
   übersteht und gleichen Inhalt nur einmal lesen lässt (Ablage nach Hash wie `AssetText`). Positionen verschieben
   sich schon, wenn vorn ein Anhang dazukommt. Ändert sich die Reihenfolge, bleiben die inhaltsbasierten Kennungen.
   Unter inhaltsgleichen Einträgen kann die laufende Nummer wandern; dabei tauschen nur Anzeigename und Position, Text
   und Ergebnisse sind gleich. Eine Position gilt nie über den Stand des Behälters hinaus. Das Verzeichnis eines
   Behälters gehört zu einem Stand und einer Extraktion und wird beim Neulesen als Ganzes ersetzt (Punkt 11). Ein
   früher gescheiterter Eintrag wird im selben Stand über seine Position wiedergefunden; sind seine Bytes jetzt
   lesbar, bekommt er die inhaltsbasierte Kennung. Was der Parser des Behälters gar nicht als Datei übergibt
   (Messung: verschlüsselter Eintrag in einem ZIP), hat keinen Eintrag; der Behälter trägt dann `PARTIAL` mit Grund.
6. **Das Original bleibt abrufbar, ohne zweite Kopie.** Das Original einer enthaltenen Datei liefert der Core, indem er
   den Behälter erneut öffnet und die Datei mit passendem Hash herausliest: aus der Ablage (Anhänge je SHA-256, Seiten
   der Quelle `files`) oder, wo der Core kein Original hält, über die Quelle. Eine eigene Kopie je enthaltener Datei
   wird nicht abgelegt. Ist der Behälter weder abgelegt noch lieferbar, bleiben Text und Ergebnisse, und der Abruf des
   Originals meldet das mit festem Code.
7. **Status und Grenzen sind Daten, kein Text.** Jedes Dokument und jeder Eintrag im Verzeichnis trägt `status` (`OK`,
   `PARTIAL`, `UNSUPPORTED`, `FAILED`, `EXCLUDED`), das Kennzeichen `truncated` und einen festen Grund, etwa
   `encrypted`, `corrupt`, `empty`, `timeout`, `too-deep`, `too-many-files`, `unpacked-too-large`, `memory-limit`,
   `media-type-excluded`, `too-large` (nur bei einer vom Betreiber gesetzten Größe). Ist `truncated` gesetzt, steht
   dabei, wie weit gelesen wurde. `EXCLUDED` heißt wie im Manifest (ADR 0023): bewusst nicht gelesen. Hinweise wie
   „Anhang nicht lesbar“ oder Dateinamen als Überschrift stehen nie im Inhalt eines Dokuments, sonst hielte eine Analyse
   sie für eine Aussage der Quelle. Ansichten für Menschen dürfen den Status zeigen. Meldungen und Protokoll nennen
   weder Dateinamen noch Inhalte.
8. **Größe allein schließt nichts aus; Schutzgrenzen gelten beim Lesen.** Die heutigen 20 MiB je Datei und 1 Mio.
   Zeichen je Text sind eine technische Einschränkung des heutigen Wegs und kein gewollter Standard. Sie entfallen im
   ersten Schritt von Stufe 2:

   - **Lesen aus der Datei.** Der Adapter liest das abgelegte Original als Datei, nie als Byte-Array. Enthaltene
     Dateien schreibt er beim Auspacken zählend in eine temporäre Datei und bildet dabei den Hash; gelesen wird aus
     dieser Datei. Tikas Mail-Parser wird so eingestellt, dass er Anhänge als Strom durchreicht; die Textteile der
     Mail ordnet der Adapter dann selbst zu (Punkt 13).
   - **Text in Abschnitten.** Blöcke gehen fortlaufend in die Ablage, in Abschnitten mit einer Höchstgröße (Vorschlag
     100 000 Zeichen), geschnitten an Blockgrenzen. Kein Text und keine Ergebnisliste liegt als Ganzes im Speicher.
     Volltextindex, Export und Analysen lesen abschnittsweise.
   - **Vollständigkeit ist sichtbar.** Nichts gilt stillschweigend als vollständig gelesen. Jede erreichte Grenze ist
     ein Status nach Punkt 7; was bis dahin gelesen ist, bleibt.
   - **Nachholen ohne Download.** Jede Grenze ist einstellbar (beide Loader, `ConfigurationParityTest`). Nach einer
     geänderten Einstellung liest der Auftrag aus Punkt 11 die betroffenen Dokumente aus der Ablage neu. Ein
     abgebrochenes Dokument wird dabei von vorn gelesen; mittendrin fortsetzen kann Tika nicht.

   Was bleibt, schützt den Dienst und soll keine normale Datei treffen. Vorgeschlagene Standardwerte:

   | Schutz | Standard | Gegen |
   |---|---|---|
   | Größe der Datei | keine Grenze (`0`), wie `assets.max-size` | – |
   | Zeichen je Dokument | keine Grenze | – |
   | Ausgepackt zu gepackt je Behälter | 100 zu 1, geprüft ab 100 MiB ausgepackt | gepackte Archive |
   | Zeichen zu Dateigröße je Dokument | 100 zu 1, geprüft ab 1 Mio. Zeichen (Tikas Voreinstellung) | Textbomben |
   | Verschachtelungstiefe | 10 (Tikas Voreinstellung für Archive) | endlose Verschachtelung |
   | Enthaltene Dateien je äußerem Behälter | 10 000 (wie `AttachmentListSource.MAX_ATTACHMENTS`) | Archive aus Kleinstdateien |
   | Laufzeit je Dokument | 30 s ohne Fortschritt, 30 min insgesamt | hängende Parser |
   | Freier Platz für temporäre Dateien | Abbruch unter einer Reserve (Vorschlag 1 GiB) | volle Platte |
   | Gleichzeitige Läufe | 2 (wie heute) | Last |

   Geprüft wird vor dem Öffnen (Tiefe, Anzahl) und während des Lesens (Bytes, Verhältnis, Fortschritt, Platz), bevor
   Inhalt in den Speicher oder in die Ablage geht, nicht erst am fertigen Ergebnis. Erreicht ein Teil seine Grenze,
   endet nur dieser Teil, die übrigen werden gelesen. Tikas gemeinsame Zeichengrenze und Tikas Sammelliste aller Teile
   (`RecursiveParserWrapperHandler`) werden nicht benutzt. Eine Zeitgrenze mit unterbrochenem Thread ist kein
   Speicherschutz: Dass der Bedarf nicht mit der Datei wächst, weisen Tests mit kleinem Heap nach (Abnahme 5).
   Erweist sich ein Format dabei als speicherabhängig, gilt für dieses Format bis zum Lesen im eigenen Prozess (Stufe
   6) eine einstellbare Schranke (Vorschlag 100 MiB wie `Assets.MAX_IN_MEMORY_BYTES`) mit Status `EXCLUDED` und Grund
   `memory-limit`; SOURCE_ADAPTERS.md nennt sie je Format. Die Texterkennung (OCR) wird ausdrücklich abgeschaltet; ihr
   Modul liegt im Klassenpfad.
9. **Die Outlook-Quelle liefert ihre Anhänge.** Per Patch im Enterprise AI Client, nie in der Kopie: Die Quelle bekommt
   die Fähigkeiten `AttachmentListSource` und `PageViewSource`. Die Bytes holt java-libpst
   (`PSTAttachment#getFileInputStream`), Tika liest nur. Ein Anhang aus einem Postfach läuft danach durch dieselbe
   Ablage und Extraktion wie ein Confluence-Anhang. Der Core bildet beide Fähigkeiten ab
   (`KnowledgeSourceHarvestSource`), erprobt ist das aber nur mit Confluence. Für eine Quelle, deren Seiten über
   `discover` kommen, passen drei Stellen noch nicht; Stufe 3 klärt sie:

   - **Seiten-ID.** Der Core führt die Seite einer solchen Quelle als Base64url ihrer Ressourcen-ID und reicht diese ID
     unverändert an `storedAttachments(localId)` durch. Die Schnittstelle erwartet die lokale ID der Quelle. Der Core
     übersetzt deshalb vor dem Aufruf; vorhandene Seiten-IDs der Outlook-Quelle ändern sich dabei nicht.
   - **Anhangskennung.** Der Core bildet sie aus dem letzten Pfadteil der Ressourcen-ID eines Anhangs. Der muss in der
     ganzen Quelle eindeutig und dauerhaft sein, also Postfachdatei, Nachricht und die in der Nachricht gespeicherte
     Anhangsnummer (`PSTAttachment#getAttachNum`) enthalten, nicht nur die Nummer oder die Position in der Liste.
   - **Vertrag von `PageViewSource`.** Die Quelle erfüllt ihn ganz: `isAttachment` (sonst lehnt der Core den Abruf ab),
     `loadAttachmentFile`, `streamAttachmentFile` als echten Strom ohne Byte-Array sowie die Pflichtmethoden `pageView`
     und `loadViewImage`. Vorschauen meldet der Core für diese Quelle nicht; `ATTACHMENT_PREVIEWS` hängt heute allein
     an den beiden Schnittstellen.

   Den Nachweis führt ein Integrationstest über die normale Quellen-API des Core (Abnahme 10). Danach wird die Kopie
   mit `sync-from-upstream.sh` abgeglichen.
10. **Analysen arbeiten je Dokument.** Eingabe ist der Lesetext des Dokuments (`PageText`, gebildet aus seinen Blöcken
    mit eigenem Schema, ohne Kopfdaten und ohne Status), bei großen Dokumenten abschnittsweise. Absätze (ADR 0020),
    Begriffe und Akronyme (ADR 0019), Embeddings und Navigation laufen für Anhänge und enthaltene Dateien wie für
    Seiten; Aussagen (ADR 0024) weiter nur auf ausdrücklichen Auftrag. Ergebnisse hängen am Dokument, nicht an der
    Seite: Die Ablagen bekommen neben `PageKey` die Teilkennung, ebenso die Kennungen, die heute Quelle und Seite tragen
    (Passage-ID, Knoten im Graphen). Für Seiten ändert sich nichts. Ansichten zeigen die Herkunft und können nach Seite
    und Anhang filtern; ihre Gestaltung bestellt Angelo bei den Oberflächen-Strängen. An einen KI-Dienst gehen Anhänge
    nur aus freigegebenen Quellen (ADR 0011, ADR 0023 Punkt 5). Abschaltbar über eine Einstellung, Standard an.
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
    Download. Er läuft nach einem Versionswechsel und nach geänderten Grenzen und kann auf Dokumente mit Status
    beschränkt werden (wie ↻ „Nur Fehler“).
12. **Suche und Export folgen dem Dokument.** Der Volltextindex führt je Dokument einen Eintrag unter seiner Seite,
    bei großen Dokumenten je Abschnitt. Ein Treffer bleibt ein Treffer der Seite und nennt Anhang und enthaltene
    Datei. Das JSON-Paket bekommt je Dokument `.md` neben `.txt` sowie enthaltene Dateien als eigene Einträge mit
    Verweis auf ihren Behälter, Status, Grund, `truncated` und gelesenem Umfang. Dateien im Paket heißen nach der
    Kennung, der Dateiname steht nur im Verzeichnis. Das Format steigt auf `attachments/v2`, `analysis-export/v1`
    bleibt additiv.
13. **Was zum Dokument selbst gehört, wird kein eigener Eintrag.** Inline-Bilder, Vorschaubilder und Makros eines
    Office-Dokuments sowie namenlose Textteile einer Mail gehören zu ihrem Dokument. Eigene Dokumente werden die
    übrigen Dateien, sofern ihr am Inhalt erkannter Typ zugelassen ist (`export.attachments.media-types`); Archive und
    Mails in einem Behälter werden wie heute geöffnet. Bilder als Anhang einer Mail stehen mit Name, Typ und Hash im
    Verzeichnis und bleiben bei ADR 0021 (Beschreibung nur auf Abruf).

## Stufen

| Stufe | Inhalt | Braucht |
|---|---|---|
| 1 | Vertrag: Typen, Port, Status, Kennung und Ablage in Abschnitten; Testkorpus im Repository aus selbst erzeugten Dateien, große Prüfdateien entstehen im Test. Kein geändertes Verhalten. | – |
| 2a | Große Dateien: eigener Adapter auf Tika 3.3.2, der aus der abgelegten Datei liest und den Text in Abschnitten ablegt. Damit entfallen die 20 MiB und die 1 Mio. Zeichen. Schutzgrenzen und Status nach Punkt 8, OCR aus, neue Extraktor-Version, Neulesen als Auftrag. | 1 |
| 2b | Struktur: Blöcke und Fundstellen aus Tikas XHTML, Verzeichnis der enthaltenen Dateien mit Kennung und Hash, `DocumentText` aus derselben Extraktion. | 2a |
| 3 | Enthaltene Dateien als Dokumente in Ablage, Volltextindex und Export, für Seitenanhänge und die Quelle `files`. Dazu der Outlook-Patch im Enterprise AI Client samt Integrationstest und Übersetzung der IDs (Punkt 9); der Patch kann parallel zu 1 und 2 vorbereitet werden. | 2b |
| 4 | Analysen je Dokument (Punkt 10). | 3 |
| 5 | Extraktoren je Format vor Tika (Word-Listen und Excel-Zellen über POI, HTML über jsoup), nur bei nachgewiesenem Gewinn am Testkorpus. | 2b |
| 6 | Tika in ein eigenes Modul und Umstieg auf Tika 4 mit Lesen in einem eigenen Prozess, als getrennte technische Umstellung. Braucht einen neuen Zuschnitt im Enterprise AI Client, weil `enterprise-ai-sources` Tika mitbringt. | – |

## Abnahmekriterien

1. **Suche.** Feste Suchanfragen über den Testkorpus finden vor und nach der Umstellung dieselben Seiten.
2. **Fundstellen.** Jede gesetzte Fundstelle ist gegen das Original geprüft (Text der PDF-Seite, Blattname,
   Foliennummer). Wo Tika keine liefert (Excel-Zeile, Word-Seite), ist das Feld leer.
3. **Kennung.** Zwei Anhänge mit gleichem Namen sind zwei Dokumente. Derselbe Inhalt zweimal ergibt zwei Einträge und
   eine Extraktion; in vertauschter Reihenfolge ändern sich weder Text noch Ergebnisse. Ein Dateiname mit Pfadzeichen
   wird nie ein Pfad. Ein Anhang, dessen Lesen an einer Grenze abbricht, hat einen Eintrag mit Kennung und Status;
   nach dem Neulesen mit höherer Grenze steht an derselben Position der gelesene Eintrag.
4. **Grenzen.** Jede Grenze hat einen Fall knapp darunter, genau darauf und darüber, mit für den Test gesenkten
   Werten: Anzahl enthaltener Dateien, Tiefe, Verhältnis, Laufzeit ohne Fortschritt. Über der Grenze endet der
   betroffene Teil mit Status, und die übrigen Anhänge derselben Mail werden gelesen.
5. **Speicher.** Eine eigene Testgruppe läuft in einer JVM mit kleinem Heap (Vorschlag 256 MiB). Darin werden
   vollständig gelesen: ein PDF mit mehr als 300 MiB, ein Excel mit 1 Mio. Zeilen und eine Mail mit einem Anhang von
   mehr als 300 MiB. Beim gepackten Archiv aus der Messung zählt der Test, dass nicht mehr Bytes ausgepackt wurden als
   die Grenze und ein Puffer. Ein Lauf, der nur an der Zeitgrenze endet, besteht nicht.
6. **Vollständigkeit.** Mit den Standardwerten wird keine der großen Prüfdateien gekürzt oder ausgelassen. Wo doch
   gekürzt wird, stehen Status, Grund und gelesener Umfang am Dokument und im Export.
7. **Status.** Defekte, verschlüsselte und leere Anhänge tragen ihren Status am Eintrag. Kein Analyse-Eingang enthält
   erzeugten Text.
8. **Versionen.** Eine neue Extraktor-Version mit gleichen Blöcken lässt die Analysen aktuell. Eine geänderte Ansicht
   macht genau die betroffenen Dokumente alt. Der alte Text bleibt lieferbar, bis der neue gelesen ist.
9. **Original.** Eine enthaltene Datei ist aus dem abgelegten Behälter mit passendem Hash abrufbar.
10. **Outlook.** Ein Integrationstest liest ein Postfach über die normale Quellen-API des Core, findet eine Nachricht,
    listet ihre Anhänge und ruft deren Originalbytes ab; der Hash stimmt mit dem Anhang überein. Zwei Nachrichten mit
    je einem ersten Anhang ergeben zwei verschiedene Anhangskennungen.
11. **Mails.** Tests im Core decken Mails mit Anhängen ab (ZIP in Mail, Mail in Mail, Outlook-Mail in Mail, defekte
    Anhänge, doppelte Namen). Heute deckt kein Test im Core Mails ab.

## Folgen

- Ablage und Index wachsen, weil große Dateien erstmals vollständig Text liefern: Ein Excel mit 1 Mio. Zeilen ergab
  141,6 Mio. Zeichen. Embeddings für Anhänge kosten Aufrufe beim KI-Dienst, nur für freigegebene Quellen.
- Nach Stufe 2 werden alle abgelegten Originale einmal neu gelesen, ohne Download, darunter erstmals die über 20 MiB.
- Der Text eines Behälters wird kürzer, weil seine Dateien eigene Dokumente sind. Ein Treffer wandert von der Mail zu
  ihrem Anhang; die gefundene Seite bleibt dieselbe.
- PDFs bleiben ohne Überschriften und Listen, weil Tika dort keine Struktur liefert. Das ist eine benannte
  Qualitätsgrenze und kein Teil dieser Entscheidung.
- Gewollte Abweichung vom Enterprise AI Client (SH+), in PARITY.md durch Strang S nachzutragen.

## Verworfen

- **Anhang als künstliche Seite im Cache.** Die Inventur sähe sie als gelöscht, und Seitenzahlen, Baum und `pages.json`
  wären verfälscht.
- **Tikas Namenspfad als Kennung** (Messung, Punkt 5).
- **Nur der Hash als Kennung.** Ein Eintrag ohne lesbare Bytes hätte keine Identität (Punkt 5).
- **Nur die Position als Kennung.** Sie verschiebt sich mit jedem neuen Stand des Behälters und kann sich mit der
  Tika-Version ändern (Punkt 5).
- **Eigene Bytekopie je enthaltener Datei** (Punkt 6).
- **Feste Größen- und Zeichengrenze als Standard.** Sie schließt normale große Dateien aus, ohne den Dienst besser zu
  schützen als die Grenzen aus Punkt 8.
- **Markdown als führendes Format.** Tabellen, Fundstellen und Status passen nicht verlustfrei hinein (Punkt 3).
- **Neue Bibliotheken** für PDF-Struktur oder Markdown (Docling, MarkItDown, OpenDataLoader PDF).

## Offen

- Welchen Medientyp das Confluence der HaVdB für `.msg` meldet. Bei `application/octet-stream` schließt die
  Standardliste die Mail aus.
- Ob Archive in die Standardliste von `export.attachments.media-types` kommen. Heute bleibt ein ZIP an einer Seite
  draußen, ein ZIP in einer Mail wird gelesen.
- Angehängte Outlook-Elemente ohne Dateibytes (`ATTACHMENT_METHOD_EMBEDDED`): Ihre Form legt der Patch fest.
- Die Standardwerte der Schutzgrenzen aus Punkt 8; sie sind an selbst erzeugten Dateien bemessen, nicht an echten
  Beständen.
- Der Speicherbedarf der nicht gemessenen Formate (alte Office-Formate, PowerPoint, Outlook-`.msg`, `.pst`); er
  entscheidet, ob die Schranke `memory-limit` gebraucht wird.
- Ob KI-gestützte Analysen bei sehr großen Dokumenten ein eigenes, sichtbares Budget bekommen. Aus einer einzigen
  Tabelle können über 100 Mio. Zeichen werden.
- Die Unterstützung für Tika 3 endet laut Apache je nach Stelle sechs Monate nach Tika 4.0.0 (erschienen am
  18.08.2026, also im Februar 2027) oder im März 2027. Stufe 6 sollte vorher erledigt sein.

## Nachtrag vom 13.11.2026: Stand nach der Umsetzung der Stufen 1 bis 5

Die Stufen 1 bis 5 sind umgesetzt (PRs #700 bis #744, Enterprise AI Client #220 und #221). Dieser Nachtrag hält fest,
wo die Umsetzung von der Entscheidung abweicht oder sie genauer fasst. Alles Übrige gilt wie beschlossen.

### Abweichungen

1. **Enthaltene Dateien liegen im Stand ihres Behälters.** Die Extraktion eines Teils wird nicht unter einem eigenen
   Schlüssel abgelegt, sondern in der Generation des äußeren Behälters, und wird mit ihm gültig und ersetzt. Derselbe
   Inhalt in zwei Behältern wird deshalb zweimal gelesen. Grund: Ein Behälter ist so immer als Ganzes gültig oder
   nicht; Kennungen nach Position gelten ohnehin nur für einen Stand.
2. **Speicherschranke je Medientyp.** Statt einer allgemeinen Grenze gibt es eine Schranke `memory-limit` je Typ. Sie
   ist nur für Typen aufgehoben, deren vollständiges Lesen einer Datei über 300 MiB bei 256 MiB Heap im Test
   nachgewiesen ist: PDF, `.docx`, `.xlsx`, `.pptx`, Mail (`.eml`), Text, CSV, HTML und XHTML, ZIP, GZIP, TAR. Für
   alle anderen Typen bleibt sie bei 20 MiB, namentlich `.doc`, `.xls`, `.ppt`, `.msg` und `.pst`, weil deren Leser
   die Datei im Speicher halten, aber auch `.xlsm` und andere Office-Varianten mit Makros, für die kein eigener
   Nachweis vorliegt. Ein neuer Typ wird nur mit Nachweis freigegeben. Die Schranke von 20 MiB bei `.msg`, `.pst`
   und den älteren Office-Formaten ist eine benannte Qualitätsgrenze der Leser, kein gewollter Ausschluss großer
   Dokumente (Angelo, 13.11.2026).
3. **Verhältnis von Ausgepacktem zu Gepacktem.** Am Verhältnis endet nur der betroffene Teil; seine Bytes zählen
   danach nicht weiter, die folgenden Teile werden gelesen, der Behälter wird `PARTIAL/unpacked-too-large`.
4. **Laufzeit.** Die Grenze ohne Fortschritt gilt für den ganzen Lauf, die Gesamtgrenze je Dokument und Teil.
5. **Weitere Gründe am Status:** `text-ratio-exceeded`, `temp-space-low`, `entries-not-readable`,
   `unsupported-format`, `no-text`. Ein leerer Anhang ist `UNSUPPORTED/empty`, Bytes ohne Text `UNSUPPORTED/no-text`;
   ein Behälter ohne eigenen Text, aber mit Einträgen, ist `OK`.

### Genauer gefasst

6. **Outlook.** Ein angehängtes Outlook-Element liefert die Quelle als erzeugte Mail (`message/rfc822`) bis zur
   Tiefe 8; tiefere stehen in der Liste und sind nicht abrufbar. Teile ohne Datei meldet die Quelle mit der Warnung
   `entries-not-readable;id=…;count=…`; der Anhang wird dann `PARTIAL/entries-not-readable`. java-libpst entpackt
   Anhänge, die mit einem zlib-Kopf beginnen, von selbst; Größe und Hash weichen dann vom Original ab (#706).
7. **Fundstellen.** PDF: laufende physische Seite. `.ppt`: Folie in Folienreihenfolge. `.pptx`: Tika gibt die Folien
   in der Reihenfolge der Beziehungskennungen aus; die Foliennummer kommt deshalb aus `p:sldIdLst` des Originals und
   wird nur gesetzt, wenn die Zuordnung belegt ist, die Blöcke bleiben in Tikas Reihenfolge. `.xlsx` und `.xlsm`:
   Zellbezug (`C3`) aus dem Blatt-XML des Originals, je Zeile gegen Tikas Ausgabe belegt; nicht für `.xls`, `.xlsb`,
   ODS und CSV. Mappen und Präsentationen, die als eigene Teile aus Mails und Archiven gelesen werden, bekommen
   Zellbezug und Foliennummer ebenso, aus ihrer temporären Datei; ohne bleiben nur Dateien, die Tika innerhalb eines
   anderen Dokuments ausgibt (etwa eine Mappe als eingebettetes Objekt in Word). Beide Zuordnungen sind an die
   geprüfte Fassung von Tika und POI gebunden; mit einer anderen Fassung bleiben die Felder leer, bis die Tests dort
   bestanden sind.
8. **Word-Listen bleiben Absätze.** Tika setzt die berechnete Marke in den Text, liefert aber weder Ebene noch
   Listenart. Eine Zuordnung über das Original wäre nur nach Wahrscheinlichkeit möglich und wird nicht gebaut.
9. **HTML** liest ein eigener Strom-Parser in Tikas Konfiguration (jsoup `StreamParser` aus der Version, die Tika
   mitbringt), weil Tikas Weg den ganzen DOM-Baum hält. Er benutzt zwei nicht öffentliche Klassen von Tika
   (`HtmlHandler`, `XHTMLDowngradeHandler`); fehlen sie nach einer Anhebung, gilt wieder Tikas Weg mit der Schranke
   von 20 MiB. Bei jeder Tika-Anhebung zu prüfen.
10. **Schlüsselform.** `DocumentKey` liefert `value()`, `fileName()`, `storagePath()` und `hash()`; alle Ablagen der
    Analysen benutzen nur diese Methoden. In Daten stehen `pageId`, `attachmentId` und `partId` immer getrennt.
11. **Analysen sind aktuell,** wenn der Hash des Lesetextes, die Ansicht (`document-text/v1`) und die Version des
    Prozessors gleich sind; die Version des Extraktors gehört nicht dazu. Der Lauf bestimmt seine Arbeit aus dem
    Zustand und holt den Bestand nach.
12. **Embeddings je Dokument** sind durch `space-harvester.enrichment.documents.embedding-max-chars` begrenzt
    (Standard 2 000 000 Zeichen, `0` = ohne Grenze); eine Kürzung steht als Daten am Ergebnis. Absätze und Navigation
    laufen lokal und ohne diese Grenze. Abschalten: `space-harvester.enrichment.documents.enabled`.
13. **Export** `attachments/v2` in `json-package/5`: je Dokument und Teil ein Eintrag mit Status, Umfang und
    Versionen (`extraction` mit Extraktor, Version und Modell). Enthaltene Dateien liegen nach Kennung
    (`parts/<Anhang>/<Kennung>.<Endung>`), ihr Anzeigename steht nur im Feld `title`; das Original eines
    Seitenanhangs behält wie bisher seinen bereinigten Dateinamen. Importierter Text gilt als veraltet, bis das
    Original neu gelesen ist (`stale`).
14. **Medientypen enthaltener Dateien.** Entschieden (Angelo, 13.11.2026): In Mails und Archiven werden
    standardmäßig alle technisch unterstützten, lesbaren Dateitypen verarbeitet, damit nichts nur wegen seines Formats
    verloren geht. Die Liste `export.attachments.media-types` gilt nur für Anhänge der Seite und wird nicht auf
    enthaltene Dateien übertragen. Die Schutzgrenzen gegen Archivbomben und Überlastung gelten weiter, Dateien werden
    nie ausgeführt, nicht Lesbares und Begrenztes trägt einen sichtbaren Status. Im Code bleibt `PartRules.ALL` der
    Standard; eine Filterung über `PartRules` ist im Adapter vorbereitet, aber nicht angeschlossen.

### Benannte Möglichkeiten, nicht bestellt

- Eigener SAX-Leser für `.pptx` (echte Folienreihenfolge der Blöcke).
- Zellbezug und Foliennummer für Dateien, die Tika innerhalb eines anderen Dokuments ausgibt.
- Eigene, einstellbare Filterung enthaltener Dateien nach Medientyp über `PartRules`.
- Oberflächen: Herkunft anzeigen, nach Anhang filtern, Status sichtbar.
- Stufe 6 (Tika-Modul im Enterprise AI Client, Tika 4).
