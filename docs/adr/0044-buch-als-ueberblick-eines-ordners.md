# ADR 0044: Das Buch als Überblick eines Ordners, mit Auflagen

- Status: **Angenommen** (27.11.2026) nach Angelos Vorgabe.
- Quelle: Angelos Nachricht vom 27.11.2026, 15:41 MEZ: „Eigentlich ist dieses Buch erstellen ja ein erweiterter
  Modus für die KI-Zusammenfassung. […] Wenn ein Buch existiert, sollte das eigentlich in erster Linie die
  KI-Zusammenfassung verdrängen und auch erstmal keine neue KI-Zusammenfassung erstellt werden. […] Neue Auflage
  erzeugen, das ist auch cool. […] Man soll halt trotzdem zu der Standard-KI-Zusammenfassung wechseln können. […] Der
  Podcast [nimmt] dann ebenfalls das Buch als Vorlage. […] Sonst müsste er sich das ja für jeden Reiter merken.“
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0028](0028-ki-freigabe-je-seite.md): KI-Freigabe
  - [ADR 0034](0034-favoriten-ordner-als-sammlungen.md): Favoriten-Ordner, Abschnitt 7 (Leseansicht und
    Podcast-Ansicht des Ordners)
  - [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md): Projekte; das Buch ist dort ein Arbeitsergebnis
- Ändert ADR 0034 in Abschnitt 7 in einem Punkt: Der Überblick startet beim Öffnen nicht von selbst, wenn der Ordner
  ein Buch hat (Abschnitt 3). Alles andere aus ADR 0034 gilt weiter.

## Kontext

Ein Ordner hat keinen eigenen Inhalt. Seine Leseansicht zeigt deshalb den Überblick über den Bereich (ADR 0034,
Abschnitt 7), die Podcast-Ansicht einen Podcast auf dessen Grundlage. „Buch schreiben“ erzeugt aus demselben Ordner
ein Buch. Bisher landet es nur als Datei im Ausgabeordner (`BookJob.write`); in der App ist es nicht zu sehen.

Angelo ordnet beides ein: Das Buch ist derselbe Überblick, nur gründlicher und mit mehr Rechenzeit. Es gehört deshalb
an dieselbe Stelle.

## Entscheidung

### 1. Das Buch gehört zum Ordner und hat Auflagen
- Jeder abgeschlossene Lauf von „Buch schreiben“ legt am Ordner eine **Auflage** ab: laufende Nummer, Zeitpunkt,
  Buchtext, Missionsbericht und der Stand der Quellen, aus denen sie entstand (je Seite Kennung und Version).
- Auflagen werden nie überschrieben. Gezeigt wird die neueste; frühere bleiben wählbar.
- Ablage neben den Angaben zum Ordner (`BookNotes`) im Datenordner der App. Der Export in den Ausgabeordner bleibt
  wie bisher.
- Das Buch ist ein eigener Typ mit eigener Ablage (ADR 0043, Abschnitt 1), kein Feld am Ordner und kein Teil der
  Zusammenfassung.

### 2. Die Leseansicht zeigt das Buch statt des Überblicks
- Hat der Ordner ein Buch, zeigt die Leseansicht die neueste Auflage.
- Der Umschalter unten in Lese- und Podcast-Ansicht bekommt an solchen Ordnern eine weitere Hälfte „Buch“. Sie ist
  dort vorgewählt. Es entsteht kein zweiter Umschalter.
- **Die Wahl gilt je Ordner, nicht je Reiter.** Lese- und Podcast-Ansicht folgen derselben Wahl.
- Ohne Buch bleibt alles wie in ADR 0034.

### 3. Kein Überblick von selbst, wenn es ein Buch gibt
- An einem Ordner mit Buch startet der Überblick nie beim Öffnen. Er entsteht nur auf den Klick „Zusammenfassen“,
  nachdem der Benutzer auf den Überblick umgeschaltet hat.
- Ein schon vorhandener Überblick bleibt erhalten und erscheint beim Umschalten sofort.

### 4. Neue Auflage
- Weicht der Stand der Quellen vom Stand der neuesten Auflage ab (Seite hinzugekommen, entfernt oder in neuer
  Version), zeigt die Ansicht „veraltet“ wie beim Überblick und den Knopf **„Neue Auflage erzeugen“**. Derselbe
  Befehl steht im Rechtsklick-Menü des Ordners.
- Die Abweichung wird über die vorhandenen Beobachter erkannt (ADR 0027). Sie startet nie von selbst einen
  KI-Lauf.
- Der Klick startet „Buch schreiben“ mit den am Ordner gespeicherten Angaben (Auftrag, Weidezaun, Fragen, Wahl der
  Begriffe) vorbelegt. Die bisherige Auflage bleibt lesbar, bis die neue fertig ist, und danach als frühere Auflage.

### 5. Podcast
- Steht die Wahl auf „Buch“, ist das Buch die Vorlage des Podcasts, die Kapitel sind seine Themen.
- Das Buch ist für den Podcast ein weiterer Typ hinter derselben Schnittstelle wie Zusammenfassung und Seite
  (`PodcastControl` ist bereits generisch über das Gezeigte). Kein Sonderfall in der Steuerung.
- Steht die Wahl auf dem Überblick, bleibt der Podcast wie in ADR 0034.

### 6. KI-Freigabe
- In ein Buch gehen wie bisher nur Seiten mit KI-Freigabe (ADR 0028). Die Auflage hält fest, welche Seiten das waren.

## Umsetzung

Kleine, voneinander unabhängige Schritte:
1. Auflage speichern und in der Leseansicht zeigen.
2. Umschalter mit „Buch“, Wahl je Ordner, kein Überblick von selbst.
3. „Veraltet“ und „Neue Auflage erzeugen“.
4. Podcast aus dem Buch.

Schritt 2 fasst den Start des Überblicks in `AreaSummaryView` an und folgt deshalb der laufenden Reparatur des
Überblicks am Favoritenordner.

## Offene Punkte

- Verliert eine Seite nach dem Schreiben die KI-Freigabe oder das Leserecht, gilt die Auflage zunächst nur als
  veraltet. Ob ihr Text dann weiter gezeigt werden darf, ist nicht entschieden.
- Eine neue Auflage rechnet heute alles neu. Ob Zwischenergebnisse unveränderter Quellen wiederverwendet werden, ist
  eine spätere Optimierung und kein Teil dieser Entscheidung.
