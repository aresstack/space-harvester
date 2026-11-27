# ADR 0034: Favoriten-Ordner als eigene Sammlungen

- Status: **Angenommen** (23.11.2026) für Angelos Vorgaben; Zuschnitt von UX-Manager und Architekt abgenommen. Die
  offenen Punkte unten gelten als Empfehlung, bis Angelo widerspricht.
- Teilweise geändert durch [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md) (27.11.2026): Favoriten gehören
  zu einem Projekt, dieselbe Quelle darf in mehreren Projekten vorkommen, und es gibt die Wurzel „nur Seite“.
- Teilweise geändert durch [ADR 0044](0044-buch-als-ueberblick-eines-ordners.md) (27.11.2026): Hat ein Ordner ein
  Buch, zeigt die Leseansicht das Buch, und der Überblick aus Abschnitt 7 startet dort nicht mehr von selbst.
- Bezüge: [ADR 0027](0027-beobachter-statt-abfragen.md) (kein Polling), [ADR 0028](0028-ki-freigabe-je-seite.md)
  (KI-Freigabe), [ADR 0029](0029-auswahl-und-ausgabe-aus-dem-bestand.md) (Auswahl), ADR 0030/0031
  (Update-Zusammenfassung, Quellenseite), [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md) (Chat-Wissen).
  Vorbild für das Verhalten des Ordnerbaums: `FavoritesStore`/`FavoritesPanel` im Enterprise AI Client (exedra), nicht
  kopiert. Keine neue Bibliothek.
- Begriffe: im Code **Collection**, in der Oberfläche **Sammlung** bzw. Ordner. Symbol: Ordner mit Stern, damit er
  sich vom Space unterscheidet.

## Vorgaben Angelos (23.11.2026, bindend)

1. Favoriten bekommen Ordner, beliebig verschachtelt, mit Verschieben wie im Enterprise AI Client.
2. Ein Ordner ist wählbar und verhält sich wie ein Oberknoten (Space): Leseansicht mit KI-Zusammenfassung über seine
   Quellen, Podcast-Ansicht mit Podcast aus dieser Zusammenfassung, Analysen und übrige Reiter funktionieren.
3. Die Favoriten eines Ordners gelten als virtuelle Unterseiten; „mit Unterseiten“ aggregiert rekursiv alle Kinder und
   Kindeskinder. Der oberste Ordner enthält alles darunter.
4. Ist ein Ordner gewählt, arbeitet der Chat nur mit dessen Quellen. Interne Suche und Websuche bleiben optionale
   Schalter, standardmäßig aus (ADR 0033).
5. **DRY**: vorhandenen Code direkt wiederverwenden oder erst refaktorisieren und dann wiederverwenden; nie kopieren
   und abändern, keine zweite Variante einer fast gleichen Funktion.

## Entscheidung

### 1. Ein Favorit bedeutet überall dasselbe
Ein Favorit steht für dieselbe Menge wie die Auswahl seines Knotens im Baum (ADR 0029): eine Seite samt Unterseiten,
ein Space oder eine Quelle ganz. Chat, Export, Stand, Zusammenfassung, Podcast und Analysen lösen damit dieselbe Menge
auf; der Core braucht keine Wurzel „nur Seite“. Es gibt **eine** Abbildung `Favorite → holdings.Selection.Root`,
abgeleitet aus der heutigen `toCore`-Logik.

### 2. Ordnerbaum (App)
- Das Verhalten des EAC wandert in die vorhandene Klasse `Favorites` (Jackson, `onChange` bleibt); `all()`,
  `contains()`, `toggle()` bleiben für heutige Aufrufer gleich. API für alle Folge-PRs:
  `tree()`/`folders()`, `Folder(id, name, children)`, `favoritesIn(folderId, rekursiv)`, `createFolder`,
  `renameFolder`, `remove`, `move`/`moveAll` (Block in Baumreihenfolge, nie in sich selbst), `toggle(favorite, folderId)`,
  `onChange`. Ein Favorit ist im ganzen Baum einmalig, Ordner dürfen gleich heißen, alles über IDs.
