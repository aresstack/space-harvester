# ADR 0049: Mission Control auf Flowable mit CMMN statt eigener Engine

- Status: **Vorschlag** (28.11.2026). Offen ist eine Entscheidung von Angelo, siehe Abschnitt 5. **Nichts aus diesem
  ADR ist zum Bau freigegeben.**
- Ersetzt [ADR 0048](0048-mission-control-ablaeufe-aus-faehigkeiten.md), das Angelo am 28.11.2026 zurückgenommen hat.
- Quellen:
  - **Maßgeblich:** Angelos Nachricht vom 28.11.2026, 03:20 MEZ: „Die bisherige Architekturentscheidung zur
    Eigenentwicklung der Workflow-Engine wird zurückgenommen. Meine ausdrückliche Bereitschaft, vom bisherigen
    MainframeMate-Konzept abzuweichen, sollte die Verwendung etablierter Bibliotheken und Standards erleichtern, nicht
    deren Verwendung verhindern. Die Prioritäten sind: 1. Etablierte Workflow-Standards und vorhandene
    Ausführungsengines verwenden. 2. Möglichst viele vorhandene Bibliotheksfunktionen unmittelbar nutzen. 3. Das
    fachliche Konzept bei Bedarf an den gewählten Standard anpassen. 4. Nur diejenigen Komponenten selbst entwickeln,
    die Space Harvester tatsächlich zusätzlich benötigt. CWL, Flowable, Nextflow und andere etablierte Lösungen sind
    erneut unter diesen Voraussetzungen zu bewerten. Es ist ausdrücklich erlaubt, von meinem ursprünglichen
    Variablenmodell abzuweichen, wenn eine etablierte Engine dafür ein eigenes geeignetes Konzept mitbringt. Entwickle
    keine eigene Workflow-Sprache, keine eigene Workflow-Engine und keine eigene Ausführungsinfrastruktur, nur um das
    ursprüngliche Konzept exakt nachzubilden.“
  - Von Angelo am 28.11.2026, 03:22 MEZ weitergegebene GPT-Antwort (kein eigener Wortlaut): „Wir sollten das
    fachliche Prinzip beibehalten, gerade weil es dafür bereits einen Standard gibt, und möglichst viel vorhandene
    Implementierung wiederverwenden. Die noch zu klärende Frage war lediglich, welche etablierte Bibliothek wir dafür
    einsetzen können.“
  - Angelos Nachricht vom 28.11.2026, 03:28 MEZ, im Strang zum Proving Ground: „Die entwickeln einzig und allein in
    Java.“ Kein Node.js und nichts Vergleichbares, also auch kein Ausführer in Python.
  - Das Prinzip, Angelos Nachricht vom 28.11.2026, 00:38 MEZ, über die Ausgabevariablen eines Schritts: „Und der
    nächste Schritt ist dann erst gestartet, wenn diese Variable zur Verfügung stand. Das kann man halt auch mit
    Dateien machen.“
- Bezüge:
  - [ADR 0003](0003-lucene-cache.md): „nur Lucene, kein Datenbankserver“, siehe Abschnitt 5
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md), Punkt 9: UI-unabhängige Live-Werkzeuge
  - [ADR 0047](0047-artefakte-des-projekts.md): globale, versionierte Artefakte des Projekts
