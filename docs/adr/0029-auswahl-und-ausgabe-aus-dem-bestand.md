# ADR 0029: Auswahl und Ausgabe aus dem Bestand

- Status: **Vorgeschlagen**. Vorab entschieden (Angelo, 13.11.2026): Was links im Baum gewählt ist, bestimmt immer die
  Mitte und rechts (Lesen, Chat, Analysen, Export); der Export-Reiter bezieht sich auf den gewählten Knoten samt
  Kindern; „Hauptsache, das wird alles umgesetzt. Und es bleibt nichts offen.“ Alle übrigen Punkte sind Vorschlag.
- Bezüge: `/mnt/project-files/export-ansicht/architektur-bestand-und-ausgaben.md` (Abschnitte 3, 4 C und D, 7.2, 7.3,
  7.5). Baut auf [ADR 0027](0027-beobachter-statt-abfragen.md) (Bestand) auf. Berührt
  [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md) (Export), [ADR 0013](0013-suche-ueber-den-cache.md) (Suche),
  [ADR 0019](0019-begriffsindex-und-begriffsbeziehungen.md) (Begriffe),
  [ADR 0018](0018-medienbeschaffung-und-otp-im-export.md) (Bilder), [ADR 0023](0023-dateianhaenge-im-export.md) und
  [ADR 0026](0026-anhaenge-und-enthaltene-dateien-als-dokumente.md) (Anhänge),
  [ADR 0024](0024-aussagenanalyse-und-globaler-knowledge-graph.md) (Aussagen). Keine neue Bibliothek.

## Kontext

Die App kennt fünf Umfangstypen nebeneinander (Suchumfang je Quelle und Space, `ExportRequest(rootPageId, depth)`,
`StatementAnalysisJobs.Scope`, `TermSearch(sourceId, spaceKey)`, Synonym- und Beziehungsumfang). Eine Seite zählt im
Suchumfang als ihr Space. „Seite samt Unterseiten“ kann keine Ansicht filtern: Der Index kennt nur `PARENT_ID`, kein
Vorfahren-Feld, und eine Live-Suche lässt sich nicht auf einen Teilbaum begrenzen.

Ein Export liest heute nur dann aus dem Cache, wenn die Generation des Space bestätigt ist; sonst beschafft er neu,
auch wenn alle Seiten der Auswahl längst lokal sind. Nach einem Teilbaum-Export ist die Generation nicht bestätigt, der
nächste Export liest also wieder aus der Quelle. Bilder und Anhänge hängen an einem Export-Auftrag (`exportId`,
`assets.json` im Export-Verzeichnis) und verfallen mit ihm.

## Entscheidung

### 1. Ein Auswahl-Typ im Core

- `Selection` ist eine Menge von Wurzeln: eine Quelle, ein Space oder eine Seite samt allen Unterseiten
  (`Selection.Root.source/space/subtree`). Mehrfachauswahl im Baum ergibt mehrere Wurzeln.
- `Holdings.resolve(caller, selection)` löst sie zu `PageSelection` auf, über denselben Baum wie die Bestandsübersicht
  (`PageTreeBuilder`: Zyklen aufgebrochen, fehlende Elternseite macht eine Wurzel), ohne Quellenzugriff:
  - `pages`: je Quelle die Seiten-IDs, die der Aufrufer sehen darf, auch nur aus dem Katalog bekannte und gesperrte;
  - `counts`: Zähler über diese Seiten, jede einmal; `pages` ist das „m“, `stored()` das „n“ von „n von m Seiten lokal“;
  - `spaces`: Stand jedes berührten Space („Stand vom“);
  - `complete`: nur wahr, wenn jeder berührte Space eine bestätigte Inventur hat und jede gewählte Seite im Katalog
    steht. Ohne Inventur kennt der Cache nur einzeln geladene Seiten, deren Eltern oft fehlen; jede Ansicht, die nach
    der Auswahl filtert, zeigt dann „lückenhaft“.
- Kein Vorfahren-Feld im Index. Aufgelöst wird bei jeder Abfrage; eine verschobene Seite stimmt nach der nächsten
  Inventur von selbst.
- Rechte wie im Seitenbaum: `metadata` für jeden gewählten Space (bei einer Quelle für die Quelle, dann nur die
  sichtbaren Spaces), Seiten ohne Freigabe fehlen.
- An die Abfragen geht die aufgelöste Menge als `PageSet` (Paket `application.snapshot`, je Quelle die Seiten-IDs,
  Prüfung in O(1), dazu ein Fingerabdruck über die sortierten IDs für Cursor). So hängen Suche, Begriffe und Graph nicht
  am Bestand.

### 2. Filter „Seite samt Unterseiten“

