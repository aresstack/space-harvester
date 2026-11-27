# Artefakt-Liste im Reiter „Export“: Entwurf

Entwurf des UX-Strangs vom 27.11.2026, gehört zu [ADR 0047](adr/0047-artefakte-des-projekts.md), Abschnitt 5. Alle
Beispiele sind erfunden. Der Entwurf nutzt nur vorhandene Steuerelemente aus `app.ui.controls`.

## 1. Wo die Liste steht

Ist im Reiter „Export“ kein Export und kein Artefakt geöffnet, füllt die Liste die ganze Fläche der Seitenmitte.
Die Ausgabe-Knöpfe bleiben, wo sie seit ADR 0045 sind: mittig in der unteren Leiste neben der Lupe. Die Liste hat
keine eigene Leiste, keine eigene Suche und keinen eigenen Statusbereich.

Sobald ein Reiter offen ist, zeigt die Fläche dessen Inhalt. Zur Liste zurück führt das Schließen des letzten
Reiters oder der Eintrag „Alle Artefakte zeigen“ im ▾-Menü (Abschnitt 6).

## 2. Aufbau

```
 [Dokumente 4] [Grafiken 2] [Bücher 1] [Podcasts 3]                      [Zusammenfassungen 12]
 ──────────────────────────────────────────────────────────────────────────────────────────────
 ▤  Betriebshandbuch Lagerverwaltung      Handbücher › Lager                        heute 14:02
 ◔  Ablauf Wareneingang                   Handbücher › Lager › Wareneingang         heute 13:40
 ▥  Buch: Lagerprozesse                   Handbücher › Lager             [Veraltet]      23.11.
 ♫  Podcast: Inventur in zehn Minuten     Handbücher › Lager › Inventur  [Veraltet]      21.11.
 ▤  Notizen Jahresabschluss               Projekt                                        19.11.
```

Die Zeichen links stehen hier nur für die gezeichneten Symbole (Abschnitt 3).

### Kopfzeile: Filter je Art

Eine Reihe `CountChip`, je Art einer, mit Wort und Zahl. Das Verhalten ist das der Analysen: Ein Klick zeigt nur
diese Art, ein zweiter hebt den Filter auf. Eine Art ohne Artefakte zeigt keinen Chip. Die Zahlen gelten für die
Auswahl im Baum links.

### Zusammenfassungen

Die Zusammenfassungen sind zunächst ausgeblendet. Sichtbar macht sie derselbe `CountChip` „Zusammenfassungen 12“,
abgesetzt am rechten Rand der Kopfzeile. Einen eigenen Schalter gibt es nicht:

- Ohne gewählten Chip zeigt die Liste alles außer den Zusammenfassungen.
- Der Chip „Zusammenfassungen“ zeigt nur die Zusammenfassungen. Das ist Angelos „gesondert sichtbar machen“.
- Die Zahl im Chip sagt schon vorher, ob sich der Klick lohnt. Ein Schalter kann das nicht.
- Es entsteht kein neues Steuerelement und kein zweites Bedienmuster neben den Chips.

Die Wahl bleibt gemerkt, solange die App läuft, und beginnt nach dem Start wieder ausgeblendet.

### Zeile

Eine Zeile je Artefakt, einzeilig, von links nach rechts:

1. **Symbol der Art** (Abschnitt 3).
2. **Titel**, in normaler Schrift. Zu lange Titel enden mit „…“, der volle Titel steht im Tooltip (`TreeTextTip`).
3. **Herkunft**, gedämpft: der Pfad im Baum, etwa „Handbücher › Lager › Inventur“. Reicht der Platz nicht, wird in
   der Mitte gekürzt, damit Quelle und letzter Teil lesbar bleiben. Ein Artefakt ohne Herkunft zeigt „Projekt“.
4. **Zustand**, nur wenn nicht aktuell: die Marke „Veraltet“ in Orange, genau wie in den Analysen (#1619,
   `StatementsTab`). Die Marke ist der Knopf: Ein Klick erzeugt neu. Rot gibt es nur bei „Fehlgeschlagen“, auch das
   ist dann der Knopf zum Wiederholen. Dokumente und Grafiken haben nie eine Marke.
5. **Zeit** der letzten Änderung, rechtsbündig, gedämpft: heute als Uhrzeit, sonst als Datum.

Unter dem Mauszeiger und an der gewählten Zeile erscheinen vor der Zeit zwei `FlatIconButton`: „Ausgeben“ und
„Löschen“. Sie tun dasselbe wie die Einträge im Rechtsklick-Menü. Sonst trägt die Zeile keine Knöpfe.

Während ein Artefakt neu erzeugt wird, steht an der Stelle der Marke gedämpft „wird erzeugt …“. Der Fortschritt
steht nur im Auftrag rechts und in der Statuszeile, nicht in der Liste.

### Reihenfolge und Gruppen

