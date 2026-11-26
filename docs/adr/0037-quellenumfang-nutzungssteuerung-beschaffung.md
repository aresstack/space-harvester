# ADR 0037: Quellenumfang, Nutzungssteuerung und Beschaffungsstrategien

- Status: **Angenommen** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in #1460, Epic #1461 Abschnitt C
  und #1475). Nutzungsoptionen und quellenweise Beschaffung sind auf main. Die Punkte 10 und 11 sind
  **Konkretisierungen vom 26.11.2026** aus dem ursprünglichen Chat; Angelo hat sie zur Gegenprüfung gegeben, sie sind
  noch nicht als ausdrückliche Entscheidung bestätigt. Die Punkte unter „Offene Fragen“ sind nicht entschieden.
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
   sich ohne neue Angaben nicht.
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
   nicht wie ein Fehler aussehen.
6. **Kein zweites Synchronisierungsmodell.** Die Beschaffung nutzt `SourceSyncOverride`, `SyncSettings`,
   `SyncMode`, `Indexing` und `IndexMarks`/`FileIndexMarks`. Die Regeln je Quelle stehen im Reiter
   „Synchronisierung“.
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
9. **Zwei unabhängige Beschaffungsarten für FTP und NDV** (#1475), einzeln oder zusammen einstellbar:
   - **Bestandsorientierte Synchronisierung:** Der konfigurierte Bereich wird vollständig inventarisiert und nach
     den Regeln erschlossen.
   - **Navigationsgetriebene Befüllung** (`navigationFill`): Beim Navigieren im Deck gehen die direkt aufgelisteten
     Ressourcen über die gemeinsame Hintergrund-Pipeline in den Bestand der passenden Wissensquelle, wie bei
     Confluence. Ziel sind COBOL-Programme, Copybooks, JCL und Prozeduren, nicht der ganze Mainframe.

   Die Zuordnung einer Deck-Sitzung zu einer Quelle verlangt denselben Host, Port und dasselbe Konto (ADR 0036,
   Punkt 5). Die navigationsgetriebene Befüllung erweitert den Inventurbereich nie.
10. **Eine Eignungsprüfung für jede automatische Beschaffung** (Konkretisierung 26.11.2026). Synchronisierung,
    Navigationsbefüllung, Hintergrundfüllung und Prefetch benutzen dieselbe Prüfung; es gibt keine zweite
    Implementierung.
    - Ladebibliotheken und eindeutig binäre Inhalte werden nie automatisch zur Textextraktion geladen.
    - Quelltext wird an Metadaten und Inhalt erkannt, nicht allein am Namen. Unbekannte Typen werden klassifiziert.
    - Es gelten die vorhandenen, einstellbaren Größen- und Beschaffungsgrenzen. Eine neue, willkürliche Grenze
      kommt nicht dazu.
    - Der ausdrückliche Zugriff auf jede andere Datei bleibt möglich.
11. **Erwerbsgründe statt Klassen** (Konkretisierung 26.11.2026). Eine Wissensressource gibt es genau einmal, ohne
    mehrfache Kopien oder Indexeinträge. Sie kann aus mehreren Gründen im Bestand sein:
    - sie liegt im konfigurierten Inventurbereich,
    - sie wurde durch Navigationsbefüllung erworben,
    - sie wurde ausdrücklich indexiert.

    Fällt ein Grund weg, wird geprüft, ob ein anderer besteht. Eine Ressource wird nicht pauschal als
    „Inventurressource“ oder „Navigationsressource“ eingestuft, denn sie kann über beide Wege gekommen sein.

    | Ereignis | Verhalten |
    | --- | --- |
    | Die Ressource ist im Quellsystem nachweislich gelöscht | Bestand und Index bereinigen |
    | Der Benutzer nimmt einen Bereich ausdrücklich aus dem Umfang | Ressourcen ohne weiteren Erwerbsgrund aus diesem Wissensbestand entfernen (siehe F37-7) |
    | Die Inventur ist unvollständig (Fehler, Tiefen- oder Mengengrenze, Abbruch) | Keine Löschung aus bloß fehlenden Inventurergebnissen |

    Ressourcen, die außerhalb des Inventurbereichs durch Navigation erworben wurden, löscht der Inventurabgleich nie.
12. **Inventur ist strikt.** Fehler, unbekannte Revisionen, fehlende ISPF-Statistiken, erreichte Grenzen
    (`maxResources`, `maxDepth`) und Abbruch gelten nie als vollständiger Bestand und nie als Löschung. Änderungszeit
    und Version zählen nur, wo sie verlässlich sind, sonst der Inhalts-Hash.
13. **Operative Live-Dienste brauchen keinen Wissensquellen-Eintrag.** BetaView, JES und freie Recherche werden über
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
| Presets „Vollständig“ und „Selektiv“ im Reiter „Synchronisierung“ | nicht gefunden |
| Punkt 8: Fund erfassen unabhängig vom Prefetch | **weicht ab**, siehe unten; Korrektur beauftragt |
| Punkt 10: gemeinsame Eignungsprüfung in der FTP-Navigationsbefüllung | **weicht ab**; Korrektur beauftragt |
| Punkt 11: Erwerbsgründe je Ressource | nicht umgesetzt; heute schützt der Inventurabgleich nur, was außerhalb seines Bereichs liegt (#1477) |

## Widersprüche und Auffälligkeiten

- **Der Fund wird heute nur bei erlaubtem Prefetch erfasst.** `KeywordSearch` überspringt das Katalogisieren, wenn
  `allowsPrefetch` für die Quelle `false` liefert. Die Hintergrundfüllung erfährt dann nichts von neu gefundenen
  Ressourcen, obwohl sie an ist. Das widerspricht Punkt 8 und ist im ursprünglichen Chat als Implementierungsfehler
  bestätigt.
- **Punkt 11 steht in Spannung zu ADR 0036, Punkt 5.** Dort (und in #1461, B.7) löscht das Entfernen einer
  Markierung nichts, und das Bereinigen ist eine eigene, bewusste Aktion. Punkt 11 entfernt beim Verkleinern des
  Umfangs dagegen selbsttätig. Siehe F37-7.
- **#1460 ist durch PR #1462 geschlossen, dessen Titel nur die Schnitte A und B nennt.** Schnitt C (quellenweiser
  Prefetch, Beschaffungsregeln) ist dennoch im Code vorhanden. Die Presets „Vollständig“ und „Selektiv“ aus #1460
  Abschnitt 3 habe ich im Code nicht gefunden.
- **`navigationFill` ist eine Option des Adapters** (`options.navigationFill` der Quelle), während `background-fill`
  und `prefetch-hits` Regeln in `SourceSyncOverride` sind. #1460 verlangt „kein zweites Sync-Modell“. Die
  Beschaffung einer Quelle wird damit an zwei Stellen eingestellt.
- **Farbe der Kennzeichnung:** #1460 nennt drei Vorschläge (hellrot vom PO, Bernstein oder Schieferblau vom AG) und
  verweist auf eine visuelle Abnahme. Eine Entscheidung ist nirgends festgehalten.

## Offene Fragen an Angelo

- **F37-1:** Welche Vorgabe gilt für neue technische Quellen (FTP, NDV), die aus dem Deck entstehen: „In globale
  Suche einbeziehen“ standardmäßig an oder aus? Für lokale Ordner fragt der Dialog heute nach.
- **F37-2:** Sollen die Presets „Vollständig“ und „Selektiv“ im Reiter „Synchronisierung“ noch kommen, oder reichen
  die einzelnen Schalter?
- **F37-3:** Soll `navigationFill` zu den Beschaffungsregeln der Quelle im Reiter „Synchronisierung“ wandern, oder
  bleibt es bewusst eine Option des FTP- und NDV-Adapters?
- **F37-4:** Welche Farbe bekommt „Nur gezielte Suche“ endgültig?
- **F37-5 (zu Punkt 8):** „Suchtreffer nachladen“ aus, „Hintergrundfüllung“ an: Soll die Hintergrundfüllung auch
  Funde laden, die eine Suche außerhalb des inventarisierten Bereichs entdeckt hat? Dann löst eine Suche mittelbar
  doch Downloads aus, nur später. Mein Vorschlag: Die Hintergrundfüllung lädt nur, was im konfigurierten Umfang
  der Quelle liegt; Funde außerhalb bleiben Kopfdaten, bis sie jemand öffnet oder indexiert.
- **F37-6 (zu Punkt 10):** „Am Inhalt erkennen“ verlangt, Inhalt zu lesen, bevor feststeht, ob er geladen werden
  darf. Mein Vorschlag: zuerst die Metadaten (bei MVS `DSORG`, `RECFM`, `LRECL`; `RECFM=U` ist eine Ladebibliothek
  und wird nie gelesen), und nur bei unklarem Typ eine kleine, begrenzte Leseprobe vom Anfang. Das Ergebnis wird je
  Revision gemerkt. Einverstanden, auch mit der Leseprobe?
- **F37-7 (zu Punkt 11):** Nimmt der Benutzer einen HLQ oder eine Bibliothek aus dem Umfang, sollen die Ressourcen
  dann sofort und selbsttätig entfernt werden, oder nach einer Rückfrage mit Anzahl („412 Ressourcen gehören zu
  keinem Bereich mehr. Entfernen?“)? Mein Vorschlag ist die Rückfrage, damit die Regel „Umschalten löscht nichts“
  (Punkt 3, ADR 0036 Punkt 5) nicht still gebrochen wird.
- **F37-8 (zu Punkt 11):** Wird die Navigationsbefüllung einer Quelle ausgeschaltet: Verlieren die so erworbenen
  Ressourcen damit ihren Erwerbsgrund (und werden entfernt), oder bleiben sie, weil nur künftig nichts mehr
  dazukommt? Mein Vorschlag: Sie bleiben.
- **F37-9 (zu Punkt 11):** Ressourcen, die nur durch Navigation erworben wurden, liegen außerhalb jeder Inventur.
  Niemand stellt je fest, dass sie im Quellsystem gelöscht oder geändert wurden. Mein Vorschlag: Sie werden beim
  nächsten Besuch ihres Ordners und beim Öffnen einzeln geprüft, und der Bestand zeigt für sie „zuletzt geprüft“.
  Reicht das, oder sollen sie zusätzlich regelmäßig einzeln nachgeprüft werden?
- **F37-10 (zu Punkt 11):** Mein Vorschlag zur Umsetzung: Nur der Grund „Navigationsbefüllung“ wird an der
  Ressource gespeichert. „Im Inventurbereich“ ergibt sich jederzeit aus der Konfiguration, „ausdrücklich
  indexiert“ aus den vorhandenen `IndexMarks`. So können gespeicherte Gründe und Konfiguration nicht
  auseinanderlaufen. Einverstanden?

## Folgen

- Ein großer technischer Bestand lässt sich einbinden, ohne die fachliche Suche und das Glossar zu überlagern.
- Jeder neue Suchweg und jede neue globale Auswertung muss den wirksamen Umfang aus `SearchScope` benutzen.
- Jeder neue Beschaffungsweg muss die Regeln der Quelle prüfen, bevor er etwas dauerhaft ablegt.
