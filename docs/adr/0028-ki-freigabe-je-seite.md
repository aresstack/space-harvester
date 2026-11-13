# ADR 0028: KI-Freigabe je Seite

- Status: **Vorgeschlagen**. Vorab entschieden (Angelo, 13.11.2026, 19:24 bis 19:35 MEZ):
  - Der KI-Haken an der Quelle ist ein Ausschluss. Ausnahme ist eine Liste freigegebener Seiten (Whitelist), in die der
    Benutzer Seiten durch seine Interaktion mit dem Chat einträgt.
  - Eine Seite auf der Liste ist für **alle** KI-Auswertungen freigegeben (Embeddings, Reranking, Aussagen,
    Themenüberschriften), ohne feinere Unterscheidung.
  - Die Einstellungen führen die Liste auf (nach Space und Knoten gebündelt, je Gruppe entfernbar). Entfernen löscht
    auch die KI-Ergebnisse der Seite.
  - Die Liste liegt im Core neben dem Cache, nicht in der YAML, und ist effizient („Hashmap oder sowas in der Art“).
  Alle übrigen Punkte sind Vorschlag.
- Bezüge: `/mnt/project-files/export-ansicht/architektur-bestand-und-ausgaben.md` (Abschnitt 7.6, Nachträge 19:27 bis
  19:35 MEZ), `entwurf-export-ansicht.md` (Nachträge 4 bis 6). Baut auf [ADR 0027](0027-beobachter-statt-abfragen.md)
  (Meldungen statt Abfragen) und [ADR 0029](0029-auswahl-und-ausgabe-aus-dem-bestand.md) (Auswahl) auf. Berührt
  [ADR 0020](0020-semantische-absaetze.md) (Absätze, Navigation, Themen) und
  [ADR 0024](0024-aussagenanalyse-und-globaler-knowledge-graph.md) (Aussagen). Keine neue Bibliothek.

## Kontext

Ob eine Seite an den KI-Dienst gehen darf, entscheidet heute allein ihre Quelle: `ai.external-processing-sources`
(`AiServiceSettings.mayProcessExternally(sourceId)`). Die Prüfung steckt einzeln in jedem Verbraucher und nimmt nur die
Quelle:

