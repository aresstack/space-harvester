# ADR 0047: Lokale und globale Artefakte

- Status: **Angenommen** (27.11.2026) nach Angelos Entscheidung von 21:26 MEZ. Offen ist nur Abschnitt 5 (Ordnung
  vieler globaler Artefakte). Was nicht sein Wortlaut ist, steht in Abschnitt 6 als Entscheidung des Architekten.
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
  - Angelos Nachricht vom 27.11.2026, 21:19 MEZ, zum Vorschlag einer Liste über das ganze Projekt: „Das Problem ist,
    wie wir mit so vielen Artefakten in einem Projekt umgehen. Wenn jede Datei ein neues Artefakt generiert, weißt
    du, wie übersichtlich das nachher ist“.
  - Angelos Nachricht vom 27.11.2026, 21:22 MEZ, zum Vorschlag, auch im Chat Geschriebenes an Knoten des Baums zu
    hängen: „Der Chat hat ja überhaupt keine Verbindung zu den Knoten. Wie soll das bitte funktionieren?“
  - **Maßgeblich:** Angelos Nachricht vom 27.11.2026, 21:26 MEZ: „Also ich würde sagen, die Podcasts und alles, was zur
    Seite gehört, die Zusammenfassungen, die bleiben auch an der Seite. Die kann man dann auch als Artefakte sehen an
    der Seite, aber nicht in einer globalen Zusammenfassung. Und dann gibt es globale Artefakte aus dem Chat, die
    sieht man überall, würde ich sagen. Das heißt, wir müssen unterteilen in lokale und globale Artefakte. So, und
    die globalen Artefakte werden auch [versioniert], das hatte ich auch gesagt. Nur die lokalen Artefakte werden
    nicht [versioniert]. Weil es wäre scheiße, wenn wir ein Buch schreiben und haben eine neue Version und können
    aber nicht die alte Version sehen, für die wir mega viel Arbeit investiert haben.“
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0034](0034-favoriten-ordner-als-sammlungen.md), Abschnitt 7: Überblick eines Ordners
  - [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md), Nachtrag F3: wird geändert (Abschnitt 2)
  - [ADR 0044](0044-buch-als-ueberblick-eines-ordners.md), Nachtrag: das Buch als Artefakt
  - [ADR 0045](0045-schreibumgebung-im-chat.md), Abschnitt 1: wird geändert (Abschnitt 2)
- Tickets: Zu Artefakten und ihrem Besitzer gibt es am 27.11.2026 kein Ticket in space-harvester,
  writing4j oder knowledge4j, das dem widerspricht (geprüft).

## Ausgangslage (develop, 27.11.2026)

- `ChatArtifactStore.owner` gibt für einen Chat im Standardprojekt „AresStack“ den Ordner des Chats zurück, für jedes
  andere Projekt den Ordner des Projekts. Im Standardprojekt gehört ein Artefakt deshalb dem Chat, in dem es
  entstanden ist. Ein neuer Chat sieht es nicht, und das ▾-Menü listet erst, wenn der alte Chat geladen ist.
- Bücher (`BookEditions`), Podcasts (`PodcastStore`, `PagePodcasts`) und Zusammenfassungen (`AreaSummarizer`,
  Leseansicht) liegen jeweils an eigener Stelle. Es gibt keinen Ort, an dem alles Erzeugte zusammen zu sehen ist.

## Entscheidung

### 1. Ein Begriff, zwei Sorten
Alles, was die App oder die KI erzeugt und ablegt, ist ein **Artefakt**. Es gibt zwei Sorten:

- **Lokale Artefakte** gehören zu einer Seite (oder einem Ordner, einem Space): Zusammenfassung, Podcast und alles,
  was sonst aus ihr erzeugt wird.
- **Globale Artefakte** kommen aus dem Chat: Dokument und Grafik. Das **Buch** ist ebenfalls global (Abschnitt 4).