- Datei `favorites.json` mit Versionsfeld. Die flache Datei wird ausdrücklich umgewandelt (oberste Ebene), vorher
  einmalig gesichert. **Eine unlesbare Datei kommt in Quarantäne und wird nie überschrieben** (heute ergibt ein
  Lesefehler eine leere Liste und der nächste Stern überschreibt die Datei).
- Der Stern läuft über **eine** Methode, die den Zielordner kennt; `PageDetailPanel.toggleFavorite` und
  `Explorer.favorite` werden zusammengeführt.
- Oberfläche (UX-Manager): Die Favoritenansicht wird aus dem Explorer in eine eigene `FavoritesView` gelöst. Der Baum
  nutzt ein **generisches Ordnerbaum-Steuerelement mit Drag and Drop in `app.ui.controls`** und denselben Renderer wie
  der Seitenbaum. Soll es auch der EAC nutzen, zuerst upstream umbauen, dann per Sync übernehmen. „Neuer Ordner“ als
  Symbolknopf mit Tooltip in der Leiste und im Kontextmenü; Umbenennen direkt im Baum (F2); Verschieben per Ziehen mit
  deutlicher Einfügemarke und ohne Maus über „Verschieben nach …“; Entf entfernt. „Wirklich …?“ Ja/Nein nur bei einem
  Ordner mit Inhalt. Filter mit ✕ im Feld. Ein neuer Stern landet im gewählten Ordner, sonst oben.
- Ordner wählen ist eine Auswahl wie im Seitenbaum: Mitte und rechts folgen, Titel in der Tab-Zeile.

### 3. Auswahl als versiegelter Typ
- `ExportPanel.Selection` wird ein versiegelter Typ `Node | All | Collection` mit `roots()`; `toCore` verpackt nur
  `roots()`. Heute heißt `global()` `sourceId == null`, eine Sammlung würde sonst als „Alle Wissensquellen“ gelten.
- `Collection(folderId, title, roots, withSubfolders)`: Die aufgelösten Wurzeln stehen in `equals`, damit eine Änderung
  am Ordner ab der nächsten Frage gilt (`ChatPanel.apply` überspringt gleiche Auswahlen). `withSubfolders` ist der
  Zustand der Checkbox (Abschnitt 6).
- Stellen mit direktem Zugriff auf `sourceId`/`spaceKey`/`pageId`, die auf `roots()` umgestellt werden: Export-Abgleich
  (`Selection.covers/matches`), Export-Übersicht, Inhaltsweiche (`ContentView.selection`), Glossar (`GlossaryTab`,
  `SelectionTerms`), Holdings-Abos, Löschfragen, Hinweis zur KI-Freigabe, Laden-Knopf.
- Ein gemeinsamer Helfer „Holdings je Wurzel einer Selection“ ersetzt die drei Kopien in `ChatPanel`, `ExportPanel` und
  `PageDetailPanel` und beobachtet zusätzlich `Favorites.onChange`.
- „Löschen“ im Stand wirkt bei einer Sammlung nur auf die aufgelösten Seiten, nie auf eine ganze Quelle.

### 4. Core: nachsichtige Auflösung und Fingerabdruck
- Holdings bekommt eine **nachsichtige Auflösung** mit den Rechten des Aufrufers: nicht sichtbare oder unbekannte
  Wurzeln (etwa veraltete Favoriten) werden übersprungen und als `skippedRoots` gemeldet, statt die ganze Auflösung
  zu brechen. Die Oberfläche zeigt „n Favoriten nicht verfügbar“.
- `Holdings.fingerprint(caller, PageSelection)` über `loadedVersion`, `stale`, `locked` aus dem Katalog; die Logik
  wird aus `Exports.holdingsState` verlegt, Exports nutzt sie mit. Berechnet beim Lesen, ohne Scan und ohne Abfrage.

### 5. Chat
- Ordner gewählt = Bereich ist die Sammlung, wie bei einem Space nach ADR 0033: kein Seitentext automatisch, nur die
  Bereichszeile (Ordnername und Seitenzahl). Interne Suche und Websuche bleiben aus, bis der Benutzer sie einschaltet.
- `seiten_auflisten` ohne Knoten nennt die Favoriten des Ordners; Unterordner erscheinen als virtuelle Knoten
  `ordner/<id>`, geliefert von einem Knotenlieferanten der App in `ChatTools`, geprüft wie `allows()`. Der Core-Typ
  bleibt `PageSelection`.