| Verbraucher | Prüfung heute |
|---|---|
| Embeddings (`EmbeddingProcessor`) | `covers(sourceId)` |
| Absätze und Navigation samt Themenüberschriften (`PassageProcessor`, `NavigationProcessor`, `ChatNavigationLabeler`) | `embedding.covers(sourceId)`, `mayLabel(sourceId)` |
| Satzanalyse mit dem Chat-Modell (`StatementProcessor`, #108) | `StatementChatModels.mayProcessExternally(sourceId)` |
| Reranking (`ModelReranker`, `ExcerptPriority`) | je Treffer die Quelle |
| Bildbeschreibungen (`Assets.describer`, `PageImageAnalysis`) | `mayDescribe(sourceId)`; „einmalig beschreiben“ je Seite speichert nichts |
| Synonyme, Abkürzungen, Glossar-Einordnung | je Beleg die Quelle |

Die Aussagenanalyse nach ADR 0024 läuft lokal (MaltParser) und fragt den KI-Dienst nie; sie braucht keine Freigabe.

Die Prädikate für Embeddings und Reranking lesen einen festen Stand von `AiServiceSettings` und werden nur neu gebaut,
wenn sich diese Einstellungen ändern. Ein Entzug je Quelle (`ReleaseRecomputation`) entfernt Embeddings und
Satzanalyse, rechnet Absätze und Navigation nach Struktur neu und lässt gespeicherte Bildbeschreibungen stehen. Der
Startabgleich entscheidet je Quelle.

## Entscheidung

### 1. Eine Prüfung im Core

- `AiRelease.mayProcess(PageKey)` ist die einzige Frage: Quelle in `ai.external-processing-sources` **oder** Seite auf
  der Liste. Jeder Verbraucher oben fragt sie mit dem Schlüssel der Seite, um die es geht; die Prüfung je Quelle
  (`mayProcessExternally(sourceId)`) bleibt nur für „ganze Quelle freigegeben“ (etwa den Startabgleich und die
  Hintergrundauswertungen, die eine ganze Quelle betreffen).
- Die Prüfung liest bei jedem Aufruf den aktuellen Stand: Ein Eintrag oder ein Entfernen wirkt ab sofort, auch für
  schon eingereihte Berechnungen, ohne Prädikate neu zu bauen.
- Text des Benutzers (Suchanfrage, Chat-Frage) prüft niemand; er stammt nicht aus einer Quelle.
- Die Synonymsuche fragt je Fundstelle deren Seite: Textstellen einer Seite der Liste gehen an das Modell (und an das
  Embedding der Vorauswahl), die übrigen Textstellen derselben Quelle nicht. Ein Paar ohne freigegebene Fundstelle auf
  einer Seite bleibt übersprungen.
- Das Auflösen von Abkürzungen und die Glossar-Einordnung behalten die Prüfung je Quelle. Beide laufen als Auftrag
  über eine ganze Quelle und ersetzen deren Ergebnis als Ganzes; ein Lauf nur über die Seiten der Liste würde die
  Befunde der übrigen Seiten als fehlend oder veraltet führen. Für sie braucht es weiter die Freigabe der Quelle.

### 2. Die Liste

- Port `PageReleases`, je Seite ein Eintrag: Quelle, Seite, Space, der Knoten, über den sie hineinkam (Seite samt
  Unterseiten oder Space, wie in der Auswahl), Herkunft (`chat`) und Zeitpunkt.
- Ablage in Lucene neben dem Cache (`cache/page-releases`, eigenes kleines Verzeichnis wie die Warteschlange der
  Hintergrundfüllung, Aufbau `page-releases/v1`), ein Commit je Änderung. Im Arbeitsspeicher eine Hash-Menge der
  Seitenschlüssel, beim Öffnen einmal geladen und bei jeder Änderung mitgeführt; `mayProcess` liest nur sie.
- Wird eine Quelle entfernt, entfallen ihre Einträge mit ihren übrigen Daten (`SourceDataStores`).
- Änderungen meldet die Liste an Beobachter (ADR 0027); die Oberfläche fragt nie ab.

### 3. Eintragen

- Der Chat trägt beim Senden die Seiten der links gewählten Auswahl ein: die lokal gespeicherten Seiten der aufgelösten
  Auswahl (`Holdings.resolve`, ADR 0029), mit der Wurzel der Auswahl als Knoten. Was nicht lokal ist, kommt nicht auf
  die Liste; der Chat lädt die Auswahl vorher wie der Export. Neue Unterseiten kommen erst mit dem nächsten Senden dazu.
- Ein Eintrag reiht die KI-Auswertungen der neuen Seiten im gedrosselten Hintergrund der Anreicherungen ein, wie eine
  neue Freigabe je Quelle, ohne Abruf von der Quelle. Bis sie fertig sind, findet die Suche die Seiten über ihre
  Schlagworte.
- Vor dem Senden sagt der Core, wie viele Seiten der Auswahl neu auf die Liste kämen, damit der Chat es nennen kann.

### 4. Entfernen

- Je Seite, je Gruppe (Knoten) oder je Space. Entfernt werden die KI-Ergebnisse der Seiten, die danach nicht mehr
  freigegeben sind (eine Seite einer freigegebenen Quelle behält ihre):
  - Embeddings samt Vektoren, Ergebnis und Fakten der Satzanalyse, auch die ihrer Anhänge (ADR 0026);
  - Absätze und Navigation rechnen nach Struktur neu (`source-not-approved`), die Themenüberschriften entfallen damit,
    auch die der Anhänge: Ihre Stände mit Modell gelten dann als veraltet und rechnen im selben Hintergrund ohne
    Modell neu;
  - die Bildbeschreibungen der Seite: ihr Ergebnis `images`, ihr Text im Volltextindex und die gespeicherte
    Beschreibung selbst, sobald keine freigegebene Seite mehr ein Bild mit demselben SHA-256 trägt. Verliert die Seite
    die Freigabe, während das Modell beschreibt, prüft die Beschreibung nach dem Speichern erneut und nimmt zurück,
    was sie eben ablegte.
  - Reranking speichert nichts; es fragt ab sofort nicht mehr.
- Derselbe Weg gilt für den Entzug je Quelle: Auch dort entfallen künftig die Bildbeschreibungen. Damit gibt es eine
  Regel für beide Wege. Ist nur ein Vision-Modell eingestellt (kein Embedding- und kein Chat-Modell), läuft beim
  Entzug nur diese Rücknahme.
- Die Rücknahme der Bildbeschreibungen läuft im gedrosselten Hintergrund der Neuberechnung, je höchstens 100 Seiten,
  und prüft bei der Ausführung je Seite erneut: Ist sie inzwischen wieder freigegeben, bleibt alles stehen. Eine
  Beschreibung bleibt gespeichert, solange eine freigegebene Seite irgendeiner Quelle ein Bild mit demselben SHA-256
  trägt; der Bildanhang bleibt im Volltextindex mit seinem Dateinamen auffindbar.
- Der Startabgleich nimmt keine Bildbeschreibungen zurück. So bleibt „Nur diese Seite einmalig beschreiben“ (speichert
  die Beschreibungen, aber keine Freigabe) über Neustarts erhalten.

### 5. Startabgleich

- `ReleaseRecomputation.reconcileOnStart` entscheidet je Seite mit `mayProcess(PageKey)` statt je Quelle. Für eine
  nicht freigegebene Quelle rechnen so die Seiten der Liste mit Modell nach, die übrigen behalten ihre Fallbacks;
  Seiten mit Ergebnissen, die weder Quelle noch Liste decken, verlieren sie wie bei einem Entzug.

### 6. Für die Oberfläche

- Liste gebündelt nach Quelle, Space und Knoten mit Anzahl, je Seite Titel aus dem Cache, Zeitpunkt und ob sie
  indexiert ist (Embedding vorhanden); Entfernen je Seite, Gruppe oder Space; Anzahl neuer Seiten einer Auswahl. Alles
  über `Workspace`, mit Meldung bei jeder Änderung.
- Im Core `PageReleaseService` (über `Holdings.storedPages`), in der App `Workspace`: `releasePagesForChat` und
  `newPageReleases` (Auswahl), `pageReleaseGroups` und `releasedPages` (Liste), `removePageReleases`,
  `removePageReleaseGroup` und `removePageReleaseSpace` (Entfernen), `subscribePageReleases` (Meldungen) und
  `imagesReleased(sourceId, pageId)` für den Reiter „Bilder“. Nur Spaces, die der Aufrufer sehen darf, erscheinen.
- Die Tabelle unter Einstellungen › Sicherheit und die Statuszeile des Chats bauen die Oberflächen-Stränge.

### 7. Nicht Teil dieser Entscheidung

- Der Chat selbst ([ADR 0012](0012-core-als-gemeinsame-bibliothek.md) bekommt dafür einen Nachtrag) und seine
  Statuszeile.
- **REST** für die Liste; Gegenstück eigener Punkt (`docs/PARITY.md`).

## Umsetzung

Je ein kleiner Pull Request, Core zuerst:

1. Dieses ADR, `PageReleases` mit Lucene-Ablage und Hash-Menge, Löschen mit der Quelle, `AiRelease` mit Meldungen.
2. Alle Verbraucher prüfen je Seite; Eintragen reiht die Auswertungen ein; Entfernen löscht die Ergebnisse samt
   Bildbeschreibungen, auch beim Entzug je Quelle.
3. Startabgleich je Seite; `Workspace`: Eintragen aus einer Auswahl, gebündelte Liste, Entfernen, Anzahl neuer Seiten.

## Folgen

- Eine einzige Freigabeprüfung statt sieben; neue KI-Auswertungen übernehmen die Liste von selbst.
- `docs/CONFIGURATION.md` („Absätze gehen nur aus Quellen in `ai.external-processing-sources` an den Dienst, sonst
  nie“) gilt nicht mehr wörtlich: auch die Seiten der Liste.
- Ein Chat über einen großen Space trägt tausende Seiten ein; die Hash-Menge hält das ohne Abfrage je Berechnung, die
  Lucene-Ablage ohne Zusatzabhängigkeit.
- Bildbeschreibungen bleiben nach einem Entzug nicht mehr lesbar; wer sie wieder braucht, gibt die Seite erneut frei.
