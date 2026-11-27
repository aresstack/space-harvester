# ADR 0047: Artefakte gehören dem Projekt und stehen in einer Liste unter „Export“

- Status: **Angenommen** (27.11.2026) nach Angelos Vorgabe. Was nicht sein Wortlaut ist, steht in Abschnitt 6 als
  Lesart des Architekten.
- Quellen:
  - Angelos Nachricht vom 27.11.2026, 20:55 MEZ: „Ich würde sagen, dass wir Bücher auch als so ein Artefakt behandeln
    sollten und dann unter der Exportansicht anzeigen sollten.“
  - Angelos Nachricht vom 27.11.2026, 21:04 MEZ: „Ja, also ich würde tatsächlich sagen, dass wir Podcasts in Zukunft
    auch irgendwie wie Artefakte behandeln. Wir müssen das Ganze natürlich ein bisschen besser strukturieren. Beim
    Podcast erzeugen wir ja ein Transkript. Wichtig ist natürlich, dass dieser Podcast auch veraltet sein kann. Und
    wir ihn gegebenenfalls löschen müssen. Ich würde da auch relativ rigoros umgehen. Ich würde bei diesen Artefakten
    keine Versionierung einführen. Dann kommt man nachher durcheinander. Ich würde da einfach das überschreiben. Wir
    haben ja auch die Zusammenfassung auf der Leseansicht. Die sind ja im Grunde auch Artefakte, die irgendwo abgelegt
    sind. Das könnte man also gemeinsam unter der Exportansicht ablegen, alles. Allerdings würde ich sie
    standardmäßig irgendwie ausblenden. Ich würde die halt da irgendwie gesondert sichtbar machen, weil aktuell sind
    sie ja eher unsichtbar. Irgendwie ist diese Artefaktansicht auch nicht wirklich gut. Das haben wir irgendwie noch
    nicht richtig designt. […] Also die hängen ja offenbar an einem Chat. Das bedeutet aber auch gleichzeitig, wenn
    ich einen neuen Chat aufmache, komme ich nicht an die Artefakte dran. Also das über Projekte teilen und am selben
    Artefakt arbeiten, was ich eigentlich geplant hatte, das funktioniert damit nicht. Können wir das irgendwie anders
    machen? Mir fehlt gerade der Plan.“
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0034](0034-favoriten-ordner-als-sammlungen.md), Abschnitt 7: Überblick eines Ordners
  - [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md), Nachtrag F3: wird geändert (Abschnitt 2)
  - [ADR 0044](0044-buch-als-ueberblick-eines-ordners.md), Nachtrag: das Buch als Artefakt
  - [ADR 0045](0045-schreibumgebung-im-chat.md), Abschnitte 1 und 5: werden geändert (Abschnitte 2 und 5)
  - [Entwurf der Liste](../artefakt-liste.md) vom UX-Strang
- Tickets: Zu Artefakten, ihrem Besitzer und ihrer Liste gibt es am 27.11.2026 kein Ticket in space-harvester,
  writing4j oder knowledge4j, das dem widerspricht (geprüft).

## Ausgangslage (develop, 27.11.2026)

- `ChatArtifactStore.owner` gibt für einen Chat im Standardprojekt „AresStack“ den Ordner des Chats zurück, für jedes
  andere Projekt den Ordner des Projekts. Im Standardprojekt gehört ein Artefakt deshalb dem Chat, in dem es
  entstanden ist. Ein neuer Chat sieht es nicht, und das ▾-Menü listet erst, wenn der alte Chat geladen ist.
- Bücher (`BookEditions`), Podcasts (`PodcastStore`, `PagePodcasts`) und Zusammenfassungen (`AreaSummarizer`,
  Leseansicht) liegen jeweils an eigener Stelle. Es gibt keinen Ort, an dem alles Erzeugte zusammen zu sehen ist.

## Entscheidung

### 1. Ein Begriff
Alles, was die App oder die KI erzeugt und ablegt, ist ein **Artefakt**. Es gibt fünf Arten: Dokument, Grafik, Buch,
Podcast, Zusammenfassung.

| Art | entsteht durch | gehört zu | bearbeitbar | veraltet, wenn | Ausgabe |
|---|---|---|---|---|---|
| Dokument | Chat oder Benutzer | Projekt, mit Herkunft | ja, Benutzer und Chat | nie | wie in ADR 0045 |
| Grafik | Chat oder Benutzer | Projekt, mit Herkunft | ja, Benutzer und Chat | nie | wie in ADR 0045 |
| Buch | „Buch schreiben“ am Ordner | Projekt, Herkunft ist der Ordner | ja, Benutzer und Chat | sich die Quellen des Ordners ändern | wie Dokument |
| Podcast | Kopfhörer | Seite, Ordner oder Space | nein, Sprechtext nur lesen | sich die Vorlage ändert | WAV, Video |
| Zusammenfassung | Leseansicht | Seite oder Bereich | nein | sich der Inhalt ändert | keine |

