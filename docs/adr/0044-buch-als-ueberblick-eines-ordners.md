# ADR 0044: Das Buch als Überblick eines Ordners, mit Auflagen

- Status: **Angenommen** (27.11.2026) nach Angelos Vorgabe. **Geändert** am 27.11.2026 durch den Nachtrag „Das Buch
  ist ein Artefakt der Schreibumgebung“ am Ende: Die Abschnitte 2 und 3 gelten nicht mehr, die Abschnitte 1, 4 und 5
  gelten in der Fassung des Nachtrags. Das Buch ist ein globales, versioniertes Artefakt nach
  [ADR 0047](0047-artefakte-des-projekts.md): Jede Auflage bleibt erhalten (Nachtrag N3).
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
- Änderte ADR 0034 in Abschnitt 7 in einem Punkt (kein Überblick von selbst an einem Ordner mit Buch, Abschnitt 3).
  Mit dem Nachtrag entfällt diese Änderung wieder; ADR 0034 gilt dort unverändert.

## Kontext

Ein Ordner hat keinen eigenen Inhalt. Seine Leseansicht zeigt deshalb den Überblick über den Bereich (ADR 0034,
Abschnitt 7), die Podcast-Ansicht einen Podcast auf dessen Grundlage. „Buch schreiben“ erzeugt aus demselben Ordner
ein Buch. Bisher landet es nur als Datei im Ausgabeordner (`BookJob.write`); in der App ist es nicht zu sehen.

Angelo ordnet beides ein: Das Buch ist derselbe Überblick, nur gründlicher und mit mehr Rechenzeit. Es gehört deshalb
an dieselbe Stelle.

## Entscheidung

### 1. Das Buch gehört zum Ordner und hat Auflagen

> Ergänzt durch den Nachtrag N3 und N4: Die Auflagen sind Versionen eines Artefakts, das dem Projekt gehört. Es
> bleibt dabei: Auflagen werden nie überschrieben, frühere bleiben wählbar.

- Jeder abgeschlossene Lauf von „Buch schreiben“ legt am Ordner eine **Auflage** ab: laufende Nummer, Zeitpunkt,
  Buchtext, Missionsbericht und der Stand der Quellen, aus denen sie entstand (je Seite Kennung und Version).
- Auflagen werden nie überschrieben. Gezeigt wird die neueste; frühere bleiben wählbar.
- Ablage neben den Angaben zum Ordner (`BookNotes`) im Datenordner der App. Der Export in den Ausgabeordner bleibt
  wie bisher.
- Das Buch ist ein eigener Typ mit eigener Ablage (ADR 0043, Abschnitt 1), kein Feld am Ordner und kein Teil der
  Zusammenfassung.

### 2. Die Leseansicht zeigt das Buch statt des Überblicks

> Gilt nicht mehr, siehe Nachtrag N1 und N2. Für den Podcast siehe N6.

- Hat der Ordner ein Buch, zeigt die Leseansicht die neueste Auflage.
- Der Umschalter unten in Lese- und Podcast-Ansicht bekommt an solchen Ordnern eine weitere Hälfte „Buch“. Sie ist
  dort vorgewählt. Es entsteht kein zweiter Umschalter.
- **Die Wahl gilt je Ordner, nicht je Reiter.** Lese- und Podcast-Ansicht folgen derselben Wahl.
- Ohne Buch bleibt alles wie in ADR 0034.

### 3. Kein Überblick von selbst, wenn es ein Buch gibt

> Gilt nicht mehr, siehe Nachtrag N2.

- An einem Ordner mit Buch startet der Überblick nie beim Öffnen. Er entsteht nur auf den Klick „Zusammenfassen“,
  nachdem der Benutzer auf den Überblick umgeschaltet hat.
- Ein schon vorhandener Überblick bleibt erhalten und erscheint beim Umschalten sofort.

### 4. Neue Auflage

> Ergänzt durch den Nachtrag N5: „veraltet“ und der Knopf stehen am Reiter des Buchs.

- Weicht der Stand der Quellen vom Stand der neuesten Auflage ab (Seite hinzugekommen, entfernt oder in neuer
  Version), zeigt die Ansicht „veraltet“ wie beim Überblick und den Knopf **„Neue Auflage erzeugen“**. Derselbe
  Befehl steht im Rechtsklick-Menü des Ordners.
- Die Abweichung wird über die vorhandenen Beobachter erkannt (ADR 0027). Sie startet nie von selbst einen
  KI-Lauf.