| Art | Sorte | entsteht durch | gehört zu | bearbeitbar | versioniert |
|---|---|---|---|---|---|
| Dokument | global | Chat oder Benutzer | Projekt | ja, Benutzer und Chat | ja |
| Grafik | global | Chat oder Benutzer | Projekt | ja, Benutzer und Chat | ja |
| Buch | global | „Buch schreiben“ am Ordner | Projekt, erreichbar an seinem Ordner | ja, Benutzer und Chat | ja, jede Auflage bleibt |
| Podcast | lokal | Kopfhörer | Seite, Ordner oder Space | nein, Sprechtext nur lesen | nein, wird überschrieben |
| Zusammenfassung | lokal | Leseansicht | Seite oder Bereich | nein | nein, wird überschrieben |

### 2. Globale Artefakte: das Projekt besitzt, nie der Chat
- Dokumente, Grafiken und Bücher gehören immer dem Projekt, auch im Standardprojekt „AresStack“. Das ersetzt aus
  ADR 0043, F3, „im Standardprojekt besitzt der Chat“ und aus ADR 0045, Abschnitt 1, „Artefakte gehören zum Chat“.
- Man sieht sie überall: Jeder Chat des Projekts sieht, öffnet und bearbeitet dieselben Artefakte, auch ein neuer
  Chat, gleich welche Seite links gewählt ist. Die Werkzeuge der KI arbeiten auf den Artefakten des Projekts.
- Die Artefakte eines Projekts lassen sich lesen, ohne dass ein Chat geladen wird.
- Sie haben keinen Knoten im Baum. Ein Chat hat für den Benutzer keine Verbindung zu den Knoten; dass `ChatStore`
  ihn bei dem Bereich ablegt, in dem er begonnen wurde, ist keine Ordnung für Artefakte. Am Artefakt wird nur
  festgehalten, welcher Chat es erzeugt hat.
- Einen Chat zu löschen löscht kein Artefakt. Gelöscht wird am Artefakt, nach „Artefakt wirklich löschen?“ Ja/Nein,
  mit Papierkorb bis zum Neustart wie beim Chat.
- Die vorhandenen Artefakte der Chats ziehen einmal ins Standardprojekt um: lokal, ohne Netz und ohne KI, mit dem
  vorhandenen Umzug (`moveToProject`), der Kennungen neu vergibt und Verweise nachzieht.
- Kommt ein Chat aus dem Standardprojekt in ein anderes Projekt, ziehen die von ihm erzeugten Artefakte mit, wie
  bisher (ADR 0043, F3). Sonst bleiben Artefakte in ihrem Projekt.

### 3. Lokale Artefakte: bleiben an der Seite
- Zusammenfassung und Podcast bleiben an ihrer Seite, in ihrer heutigen Ablage. Sie sind in jedem Projekt zu sehen,
  weil sie aus dem Wissen entstehen und nicht aus einem Chat.
- An der Seite sind sie als Artefakte zu sehen. Sie stehen in keiner globalen Übersicht und in keiner Liste des
  Projekts. Das ist die Menge, die mit jeder Seite wächst.
- Sie werden nicht versioniert. Neu erzeugen überschreibt.
- Ein lokales Artefakt ist veraltet, wenn sich seine Vorlage geändert hat. Das wird über die vorhandenen Beobachter
  erkannt (ADR 0027). Veraltetes zeigt die Marke „Veraltet“; die Marke ist der Knopf zum Neuerzeugen. Nichts wird von
  selbst neu erzeugt und nichts von selbst gelöscht.
- Der Podcast eines Ordners entsteht wie festgelegt aus dem Buch des Ordners, wenn es eines gibt (ADR 0044,
  Abschnitt 5 und Nachtrag N6). Er ist lokal und wird überschrieben.