### 2. Das Projekt besitzt, nie der Chat
- Dokumente, Grafiken und Bücher gehören immer dem Projekt, auch im Standardprojekt „AresStack“. Das ersetzt aus
  ADR 0043, F3, „im Standardprojekt besitzt der Chat“ und aus ADR 0045, Abschnitt 1, „Artefakte gehören zum Chat“.
- Jeder Chat des Projekts sieht, öffnet und bearbeitet dieselben Artefakte, auch ein neuer Chat. Die Werkzeuge der KI
  arbeiten auf den Artefakten des Projekts; `dokumente_auflisten` nennt die zur aktuellen Auswahl zuerst.
- Die Liste der Artefakte kommt aus dem Projekt, ohne dass ein Chat geladen wird.
- Einen Chat zu löschen löscht kein Artefakt. Gelöscht wird am Artefakt, nach „Artefakt wirklich löschen?“ Ja/Nein,
  mit Papierkorb bis zum Neustart wie beim Chat.
- Die vorhandenen Artefakte der Chats ziehen einmal ins Standardprojekt um: lokal, ohne Netz und ohne KI, mit dem
  vorhandenen Umzug (`moveToProject`), der Kennungen neu vergibt und Verweise nachzieht.
- Kommt ein Chat aus dem Standardprojekt in ein anderes Projekt, ziehen die von ihm erzeugten Artefakte mit, wie
  bisher (ADR 0043, F3). Sonst bleiben Artefakte in ihrem Projekt.
- Podcasts und Zusammenfassungen bleiben in ihrer heutigen Ablage an der Quelle und sind in jedem Projekt zu sehen.
  Sie entstehen aus dem Wissen, nicht aus einem Chat. Sie ziehen nicht um.

### 3. Jedes Artefakt kennt seine Herkunft
- Ein Artefakt gehört zu einer Seite, einem Ordner, einem Space oder einer Quelle. Bei Dokument und Grafik ist das
  die Auswahl, in der der erzeugende Chat stand; beim Buch der Ordner. Dazu merkt sich das Artefakt den Chat, der es
  erzeugt hat.
- Die Auswahl im Baum links filtert die Liste, wie bei der Suche. Ohne Auswahl zeigt die Liste das ganze Projekt.
- Ein Artefakt ohne Herkunft zeigt „Projekt“.

### 4. Keine Versionen, „veraltet“ und neu erzeugen
- Je Sache gibt es genau ein Artefakt. Neu erzeugen überschreibt. Ein Ordner hat ein Buch (ADR 0044, Nachtrag N3),
  eine Seite einen Podcast und eine Zusammenfassung. Eine Reihe von Auflagen oder Fassungen gibt es nicht.
- Ein erzeugtes Artefakt (Buch, Podcast, Zusammenfassung) ist veraltet, wenn sich seine Vorlage geändert hat. Das
  wird über die vorhandenen Beobachter erkannt (ADR 0027).
- Veraltetes zeigt die Marke „Veraltet“. Die Marke ist der Knopf: Ein Klick erzeugt neu. Nichts wird von selbst neu
  erzeugt und nichts von selbst gelöscht.
- Der Podcast eines Ordners entsteht wie festgelegt aus dem Buch des Ordners, wenn es eines gibt (ADR 0044,
  Abschnitt 5 und Nachtrag N6).

### 5. Ein Ort: die Liste unter „Export“
- Ist im Reiter „Export“ kein Reiter offen, zeigt die Fläche die Liste der Artefakte zur Auswahl links. Die
  Ausgabe-Knöpfe bleiben in der unteren Leiste (ADR 0045, Abschnitt 5).
- Zusammenfassungen sind zunächst ausgeblendet. Ein abgesetzter Zähler „Zusammenfassungen“ rechts in der Kopfzeile
  zeigt, wie viele es gibt, und holt sie auf Klick hervor. Podcasts stehen sichtbar in der Liste.
- Ein Klick auf Dokument, Grafik oder Buch öffnet den Reiter in „Export“. Ein Klick auf einen Podcast öffnet die
  Podcast-Ansicht, in der er heute läuft; es gibt keinen zweiten Player. Ein Klick auf eine Zusammenfassung öffnet
  die Leseansicht ihrer Seite.
- Das Menü „Weitere Aktionen zur Seite“ (▾) zeigt dieselbe Liste in Kurzform als schnellen Weg.
- Die Leseansicht und die Podcast-Ansicht bleiben, wie sie sind.
- Aufbau der Zeilen, Filter je Art, Rechtsklick-Menü und das Verhalten bei sehr vielen Artefakten stehen im
  [Entwurf der Liste](../artefakt-liste.md).