- `ChatNode` bekommt eine Variante für Sammlungen (Ablage und Bereichsschlüssel).
- Die Leseansicht des Ordners setzt den Chat-Bereich nicht auf eine Seite; eine daraus geöffnete Seite verengt ihn nach
  ADR 0033 §9.
- KI-Freigabe weiter je gelieferter Seite (`releasePageOrThrow`), nie ein ganzer Space, auch bei einem Space-Favoriten.
- Test: Ein Favorit wird entfernt, die nächste Frage lehnt die Seite ab.

### 6. Analysen und übrige Reiter: der Ordner als breite Ebene
- Der Ordner läuft wie ein Space über die breite Ebene (`overviewSelection`, `toCore`, `resolveSelection`), nicht über
  `Subpages.Below`. Subpages, Below und die Seitenreiter bleiben unverändert.
- Die Checkbox „mit Unterseiten“ (nie gesperrt) steht im Fuß der breiten Ebene: **aus** = die direkten Favoriten des
  Ordners, **an** = zusätzlich alle Unterordner rekursiv. Am Ordner steht sie standardmäßig **an** (UX-Manager: der
  oberste Ordner enthält alles darunter). Jeder Favorit zählt nach Abschnitt 1.
- Reiter für Einzelseiten (Bilder, Absätze, Verweise) bleiben wie beim Space leer. Wer sie später für Sammlungen will,
  baut sie für alle breiten Ebenen gemeinsam.

### 7. Leseansicht und Podcast-Ansicht des Ordners
- **Engine verallgemeinern statt kopieren:** Die Zusammenfassung von `UpdateSummarizer` wird eine thematische
  Zusammenfassung über eine Seitenmenge mit zwei Materialarten: *Änderungen eines Updates* (heute) und *Bestand einer
  Auswahl* (neu, je Seite Titel, Pfad, Auszug des Lesetexts). Teilen, Zusammenführen, Prüfung der Seiten-IDs, Plan-Modell,
  parallele Teile, Streaming und Ausgabeform bleiben eine Implementierung. Vorarbeit ohne Verhaltensänderung:
  Seitenaliase auf `PageKey`, `InputPage` mit `sourceId`, ChildJobs aus `ChangeLog` lösen; die Ausgabe für Updates bleibt
  byteweise gleich (`UpdateSummaryTest`).
- **Ablage nicht nach Ordner-ID.** Schlüssel des Bereichs: Update (wie heute) oder Auswahl =
  `sha256(Version, caller.jobOwner(), kanonische Auswahl)`; kanonisch = Wurzeln sortiert, ohne Doppelte, je mit
  Unterseiten-Flag (Vorbild `PagePodcasts.key`). Gemeint sind die aufgelösten Wurzeln nach Anwendung von
  `withSubfolders`, so bekommen beide Stellungen der Checkbox getrennte Zusammenfassungen; das Unterseiten-Flag ist nach
  Abschnitt 1 immer gesetzt und bleibt nur für eine spätere Wurzel „nur Seite“ im Schlüssel. Die Core-API nimmt eine `holdings.Selection`; Ordner-ID und Name sind
  nur Beschriftung der App. So überschreiben sich Benutzer auf dem Server nicht und sehen keine fremden Inhalte.
- **Prüfung beim Lesen:** Jedes Lesen prüft Rechte und KI-Freigabe; `ChangeLog.valid()` wird dafür zu einem gemeinsamen
  Helfer verallgemeinert. Entzug deckt der vorhandene Weg (`ChangeLog.withdrawn`) mit demselben Prädikat.
- **Veraltet** = Fingerabdruck der Wurzeln oder Inhalte (Abschnitt 4) hat sich geändert. **Eine Änderung am Ordner
  startet nie von selbst einen KI-Lauf**, sie markiert nur „veraltet“; neu erstellt wird über den Symbolknopf
  „Zusammenfassen“. Fehlt die Zusammenfassung bei einem nie zusammengefassten Ordner, startet sie beim Öffnen wie bei
  Updates. Leerer Ordner: Hinweis, kein Modellaufruf. Feste Regel: Der automatische Lauf beim Öffnen ist wie an
  Quelle und Space ein nachrangiger Folgeauftrag (`jobs.submit(UPDATE_SUMMARY)`), ein Klick auf „Zusammenfassen“
  läuft über `submitInteractive`.
