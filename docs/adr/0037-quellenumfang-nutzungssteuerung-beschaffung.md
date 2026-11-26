# ADR 0037: Quellenumfang, Nutzungssteuerung und Beschaffungsstrategien

- Status: **Angenommen** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in #1460, Epic #1461 Abschnitt C
  und #1475). Nutzungsoptionen und quellenweise Beschaffung sind auf main. Was im Text mit
  *vorgeschlagen* gekennzeichnet ist, stammt aus den Antworten des ursprünglichen Chats vom 26.11.2026, 13:21, und
  gilt erst mit Angelos Bestätigung; die Übersicht steht unter „Geklärt am 26.11.2026“.
- Quellen: #1460, Epic #1461 (Abschnitte C und C.1), #1475, PRs #1462, #1477, #1482; Angelos Zusammenfassung vom
  26.11.2026 (Abschnitte 3, 5 und 12) und Klärung vom 26.11.2026, 13:12 (Punkte 1 bis 3).
- Bezüge: [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md) (Rechte),
  [ADR 0015](0015-konfiguration-zur-laufzeit.md) (Konfiguration zur Laufzeit),
  [ADR 0016](0016-auswahl-beschaffung-hintergrund.md) (Beschaffung, Hintergrundfüllung),
  [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md) (Begriffe, „Indexieren“),
  [ADR 0038](0038-indexbasierte-progressive-und-agentengestuetzte-suche.md) (Wirkung auf die Suche).

## Kontext

Bisher war eine eingerichtete Wissensquelle automatisch Teil des allgemeinen Wissensbestands: sichtbar im Baum,
in jeder Suche, mit Such-Prefetch und Hintergrundfüllung. Ein großer technischer Bestand (COBOL, Natural) würde so
die fachliche Suche und das Glossar überlagern und ungefragt vollständig heruntergeladen.

## Entscheidung

1. **Der Umfang einer Wissensquelle ist ausdrücklich abgegrenzt.** Eine Quelle umfasst genau die konfigurierten
   Bereiche (Ordner, HLQs und Datasets, Bibliotheken, Spaces). Nichts erweitert den Umfang stillschweigend.

   | System | Live-Zugriff | Dauerhafter Bestand |
   | --- | --- | --- |
   | Lokales Dateisystem | Freie Navigation | Ausgewählte Ordner |
   | FTP/MVS | Datasets und Member | Ausgewählte HLQs, Datasets, Programme, Copybooks, ausdrückliche SYSLIBs |
   | NDV | Bibliotheken und Objekte | Ausgewählte oder alle zugänglichen Bibliotheken |
   | SharePoint | Native Suche und Navigation | Konfigurierte Bibliotheken |
   | Confluence | Native Suche | Konfigurierte Wissensbereiche |
   | BetaView | Reportabfragen | Gezielte Übernahme |
   | JES | Job- und Spoolabfragen | Gezielte Archivierung |
   | Terminal | Interaktive Bedienung | Kein Wissensbestand |

   Bei FTP gibt der Benutzer die HLQs von Hand an. Eine globale Aufzählung aller HLQs ist verworfen. Bei NDV fragt
   die Oberfläche die verfügbaren Bibliotheken ab und bietet eine Mehrfachauswahl; „alle zugänglichen Bibliotheken“
   ist eine ausdrückliche Wahl.
2. **Vier unabhängige Eigenschaften je Quelle.** Keine ist am Adapter oder Quellentyp fest verdrahtet.

   | Eigenschaft | Einstellung | Wirkung von „aus“ |
   | --- | --- | --- |
   | Quelle aktiviert | `enabled` (wie bisher) | Die Quelle nimmt an keinem Zugriff teil. Der Bestand bleibt erhalten. |
   | Im Wissensbaum anzeigen | `showInExplorer` | Nur der normale Wissensbaum blendet sie aus. Quellenverwaltung, ausdrückliche Auswahl, Indexierung, Rechte und direkte Navigation bleiben. |
   | In globale Suche einbeziehen | `includeInGlobalSearch` | Die Quelle fehlt in jeder impliziten, globalen Suche. Gezielte Suche bleibt möglich („Nur gezielte Suche“). |
   | Beschaffung | `prefetch-hits`, `background-fill` je Quelle | Suchtreffer bzw. fehlende Seiten werden nicht automatisch geladen. |

   Fehlende Angaben bedeuten das bisherige Verhalten (`true` bzw. der globale Standard). Bestehende Quellen ändern
   sich ohne neue Angaben nicht. **Neue technische Quellen** (FTP, NDV) entstehen mit „In globale Suche
   einbeziehen“ aus; der Benutzer kann das ändern (beschlossen).