Neueste zuerst, in einer durchgehenden Liste. Ich empfehle keine aufklappbaren Gruppen je Art: Die Chips filtern
schon, und Gruppen würden bei fünf Arten fünf Überschriften über oft nur ein bis zwei Zeilen setzen.

### Leere Liste

`PlaceholderList` mit einem gedämpften Satz oben links: „Zu dieser Auswahl gibt es noch keine Artefakte.“ Sind nur
Zusammenfassungen vorhanden, bleibt deren Chip sichtbar, damit man sie findet.

## 3. Symbole je Art

Gezeichnete Symbole, keine Unicode-Zeichen. Jede Art nimmt das Symbol, das die App an ihrer heutigen Stelle schon
zeigt. Welche Klasse es jeweils liefert, habe ich nicht im Code nachgeschlagen; das klärt der bauende Strang. Fehlt
für eine Art ein Symbol, bitte bei mir melden statt eines zu erfinden.

| Art | Symbol |
|---|---|
| Dokument | das Blatt der Seite aus dem Baum |
| Grafik | das Symbol des Chat-Schaubilds |
| Buch | das Buch der Leseansicht („Buch \| Bereich“) |
| Podcast | der Kopfhörer |
| Zusammenfassung | das Symbol der KI-Zusammenfassung der Leseansicht |

Alle in einer Größe und in der gedämpften Linienfarbe. Farbe trägt in der Zeile nur die Marke.

## 4. Klick, Tastatur, Rechtsklick

- **Klick** auf Dokument, Grafik oder Buch öffnet den Reiter im Export. Ein Podcast öffnet die Podcast-Ansicht,
  eine Zusammenfassung die Leseansicht ihrer Seite. Die Auswahl im Baum ändert sich dabei nicht (wie bei den
  Suchtreffern, blauer Rahmen).
- **Tastatur:** Pfeile wählen, Enter öffnet, Entf löscht nach Rückfrage.
- **Rechtsklick-Menü**, nur Einträge, die für die Art gelten:
  - Öffnen
  - Ausgeben ▸ (die Formate dieser Art; bei Zusammenfassungen fehlt der Eintrag)
  - Neu erzeugen (nur Buch, Podcast, Zusammenfassung; beim nachbearbeiteten Buch mit der Rückfrage aus dem Konzept)
  - Umbenennen (nur Dokument und Grafik)
  - Herkunft im Baum zeigen
  - Löschen … („Artefakt wirklich löschen?“ Ja/Nein)

## 5. Sehr viele Artefakte

- Der Baum links ist der Filter, wie bei der Suche. Ohne Auswahl zeigt die Liste das ganze Projekt.
- Die Lupe in der unteren Leiste filtert die Liste nach Titel und Herkunft, solange die Liste vorn ist. Es gibt
  keine zweite Suche in der Liste.
- Die Liste zeichnet nur, was sichtbar ist (eine `JList` mit Renderer, keine Komponente je Zeile), und lädt beim
  Blättern nach. Es gibt keine Seitenknöpfe und keine feste Obergrenze.
- Weil die Zusammenfassungen die mit Abstand größte Menge sind und ausgeblendet beginnen, bleibt die Liste im
  Standardprojekt kurz.

## 6. Das ▾-Menü „Weitere Aktionen zur Seite“

Oben „Neuer Export“ wie heute. Darunter dieselben Zeilen in Kurzform (Symbol, Titel, Marke „Veraltet“ als Text) für
die Auswahl im Baum, neueste zuerst, ohne Zusammenfassungen. Passt nicht alles auf die Höhe des Menüs, endet es mit
„Alle Artefakte zeigen“, das den Reiter „Export“ mit der Liste nach vorn holt. Die Marke ist im Menü kein Knopf;
neu erzeugt wird in der Liste.

## 7. Für die Bau-Stränge

- Vorhandene Teile: `CountChip`, `PlaceholderList`, `FlatIconButton`, `TreeTextTip`, die Marke „Veraltet“ aus den
  Analysen, das Rechtsklick-Menü im Stil der übrigen Listen. Die Marke bitte aus `StatementsTab`/`TermsTab`
  herauslösen und teilen, nicht kopieren.
- Die Zeile fragt nur die gemeinsame Schnittstelle „Artefakt“ (Art, Titel, Herkunft, Zustand, Zeit, erlaubte
  Aktionen). Keine Abfrage der Art in der Liste; welches Menü und welches Klickziel gilt, sagt das Artefakt.
- Zahlen in den Chips und Zeilen kommen über Beobachter der Ablagen, kein Polling.

## 8. Entschieden vom Architekten (27.11.2026)

1. Chip statt Schalter für die Zusammenfassungen (Abschnitt 2).
2. Die Liste füllt die Fläche von „Export“, wenn kein Reiter offen ist. Die Ausgabe-Knöpfe stehen in der unteren
   Leiste (ADR 0045, Abschnitt 5).
3. Zusammenfassungen lassen sich einzeln löschen, mit derselben Rückfrage.