- **Aufräumen:** Eine neue Zusammenfassung ersetzt die vorige desselben Platzes samt Podcasts. Beim Löschen eines
  Ordners ruft die App nach „wirklich …?“ `forget(caller, Selection)` auf; Rückfallnetz ist die vorhandene
  Aufbewahrungsfrist.
- **Seitenbudget** für „Bestand“ als Schlüssel in der einen YAML (beide Loader, `ConfigurationParityTest`); Seiten
  darüber nur als Zahl, wie „nicht lesbar“.
- **KI-Freigabe:** Ins Modell gehen nur Seiten mit `AiRelease.mayProcess`; sichtbar „x von y Seiten nicht für KI
  freigegeben“. Ein Space-Favorit gibt nie den ganzen Space frei.
- Oberfläche: Der Baustein `SummaryReport` wird aus `SourceUpdatesView` gelöst (paketintern in `app.ui.content`);
  `SkyLayer` wird getrennt in Kugel/Busy über dem Scrollbereich und optionalen Himmel. Quellenseite und Ordner nutzen
  ihn gemeinsam, der Ordner ohne Wolkenleiste. Der Lauf ist ein Auftrag in der rechten Seitenleiste (x/y),
  BusyOverlay mit Esc-Kreis, Teilergebnisse sofort; Meldungen in die Statuszeile.
- Podcast: `UpdatePodcasts` wird auf einen Bereichsschlüssel verallgemeinert (gleicher Schlüssel für Updates), die
  Produktion bleibt dieselbe. Die Podcast-Ansicht (Kopfhörer) zeigt ihn wie bei Quelle/Space.

## Umsetzung

Alle PRs aus Gruppe 1 und 2 starten sofort und gleichzeitig gegen develop. Jede Funktion aus Gruppe 3 baut nur auf
Gruppe 1 und 2 auf, nie auf eine andere Funktion; sie kann gegen die Typen aus C1 und eine Fake-Liste vorarbeiten.

**Gruppe 1, Verträge**
- C1: `Selection` versiegelt mit `Collection(folderId, title, roots, withSubfolders)`, ohne Verhaltensänderung.
- C2: Schlüssel des Zusammenfassungs-Bereichs (Update | Auswahl mit Besitzer und Fingerabdruck) in der Ablage; nur der
  Update-Fall angeschlossen.
- C3: Holdings: nachsichtige Auflösung mit `skippedRoots`, `Holdings.fingerprint` (aus Exports verlegt).

**Gruppe 2, Umbau ohne Verhaltensänderung**
- 1: `Favorites` als Baum (Store, Datei, Umwandlung, Sicherung, Quarantäne).
- 2a: Favoritenansicht aus dem Explorer in `FavoritesView` lösen.
- 2c: Generisches Ordnerbaum-Steuerelement mit Drag and Drop in `app.ui.controls`.
- 4a: Zusammenfassungs-Engine verallgemeinern (Ausgabe byteweise gleich).
- 5a: `SummaryReport` aus `SourceUpdatesView` lösen, `SkyLayer` trennen.
- 5b: `UpdatePodcasts` auf Bereichsschlüssel.

**Gruppe 3, Funktionen**
- 2b: Ordnerbaum (1, 2a, 2c).
- 3b: Chat (C1, C3).
- 3c: Export und Stand (C1).
- 3d: Graph und Begriffe (C1).
- 4b: Materialart „Bestand einer Auswahl“ mit Budget, Streaming, Plan-Modell und parallelen Teilen; echter Test über
  Pyloros gegen Ollama (4a, C2, C3).
- 5c: Lese- und Podcast-Ansicht des Ordners (5a, 5b, 4b, C1).
- 6: Checkbox im breiten Fuß (C1, 1).

## Offene Punkte

- Angelo kann widersprechen: Checkbox am Ordner (aus = direkte Favoriten, an = mit Unterordnern, Vorgabe an) und
  Seiten-Favorit samt Unterseiten überall.
- Updates (Wolkenleiste) für einen Ordner: Ausschnitt der Updates auf die Seiten der Sammlung; nicht in dieser Runde.