3. **Umschalten löscht nichts.** Keine der Eigenschaften entfernt vorhandene Daten, ändert Berechtigungen oder
   deutet Analysefreigaben um.
4. **Eine Durchsetzungsstelle für den Suchumfang.** Titel-, Volltext-, Vektor- und Chat-Suche sowie der Prefetch
   benutzen denselben wirksamen Umfang (`SearchScope`).
   - Eine Liste aller Quellen, zu der ein Aufrufer eine globale Anfrage vorab expandiert, ist keine ausdrückliche
     Benutzerwahl.
   - Eine leere ausdrückliche Auswahl heißt nie „alle“.
   - Eine deaktivierte Quelle wird durch ausdrückliche Auswahl nicht wieder aktiv.
   - Die Rechteprüfung gilt zusätzlich und unverändert.
   - Globale Glossar-, Begriffs-, Akronym-, Synonym- und Änderungsansichten schließen ausgeschlossene Quellen aus;
     ausdrücklich gewählte Bereiche bleiben erreichbar.
5. **Kennzeichnung „Nur gezielte Suche“** mit Symbol, Tooltip und Text. Farbe ist nie das einzige Signal und darf
   nicht wie ein Fehler aussehen. Die Farbe ist ein bernsteinfarbener Rand (*vorgeschlagen*).
6. **Kein zweites Synchronisierungsmodell.** Die Beschaffung nutzt `SourceSyncOverride`, `SyncSettings`,
   `SyncMode`, `Indexing` und `IndexMarks`/`FileIndexMarks`. Die Regeln je Quelle stehen im Reiter
   „Synchronisierung“. Dort gibt es zusätzlich zu den einzelnen Schaltern die Presets „Vollständig“ und „Selektiv“
   (beschlossen); weichen die Regeln von beiden ab, steht dort „Benutzerdefiniert“.
7. **Vollindexierung ist freiwillig.** Das Einbinden einer Quelle verpflichtet zu nichts. Der Benutzer steuert
   unabhängig voneinander: Voll- oder Teilindexierung, dauerhaftes Nachladen von Suchtreffern (Prefetch) und
   Hintergrundfüllung. Ausdrückliches Indexieren, Öffnen und gezieltes Lesen bleiben immer möglich.
8. **„Suchtreffer nachladen“ und „Hintergrundfüllung“ sind unabhängig** (ausdrücklich vereinbart, bestätigt am
   26.11.2026).

   | Suchtreffer nachladen | Hintergrundfüllung | Verhalten |
   | --- | --- | --- |
   | aus | aus | Native Funde anzeigen, keine automatische Inhaltsbeschaffung |
   | an | aus | Suchtreffer beschaffen, keine weiterführende Hintergrundfüllung |
   | aus | an | Die Suche lädt nichts unmittelbar nach, die reguläre Hintergrundfüllung läuft |
   | an | an | Beide Wege aktiv |

   - **Einen Fund zu erfassen und seinen Inhalt zu beschaffen sind getrennte Verantwortlichkeiten.** Der
     `FindingCatalog` führt die Kopfdaten eines Fundes unabhängig vom Prefetch. Wann und ob der Inhalt geladen wird,
     entscheiden allein die Beschaffungsregeln der Quelle.
   - Sind beide Wege aus, legt kein Weg Inhalte oder Embeddings dauerhaft ab: nicht der Prefetch, nicht die
     semantische Vorbereitung (`SemanticPreparation`), nicht die Hintergrundfüllung. Quellenüberschreibungen gelten
     auch dann, wenn der globale Standard an ist.
   - Ist nur die Vollindexierung aus, darf der erlaubte Such-Prefetch Treffer im regulären Cache behalten (stabile
     Fundstellen, kein Wegwerfindex).
   - **Die Hintergrundfüllung bleibt auf den konfigurierten Umfang der Quelle beschränkt** (*vorgeschlagen*). Ist
     „Suchtreffer nachladen“ aus und die Hintergrundfüllung an, darf sie Suchfunde innerhalb des Umfangs
     verarbeiten. Außerhalb des Umfangs löst eine Suche nie mittelbar eine Erweiterung des dauerhaften Bestands
     aus. Berechtigungen werden davon unabhängig immer zusätzlich geprüft.
