# ADR 0047: Lokale und globale Artefakte

- Status: **Angenommen** (27.11.2026) nach Angelos Entscheidungen von 21:26, 21:30 und 23:07 MEZ. Was nicht sein
  Wortlaut ist, steht in Abschnitt 6 als Entscheidung des Architekten.
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
  - **Maßgeblich für den Ort der globalen Artefakte:** Angelos Nachricht vom 27.11.2026, 21:30 MEZ: „Wir könnten doch
    auch Folgendes machen, dass wir globale Artefakte einen ganz anderen Platz geben. Dass wir die nämlich gar nicht
    unter den Exporten verorten, sondern dass wir sagen, wir schalten links um zwischen Wissensquellen und
    Artefakten. Wir haben ja schon diesen schwarzen Toggle-Button, den haben wir zum Beispiel bei den Aufträgen schon
    verwendet, Aufträge und Hintergrund. Denselben Button könnten wir jetzt auch wieder bei Wissensquellen verwenden.
    Machen wir Wissensquellen und Artefakte und dann kann man die Ansicht umschalten und kriegt unten die Liste mit
    Artefakten, also mit den globalen Artefakten natürlich nur. Und dann hat man genug Platz, diese Artefakte auch
    irgendwie sauber zu sortieren.“
  - **Maßgeblich für das Ordnen und für den Ort der lokalen Artefakte:** Angelos Nachricht vom 27.11.2026, 23:07 MEZ:
    „Artefakte sortieren. […] Wenn ich jetzt in mehreren Chats ein Artefakt verwende, können diese Chats verschiedene
    Gruppenfarben haben. Da müsste ich dann eine Mischfarbe draus machen. Das ist Quatsch. Also ich würde halt die
    auf jeden Fall erstmal nach Typ sortieren […] nach Bildern oder Schriftstücken […]. Also die Suche ist ja sowieso
    schon in der Seitenleiste vorhanden. Also die würde ich ja genauso übernehmen […]. Das ist ja auch eigentlich
    streng genommen eher ein Filter […]. Und ja, eben nach Art gruppieren, das kann man aber optional vielleicht
    einschalten unten in der Leiste, dass man das an- und abschalten kann. […] Das müsste dann natürlich wieder im
    Application State gespeichert sein […] wir speichern das halt einmal global, diesen Zustand, und nicht irgendwie
    an jedem Baum oder jeder Datei einzeln. […] Und die lokalen Artefakte […], die kannst du quasi im Dropdown
    lassen. Meine Idee war aber eigentlich […], dass das bei den Exporten direkt auftaucht. Man kann das ja quasi auf
    der leeren Seite machen. Wenn ich auf Exporte gehe, ist diese Seite ja normalerweise erstmal leer. So bevor ich
    einen neuen Tab öffne. Und da könnte man sie bereits präsentieren, diese Artefakte. Und man kann sie auch
    zusätzlich nochmal ins Dropdown packen, falls eine Seite auf ist, dass man trotzdem noch drankommt.“
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
| Zusammenfassung | lokal | Leseansicht | Ordner, Space oder Quelle | nein | nein, wird überschrieben |

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
- Stand im Code (develop, 27.11.2026): Eine einzelne Seite hat als lokales Artefakt ihren Erklär-Podcast; eine
  Zusammenfassung einer einzelnen Seite gibt es nicht. Ordner, Space und Quelle haben die Zusammenfassung des
  Bereichs und den Podcast des Bereichs, der Ordner zusätzlich den Podcast aus dem Buch. Gezeigt wird je Knoten nur,
  was es für ihn gibt.
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

### 5. Ort der globalen Artefakte: links, „Wissensquellen | Artefakte“
- Die globalen Artefakte stehen nicht unter „Export“. Im Kopf der linken Seitenleiste steht dieselbe Wippe wie bei
  „Aufträge | Hintergrund“ (`SegmentedToggle`), hier mit den Hälften **„Wissensquellen“** und **„Artefakte“**. Sie
  steht an der Stelle der Überschrift, so wie bei den Aufträgen in der Titelzeile; Angelos Bild zur Nachricht zeigt
  diese Stelle. Das ist eine bewusste Ausnahme von der Regel „Reiter einer Seitenleiste stehen mittig im `SideFoot`“.
- Zeile, Rechtsklick-Menü und Übergang stehen im [Entwurf des UX-Strangs](../artefakte-seitenleiste.md).
- Unter „Wissensquellen“ zeigt die Seitenleiste den Baum wie heute. Unter „Artefakte“ zeigt sie die Liste der
  globalen Artefakte des aktiven Projekts, und nur diese. Lokale Artefakte stehen dort nie.
- Die Liste nutzt Kopf und Fuß der Seitenleiste wie der Baum (`SideHead` mit Filterfeld, `SideFoot`). Es entsteht
  kein neues Steuerelement.
