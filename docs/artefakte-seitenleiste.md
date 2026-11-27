# „Artefakte“ in der Seitenleiste: Entwurf

Entwurf des UX-Strangs vom 27.11.2026 zu [ADR 0047](adr/0047-artefakte-des-projekts.md), Abschnitt 5, mit den
Entscheidungen des Architekten (Abschnitt 5 unten). Alle Beispiele sind erfunden, alle Steuerelemente gibt es schon in
`app.ui.controls`. Was Angelo entschieden hat, steht im ADR.

## 1. Die Zeile in der Seitenleiste

```
 [ Wissensquellen | Artefakte ]                    (o) (o)
 [ Titel filtern …                                  🔍 ]
 ──────────────────────────────────────────────────────
 ▤  Betriebshandbuch Lagerverwaltung             14:02
 ◔  Ablauf Wareneingang                          13:40
 ▥  Lagerprozesse                    [Veraltet]  23.11.
 ▤  Notizen Jahresabschluss                      19.11.
```

Die Zeichen stehen nur für gezeichnete Symbole. Kopf ist der `SideHead` mit der Wippe an der Stelle der
Überschrift und den zwei Symbolknöpfen rechts wie heute; darunter sein Filterfeld. Kein zweites Suchfeld.

Eine Zeile je Artefakt, einzeilig:

1. **Symbol der Art** (Dokument, Grafik, Buch): das Symbol, das die App für diese Art heute schon zeigt. Fehlt
   eines, bitte bei mir melden statt eines zu erfinden.
2. **Titel.** Reicht die Breite nicht, endet er mit „…“; der volle Titel steht im Tooltip (`TreeTextTip`), beim Buch
   dazu der Ordner, aus dem es geschrieben wurde. Dokumente und Grafiken haben keinen Knoten im Baum und zeigen
   deshalb keine Herkunft.
