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

## 3. Ordnen bei vielen Artefakten

Entschieden von Angelo am 27.11.2026, 23:07 MEZ (Wortlaut in ADR 0047):

- Der Filter ist das vorhandene Filterfeld des `SideHead`, unverändert. Er filtert nach dem Titel.
- Im `SideFoot` steht links ein Schalter „Nach Art gruppieren“. Aus: eine Liste, neueste zuerst. An: je Art eine
  Gruppe (Dokumente, Grafiken, Bücher) mit Überschrift und Zahl, darin neueste zuerst; eine Art ohne Artefakte zeigt
  keine Gruppe.
- Der Zustand des Schalters wird einmal global im Zustand der Anwendung gemerkt und gilt überall, auch nach dem
  Neustart.
- Keine farbigen Gruppen wie bei den Chats und keine eigenen Ordner.

```
 [ Wissensquellen | Artefakte ]                    (o) (o)
 [ Titel filtern …                                  🔍 ]
 ──────────────────────────────────────────────────────
 Dokumente  2
 ▤  Betriebshandbuch Lagerverwaltung             14:02
 ▤  Notizen Jahresabschluss                      19.11.
 Grafiken  1
 ◔  Ablauf Wareneingang                          13:40
 Bücher  1
 ▥  Lagerprozesse                    [Veraltet]  23.11.
 ──────────────────────────────────────────────────────
 (Nach Art gruppieren: an)
```

## 4. Lokale Artefakte an der Seite

Entschieden von Angelo am 27.11.2026, 23:07 MEZ: Sie stehen auf der leeren Seite von „Export“ und zusätzlich im
▾-Menü. Leseansicht und Podcast-Ansicht bleiben unverändert.

**„Export“ ohne offenen Export-Reiter** zeigt die lokalen Artefakte der Seite:

```
 Aus dieser Seite erzeugt
 ──────────────────────────────────────────────────────
 Zusammenfassung                         heute 09:15
 Podcast                    [Veraltet]        21.11.
```

- Eine Zeile je Artefakt mit Symbol, Name, Marke „Veraltet“ als Knopf zum Neuerzeugen und Zeit. Dieselbe Zeile wie in
  der Liste links.
- Klick auf „Zusammenfassung“ zeigt die Leseansicht, Klick auf „Podcast“ die Podcast-Ansicht. Kein zweiter Player.
- Rechtsklick: Öffnen, Neu erzeugen (überschreibt, weil lokale Artefakte nicht versioniert sind), Löschen … mit
  „wirklich löschen?“ Ja/Nein.
- Gibt es für die Seite nichts, steht dort über `PlaceholderList` „Aus dieser Seite ist noch nichts erzeugt.“
- Für Ordner, Space und Quelle gilt dasselbe mit deren lokalen Artefakten.

**▾-Menü „Weitere Aktionen zur Seite“:** unter „Neuer Export“ derselbe Abschnitt „Aus dieser Seite erzeugt“ mit
denselben Einträgen und Aktionen, damit man sie auch erreicht, wenn ein Export-Reiter offen ist.

## 5. Entschieden vom Architekten (27.11.2026)

1. Die Wippe steht im Kopf der Seitenleiste an der Stelle der Überschrift, so wie sie bei den Aufträgen in der
   Titelzeile steht; Angelos Bild von 21:30 MEZ zeigt diese Stelle. Das ist eine bewusste Ausnahme von der Regel
   „Reiter einer Seitenleiste stehen mittig im `SideFoot`“.
2. Abschnitte 1 und 2 werden so gebaut. Die eigene Artefakt-Mitte ist die Lesart des Architekten, Angelo genannt.
3. Abschnitt 3 hat Angelo entschieden. Die Gruppen heißen wie die Arten in der App: Dokumente, Grafiken, Bücher.
4. Abschnitt 4 hat Angelo entschieden; Zeile, Aktionen und der leere Zustand dort sind Entscheidungen des
   Architekten.
5. Die Marke „Veraltet“ wird aus den Analysen herausgelöst und geteilt, nicht kopiert.