- **Ordnen (Angelo, 23:07 MEZ):**
  - Der Filter ist der vorhandene der Seitenleiste, unverändert. Es ist ein Filter nach dem Titel, keine Suche.
  - Unten in der Leiste (`SideFoot`) steht ein Schalter „Nach Art gruppieren“. Aus: eine Liste, neueste zuerst. An:
    je Art eine Gruppe, darin neueste zuerst.
  - Der Zustand des Schalters steht einmal global im Zustand der Anwendung. Was zuletzt eingestellt war, gilt
    überall und nach dem Neustart, nicht je Projekt, Baum oder Datei.
  - Keine Gruppenfarben aus dem Chat und keine Gruppen von Hand: Ein Artefakt kann in mehreren Chats verwendet
    werden und hätte dann mehrere Farben.
- Verworfen, nicht bauen: eine Liste aller Artefakte des Projekts samt der lokalen (Angelo, 21:19 MEZ), und globale
  Artefakte an Knoten des Baums (Angelo, 21:22 MEZ).

### 5a. Ort der lokalen Artefakte: die leere Seite von „Export“ und das ▾-Menü
- Der Reiter „Export“ einer Seite zeigt, solange kein Export-Reiter offen ist, die lokalen Artefakte dieser Seite:
  Zusammenfassung und Podcast, mit ihrem Zustand und der Marke „Veraltet“ als Knopf zum Neuerzeugen. Die
  Ausgabe-Knöpfe bleiben in der unteren Leiste.
- Zusätzlich stehen dieselben Einträge im Menü „Weitere Aktionen zur Seite“ (▾), damit man sie auch erreicht, wenn
  ein Export-Reiter offen ist.
- Dasselbe gilt für Ordner, Space und Quelle mit deren lokalen Artefakten. Leseansicht und Podcast-Ansicht bleiben
  unverändert.

### 6. Entscheidungen des Architekten, nicht Angelos Wortlaut
1. Das Buch ist global. Angelo nennt es in seiner Nachricht als Beispiel für ein versioniertes Artefakt; versioniert
   sind nach ihm nur die globalen.
2. Ein Buch bleibt ein Artefakt, die Auflagen sind seine Versionen. So steht ein Buch einmal bei den globalen
   Artefakten und nicht je Auflage einmal.
3. Ältere Auflagen sind lesbar und ausgebbar; bearbeitet wird die neueste. Der Stand einer älteren Auflage ist ihr
   letzter Stand samt Nachbearbeitungen.
4. Die Gruppen des Schalters „Nach Art gruppieren“ heißen wie die Arten in der App: Dokumente, Grafiken, Bücher.
   Angelo nennt als Beispiel „Bilder oder Schriftstücke“.
5. „Rigoros“ (21:04 MEZ) heißt überschreiben. Veraltetes wird nicht von selbst gelöscht.
6. Ein Klick auf ein Artefakt in der Liste links öffnet es in der Mitte, so wie ein Klick im Baum eine Seite öffnet.
   Die Schreibansicht (ADR 0045) bleibt dieselbe, sie steht dann in der Mitte statt im Reiter „Export“ einer Seite.
   Unter „Export“ stehen danach nur noch Exporte. Angelo am 27.11.2026 um 21:31 MEZ so genannt.
7. Die Mitte folgt der Wippe: unter „Wissensquellen“ die Seite wie heute, unter „Artefakte“ die geöffneten
   Artefakte. `ArtifactTabs` zieht dafür aus „Export“ in die Mitte um und wird nicht neu gebaut.
8. Legt die KI im Chat ein Artefakt an oder ändert sie eines, während links „Wissensquellen“ gewählt ist, schaltet
   nichts von selbst um. Die Antwort trägt den Verweis (ein Klick wechselt), die Hälfte „Artefakte“ der Wippe zeigt
   eine Zahl, die Statuszeile meldet es einmal. Steht links „Artefakte“, öffnet sich das Artefakt wie heute.
9. Die lokalen Artefakte tragen an beiden Orten (Abschnitt 5a) dieselben Aktionen: Öffnen (Zusammenfassung in der
   Leseansicht, Podcast in der Podcast-Ansicht, kein zweiter Player), Neu erzeugen (überschreibt) und Löschen nach
   „wirklich löschen?“. Es stehen nur Einträge da, die es für die Seite gibt.

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
3. Wippe „Wissensquellen | Artefakte“ links mit der Liste der globalen Artefakte, Öffnen in der Mitte.
4. Globale Artefakte aus „Export“ und aus dem Menü „Weitere Aktionen zur Seite“ herausnehmen (Abschnitt 6,
   Punkte 7 und 8).
5. Schalter „Nach Art gruppieren“ im Fuß der Leiste, Zustand global gemerkt.
6. Lokale Artefakte auf der leeren Seite von „Export“ und im Menü „Weitere Aktionen zur Seite“.

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