3. **Marke „Veraltet“**, nur beim Buch, in Orange wie in den Analysen (#1619). Die Marke ist der Knopf: Ein Klick
   erzeugt eine neue Auflage. Die alte bleibt, weil Bücher versioniert sind. Rot nur bei „Fehlgeschlagen“.
4. **Zeit** der letzten Änderung, rechtsbündig, gedämpft: heute als Uhrzeit, sonst als Datum.

Wird die Leiste sehr schmal, weicht zuerst die Zeit, dann wird der Titel gekürzt. Symbol und Marke bleiben.

Die gewählte Zeile trägt den blauen Rahmen wie ein Suchtreffer. Leer zeigt die Liste über `PlaceholderList` den
Satz „In diesem Projekt gibt es noch keine Artefakte.“

**Klick** öffnet das Artefakt in der Mitte. **Enter** öffnet, **Entf** löscht nach Rückfrage.

**Rechtsklick-Menü**, nur was für die Art gilt:

- Öffnen
- Ausgeben ▸ (die Formate der Art)
- Umbenennen
- Neue Auflage erzeugen (nur Buch)
- Ordner in den Wissensquellen zeigen (nur Buch; schaltet die Wippe um, weil man es so gewählt hat)
- Löschen … („Artefakt mit allen Fassungen wirklich löschen?“ Ja/Nein)

Die Auflage eines Buchs wählt man am Buch in der Mitte, nicht in der Liste. Die Liste zeigt je Buch eine Zeile.

## 2. Der Übergang

**Die Mitte folgt der Wippe.** Steht links „Wissensquellen“, zeigt die Mitte die Seite wie heute. Steht links
„Artefakte“, zeigt die Mitte die geöffneten Artefakte. Das ist dieselbe Regel wie überall: Die Auswahl links
bestimmt die Mitte. Beim Zurückschalten steht jede Seite wieder so da, wie man sie verlassen hat.

**Artefakt-Reiter.** Die schließbaren Reiter der geöffneten Artefakte (`ArtifactTabs`, `ClosableTab`) ziehen aus
„Export“ in diese Artefakt-Mitte um. Es bleibt derselbe Baustein, er wird verschoben und nicht neu gebaut. Unter
„Export“ stehen danach nur noch Exporte. Die Ausgabe-Knöpfe in der unteren Leiste bleiben und gelten für das, was
vorn ist: an der Seite für die Seite, in der Artefakt-Mitte für das offene Artefakt.

**▾-Menü „Weitere Aktionen zur Seite“.** „Neuer Export“ bleibt. Die Liste der Artefakte des Chats entfällt dort,
weil sie jetzt links steht. An ihre Stelle treten die lokalen Artefakte der Seite (Abschnitt 4).

**Die KI legt ein Artefakt an oder ändert eines, während links „Wissensquellen“ steht.** Nichts schaltet um.
Man sieht drei Dinge:

1. In der Antwort im Chat steht der Verweis auf das Artefakt wie heute. Ein Klick darauf schaltet links auf
   „Artefakte“ und öffnet es. Das ist der einzige Weg, auf dem die Ansicht wechselt, und er beginnt mit einem Klick.
2. Die Hälfte „Artefakte“ der Wippe trägt eine Zahl als kleinen Chip (das kann `SegmentedToggle` schon): wie
   viele Artefakte seit dem letzten Blick neu oder geändert sind. Sie verschwindet, sobald man „Artefakte“ öffnet.
3. Die Statuszeile meldet es einmal, etwa „Dokument ‚Notizen Jahresabschluss‘ geändert“.

Steht links schon „Artefakte“, verhält sich die Mitte wie heute der Reiter „Export“: Ein Artefakt, das die KI anlegt
oder ändert, öffnet sich als Reiter und kommt nach vorn (`ArtifactHost.open`). Daran ändert der Umzug nichts.

## 3. Sortieren bei vielen Artefakten: drei Varianten für Angelo

Grundlage in allen dreien: neueste zuerst, das Filterfeld des `SideHead` filtert nach Titel.

**A. Farbige Gruppen wie bei den Chats.** Gruppen mit Namen und Farbe, zugeklappt oder offen, mit dem
durchgehenden Streifen links (`GroupStripe`, `ColorDot`, dieselbe Palette). Ein Artefakt steht in höchstens einer
Gruppe; Rechtsklick „Gruppe ▸“ mit den Gruppen und „Neue Gruppe“. Ohne Gruppe steht es oben.
Dafür: Man kennt es schon von den Chats, kein neues Bedienmuster, schnell eingerichtet.
Dagegen: nur eine Ebene; bei sehr vielen Gruppen wird auch das lang.

**B. Eigene Ordner.** Ein Baum aus selbst angelegten Ordnern (`FolderTree`), beliebig tief, Verschieben per Ziehen
oder „Verschieben nach …“.
Dafür: trägt auch sehr viele Artefakte, vertraut vom Dateisystem.
Dagegen: Man muss Ordnung halten; ein zweiter Baum neben dem der Wissensquellen, der anders gefüllt wird; mehr
Bauaufwand (Anlegen, Umbenennen, Verschieben, Löschen von Ordnern).

**C. Nur Suche und Filter je Art.** Keine Ordnung von Hand. Über der Liste drei `CountChip` „Dokumente“,
„Grafiken“, „Bücher“ als Filter, dazu das Filterfeld.
Dafür: nichts zu pflegen, sofort fertig, bleibt mit A oder B später vereinbar.
Dagegen: Zusammengehöriges (etwa alles zu einem Vorhaben) steht nicht beieinander.

Meine Neigung, keine Entscheidung: C sofort, weil es nichts kostet, und A dazu, sobald die Liste lang wird.

## 4. Lokale Artefakte an der Seite

Leseansicht und Podcast-Ansicht bleiben unverändert. „Als Artefakte zu sehen“ sind Zusammenfassung und Podcast im
▾-Menü „Weitere Aktionen zur Seite“, an der Stelle, an der bisher die Artefakte des Chats standen:

```
 Neuer Export
 ─────────────────────────────
 Aus dieser Seite erzeugt
   Zusammenfassung        heute 09:15
   Podcast                Veraltet
```

- Es stehen nur Einträge da, die es für diese Seite gibt. Gibt es nichts, entfällt der Abschnitt.
- Klick auf „Zusammenfassung“ zeigt die Leseansicht, Klick auf „Podcast“ die Podcast-Ansicht. Kein zweiter Player.
- Jeder Eintrag hat ein Untermenü: Öffnen, Neu erzeugen (überschreibt, weil lokale Artefakte nicht versioniert
  sind), Löschen … mit „wirklich löschen?“ Ja/Nein.
- Für Ordner, Space und Quelle gilt dasselbe Menü mit deren lokalen Artefakten.

Begründung: Das Menü gibt es schon, es hängt an der Seite, und es war bisher der Ort für Artefakte. Es kommt kein
neuer Reiter und keine neue Leiste dazu.

Andere Möglichkeit, falls Angelo sie dauerhaft sichtbar will: ein Abschnitt „Erzeugt“ im Technik-Reiter unter
„Analyse“, weil dort alles aufwendig Berechnete steht. Das wäre mehr Fläche, aber einen Klick weiter weg.

## 5. Entschieden vom Architekten (27.11.2026)

1. Die Wippe steht im Kopf der Seitenleiste an der Stelle der Überschrift, so wie sie bei den Aufträgen in der
   Titelzeile steht; Angelos Bild von 21:30 MEZ zeigt diese Stelle. Das ist eine bewusste Ausnahme von der Regel
   „Reiter einer Seitenleiste stehen mittig im `SideFoot`“.
2. Abschnitte 1 und 2 werden so gebaut. Die eigene Artefakt-Mitte ist die Lesart des Architekten, Angelo genannt.
3. Abschnitt 3 ist nicht entschieden. Gebaut wird zunächst nur: neueste zuerst und das Filterfeld.
4. Abschnitt 4 ist ein Vorschlag für Angelos „als Artefakte zu sehen an der Seite“ und noch nicht zum Bau vergeben.
5. Die Marke „Veraltet“ wird aus den Analysen herausgelöst und geteilt, nicht kopiert.