- Tickets: [#1141](https://github.com/aresstack/space-harvester/issues/1141),
  [#1143](https://github.com/aresstack/space-harvester/issues/1143). Zu Flowable, CMMN oder einer Workflow-Engine gibt
  es am 28.11.2026 in den Repositories von aresstack kein weiteres Ticket.

## 1. Vorschlag in einem Satz

Mission Control führt Abläufe mit der fertigen Engine **Flowable 8** aus, und zwar mit deren **CMMN**-Teil. CMMN ist
ein Standard der OMG, und er enthält Angelos Prinzip von Haus aus: Ein Schritt bekommt eine Bedingung, und er startet,
sobald die Bedingung erfüllt ist. Die Bedingung lautet hier „die Variable ist da“. Space Harvester schreibt dafür keine
eigene Sprache und keine eigene Ausführung.

## 2. Das Prinzip in CMMN

| Angelos Begriff | In CMMN und Flowable |
|---|---|
| Ablauf | Fall (`case`) mit einem Plan |
| Schritt | Aufgabe im Plan (`planItem` mit `task`) |
| „startet, sobald die Variable da ist“ | Eintrittsbedingung (`sentry` mit `ifPart`), zum Beispiel `${vars:exists(liste)}` |
| Schritt liefert Ausgabevariablen | Der Schritt setzt Variablen des Falls |
| Datei kommt von außen | Von außen wird eine Variable des Falls gesetzt, die Engine prüft die Bedingungen neu |
| Freigabe durch den Menschen | Aufgabe für den Menschen (`humanTask`) |
| Schritt noch nicht dran, läuft, fertig | Zustände `available`, `active`, `completed` |
| Ausdrücke | Die Ausdruckssprache von Flowable (`${…}`); CEL aus ADR 0048 entfällt |
| Version eines Ablaufs | Version der bereitgestellten Definition |
| Protokoll eines Laufs | Verlauf der Engine (History) |

Die Reihenfolge, in der die Schritte in der Definition stehen, spielt keine Rolle. Das ist der Unterschied zu BPMN
und zu Serverless Workflow, bei denen die Definition die Reihenfolge festlegt.

## 3. Was ausprobiert wurde

Alle Läufe am 28.11.2026 in kleinen Probeprojekten außerhalb des Repositorys, mit Java 21 und dem Gradle des Projekts.
Die Probedaten sind erfunden.

### Flowable 8.0.0 (veröffentlicht am 27.02.2026, Apache-Lizenz 2.0)

| Probe | Ergebnis |
|---|---|
| Eingebettet starten, ohne Server, mit H2 als Datei | Läuft. Start in etwa 1,5 bis 1,8 Sekunden. |
| CMMN, drei Schritte in verkehrter Reihenfolge aufgeschrieben: C braucht `ergebnis` und `datei`, B braucht `liste`, A braucht nichts | A läuft sofort, B startet von selbst, sobald `liste` da ist, C wartet. Nach dem Setzen von `datei` von außen läuft C, der Fall ist beendet. |
| CMMN, zwei lange Schritte gleichzeitig offen, ein dritter braucht beide Ergebnisse, danach Freigabe durch den Menschen, danach Schreibschritt | Läuft. Die langen Schritte bleiben `active`, bis sie von außen fertig gemeldet werden. |
| BPMN, Warten auf eine Nachricht, Programm beenden, neu starten | Die wartende Instanz ist nach dem Neustart da und läuft nach der Nachricht weiter. |
| BPMN, Unterablauf mit festgelegter Übergabe von Ein- und Ausgaben | Läuft. Der Unterablauf sieht nur, was ihm übergeben wird. |
| BPMN, neue Version bereitstellen, während eine Instanz läuft | Version 2 steht bereit, die laufende Instanz endet auf Version 1. |
| Fehlerhafte Definition bereitstellen (Verweis ins Leere, kaputtes XML) | Wird beim Bereitstellen mit Fehlermeldung abgelehnt. |
| Alle Läufe noch einmal mit den Bibliotheksständen von Space Harvester (Spring 7.0.9, Jackson 3.1.5, Commons) | Gleiche Ergebnisse. |

Abgleich mit dem Klassenpfad der App: Flowable 8 ist gegen Spring Boot 4.0.2, Spring 7.0.3 und Jackson 3.0.1 gebaut,
Space Harvester steht auf denselben Linien in neueren Ständen. Neu kämen H2, MyBatis, joda-time, java-uuid-generator
und die Mail-Bibliotheken von Jakarta dazu. Engine, MyBatis und H2 sind zusammen etwa 12 MB.

### Serverless Workflow, Java-SDK 7.35.4 (veröffentlicht am 17.11.2026, Apache-Lizenz 2.0)

Läuft eingebettet unter Java 21, Start in 0,1 Sekunden. Parallele Zweige (`fork`) und Warten auf ein Ereignis
(`listen`) laufen. Ein Schritt, der eine Variable braucht und **vor** dem liefernden Schritt aufgeschrieben ist, läuft
aber zuerst und ohne die Variable. Die Engine arbeitet in der aufgeschriebenen Reihenfolge, nicht nach verfügbaren
Daten. Sie baut auf Jackson 2 auf. Mit dem Speichermodul (`persistence-mvstore`) wurde ein wartender Lauf nach dem
Neustart in zwei Versuchen nicht wiedergefunden; ob das an der Verwendung im Probelauf liegt, ist nicht geklärt.

### Operaton 2.2.2 (veröffentlicht am 20.11.2026, Apache-Lizenz 2.0, Nachfolger von Camunda 7)

Läuft eingebettet unter Java 21. Der CMMN-Teil kennt dasselbe Prinzip: Der Schritt mit der Bedingung startete, sobald
die Variable von außen gesetzt war. Gegen Operaton spricht die Lage von CMMN in dieser Linie: Camunda schrieb 2020, CMMN
werde „simply be maintained but not fully supported“; Fluxnova, ein anderer Nachfolger von Camunda 7, führt CMMN als
veraltet und kündigt die Entfernung an; die Beschreibung von Operaton nennt BPMN, DMN und Formulare, CMMN nicht. Bei
Flowable ist CMMN ein eigener, dokumentierter Teil mit Java-Schritten.

### Nicht als eingebettete Java-Engine verfügbar (auf den eigenen Seiten nachgelesen, nicht ausprobiert)

- **CWL:** Die offizielle Liste der Ausführer enthält keinen gepflegten in Java. Rabix Bunny ist als veraltet markiert
  und seit 2019 nicht gepflegt. Die übrigen Ausführer sind Python-Programme, die Schritte sind Kommandozeilenprogramme.
- **Nextflow:** verlangt Bash und läuft unter Windows nur über WSL.
- **Temporal:** braucht einen eigenen Serverprozess.
- **Apache Camel:** ist eine Bibliothek für Nachrichtenwege, keine Engine mit diesem Prinzip. Camel wurde nicht
  ausprobiert und wird hier nicht gebraucht.

## 4. Was Flowable mitbringt und was Space Harvester dazu baut

Flowable bringt mit: das Format (CMMN 1.1 als XML), das Prüfen beim Bereitstellen, die Ausführung nach Bedingungen,
Variablen, Ausdrücke, Warten, Weiterlaufen nach dem Neustart, Aufgaben für den Menschen, Unterabläufe, Versionen und
den Verlauf jedes Laufs.

Space Harvester baut nur, was keine Engine liefern kann:

1. **Fähigkeiten von Space Harvester als Schritte.** Eine allgemeine Klasse ruft eine Fähigkeit (Quelle laden, Datei
   lesen, JES-Auftrag abschicken und so weiter) mit Eingaben aus den Variablen auf und schreibt das Ergebnis als
   Variablen zurück. Der Katalog der Fähigkeiten ist der offene Punkt aus ADR 0036 und gilt für Mensch, Chat und
   Abläufe gleich.
2. **Lange Schritte als Aufträge.** Ein langer Schritt wird ein Auftrag in der rechten Seitenleiste (`Jobs`). Er
   meldet sich bei der Engine fertig, wenn er fertig ist. Kein Polling.
3. **Auslöser.** Knopf, Chat-Werkzeug, Zeit und Anlieferung einer Datei starten einen Fall oder setzen eine Variable.
4. **Schreibfreigabe.** Welche Fähigkeiten schreiben und wer sie freigibt, bleibt Regel von Space Harvester; die
   Freigabe selbst ist eine Aufgabe für den Menschen in der Engine.
5. **Werkzeuge für die KI.** Definition lesen, prüfen lassen und zur Freigabe vorlegen. Die KI plant einmal, danach
   läuft der Ablauf ohne KI.
6. **Die Perspektive Mission Control.** Sie zeigt, was die Engine weiß: Abläufe, Versionen, laufende Fälle, Zustand
   jedes Schritts, Verlauf.
7. **Ablage.** Die Definition ist ein globales, versioniertes Artefakt des Projekts (ADR 0047) und wird von dort in
   der Engine bereitgestellt.

## 5. Offene Entscheidung: eingebettete Datenbank

Flowable und Operaton speichern in einer relationalen Datenbank. Eingebettet heißt das: H2 als Datei im
Datenverzeichnis, im selben Prozess, ohne Server. ADR 0003 hält als Angelos Grundsatz fest „nur Lucene, kein
Datenbankserver“ und nennt für den Seitenspeicher auch H2 als ausgeschlossen. H2 wäre hier kein Server und nur der
Speicher der Engine im Teilprojekt `mission-control`; Seiten, Index und Aufträge bleiben in Lucene. Ob das mit dem
Grundsatz vereinbar ist, entscheidet Angelo. Ohne Datenbank bliebe von den ausprobierten Engines nur Serverless
Workflow, also die aufgeschriebene Reihenfolge statt des Prinzips.

## 6. Befunde aus den Probeläufen, die der Bau beachten muss

- **Gleichzeitige Fertigmeldungen.** Melden sich zwei Schritte desselben Falls im selben Augenblick aus zwei Fäden
  fertig, sind danach beide Variablen da, der abhängige Schritt startet aber nicht von selbst. Nacheinander gemeldet
  startet er. Ein Aufruf von `evaluateCriteria` holt den Start nach. Space Harvester meldet deshalb je Fall
  nacheinander fertig.
- **Lange Schritte.** Ein Java-Schritt (`flowable:type="java"`) gilt nach der Rückkehr der Methode als fertig, auch
  mit `isBlocking="true"`. Für lange Arbeit eignet sich die schlichte Aufgabe (`task`), die `active` bleibt, bis sie
  von außen fertig gemeldet wird; ein Listener stößt beim Wechsel nach `active` den Auftrag an.
- **Fehler im Schritt.** Wirft ein Schritt eine Ausnahme, kommt sie beim Aufrufer an, und die Engine nimmt den
  Übergang zurück: Der Schritt steht wieder auf `available`, die vorangegangene Freigabe wieder auf `active`. Die
  Engine merkt sich den Fehler in diesem Fall nicht. Space Harvester muss den Fehler am Auftrag festhalten und darf
  einen Schreibschritt mit ungewissem Ausgang nicht von selbst wiederholen.
- **Bedingungen.** Der nackte Zugriff auf eine noch nicht gesetzte Variable (`${freigegeben}`) wirft eine Ausnahme.
  Bedingungen benutzen `vars:exists(…)` oder `vars:getOrDefault(…)`.
- **Ausdrücke werden beim Bereitstellen nicht geprüft.** Ein Ausdruck mit Syntaxfehler wurde angenommen. Space
  Harvester prüft Ausdrücke vor dem Bereitstellen zusätzlich.

## 7. Was aus ADR 0048 entfällt und was bleibt

Es entfallen: das eigene JSON-Format, CEL, die eigene Ausführung, das eigene Protokoll und die eigene
Versionsverwaltung. Es bleiben als fachliche Vorgaben von Angelo: dieselben Werkzeuge für Mensch, KI und Abläufe; die
KI plant einmal, danach genügt ein Auslöser; freigegebene Abläufe schreiben ohne Nachfrage; die KI gibt nie frei und
schaltet nie Auslöser; Auslöser sind nach dem Neustart von selbst wieder an, ohne etwas doppelt zu verarbeiten; ein
freigegebener Ablauf ist selbst eine Fähigkeit; Abläufe gehören dem Projekt.

## 8. Nicht geprüft

- Bau und Lauf der App von Space Harvester mit Flowable. Geprüft ist nur das Probeprojekt mit denselben
  Bibliotheksständen.
- Zeit-Auslöser der Engine, verschachtelte Fälle in CMMN, Versionen in CMMN (Versionen und Unterablauf sind nur in
  BPMN ausprobiert).
- Das Zeichnen eines Falls als Bild (`flowable-cmmn-image-generator` liegt bei).
- Wie gut ein Sprachmodell CMMN-Definitionen schreibt.
- Das Erkennen einer vollständig angelieferten Datei und einer erneuten Anlieferung.

## 9. Umsetzung

Erst nach Angelos Entscheidung zu Abschnitt 5 und einer gesonderten Freigabe. Dann in kleinen Schritten: Teilprojekt
`mission-control` mit eingebetteter Engine; Katalog der Fähigkeiten; lange Schritte als Aufträge; Freigabe; Auslöser;
Werkzeuge für die KI; Perspektive.