### 4. Globale Artefakte sind versioniert; das Buch behält jede Auflage
- Für globale Artefakte gilt der Vertrag aus ADR 0045, Abschnitt 1, unverändert: Jede Änderung ist eine Revision,
  nichts wird überschrieben, „Rückgängig“ geht Revision für Revision zurück.
- Das Buch ist ein Artefakt je Ordner und Projekt. Jede Auflage ist eine benannte Version darin und bleibt erhalten.
  „Neue Auflage erzeugen“ legt eine neue Version an; die bisherige bleibt zu sehen (ADR 0044, Nachtrag N3).

### 5. Offen: Ordnung vieler globaler Artefakte
- Globale Artefakte stehen wie heute im Menü „Weitere Aktionen zur Seite“ (ADR 0045, Abschnitt 5), jetzt aus dem
  Projekt und an jeder Seite gleich, neueste zuerst.
- Wie sie geordnet werden, wenn es viele sind, ist nicht entschieden. Angelo ist am 27.11.2026 gefragt: farbige
  Gruppen wie bei den Chats, eigene Ordner oder nur Suche. Bis dahin wird dafür nichts gebaut.
- Verworfen, nicht bauen: eine Liste aller Artefakte des Projekts samt der lokalen (Angelo, 21:19 MEZ), und globale
  Artefakte an Knoten des Baums (Angelo, 21:22 MEZ).

### 6. Entscheidungen des Architekten, nicht Angelos Wortlaut
1. Das Buch ist global. Angelo nennt es in seiner Nachricht als Beispiel für ein versioniertes Artefakt; versioniert
   sind nach ihm nur die globalen.
2. Ein Buch bleibt ein Artefakt, die Auflagen sind seine Versionen. So steht ein Buch einmal bei den globalen
   Artefakten und nicht je Auflage einmal.
3. Ältere Auflagen sind lesbar und ausgebbar; bearbeitet wird die neueste. Der Stand einer älteren Auflage ist ihr
   letzter Stand samt Nachbearbeitungen.
4. Wo an der Seite die lokalen Artefakte „als Artefakte zu sehen“ sind, ist noch nicht festgelegt. Bis dahin bleiben
   Leseansicht und Podcast-Ansicht ihr Ort, wie heute.
5. „Rigoros“ (21:04 MEZ) heißt überschreiben. Veraltetes wird nicht von selbst gelöscht.

Zurückgenommen: Der Architekt hatte „keine Versionierung“ von 21:04 MEZ zunächst auch auf das Buch bezogen („Neue
Auflage überschreibt“). Das war falsch und ist nie gebaut worden.

## Technik

- **Kennungen.** Alles Geschriebene eines Projekts hat denselben Besitzer. Die Kennungen (D…, G…, B…) sind deshalb
  je Projekt eindeutig, und ein Verweis `artefakt:G1` zeigt nie über einen Besitzer hinaus. Verweise über
  Projektgrenzen gibt es nicht. Zieht ein Artefakt in ein anderes Projekt, ziehen die Grafiken mit, auf die es
  verweist.
- **Offene Reiter** merkt sich weiter der Chat.
- **Versionen des Buchs.** Der Speicher markiert die Revision, die eine Auflage abschließt, mit Nummer und Zeit und
  kann die Auflagen eines Buchs auflisten und einzeln lesen.
- Podcasts und Zusammenfassungen werden nicht in den Speicher der Schreibumgebung kopiert oder umgezogen.

## Umsetzung

Jeder Schritt ein kleiner PR auf develop. Den Speicher der Artefakte ändert nur ein Strang, der der Schreibumgebung.

1. Besitzer ist immer das Projekt, Umzug der vorhandenen Artefakte, Löschen am Artefakt.
2. Buch als Artefakt des Projekts mit Auflagen als Versionen (ADR 0044, Nachtrag N7; baut auf Schritt 1).
3. Ordnung der globalen Artefakte nach Angelos Antwort zu Abschnitt 5.

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