- Der Klick startet „Buch schreiben“ mit den am Ordner gespeicherten Angaben (Auftrag, Weidezaun, Fragen, Wahl der
  Begriffe) vorbelegt. Die bisherige Auflage bleibt lesbar, bis die neue fertig ist, und danach als frühere Auflage.

### 5. Podcast

> Ergänzt durch den Nachtrag N6.

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

## Nachtrag vom 27.11.2026: Das Buch ist ein Artefakt der Schreibumgebung

Quelle: Angelos Nachricht vom 27.11.2026, 20:55 MEZ: „Wir hatten vorhin definiert, dass geschriebene Bücher bei den
Bookmarks erscheinen in der Leseansicht. Und irgendwie sollten sie die Standardleseansicht verdrängen. Mittlerweile
haben wir aber Artefakte eingeführt, die man editieren kann. Ich würde sagen, dass wir Bücher auch als so ein
Artefakt behandeln sollten und dann unter der Exportansicht anzeigen sollten. Es hätte außerdem den Vorteil, dass wir
Bücher noch nachbearbeiten könnten im Chat.“

Bezug: [ADR 0045](0045-schreibumgebung-im-chat.md) (Schreibumgebung, Abschnitte 1 bis 5),
[ADR 0043](0043-projekte-als-wissensarbeitsbereich.md) (Nachtrag, Abschnitt F3).

Stand im Code davor (develop, 27.11.2026): Auflagen liegen in `BookEditions` am Ordner, die Leseansicht zeigt die
neueste, der Umschalter hat die Hälfte „Buch“, „Neue Auflage erzeugen“ steht in der Leseansicht und im
Rechtsklick-Menü des Ordners, und die Podcast-Ansicht erzeugt den Podcast aus dem Buch.

Dazu Angelos Nachricht vom 27.11.2026, 21:26 MEZ: „die globalen Artefakte werden auch [versioniert], das hatte ich
auch gesagt. Nur die lokalen Artefakte werden nicht [versioniert]. Weil es wäre scheiße, wenn wir ein Buch schreiben
und haben eine neue Version und können aber nicht die alte Version sehen, für die wir mega viel Arbeit investiert
haben.“ Die Unterteilung in lokale und globale Artefakte hält [ADR 0047](0047-artefakte-des-projekts.md) fest; dieser
Nachtrag sagt, was daraus für das Buch folgt.

N1, N2 und der Kern von N3 (jede Auflage bleibt zu sehen) geben Angelos Vorgabe wieder. N4 folgt aus ADR 0047. Die
Einzelheiten in N3, N5 bis N7 sind Entscheidungen des Architekten, die daraus folgen.

### N1. Das Buch ist ein Dokument der Schreibumgebung
- Ein geschriebenes Buch ist ein Dokument-Artefakt nach ADR 0045: Abschnitte mit festen Kennungen und „Rückgängig“.
- Es lässt sich nachbearbeiten wie jedes Dokument: vom Benutzer je Abschnitt, von der KI im Chat über die
  Abschnittswerkzeuge, wenn der Schalter „Schreiben“ an ist. Es gibt dafür keine eigenen Werkzeuge.
- Es erscheint in der Exportansicht als schließbarer Reiter und ist an seinem Ordner zu finden (N5). Die untere
  Leiste zeigt bei einem Buch dieselben Formate wie bei jedem Dokument.

### N2. Die Leseansicht zeigt wieder den Überblick
- Das Buch verdrängt die Leseansicht nicht mehr. Die Leseansicht des Ordners zeigt den Überblick nach ADR 0034,
  Abschnitt 7, auch wenn es ein Buch gibt, und der Überblick startet wie dort beschrieben.
- Die Hälfte „Buch“ des Umschalters entfällt in der Leseansicht.

### N3. Ein Buch je Ordner, jede Auflage bleibt
- Ein Ordner hat in einem Projekt genau ein Buch, und das ist ein globales Artefakt (ADR 0047, Abschnitt 4). Jede
  Auflage ist eine benannte Version dieses Artefakts. Es gilt weiter Abschnitt 1: Auflagen werden nie überschrieben,
  gezeigt wird die neueste, frühere bleiben wählbar.
- Am Reiter des Buchs steht die Auswahl der Auflage. Ältere Auflagen sind lesbar und ausgebbar; bearbeitet wird die
  neueste. Der Stand einer älteren Auflage ist ihr letzter Stand samt Nachbearbeitungen.