| Ansicht | Filter | Ort |
|---|---|---|
| Suche über den Cache | Lucene-Klausel `TermInSetQuery` über das Seitenfeld des Volltextindex; Anhangstreffer folgen ihrer Seite | `PageTextIndex.search` |
| Live-Suche der Quellen | Nachfilter der Treffer; Vorabladen lädt nur Seiten der Auswahl. Ein Treffer, den der Katalog nicht kennt, fällt weg (die Auswahl ist dann ohnehin lückenhaft) | `KeywordSearch.batch` |
| Titelsuche | Lucene-Klausel über die Seiten-ID | `LucenePageSearchIndex.filter` |
| Begriffe und Vorkommen | Lucene-Klausel über das Seitenfeld der Begriffe | `TermIndex.Filter`, `TermQueries` |
| Aussagen und Graph | Prüfung je Seite an der einen Stelle, die Fokusseiten, Ziele und Begriffssuche zulässt | `GraphNeighbourhood.Options` |
| Semantische Suche und Chat | Nachbarsuche der Embeddings vor ihrer Grenze auf die Seiten der Auswahl gefiltert; der Chat (App) holt seinen Kontext über Schlagwort- und semantische Suche mit derselben Seitenmenge, Absätze kommen als Auszüge dieser Treffer | `EmbeddingIndex.nearest`, `RelevanceSearch` |

- Die Anfragen bekommen nur die Seitenmenge (`Query.within(PageSet)`, `PageSearchQuery.within(PageSet)`). Die
  Abdeckung (`counts`, `complete`) kommt aus der Auflösung, die die Ansicht zu ihrer Anfrage hält, und steht neben jeder
  gefilterten Antwort; gezählt wird über die Auflösung, nicht über die Treffer.
- Der Fingerabdruck des Cursors enthält den Fingerabdruck der Menge: Wer mit anderer Auswahl weiterblättert, bekommt
  einen ungültigen Cursor statt gemischter Seiten.
- Filtern geht als Lucene-Klausel, wo es einen Index gibt, damit Grenzen wie die höchstens gelesenen Dokumente nicht
  vor dem Filter greifen; nachgefiltert wird nur, was nicht im Cache liegt.
- Ohne Auswahl bleibt alles, wie es ist; die bisherigen Filter nach Quelle und Space bleiben.

### 3. Ausgabe aus dem Bestand

- Eine Ausgabe aus dem Bestand liest nur den Seiten-Cache, nie die Quelle (`ExportRequest.holdingsOnly`). Sie enthält
  die gespeicherten Seiten der Auswahl (`LOCAL` und `OUTDATED`, Letztere im gespeicherten Stand); eine Warnung nennt
  die Zahl der veralteten, die Ausgabe gilt dann nicht als vollständig, und im Datensatz steht bei jeder Seite die
  aktuelle Version der Kopfdaten neben `raw.revision` des gespeicherten Stands. Nur aus dem Katalog bekannte Seiten
  fehlen: Sie zählen als gefunden, nicht als geschrieben und nicht als gescheitert, eine Warnung nennt ihre Zahl.
  Gesperrte Seiten zählt die Auflösung (`counts`), die die Ansicht hält. Ohne Bestand scheitert der Auftrag
  (`NO_HOLDINGS`); „Neu erzwingen“ gibt es für ihn nicht, „Nur Fehler neu versuchen“ liest wieder nur den Cache.
- Ein Auftrag je gewähltem Space, sonst je gewähltem Teilbaum (`Holdings.outputs`): Datensatz, Manifest und Konverter
  kennen genau eine Startseite. Ein Teilbaum in einem gewählten Space oder unter einem anderen gewählten Teilbaum
  fällt weg, weil er schon in dessen Ausgabe steckt; eine Quelle steht für ihre sichtbaren Spaces im Bestand. Die
  Konverter lesen wie heute aus dem Cache; „einmal beschaffen, beliebig konvertieren“ bleibt.
- Ein vorhandenes Artefakt wird wiederverwendet, wenn Auswahl (Fingerabdruck), Space und Stand der Seiten gleich sind
  (`Exports.startFromHoldings`): gleiche Startseite, Tiefe und Formate, und je Seite des Umfangs dieselbe
  Elternseite, gespeicherte Version, Version der Kopfdaten und dieselbe Markierung als veraltet oder gesperrt wie beim
  Start der fertigen Ausgabe, dazu dieselben Kopfdaten (Titel, Labels, Attribute), derselbe gespeicherte Inhalt und
  dieselben für den Auftraggeber lesbaren Seiten. Dann kommt deren Auftrag zurück, ohne neuen Lauf; gilt die Freigabe
  des Auftraggebers nicht mehr, wird nichts wiederverwendet.
