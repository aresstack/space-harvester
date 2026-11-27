# ADR 0047: Artefakte gehören dem Projekt und sind am Knoten im Baum geordnet

- Status: Abschnitte 1 bis 4 **angenommen** (27.11.2026) nach Angelos Vorgabe. Abschnitt 5 (die Ansicht) ist ein
  **Vorschlag**, Angelo am 27.11.2026 um 21:22 MEZ vorgelegt, und wird erst gebaut, wenn er zugestimmt hat. Was nicht
  sein Wortlaut ist, steht in Abschnitt 6 als Lesart des Architekten.
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
  - Angelos Nachricht vom 27.11.2026, 21:19 MEZ, zur ersten Fassung dieses ADR, die eine Liste über das ganze Projekt
    vorsah: „Das Problem ist, wie wir mit so vielen Artefakten in einem Projekt umgehen. Wenn jede Datei ein neues
    Artefakt generiert, weißt du, wie übersichtlich das nachher ist“. Abschnitt 5 ist deshalb neu gefasst.
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0034](0034-favoriten-ordner-als-sammlungen.md), Abschnitt 7: Überblick eines Ordners
  - [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md), Nachtrag F3: wird geändert (Abschnitt 2)
  - [ADR 0044](0044-buch-als-ueberblick-eines-ordners.md), Nachtrag: das Buch als Artefakt
  - [ADR 0045](0045-schreibumgebung-im-chat.md), Abschnitt 1: wird geändert (Abschnitt 2)
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
- Die Artefakte eines Projekts lassen sich lesen, ohne dass ein Chat geladen wird.
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
- Die Herkunft ist die Ordnung der Artefakte (Abschnitt 5). Eine eigene Ordnerstruktur für Artefakte gibt es nicht.

### 4. Keine Versionen, „veraltet“ und neu erzeugen
- Je Sache gibt es genau ein Artefakt. Neu erzeugen überschreibt. Ein Ordner hat ein Buch (ADR 0044, Nachtrag N3),
  eine Seite einen Podcast und eine Zusammenfassung. Eine Reihe von Auflagen oder Fassungen gibt es nicht.
- Ein erzeugtes Artefakt (Buch, Podcast, Zusammenfassung) ist veraltet, wenn sich seine Vorlage geändert hat. Das
  wird über die vorhandenen Beobachter erkannt (ADR 0027).
- Veraltetes zeigt die Marke „Veraltet“. Die Marke ist der Knopf: Ein Klick erzeugt neu. Nichts wird von selbst neu
  erzeugt und nichts von selbst gelöscht.
- Der Podcast eines Ordners entsteht wie festgelegt aus dem Buch des Ordners, wenn es eines gibt (ADR 0044,
  Abschnitt 5 und Nachtrag N6).

### 5. Ordnung für sehr viele Artefakte: der Knoten im Baum (Vorschlag)
Es gibt keine Liste aller Artefakte eines Projekts. Der Baum links ist die Ordnung: „Export“ zeigt ohne offenen
Reiter nur, was zu genau dem Knoten gehört, der links gewählt ist. Die Ausgabe-Knöpfe bleiben in der unteren Leiste
(ADR 0045, Abschnitt 5).

- **Erzeugtes ist ein fester Platz am Knoten, keine Zeile.** Eine Seite hat einen Platz für ihre Zusammenfassung und
  einen für ihren Podcast, ein Ordner zusätzlich einen für sein Buch. Der Bereich „Erzeugt“ ist zugeklappt und nennt
  nur die Zahl und wie viele davon veraltet sind. Das ist Angelos „standardmäßig ausblenden“ und „gesondert sichtbar
  machen“.
- **Geschriebenes hängt an dem Knoten, an dem es entstanden ist** (Abschnitt 3). Grafiken, auf die ein Dokument
  verweist, stehen eingerückt unter diesem Dokument und nicht als eigene Zeilen.
- **Was tiefer im Baum liegt, erscheint nur als Zahl.** Ein Ordner, ein Space oder eine Quelle zeigt unter „Darunter“
  je Unterknoten eine Zeile mit Name und Anzahl des Geschriebenen, nur für Unterknoten, die etwas haben. Ein Klick
  klappt die Zeile auf; die Auswahl im Baum ändert sich dabei nicht. Erzeugtes wird für den Zweig nur gezählt, nie
  aufgelistet.
- **Aufräumen am Zweig.** „Veraltetes löschen …“ löscht nach „wirklich?“ alle veralteten Zusammenfassungen und
  Podcasts unter dem gewählten Knoten und nennt vorher die Zahl. Wird ein Chat gelöscht, fragt die App, ob die von
  ihm geschriebenen Artefakte mit gelöscht werden.
- **Finden.** Die Lupe der unteren Leiste sucht nach dem Titel im gewählten Zweig und zeigt die Treffer mit ihrem
  Pfad. Es gibt keine zweite Suche. Das Menü „Weitere Aktionen zur Seite“ (▾) zeigt nur das Geschriebene des
  gewählten Knotens.
- **Öffnen.** Ein Klick auf Dokument, Grafik oder Buch öffnet den Reiter in „Export“. Ein Klick auf den Podcast
  öffnet die Podcast-Ansicht, in der er heute läuft; es gibt keinen zweiten Player. Ein Klick auf die Zusammenfassung
  öffnet die Leseansicht der Seite. Die Leseansicht und die Podcast-Ansicht bleiben, wie sie sind.

Skizze, links ist ein Ordner gewählt (Beispiel erfunden):

```
 Ordner „Lager“
 ───────────────────────────────────────────────────────────────
 Geschrieben
   Buch       Lagerprozesse                          [Veraltet]
   Dokument   Betriebshandbuch                            23.11.
                 Grafik   Schaubild Lagerplan

 ▸ Erzeugt (2)

 Darunter
   ▸ Wareneingang     2 geschrieben
   ▸ Inventur         1 geschrieben

 In diesem Zweig erzeugt: 14 Zusammenfassungen, 3 Podcasts,
 davon 5 veraltet                          [Veraltetes löschen …]
```

Verworfen: die Liste über das ganze Projekt mit Zählern je Art (erste Fassung dieses ADR und der Entwurf
„Artefakt-Liste“ des UX-Strangs dazu). Sie wird nicht gebaut.

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
6. Zusammenfassungen und Podcasts lassen sich an ihrem Platz einzeln löschen.

Angelo hat die Lesarten 1 und 2 am 27.11.2026 gegen 21:10 MEZ genannt bekommen.

## Technik

- **Gemeinsame Schnittstelle.** Ein Typ „Artefakt“ mit Art, Titel, Herkunft, Zustand (aktuell, veraltet,
  fehlgeschlagen), Zeit und den erlaubten Aktionen (öffnen, ausgeben, neu erzeugen, umbenennen, löschen). Die fünf
  Arten stehen als Typen dahinter. Die Ansicht fragt nie nach der Art; welches Menü und welches Klickziel gilt, sagt
  das Artefakt.
- **Die Ansicht am Knoten ist eine Sicht** über die vorhandenen Ablagen: den Speicher der Schreibumgebung
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
3. Erst nach Angelos Zustimmung zu Abschnitt 5: gemeinsame Schnittstelle und die Ansicht am Knoten für Dokument,
   Grafik und Buch, ▾-Menü.
4. Erst nach Angelos Zustimmung: die Plätze für Podcast und Zusammenfassung, „Darunter“, „Veraltetes löschen …“.

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