9. **Zwei unabhängige Beschaffungsarten für FTP und NDV** (#1475), einzeln oder zusammen einstellbar:
   - **Bestandsorientierte Synchronisierung:** Der konfigurierte Bereich wird vollständig inventarisiert und nach
     den Regeln erschlossen.
   - **Navigationsgetriebene Befüllung** (`navigationFill`): Beim Navigieren im Deck gehen die direkt aufgelisteten
     Ressourcen über die gemeinsame Hintergrund-Pipeline in den Bestand der passenden Wissensquelle, wie bei
     Confluence. Ziel sind COBOL-Programme, Copybooks, JCL und Prozeduren, nicht der ganze Mainframe.

   Die Zuordnung einer Deck-Sitzung zu einer Quelle verlangt denselben Host, Port und dasselbe Konto (ADR 0036,
   Punkt 5). Die navigationsgetriebene Befüllung erweitert den Inventurbereich nie.

   **Die Navigationsbefüllung ist eine allgemeine Beschaffungsregel, keine Eigenheit von FTP und NDV**
   (*vorgeschlagen*). Eine Wissensquelle hat vier Beschaffungsarten: Synchronisierung, Navigationsbefüllung,
   Such-Prefetch und Hintergrundfüllung. Der Adapter sagt über seine Fähigkeiten, welche er unterstützt. Welche
   davon genutzt werden, steht in der allgemeinen Quellenkonfiguration im Reiter „Synchronisierung“. Alle vier
   nutzen dieselbe Infrastruktur für Aufträge, Eignungsprüfung, Berechtigungen und Ablage. Die heutige
   Adapteroption `navigationFill` bleibt lesbar, ist aber auf Dauer keine zweite, widersprüchliche Konfiguration.
10. **Drei getrennte Verantwortlichkeiten** (Leitplanke 26.11.2026). Bei jeder automatischen Beschaffung sind zu
    unterscheiden: der **Auslöser** (Inventur, Navigation, Suchtreffer, Hintergrundfüllung, ausdrückliches
    Indexieren), die **Eignungsprüfung** (darf und soll dieser Inhalt geladen und extrahiert werden) und der
    **Erwerbsgrund** (warum die Ressource zum Bestand gehört, Punkt 12). Kein Auslöser bringt eine eigene
    Eignungsprüfung oder eine eigene Ablage mit.
11. **Eine Eignungsprüfung für jede automatische Beschaffung** (Konkretisierung 26.11.2026). Synchronisierung,
    Navigationsbefüllung, Hintergrundfüllung und Prefetch benutzen dieselbe Prüfung; es gibt keine zweite
    Implementierung.
    - Ladebibliotheken und eindeutig binäre Inhalte werden nie automatisch zur Textextraktion geladen.
    - Quelltext wird an Metadaten und Inhalt erkannt, nicht allein am Namen. Unbekannte Typen werden klassifiziert.
    - Es gelten die vorhandenen, einstellbaren Größen- und Beschaffungsgrenzen. Eine neue, willkürliche Grenze
      kommt nicht dazu.
    - Der ausdrückliche Zugriff auf jede andere Datei bleibt möglich.
    - **Ablauf der Klassifizierung** (*vorgeschlagen*): zuerst die Metadaten. Ergebnis „geeignet“ lädt den
      Quelltext, „ungeeignet“ lädt nichts automatisch, „unbekannt“ führt zu einer begrenzten Leseprobe. Die
      Leseprobe ist **auf der Transportebene** begrenzt: Es wird nie ein großes Dataset ganz geladen, um seine
      ersten Kilobytes anzusehen. Das Ergebnis wird je Revision gespeichert.
    - `RECFM=U` wird für die automatische Quelltextbeschaffung vorsichtshalber ausgeschlossen. Das heißt nicht,
      dass jedes solche Dataset eine Ladebibliothek ist.
12. **Erwerbsgründe statt Klassen** (Konkretisierung 26.11.2026; das Modell ist *vorgeschlagen*, siehe auch
    „Prüfung der vorhandenen Mechanismen“). Eine Wissensressource gibt es genau einmal, ohne
    mehrfache Kopien oder Indexeinträge. Sie kann aus mehreren Gründen im Bestand sein:
    - sie liegt im konfigurierten Inventurbereich,
    - sie wurde durch Navigationsbefüllung erworben,
    - sie wurde ausdrücklich indexiert.

    Fällt ein Grund weg, wird geprüft, ob ein anderer besteht. Eine Ressource wird nicht pauschal als
    „Inventurressource“ oder „Navigationsressource“ eingestuft, denn sie kann über beide Wege gekommen sein.

    | Ereignis | Verhalten |
    | --- | --- |
    | Die Ressource ist im Quellsystem nachweislich gelöscht | Bestand und Index bereinigen |
    | Der Benutzer nimmt einen Bereich ausdrücklich aus dem Umfang | Rückfrage mit der Zahl der betroffenen Ressourcen, **bevor** die Konfigurationsänderung übernommen wird; danach werden Ressourcen ohne weiteren Erwerbsgrund aus diesem Wissensbestand entfernt (*vorgeschlagen*) |
    | Die Navigationsbefüllung wird ausgeschaltet | Bereits erworbene Ressourcen bleiben (beschlossen); es kommt nur nichts mehr dazu |
    | Die Inventur ist unvollständig (Fehler, Tiefen- oder Mengengrenze, Abbruch) | Keine Löschung aus bloß fehlenden Inventurergebnissen |

    Ressourcen, die außerhalb des Inventurbereichs durch Navigation erworben wurden, löscht der Inventurabgleich nie.

    **Woher ein Erwerbsgrund kommt** (*vorgeschlagen*):

    | Erwerbsgrund | Herkunft |
    | --- | --- |
    | Inventurbereich | abgeleitet aus der aktuellen Quellenkonfiguration, nicht gespeichert |
    | Ausdrückliche Indexierung | vorhandene `IndexMarks`, einschließlich Teilbaummarkierungen |
    | Navigationsbefüllung | neu: wird an der Ressource gespeichert |

    Eine Ressource bleibt im Bestand, solange mindestens ein gültiger Erwerbsgrund besteht. Das Modell beschreibt
    nur den Erwerbsgrund. Es ersetzt weder die Berechtigungsprüfung noch den Nachweis, dass die Ressource im
    Quellsystem noch existiert. Nur durch Navigation erworbene Ressourcen werden beim Öffnen und beim erneuten
    Besuch ihres Ordners geprüft, dazu durch die vorhandenen Aktualitätsregeln der Quelle, soweit eingestellt
    (*vorgeschlagen*).

    Mehrere Erwerbsgründe führen nie zu mehrfacher Ablage oder Indexierung. Vorhandene Indexierungsmarkierungen und
    Bestandsmechanismen werden zuerst auf Wiederverwendbarkeit geprüft, bevor etwas Neues entsteht.
13. **Inventur ist strikt.** Eine unvollständige Inventur ist niemals ein Löschungsnachweis. Fehler, unbekannte Revisionen, fehlende ISPF-Statistiken, erreichte Grenzen
    (`maxResources`, `maxDepth`) und Abbruch gelten nie als vollständiger Bestand und nie als Löschung. Änderungszeit
    und Version zählen nur, wo sie verlässlich sind, sonst der Inhalts-Hash.
14. **Operative Live-Dienste brauchen keinen Wissensquellen-Eintrag.** BetaView, JES und freie Recherche werden über
    Verbindungs- und Werkzeugfreigaben gesteuert (ADR 0036, Punkt 9), nicht über ein Live-only-Preset. Der frühere
    verpflichtende Live-only-Preset aus der ersten Fassung von #1460 ist verworfen.

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| `showInExplorer`, `includeInGlobalSearch` in `SourceSettingsConfig`, `ConfiguredSourceCatalog`, REST, App | auf main (#1462) |
| Ausschluss in `KeywordSearch`, `Glossary`, `TermRelations`, Änderungsprotokoll | auf main (#1462) |
| `prefetch-hits` je Quelle (`SourceSyncOverride.effectivePrefetch`), beachtet in `KeywordSearch` und `SemanticPreparation` | auf main (#1462) |
| FTP: `startPoints`, `navigationFill` (`FtpSourceProvider`, `FtpNavigationFill`) | auf main (#1477) |
| NDV: `startPoints`, `allLibraries`, `navigationFill` (`NdvSourceProvider`, `NdvSourceRules`) | auf main (#1482) |
| Beim Indexieren aus dem Deck wählbar: „Nur gezielte Suche (nicht in globaler Suche)“ | auf main (#1467) |
| Presets „Vollständig“ und „Selektiv“ im Reiter „Synchronisierung“ | **fehlen**, beschlossen (F37-2) |
| Neue FTP- und NDV-Quellen standardmäßig „Nur gezielte Suche“ | **nicht umgesetzt**, beschlossen (F37-1) |
| Punkt 8: Fund erfassen unabhängig vom Prefetch | **weicht ab**, siehe unten; Korrektur beauftragt |
| Punkt 11: gemeinsame Eignungsprüfung in der FTP-Navigationsbefüllung | **weicht ab**; Korrektur beauftragt |
| Punkt 12: Erwerbsgründe je Ressource | nicht umgesetzt; siehe „Prüfung der vorhandenen Mechanismen“ |

## Prüfung der vorhandenen Mechanismen (zu Punkt 12)

Zwei der drei Erwerbsgründe sind heute schon ohne neue Ablage bestimmbar:

| Erwerbsgrund | Vorhandener Mechanismus | Gespeichert an der Ressource |
| --- | --- | --- |
| Im Inventurbereich | `HarvestSource.inventoryCovers(pageId)`, für FTP `FtpInventoryCoverage` aus den Startpunkten der Quelle (#1477) | nein, ergibt sich aus der Konfiguration |
| Ausdrücklich indexiert | `IndexMarks` / `FileIndexMarks`, `IndexScope` | nein, eigene dauerhafte Markierung |
| Navigationsbefüllung | keiner | nein |

Der Abgleich (`SyncPlan`) behandelt heute alles, was `inventoryCovers` verneint, als „nicht meine Sache“: Es wird
weder gelöscht noch aktualisiert. Das schützt die durch Navigation erworbenen Ressourcen, hat aber eine Lücke:
**Nimmt der Benutzer einen HLQ aus den Startpunkten, fallen dessen Ressourcen ebenfalls aus der Abdeckung und
bleiben liegen**, ohne dass sie jemand entfernt, aktualisiert oder als verwaist anzeigt. Sie sind von
navigationserworbenen Ressourcen nicht zu unterscheiden. Genau dafür braucht Punkt 12 eine Kennzeichnung, und es
genügt, den Grund „Navigationsbefüllung“ zu speichern (F37-10).

## Widersprüche und Auffälligkeiten

- **Der Fund wird heute nur bei erlaubtem Prefetch erfasst.** `KeywordSearch` überspringt das Katalogisieren, wenn
  `allowsPrefetch` für die Quelle `false` liefert. Die Hintergrundfüllung erfährt dann nichts von neu gefundenen
  Ressourcen, obwohl sie an ist. Das widerspricht Punkt 8 und ist im ursprünglichen Chat als Implementierungsfehler
  bestätigt.
- **Punkt 12 berührt ADR 0036, Punkt 5.** Dort (und in #1461, B.7) löscht das Entfernen einer Markierung nichts
  still, und das Bereinigen ist eine eigene, bewusste Aktion. Punkt 12 entfernt beim Verkleinern des Umfangs
  Ressourcen ohne weiteren Erwerbsgrund. Beides verträgt sich nur, wenn dieses Entfernen sichtbar und bestätigt
  geschieht. Deshalb die Rückfrage vor der Übernahme der Änderung (Punkt 12).
- **#1460 ist durch PR #1462 geschlossen, dessen Titel nur die Schnitte A und B nennt.** Schnitt C (quellenweiser
  Prefetch, Beschaffungsregeln) ist dennoch im Code vorhanden. Die Presets „Vollständig“ und „Selektiv“ aus #1460
  Abschnitt 3 habe ich im Code nicht gefunden.
- **`navigationFill` ist eine Option des Adapters** (`options.navigationFill` der Quelle), während `background-fill`
  und `prefetch-hits` Regeln in `SourceSyncOverride` sind. #1460 verlangt „kein zweites Sync-Modell“. Die
  Beschaffung einer Quelle wird damit an zwei Stellen eingestellt.

## Geklärt am 26.11.2026

Stand der Antworten: **Beschlossen** gilt. *Vorgeschlagen* ist eine Empfehlung aus dem ursprünglichen Chat und gilt
erst, wenn Angelo sie bestätigt.

| Frage | Antwort | Stand |
| --- | --- | --- |
| F37-1: Vorgabe „globale Suche“ für neue technische Quellen | Aus; der Benutzer kann es ändern | **Beschlossen**, in Punkt 2 |
| F37-2: Presets „Vollständig“ und „Selektiv“ | Ja, zusätzlich zu den einzelnen Schaltern | **Beschlossen**, in Punkt 6 |
| F37-3: `navigationFill` in die allgemeinen Beschaffungsregeln | Ja, als allgemeine Beschaffungsart mit Fähigkeit des Adapters | *Vorgeschlagen*, in Punkt 9 |
| F37-4: Farbe von „Nur gezielte Suche“ | Bernsteinfarbener Rand, dazu Symbol und Tooltip | *Vorgeschlagen*, in Punkt 5 |
| F37-5: Hintergrundfüllung für Funde außerhalb des Umfangs | Nein, sie bleibt auf den konfigurierten Umfang beschränkt | *Vorgeschlagen*, in Punkt 8 |
| F37-6: Begrenzte Leseprobe | Ja, wenn die Metadaten nicht genügen; auf der Transportebene begrenzt | *Vorgeschlagen*, in Punkt 11 |
| F37-7: Bereich aus dem Umfang nehmen | Rückfrage mit Anzahl vor der Übernahme der Änderung | *Vorgeschlagen*, in Punkt 12 |
| F37-8: Navigationsbefüllung ausschalten | Erworbene Ressourcen bleiben | **Beschlossen**, in Punkt 12 |
| F37-9: Aktualität nur durch Navigation erworbener Ressourcen | Prüfung beim Öffnen und beim erneuten Besuch, dazu vorhandene Aktualitätsregeln | *Vorgeschlagen*, in Punkt 12 |
| F37-10: Nur „Navigationsbefüllung“ speichern | Ja, sofern die vorhandenen Markierungen verlässlich wiederverwendbar sind | *Vorgeschlagen*, in Punkt 12 |

## Grenzen und Limits

Nach ADR 0036, Punkt 12. Stand main `69835ca8`. „Quellenformular“ heißt: Option der Quelle, im Formular der
Quellenverwaltung einstellbar.

| Grenze | Standard | Einstellung | In der Oberfläche |
| --- | --- | --- | --- |
| FTP: Tiefe der Inventur | ohne Grenze | Quellenoption `maxDepth` | ja, Quellenformular |
| FTP, NDV: Zahl der Ressourcen | ohne Grenze | Quellenoption `maxResources` | ja, Quellenformular |
| JES: Zahl der Ressourcen | 200 | Quellenoption `maxResources` | ja, Quellenformular |
| Lokale Dateien: größte Datei (**Größenlimit**) | 50 MB, 1 bis 2000 | Quellenoption `maxFileMegabytes` | ja, Quellenformular |
| Lokale Dateien: Tiefe der Aufteilung in Bereiche | 20 | **fest im Code** (`LocalFilesKnowledgeSource.PARTITION_DEPTH`) | nein |
| Lokale Dateien: Stapelgröße der Inventur | 500 | **fest im Code** (`LocalFilesKnowledgeSource.MAX_BATCH`) | nein |
| FTP: Leseprobe zur Erkennung (**Größenlimit**) | 64 KiB | **fest im Code** (`CommonsNetFtpSession.retrieveSample`) | nein |
| NDV: gemerkte Inventur-Kopfdaten | 10 000 Einträge, 5 Minuten | **fest im Code** (`NdvKnowledgeSource.MAX_INVENTORY_METADATA`, `METADATA_AGE_NANOS`) | nein |
| Hintergrundfüllung: Parallelität | 2, 1 bis 16 | `space-harvester.sync.background-parallelism` | ja |
| Hintergrundfüllung an oder aus, je Quelle | an | `sync.background-fill`, `sync.sources.<Quelle>.background-fill` | ja |
| Suchtreffer nachladen, je Quelle | globaler Wert | `search.prefetch-hits`, `sync.sources.<Quelle>.prefetch-hits` | ja |
| Planer: Plätze im Vordergrund, je Quelle, Warteschlange | 4, 4, 200 | **fest im Code** (`AcquisitionScheduler.Limits.DEFAULT`); nur über den Builder | nein |
| Funde katalogisieren: Parallelität, Warteschlange, Gedächtnis, Wiederholung | 2, 1000, 10 000, 10 Minuten | **fest im Code** (`FindingCataloging`) | nein |
| Sofort zu ladende Funde je Stapel | 100, 1 bis 100 | `space-harvester.search.loads` | nicht geprüft |
| Adaptive Auswahl: kleiner Stapel, Wellen, Mindestzahl | 10, 3, 5 | **fest im Code** (`CandidateSelectionStrategy.Adaptive`) | nein |

Die neue Leseprobe aus Punkt 11 und die Rückfrage aus Punkt 12 bringen keine neue feste Grenze mit: Die Größe der
Leseprobe wird eine Einstellung.

## Offene Fragen an Angelo

- **F37-11 (zu Punkt 2):** „Technische Quelle“ darf keine Fallunterscheidung nach Quellentyp in der allgemeinen
  Oberfläche werden (ADR 0036, Punkt 4). Mein Vorschlag: Der Adapter nennt als Eigenschaft die Vorgabe für neue
  Quellen („in globaler Suche: aus“), FTP und NDV setzen sie. Einverstanden? Und gilt die Vorgabe „aus“ auch für
  JES und BetaView, falls dafür Quellen angelegt werden?
- **F37-12 (zu Punkt 9):** Wenn die Navigationsbefüllung allgemein wird, könnte sie auch für lokale Ordner,
  SharePoint und BSCW eingeschaltet werden. Dann indexiert bloßes Durchblättern Inhalte. Mein Vorschlag: Für jede
  Quelle ist sie standardmäßig aus und muss ausdrücklich eingeschaltet werden. Einverstanden?

## Folgen

- Ein großer technischer Bestand lässt sich einbinden, ohne die fachliche Suche und das Glossar zu überlagern.
- Jeder neue Suchweg und jede neue globale Auswertung muss den wirksamen Umfang aus `SearchScope` benutzen.
- Jeder neue Beschaffungsweg muss die Regeln der Quelle prüfen, bevor er etwas dauerhaft ablegt.
