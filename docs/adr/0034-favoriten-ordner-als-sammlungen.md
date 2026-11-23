# ADR 0034: Favoriten-Ordner als eigene Sammlungen

- Status: **Entwurf** (23.11.2026), UX-Manager abgenommen (23.11.), Architekt prüft. Vorgaben Angelos bindend, der Rest ist
  Vorschlag.
- Bezüge: [ADR 0027](0027-beobachter-statt-abfragen.md) (kein Polling), [ADR 0028](0028-ki-freigabe-je-seite.md)
  (KI-Freigabe), [ADR 0029](0029-auswahl-und-ausgabe-aus-dem-bestand.md) (Auswahl), ADR 0030/0031
  (Update-Zusammenfassung, Quellenseite), [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md) (Chat-Wissen).
  Vorbild Ordnerbaum: `FavoritesStore`/`FavoritesPanel` im Enterprise AI Client (exedra). Keine neue Bibliothek.

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

### 1. Ordnerbaum statt Liste (App)
- `Favorites` (App, `favorites.json`) wird zum Baum: Knoten = Ordner (ID, Name, Kinder) oder Favorit (der heutige
  `Favorite`-Record unverändert). Operationen und Dateiform nach `FavoritesStore` des EAC: `createFolder`,
  `renameFolder`, `remove` (Ordner samt Inhalt nur nach „wirklich …?“), `move`/`moveAll` (nie in sich selbst),
  `toggle(favorite, folderId)`. Eine flache Datei wird beim Lesen zur obersten Ebene, nichts geht verloren.
- Es bleibt **eine** Klasse `Favorites`; die flache Liste entfällt, `all()` liefert die Favoriten in Baumreihenfolge
  (heutige Aufrufer wie Stern im Baum und Vorfahren-Merker bleiben unberührt).
- Anzeige (UX-Manager): Die Favoritenansicht im Explorer (Stern unten rechts) wird ein Baum mit **demselben
  Baum-Baustein und Renderer wie der Seitenbaum**, kein zweiter. „Neuer Ordner“ als Symbolknopf mit Tooltip in der
  Leiste und im Kontextmenü; Umbenennen direkt im Baum (F2), kein Dialog; Verschieben per Drag and Drop mit deutlicher
  Einfügemarke und ohne Maus über „Verschieben nach …“; Entf entfernt. „Wirklich …?“ Ja/Nein nur bei einem Ordner mit
  Inhalt. Filter mit ✕ im Feld. Neuer Stern landet im gewählten Ordner, sonst oben.
- Ordner wählen ist eine Auswahl wie im Seitenbaum: Mitte und rechts folgen, Titel in der Tab-Zeile.

### 2. Ordner als Auswahl (ADR 0029)
- Die Auswahl `ExportPanel.Selection` bekommt eine dritte Art neben Knoten und global: **Sammlung** (Ordner-ID, Titel,
  Liste der Wurzeln). Die Wurzeln sind die Favoriten des Ordners und aller Unterordner, je so, wie die Auswahl des
  Knotens im Baum heute ist (Quelle, Space, Seite samt Unterseiten). `toCore` liefert daraus die vorhandene
  `holdings.Selection` mit mehreren Wurzeln; der Core braucht dafür nichts Neues (Überschneidungen zählen einmal).
- Wer heute `Selection` auswertet (Chat, Export, Stand, Begriffe, Graph, Eigenschaften), bekommt damit den Ordner ohne
  eigenen Zweig, soweit er über `toCore`/`resolveSelection` arbeitet. Stellen mit `sourceId`-Annahmen werden auf die
  Wurzeln umgestellt, nicht verdoppelt.
- Ändert sich der Ordner (Favorit dazu, verschoben, entfernt), meldet `Favorites.onChange`; die Auswahl wird neu
  gebildet (Beobachter, ADR 0027).

### 3. Chat
- Eine Regel überall: Ein Seiten-Favorit zählt wie die Auswahl dieser Seite im Baum (samt Unterseiten), in Chat,
  Zusammenfassung und Podcast gleich.
- Ordner gewählt = Bereich ist die Sammlung, wie bei einem Space nach ADR 0033: kein Seitentext automatisch, nur die
  Bereichszeile (Ordnername und Seitenzahl); `seiten_auflisten` ohne Knoten nennt die Favoriten des Ordners (Unterordner als Knoten),
  `seite_lesen`/`im_bereich_suchen` sind auf die Wurzeln begrenzt. Interne Suche und Websuche bleiben aus, bis der
  Benutzer sie einschaltet. KI-Freigabe wie bisher je gelieferter Seite (`releasePageOrThrow`), nie ganze Spaces,
  auch wenn ein Space-Favorit im Ordner liegt.