### 6. Lesarten des Architekten, nicht Angelos Wortlaut
1. „Keine Versionierung“ heißt: keine sichtbare Reihe von Auflagen oder Fassungen. Die Revisionen eines Dokuments
   und „Rückgängig“ beim Bearbeiten (ADR 0045, Abschnitt 1) bleiben, auch nach „Neue Auflage erzeugen“. So geht eine
   Nachbearbeitung nicht mit einem Klick verloren.
2. Ausgeblendet sind nur die Zusammenfassungen, nicht die Podcasts.
3. „Rigoros“ heißt überschreiben. Veraltetes wird nicht von selbst gelöscht.
4. Ältere Auflagen eines Buchs, die es schon gibt, werden nicht mehr angezeigt. Die Umstellung löscht ihre Dateien
   nicht.
5. Wurde ein Buch nachbearbeitet, fragt „Neue Auflage erzeugen“ vorher „wirklich?“ und sagt, dass die Änderungen
   überschrieben werden.
6. Zusammenfassungen lassen sich in der Liste einzeln löschen.

Angelo hat die Lesarten 1 und 2 am 27.11.2026 gegen 21:10 MEZ mit dem Konzept genannt bekommen.

## Technik

- **Gemeinsame Schnittstelle.** Ein Typ „Artefakt“ mit Art, Titel, Herkunft, Zustand (aktuell, veraltet,
  fehlgeschlagen), Zeit und den erlaubten Aktionen (öffnen, ausgeben, neu erzeugen, umbenennen, löschen). Die fünf
  Arten stehen als Typen dahinter. Die Liste fragt nie nach der Art; welches Menü und welches Klickziel gilt, sagt
  das Artefakt.
- **Die Liste ist eine Sicht** über die vorhandenen Ablagen: den Speicher der Schreibumgebung
  (`ChatArtifactStore`), `PodcastStore` und die Ablage der Zusammenfassungen. Nichts wird kopiert. Änderungen kommen
  über Beobachter der Ablagen.
- **Kennungen.** Alles Geschriebene eines Projekts hat denselben Besitzer. Die Kennungen (D…, G…, B…) sind deshalb
  je Projekt eindeutig, und ein Verweis `artefakt:G1` zeigt nie über einen Besitzer hinaus. Verweise über
  Projektgrenzen gibt es nicht. Zieht ein Artefakt in ein anderes Projekt, ziehen die Grafiken mit, auf die es
  verweist.
- **Herkunft** ist ein neues Feld am Artefakt. Beim Umzug ergibt es sich aus dem bisherigen Ordner des Chats.
- **Offene Reiter** merkt sich weiter der Chat.

## Umsetzung

Jeder Schritt ein kleiner PR auf develop. Den Speicher der Artefakte ändert nur ein Strang, der der Schreibumgebung.

1. Besitzer ist immer das Projekt, Umzug der vorhandenen Artefakte, Herkunft, Löschen am Artefakt.
2. Buch als Artefakt des Projekts, überschreiben statt Auflagen (ADR 0044, Nachtrag N7; baut auf Schritt 1).
3. Gemeinsame Schnittstelle und Liste in „Export“ für Dokument, Grafik und Buch, ▾-Menü auf dieselbe Liste.
4. Podcast und Zusammenfassung in der Liste, Zähler für die ausgeblendeten Zusammenfassungen.

## Der einmalige Umzug (Schritt 1, Stand des Baus vom 27.11.2026)

- Der Umzug läuft einmal je Programmlauf, sobald der Chat geladen ist, neben der Oberfläche. Er sucht die alten
  Artefakt-Ordner der Chats, verschiebt ihre Dateien in den Ordner des Projekts und schreibt die Verweise, die
  gemerkten offenen Reiter und den Verlauf des Chats auf die neuen Kennungen um. Danach findet er nichts mehr.
- Das sind nur lokale Dateioperationen, ohne Netz und ohne KI, auch bei Hunderten Chats höchstens wenige Sekunden.
  Ein sichtbarer Auftrag ist dafür nicht nötig.
- Entscheidung des Architekten: Das ist eine Umstellung der eigenen Ablage und kein Abgleich mit einer Quelle. Die
  Regel „nichts ohne Klick“ (kein Abgleich und keine KI beim Start) ist davon nicht berührt.
- Zusätzlich zieht wie bisher jeder Chat beim Öffnen seine Reste um.
- Die gemerkten offenen Reiter bleiben beim Chat. Die Werkzeuge der KI lesen und schreiben nach dem Wechsel im
  Ordner des Projekts.
- Ein Chat, dessen Projekt gelöscht oder unbekannt ist, gehört zum Standardprojekt; seine Artefakte liegen dann dort.