- Der bisherige Export-Auftrag mit Beschaffung (ADR 0010, auch über REST) bleibt; er entscheidet über das Neuladen
  künftig nach der Abdeckung seines Teilbaums im Bestand statt nach der bestätigten Generation: Sind alle Seiten des
  Teilbaums gespeichert und aktuell (dieselbe Regel wie die Zustände der Bestandsübersicht, nur nach einer Inventur
  des Space), liest auch er nur den Cache, und sein Ergebnis gilt als vollständig. Der ganze Space braucht weiter eine
  bestätigte Generation.

### 4. Bilder und Anhänge am Bestand

- „Bilder beschaffen“ und „Anhänge beschaffen“ laufen auf einer Auswahl (je Space mit ihrer Seitenmenge) statt auf
  einer `exportId`. Die Dateien liegen wie heute im gemeinsamen Speicher nach SHA-256.
- Was zu einer Seite gehört, liegt als Verzeichnis je Seite und Version im Bestand, in Lucene neben dem Cache: je Eintrag
  Art (Bild oder Dokument), SHA-256, Größe und Medientyp, ohne Dateinamen im Protokoll. Ein Export erzeugt
  `assets.json` und `attachments.json` aus diesen Verzeichnissen und kopiert die Dateien; nichts wird für eine Ausgabe
  neu geladen.
- Ein Mechanismus für alle Bildzugriffe: Auch der bisherige Export-Auftrag liest und schreibt dieselben Verzeichnisse.
  Das Verzeichnis ist die schon vorhandene Ablage der mitgebrachten Dateien eines Imports (`ImportedAssets`, in der
  Lucene-Ablage der Anreicherungen, mit der Seite entfernt); jede Beschaffung, deren Auftraggeber die ganze Quelle
  lesen darf, legt dort ihre fertig geprüften Seiten ab, die Lese-Ansicht zur Version daneben (`ReadingViewStore`).
  Läufe ohne dieses Recht legen nichts ab, damit gesperrte Einträge niemandem fehlen.
- Die Folgeaufträge einer Ausgabe aus dem Bestand tragen `holdings=true` und lesen nur die Verzeichnisse, nie die
  Quelle; Seiten ohne Verzeichnis zu ihrer Version nennt der Auftrag mit ihrer Zahl (`NOT_IN_HOLDINGS`) und endet
  unvollständig statt anzuhalten. „Neu erzwingen“ gibt es für sie nicht.
- Eine neue Version der Seite macht ihr Verzeichnis veraltet; der nächste Lauf von „Bilder beschaffen“ bzw. „Anhänge
  beschaffen“ für die Auswahl ergänzt nur, was fehlt.

### 5. Nicht Teil dieser Entscheidung

- **Umfang** (Schritt B der Architektur: was der Benutzer lokal halten will, als gespeicherter Wert) und das Umstellen
  von „Herunterladen“ vom Export-Auftrag auf den Abgleich. Bis dahin enthält eine Ausgabe, was der Bestand hält.
- **REST** für Auswahl, Filter und Ausgabe aus dem Bestand; Gegenstück eigener Punkt (`docs/PARITY.md`).
- Die Oberfläche (Auswahl-Objekt der App, Export-Reiter, Chat) bauen die Oberflächen-Stränge.

## Umsetzung

Je ein kleiner Pull Request, Core zuerst:

1. Dieses ADR, `Selection`, `PageSelection`, `PageSet`, `Holdings.resolve`, Anmeldung über `Workspace`.
2. Suche: Cache, Live-Suche, Titelsuche, Cursor.
3. Begriffe und Vorkommen.
4. Aussagen und Graph.
5. Ausgabe aus dem Bestand samt Wiederverwendung; Export-Auftrag entscheidet nach Abdeckung.
6. Verzeichnis der Bilder und Anhänge je Seite und Version, Folgeaufträge der Ausgabe aus dem Bestand daraus.
7. Beschaffen auf einer Auswahl ohne Export-Auftrag.

## Folgen

- Eine Auswahl im Baum gilt in jeder Ansicht gleich, statt fünf Umfangstypen zu pflegen.
- Jede zusammengefasste Ansicht sagt, wie viel der Auswahl sie sieht; „alle Begriffe des Space“ liest sich nicht mehr
  als vollständig, wenn nur ein Teil lokal ist.
- Ein Export einer schon geladenen Auswahl braucht keine Verbindung zur Quelle mehr; Bilder und Anhänge verfallen nicht
  mehr mit dem Export-Auftrag.
- Die Auflösung rechnet bei jeder Abfrage über den Katalog des Space; für sehr große Spaces kann später ein Zwischenstand
  helfen, den die Meldungen des Bestands ungültig machen.