### 4. Leseansicht und Podcast-Ansicht des Ordners
- Heute fasst `UpdateSummarizer` ein Update thematisch zusammen; es gibt keine Inhaltszusammenfassung eines Bestands.
  **Refaktorisierung statt Kopie:** Der Summarizer wird zu einer thematischen Zusammenfassung über eine Seitenmenge
  mit zwei Materialarten: *Änderungen eines Updates* (heute) und *Bestand einer Auswahl* (neu, je Seite Titel, Pfad,
  Auszug des Lesetexts, keine Absätze alt/neu). Teilen, Zusammenführen, Prüfung der Seiten-IDs, Grenzen und
  Ausgabeform (`zusammenfassung`, `themen`) bleiben eine Implementierung; nur die Anweisung und die Eingabe je Seite
  unterscheiden sich. Streaming wie bisher.
- Ablage im Core je Schlüssel der Ebene: heute (Quelle, Update, Space), neu (Sammlung = Ordner-ID, Fingerabdruck der
  Wurzeln). Ändern sich Wurzeln oder Seiteninhalte, gilt die Zusammenfassung als veraltet, wie beim offenen Update.
  **Eine Änderung am Ordner startet nie von selbst einen KI-Lauf**, sie markiert nur „veraltet“; neu erstellt wird über
  den Symbolknopf „Zusammenfassen“. Leerer Ordner: Hinweis, kein Modellaufruf.
- Der Lauf ist ein Auftrag in der rechten Seitenleiste (x/y), BusyOverlay mit Esc-Kreis, Teilergebnisse sofort;
  Meldungen in die Statuszeile.
- Oberfläche: Die Darstellung der Zusammenfassung (Themen, Seiten gelb, Bild rechts) wird aus `SourceUpdatesView` als
  Baustein herausgelöst und von Quellenseite und Ordner gemeinsam genutzt; die Wolkenleiste der Updates gibt es beim
  Ordner (vorerst) nicht. Fehlt die Zusammenfassung, erstellt das Chat-Modell sie beim Öffnen wie bei Updates;
  „Zusammenfassen“ erstellt sie neu.
- Podcast: `UpdatePodcasts` erzeugt den Podcast aus einer Zusammenfassung; der Schlüssel wird auf die Ebene
  verallgemeinert, die Produktion bleibt dieselbe. Die Podcast-Ansicht (Kopfhörer) zeigt ihn wie bei Quelle/Space.
- KI-Freigabe: Ins Modell gehen nur Seiten mit `AiRelease.mayProcess`; andere nur als Zahl, sichtbar als „x von y
  Seiten nicht für KI freigegeben“. Ein Space-Favorit gibt nie den ganzen Space frei.

### 5. Analysen und übrige Reiter
- Ein Ordner ist eine Seite ohne eigenen Inhalt, seine Favoriten (und Unterordner) sind ihre Unterseiten. Die
  Analyse-Reiter nutzen dafür den vorhandenen Unterseiten-Unterbau (`Subpages.Below` mit `PageSelection`): Für einen
  Ordner liefert die Aggregation statt `Workspace.descendants` die aufgelöste Sammlung. Darstellung wie heute im
  Abschnitt „Aus Unterseiten“.
- Checkbox „mit Unterseiten“ (nie gesperrt): **aus** = die Favoriten direkt im Ordner, je nur die Seite selbst;
  **an** = rekursiv alle Unterordner und je Seiten-Favorit auch seine echten Unterseiten (gleiche Bedeutung wie bei
  einer Seite). Space- und Quellen-Favoriten gehen in beiden Stellungen ganz ein. Am Ordner steht sie standardmäßig
  **an** (der oberste Ordner enthält alles darunter).
- Reiter, die eine einzelne Seite voraussetzen (Bilder, Eigenschaften einer Seite), zeigen die Sammlung wie bei einem
  Space.

## Umsetzung (kleine, unabhängige PRs auf develop)

1. `Favorites` als Baum (Store, Dateiform, Migration, Tests).
2. Favoritenbaum im Explorer mit Ordnern, Verschieben, Kontextmenü.
3. `Selection` Art Sammlung, Ordnerauswahl speist Chat, Export, Stand, Graph (Chat-Scope).
4. Summarizer-Refaktorisierung: Materialart „Bestand einer Auswahl“, Ablage je Sammlung (Core, mit Test gegen Modell).
5. Zusammenfassungs-Baustein aus `SourceUpdatesView` lösen, Ordner-Leseansicht und Podcast-Ansicht.
6. Analyse-Reiter: Sammlung als Unterseiten-Aggregation.

1 und 4 sind unabhängig voneinander; 2 baut auf 1, 3 auf 1, 5 auf 3 und 4, 6 auf 3.

## Offene Punkte

- Angelo kann den beiden Annahmen widersprechen: Checkbox-Semantik am Ordner (Abschnitt 5) und Seiten-Favorit samt
  Unterseiten (Abschnitt 3). UX-Manager hat beide abgenommen.
- Updates (Wolkenleiste) für einen Ordner: Ausschnitt der Updates auf die Seiten der Sammlung; nicht in dieser Runde.