- Nachbearbeitungen sind Revisionen innerhalb ihrer Auflage. Eine neue Auflage wird neu geschrieben;
  Nachbearbeitungen der früheren gehen nicht in sie über und bleiben in der früheren erhalten.
- Der Buchtext liegt nur noch im Artefakt. Die Buchverwaltung am Ordner (`BookEditions`) behält je Auflage, was kein
  Text ist: laufende Nummer, Zeitpunkt, Missionsbericht, Stand der Quellen je Seite, die Seiten mit KI-Freigabe, und
  je Projekt die Kennung des Artefakts.
- Die Kapitel des Buchs sind die Abschnitte des Dokuments.
- Auflagen, die schon mit Text in `BookEditions` liegen, werden ohne KI in Versionen des Artefakts überführt, in
  ihrer Reihenfolge. Nichts geht verloren.
- Der Export in den Ausgabeordner beim Schreiben bleibt wie bisher.

### N4. Besitzer
- Das Buch gehört immer dem Projekt, das beim Schreiben aktiv war, auch im Standardprojekt „AresStack“ (ADR 0047,
  Abschnitt 2). Alle Chats des Projekts sehen und bearbeiten es. Eine eigene Ablage des Buchs am Ordner gibt es nicht.
- Das Buch bleibt an seinem Ordner erreichbar: Die Buchverwaltung am Ordner hält je Projekt
  die Kennung des Artefakts. Darüber findet „Buch öffnen“ am Ordner das Buch des aktiven Projekts.

### N5. Öffnen, „veraltet“ und neue Auflage
- Ist „Buch schreiben“ fertig, öffnet sich das Buch als Reiter in „Export“, wie ein Artefakt, das die KI anlegt.
- Das Rechtsklick-Menü des Ordners bekommt „Buch öffnen“. Ist links ein Ordner mit Buch gewählt, steht das Buch
  im Menü „Weitere Aktionen zur Seite“ an erster Stelle.
- „veraltet“ und der Knopf „Neue Auflage erzeugen“ (Abschnitt 4) stehen am Reiter des Buchs statt in der
  Leseansicht. Der Befehl im Rechtsklick-Menü des Ordners bleibt. Die Abweichung wird weiter über die vorhandenen Beobachter erkannt und startet nie von selbst einen KI-Lauf.
- Die bisherige Auflage bleibt lesbar, bis die neue fertig ist, und danach als frühere Auflage (Abschnitt 4).
  „Neue Auflage erzeugen“ braucht deshalb keine Warnung vor einem Verlust.

### N6. Podcast
- Der Podcast des Ordners ist ein lokales Artefakt (ADR 0047, Abschnitt 3). Er bleibt in seiner heutigen Ablage und
  läuft in der Podcast-Ansicht des Ordners.
- Die Vorgabe von 15:41 MEZ gilt weiter: Hat der Ordner ein Buch, ist das Buch die Vorlage des Podcasts, mit der Wahl
  „Buch | Bereich“ nur in der Podcast-Ansicht (Abschnitt 5).
- Vorlage ist der aktuelle Text der neuesten Auflage. Wird sie nachbearbeitet oder entsteht eine neue Auflage, gilt
  der Podcast als veraltet wie jeder andere Podcast nach einer Änderung seiner Vorlage. Der Podcast wird
  überschrieben, nicht in Fassungen aufbewahrt.

### N7. Umsetzung
1. Speicher der Artefakte: Besitzer ist immer das Projekt, Auflagen als Versionen eines Buchs (ADR 0047, Technik). Das baut genau
   ein Strang, der der Schreibumgebung.
2. Buch als Artefakt des Projekts anlegen, Kennung in der Buchverwaltung, vorhandene Auflagen als Versionen
   überführen, Buch öffnet sich in „Export“, Auswahl der Auflage am Reiter.
3. Leseansicht zurück auf den Überblick, Hälfte „Buch“ dort entfernen, Start des Überblicks wieder nach ADR 0034.
4. „veraltet“ und „Neue Auflage erzeugen“ am Reiter des Buchs, „Buch öffnen“ im Rechtsklick-Menü.

Schritt 2 kommt vor Schritt 3, damit das Buch in keiner Zwischenfassung unsichtbar ist.

Die offenen Punkte oben gelten weiter. Abschnitt 6 (KI-Freigabe) ist unverändert.
