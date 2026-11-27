# ADR 0044: Das Buch als Überblick eines Ordners, mit Auflagen

- Status: **Angenommen** (27.11.2026) nach Angelos Vorgabe. **Geändert** am 27.11.2026 durch den Nachtrag „Das Buch
  ist ein Artefakt der Schreibumgebung“ am Ende: Die Abschnitte 2 und 3 gelten nicht mehr, die Abschnitte 1, 4 und 5
  gelten in der Fassung des Nachtrags.
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

N1 und N2 geben Angelos Vorgabe wieder. N3 bis N5 und N7 sind Entscheidungen des Architekten, die daraus folgen. N6
ist bei Angelo offen.

### N1. Jede Auflage ist ein Dokument der Schreibumgebung
- Ein geschriebenes Buch ist ein Dokument-Artefakt nach ADR 0045: Abschnitte mit festen Kennungen, Revisionen und
  „Rückgängig“.
- Es lässt sich nachbearbeiten wie jedes Dokument: vom Benutzer je Abschnitt, von der KI im Chat über die
  Abschnittswerkzeuge, wenn der Schalter „Schreiben“ an ist. Es gibt dafür keine eigenen Werkzeuge.
- Es erscheint in der Exportansicht als schließbarer Reiter und steht in der Liste der Artefakte im Menü „Weitere
  Aktionen zur Seite“ (ADR 0045, Abschnitt 5). Die untere Leiste zeigt bei einem Buch dieselben Formate wie bei jedem
  Dokument.

### N2. Die Leseansicht zeigt wieder den Überblick
- Das Buch verdrängt die Leseansicht nicht mehr. Die Leseansicht des Ordners zeigt den Überblick nach ADR 0034,
  Abschnitt 7, auch wenn es ein Buch gibt, und der Überblick startet wie dort beschrieben.
- Die Hälfte „Buch“ des Umschalters entfällt in der Leseansicht.

### N3. Auflage und Artefakt
- Jede Auflage ist ein eigenes Artefakt. Eine neue Auflage legt ein neues Artefakt an und ändert die frühere nicht.
  Frühere Auflagen bleiben lesbar und bearbeitbar. Damit gilt weiter: Auflagen werden nie überschrieben.
- Nachbearbeitungen sind Revisionen innerhalb ihrer Auflage. Eine neue Auflage wird neu geschrieben;
  Nachbearbeitungen der früheren gehen nicht in sie über und bleiben in der früheren erhalten.
- Der Buchtext liegt nur noch im Artefakt. `BookEditions` am Ordner behält, was kein Text ist: laufende Nummer,
  Zeitpunkt, Missionsbericht, Stand der Quellen je Seite, die Seiten mit KI-Freigabe und neu die Kennung des
  Artefakts.
- Die Kapitel des Buchs sind die Abschnitte des Dokuments.
- Auflagen, die schon mit Text in `BookEditions` liegen, werden beim ersten Öffnen ohne KI in Artefakte überführt.
  Nichts geht verloren.
- Der Export in den Ausgabeordner beim Schreiben bleibt wie bisher.

### N4. Besitzer
- Im Standardprojekt „AresStack“ liegt das Buch am Ordner, neben seinen Auflagen, und jeder Chat des
  Standardprojekts sieht und bearbeitet es. Das ergänzt ADR 0043, Abschnitt F3: Dort gehört ein Artefakt im
  Standardprojekt seinem Chat; ein Buch entsteht aber an einem Ordner und hat keinen Chat.
- In jedem anderen Projekt gehört das Buch dem Projekt, das beim Schreiben aktiv war (ADR 0043, Abschnitt F3). Alle
  Chats des Projekts sehen und bearbeiten es.
- Am Ordner gilt als „das Buch“ die neueste Auflage im aktiven Projekt.

### N5. Öffnen, „veraltet“ und neue Auflage
- Ist „Buch schreiben“ fertig, öffnet sich die Auflage als Reiter in „Export“, wie ein Artefakt, das die KI anlegt.
- Das Rechtsklick-Menü des Ordners bekommt „Buch öffnen“. Ist links ein Ordner mit Buch gewählt, steht dessen
  neueste Auflage in der Liste der Artefakte an erster Stelle.
- „veraltet“ und der Knopf „Neue Auflage erzeugen“ (Abschnitt 4) stehen am Reiter des Buchs statt in der
  Leseansicht. Der Befehl im Rechtsklick-Menü des Ordners bleibt. Die Abweichung wird weiter über die vorhandenen
  Beobachter erkannt und startet nie von selbst einen KI-Lauf.
- Ist die neueste Auflage nachbearbeitet, fragt „Neue Auflage erzeugen“ vorher „wirklich?“ und sagt, dass die
  Nachbearbeitungen in der bisherigen Auflage bleiben.

### N6. Podcast: offen bei Angelo
- Die Vorgabe von 15:41 MEZ, dass der Podcast das Buch als Vorlage nimmt, ist gebaut. Die Nachricht von 20:55 MEZ
  sagt zum Podcast nichts. Angelo ist gefragt, ob der Podcast aus dem Buch in der Podcast-Ansicht des Ordners bleibt,
  zum Buch in die Exportansicht zieht oder entfällt.
- Bis zu seiner Antwort bleibt der Podcast aus dem Buch in der Podcast-Ansicht des Ordners, mit der Wahl
  „Buch | Bereich“ nur dort (Abschnitt 5 gilt für die Podcast-Ansicht weiter).
- Vorlage ist der aktuelle Text des Artefakts. Wird das Buch nachbearbeitet, gilt sein Podcast als veraltet wie jeder
  andere Podcast nach einer Änderung seiner Vorlage.

### N7. Umsetzung
1. Auflage als Artefakt anlegen, Kennung in `BookEditions`, vorhandene Auflagen überführen, Buch öffnet sich in
   „Export“. Dazu braucht der Speicher der Artefakte einen Besitzer ohne Chat (N4); das baut genau ein Strang,
   der der Schreibumgebung.
2. Leseansicht zurück auf den Überblick, Hälfte „Buch“ dort entfernen, Start des Überblicks wieder nach ADR 0034.
3. „veraltet“ und „Neue Auflage erzeugen“ am Reiter des Buchs, „Buch öffnen“ im Rechtsklick-Menü.
4. Podcast nach Angelos Antwort zu N6.

Schritt 1 kommt vor Schritt 2, damit das Buch in keiner Zwischenfassung unsichtbar ist.

Die offenen Punkte oben gelten weiter. Abschnitt 6 (KI-Freigabe) ist unverändert.
