# ADR 0048: Mission Control – Abläufe aus Fähigkeiten

- Status: **Vorbereitet** (28.11.2026). **Die Umsetzung ist nicht freigegeben.** Angelo gibt sie gesondert frei; bis
  dahin leitet kein Strang aus diesem ADR einen Bauauftrag ab. Ticket: #1143 (übergeordnet #1141).
- Quellen:
  - Angelos Nachricht vom 28.11.2026, 00:38 MEZ, zur Engine in MainframeMate: „Ich fand meine Engine relativ charmant,
    weil sie halt immer Variablen verwendet hat. […] Sie hat dann Ausgabevariablen definiert und dann konnte man diese
    Variablen wieder als Eingabe nehmen für was anderes, für den nächsten. Und der nächste Schritt ist dann erst
    gestartet, wenn diese Variable zur Verfügung stand. Das kann man halt auch mit Dateien machen.“
  - Angelos Nachricht vom 28.11.2026, 00:56 MEZ: „Ich glaube CLAUDE hat nicht verstanden, dass ich hier das wirklich
    neu entwickeln will. Ich will nicht das alte wiederverwenden. Ich will nur das Prinzip beibehalten.“
  - Angelos Nachricht vom 28.11.2026, 00:59 MEZ: „Außerdem hatten wir vorher schon sowas wie MCP-Tools verwendet, die
    wir miteinander verdrahtet haben. Also so, dass es halt auch von der KI ausgeführt werden kann. […] Nur dass von
    der KI machen zu lassen, haben wir halt die Schritte fest verdrahtet. Das macht an sich auch Sinn, weil eine KI
    könnte dann quasi die Schritte planen und immer wieder erneut ausführen. Es […] bräuchte dann nur noch einen
    Trigger, wenn es einmal von der KI geplant wurde“.
  - Angelos Nachricht vom 28.11.2026, 01:06 MEZ. Sie besteht aus einer Antwort aus seiner GPT-Unterhaltung, die er
    ohne eigene Worte weitergegeben hat. Daraus stammen die beiden „Ja“ in Abschnitt 6 und 7 und die Ergänzungen, die
    unten mit „(01:06)“ gekennzeichnet sind.
  - Angelos Nachricht vom 28.11.2026, 01:17 MEZ, ebenfalls eine weitergegebene Antwort aus seiner GPT-Unterhaltung
    nach deren Durchsicht dieses ADR. Daraus stammen die Punkte, die unten mit „(01:17)“ gekennzeichnet sind. Jeder
    davon ist vor der Übernahme am Code geprüft worden.
- Was nicht aus diesen Nachrichten stammt, ist eine Entscheidung des Architekten und in Abschnitt 12 aufgezählt.

## Kontext

Mission Control ist die vierte Perspektive (#1141, früher „Mission Protocol“, #1143). Vorbild für die Fachlichkeit ist
die Workflow-Funktion von MainframeMate: Eine Datei wird in das Programm gezogen und startet einen Ablauf aus
Werkzeugen, die auch die KI benutzen kann.

Mission Control ist eine **Neuentwicklung**. Aus MainframeMate wird kein Code, kein Datenmodell, kein Parser und kein
Speicherformat übernommen. Die Regel „Extrahieren heißt verschieben“ gilt hier nicht, weil Angelo ausdrücklich nur das
Prinzip behalten will.

Die alte Engine liefert Anforderungen aus ihren Schwächen (gelesen in MainframeMate, Stand `8a701e0`): Sie wartet nicht
auf Variablen, sondern geht die offenen Schritte wiederholt durch und bricht ab, sobald ein Durchgang nichts bewegt
(`WorkflowRunnerImpl.java:53-123`). Gescheiterte Schritte werden entfernt und nur auf die Konsole geschrieben
(Zeilen 63-66, 108-111). Sie läuft im Oberflächen-Thread und hält ihren Zustand nur im Speicher.

## Entscheidung

### 1. Drei Arten, dieselben Systeme zu bedienen

| Perspektive | Wer bedient |
| --- | --- |
| Command Deck | der Mensch |
| Chat | die KI, jedes Mal neu geplant |
| Mission Control | ein gespeicherter Ablauf, jedes Mal gleich |

Alle drei benutzen dieselben Fähigkeiten. Ein Ablauf, den die KI einmal geplant und der Benutzer freigegeben hat,
läuft danach beliebig oft ohne KI.

### 2. Das Prinzip

- Ein Schritt nennt die Variablen, die er braucht, und die Variablen, die er liefert.
- Ein Schritt startet, sobald alle seine Eingaben da sind.
- Die Reihenfolge wird nirgends gespeichert. Sie ergibt sich aus den Variablen.
- Unabhängige Schritte laufen gleichzeitig.
- Auslöser und äußere Ereignisse liefern Variablen auf demselben Weg wie Schritte.
- Eine Variable kann ein Wert, eine strukturierte Angabe oder der Verweis auf eine Datei sein.

### 3. Fähigkeitenkatalog

Ein Eintrag beschreibt eine Fähigkeit: Name, Beschreibung, Schema der Eingaben, Schema der Ausgaben, Wirkung (liest,
schreibt, fragt eine KI), benötigte Verbindung und Freigabe, die Ressource, auf die er schreibt, ob eine Wiederholung
gefahrlos ist, und ein Vertragsstand. Dahinter steht ein Anwendungsfall in Java ohne Bezug zur Oberfläche.

Befund am heutigen Code: Diesen Katalog gibt es noch nicht. Die Werkzeuge des Chats (`ChatToolset`) nehmen JSON
entgegen und liefern Text für das Modell, ohne Schema der Ausgabe. Einige hängen an der sichtbaren Ansicht
(`JesChatTools` lehnt ab, wenn die JES-Ansicht nicht aktiv ist). ADR 0036 führt „UI-unabhängige Live-Werkzeuge mit
Freigabe je Verbindung“ als offen. Der Katalog erledigt diesen offenen Punkt und ist kein zweiter Mechanismus daneben.

- Der Chat bekommt Werkzeuge über einen Adapter aus dem Katalog; das strukturierte Ergebnis wird dort zu Text.
- Vorhandene Chat-Werkzeuge bleiben unangetastet und ziehen einzeln um, wenn ein Ablauf sie braucht.
- MCP ist ein Adapter am Rand: Er kann Einträge nach außen anbieten oder fremde MCP-Werkzeuge als Einträge aufnehmen.
  Die Ausführung selbst spricht kein MCP.

### 4. Ablaufdefinition

Ein maschinenlesbares Dokument (JSON), das Benutzer und KI lesen und ändern können:

- Eingaben des Ablaufs, mit Typ.
- Schritte: Fähigkeit mit Vertragsstand, Eingaben (fester Wert, Verweis auf eine Variable oder Ausdruck), Namen der
  Ausgabevariablen, optional „nur wenn“, optional „für jedes Element“.
- Ergebnisse des Ablaufs.

Regeln:

- Jede Variable hat genau einen Erzeuger: eine Eingabe, einen Auslöser oder einen Schritt.
- Geprüft wird beim Speichern, nicht erst beim Lauf: unbekannte Fähigkeit, fehlende Variable, falscher Typ gegen das
  Schema, Kreis, zwei Erzeuger. Die Prüfung nennt alle Schreibschritte mit ihrem Ziel.
- Freie Schleifen gibt es nicht, damit jeder Ablauf endet.
- Der Graph in der Oberfläche ist eine Ansicht der Variablen, nie die gespeicherte Wahrheit.
- Gelesen und geschrieben wird mit Gson (01:17). Befund: Beide Bibliotheken sind im Projekt, Jackson in Core, App und
  Server, Gson in `proving-ground`, `browser-runtime` und den `enterprise-ai`-Modulen. Ein zwingender Grund gegen Gson
  besteht nicht. Die Schnittstelle des Katalogs im Core nennt keine Typen einer JSON-Bibliothek.

### 5. Ausdrücke

Die meisten Verknüpfungen sind reine Verweise auf eine Variable. Für Bedingungen und kleine Umformungen dient CEL
(Common Expression Language, `dev.cel:cel`). Ein eigener Parser wird nicht entwickelt (01:06). **CEL wird erst
verwendet, wenn der vollständige Klassenpfad der App erfolgreich geprüft ist (01:17).**

Gründe: nicht Turing-vollständig, Auswertung ohne Seiteneffekte, Prüfung von Variablen und Typen vor dem Lauf. SpEL
liegt zwar über Spring im Klassenpfad, scheidet aber aus: Die Spring-Dokumentation warnt vor Ausdrücken aus nicht
vertrauenswürdiger Quelle, und hier schreibt die KI Ausdrücke. JEXL ist eine Skriptsprache und bräuchte eine Sandbox.

Prüfung am 28.11.2026 (Probe außerhalb des Repos, CEL 0.15.0 unter Java 21):

| Geprüft | Ergebnis |
| --- | --- |
| ANTLR-Laufzeit | CEL verlangt 4.13.2, dieselbe Fassung wie `libs.versions.toml` |
| `records.filter(r, r.valid).map(r, r.id)` | wird ausgewertet |
| Variable, die nicht erklärt ist | vor dem Lauf abgelehnt („undeclared reference“) |
| Falscher Typ (`records + 1`) | vor dem Lauf abgelehnt |
| Benutzte Variablen | aus dem geprüften Ausdruck ablesbar; Hilfsnamen der Makros sind herauszufiltern |
| Weitere Abhängigkeiten | Guava 33.7.2-jre, protobuf-java 4.36.2, RE2/J 1.8, threeten-extra 1.11.0, SnakeYAML 2.7 |

Abgleich mit dem aufgelösten Laufzeit-Klassenpfad der App (`:space-harvester-app:runtimeClasspath`, develop
`fc0767b7f`, 28.11.2026):

| Bibliothek von CEL | Im Klassenpfad der App | Folge |
| --- | --- | --- |
| antlr4-runtime 4.13.2 | 4.13.2 | gleich |
| jspecify 1.0.1 | 1.0.1 | gleich |
| SnakeYAML 2.7 | 2.6 (über die Spring-Boot-Vorgabe) | würde auf 2.7 steigen |
| Guava, protobuf-java, RE2/J, threeten-extra, auto-value-annotations | nicht vorhanden | kommen neu dazu, kein Versionskonflikt |

Damit sind die Versionen abgeglichen. Nicht geprüft ist ein Bau und Lauf der App mit CEL und mit SnakeYAML 2.7; das
zeigt die CI im ersten Bau-Schritt. Gson liegt in Fassung 2.14.0 schon im Klassenpfad der App.

### 6. Auslöser

Ein Auslöser ist von der Definition getrennt: Ereignis, Filter, Ablauf, und welche Angabe des Ereignisses in welche
Eingabe geht.

- Arten: von Hand starten, Datei in die App ziehen, Datei erscheint in einem Ordner, später Zeit und Ereignisse der
  App (etwa „Auftrag fertig“).
- Einen Auslöser einzuschalten ist ein Klick des Benutzers. Ab Werk ist keiner eingeschaltet. Eingeschaltete
  Auslöser stehen sichtbar in einer Liste mit Schalter und in der Statuszeile.
- **Ein eingeschalteter Auslöser ist nach einem Neustart der App von selbst wieder eingeschaltet. Bereits
  verarbeitete Ereignisse werden dadurch nicht erneut verarbeitet (01:06).** Das ist eine ausdrückliche Ausnahme von
  „nichts beim App-Start“.
- **Datei-Auslöser erkennen vollständige Dateien und vermeiden doppelte Verarbeitung (01:06).** Das Verfahren für
  „vollständig“ wird im Bau am Windows-Rechner erprobt, auch auf Netzlaufwerken; jede Wartezeit ist eine Einstellung.
- **Verarbeitet wird eine Anlieferung, nicht eine Datei (01:17).** Eine Anlieferung ist das Erscheinen oder
  Überschreiben einer Datei an einem Pfad. Der Auslöser führt ein Verzeichnis der verarbeiteten Anlieferungen, das
  den Neustart überdauert, mit Pfad, Größe, Änderungszeit und Prüfsumme.

  | Ereignis | Verhalten |
  | --- | --- |
  | Neue Datei erscheint | Ablauf starten |
  | App wird neu gestartet | bereits verarbeitete Anlieferungen übergehen |
  | Datei kam, während die App aus war, und liegt noch da | nachholen, genau einmal |
  | Datei wird überschrieben | neue Fassung verarbeiten |
  | Dieselbe Datei wird bewusst erneut angeliefert | als neue Anlieferung verarbeiten |
  | App endet mitten in einem Schreibschritt | ungewiss, nie von selbst wiederholen (Abschnitt 10) |

- Woran eine erneute Anlieferung derselben Datei sicher erkannt wird, wird im Bau am Windows-Rechner erprobt. Von
  Hand geht „Erneut verarbeiten“ in der Liste der Anlieferungen immer.
- **Ereignisgesteuerte Überwachung mit Bestandsabgleich (01:17):** Für den Ordner bietet das JDK `WatchService`. Beim
  Start und wenn das Dateisystem verlorene Ereignisse meldet (`OVERFLOW`), gleicht der Auslöser den Ordner einmal mit
  seinem Verzeichnis ab. Das ist kein Abfragen in Abständen (ADR 0027). Das Verhalten von `WatchService` auf
  Netzlaufwerken ist nicht geprüft.
- Grenze: Eine Datei, die erschienen und wieder verschwunden ist, während die App aus war, kann kein Abgleich sehen.

### 7. Freigabe

- Was die KI anlegt oder ändert, ist ein Entwurf. Nur der Benutzer gibt frei.
- **Ein freigegebener Ablauf führt seine genehmigten Schreibschritte ohne erneute Nachfrage aus (01:06).**
- Eine Freigabe gilt für genau eine Version des Ablaufs und hält fest:
  - die erlaubten Aufrufarten: von Hand, durch Auslöser, durch die KI;
  - **je Schreibschritt das zulässige Zielsystem und die zulässigen Ressourcen (01:06)**;
  - den Vertragsstand jeder benutzten Fähigkeit und die Version jedes benutzten Ablaufs.
- Geprüft wird bei jedem Start und vor jedem Schreibschritt. Liegt ein Ziel, das erst im Lauf aus Variablen entsteht,
  außerhalb der Freigabe, scheitert der Schritt vor dem Schreiben und nennt den Grund.
- **Die KI darf einen freigegebenen Ablauf aufrufen, auch mit Schreibschritten, wenn die Freigabe die Aufrufart „durch
  die KI“ einschließt. Die KI erteilt nie eine Freigabe und schaltet nie einen Auslöser ein (01:06).**
- **Eine Freigabe ist jederzeit widerrufbar (01:17).** Nach dem Widerruf startet kein Lauf mehr, die Auslöser des
  Ablaufs halten sichtbar an, und ein laufender Lauf beginnt keinen weiteren Schreibschritt.
- Die Regel des Command Deck bleibt: Im Terminal füllt die KI Masken und schickt nie ab. Schreiben kann die KI nur
  über einen freigegebenen Ablauf.

### 8. Versionen

- **Abläufe sind versioniert. Ein Ablauf, der einen anderen benutzt, verweist auf eine bestimmte freigegebene
  Version, damit eine Änderung am benutzten Ablauf die bestehende Freigabe nicht unbemerkt erweitert (01:06).**
- Jede Freigabe erzeugt eine unveränderliche Version. Eine Änderung erzeugt einen neuen Entwurf; die freigegebene
  Version läuft weiter, bis der Benutzer die neue freigibt.
- Der übergeordnete Ablauf zeigt an, dass es eine neuere Version gibt. Der Wechsel ist eine Änderung an ihm selbst
  und braucht eine neue Freigabe.
- Ändert sich an einer Fähigkeit etwas Sicherheitsrelevantes (Wirkung, Ziel, Berechtigung, unverträgliches Schema),
  steigt ihr Vertragsstand. Freigaben von Abläufen, die sie benutzen, ruhen dann. Ihre Auslöser halten sichtbar an,
  nichts läuft, bis der Benutzer neu freigibt.

### 9. Ein Ablauf ist selbst eine Fähigkeit

Ein freigegebener Ablauf mit Eingaben und Ergebnissen erscheint selbst im Katalog. Der Chat und andere Abläufe können
ihn benutzen. Die Prüfung erkennt Kreise über Abläufe hinweg. Enthält er einen Schreibschritt, gilt er als schreibend.
So wird ein einmal geplanter Ablauf zu einem Skill, den die KI ohne Training erworben hat.

**Ein Ablauf, der andere benutzt, erklärt deren Schreibwirkungen, Ziele und Berechtigungen vollständig nach außen
(01:17).** Die Freigabe des äußeren Ablaufs zeigt und umfasst sie alle, über jede Tiefe.

### 10. Ausführung und Zustand

- Fachlich ist eine Variable ein Name mit Zustand und Wert, festgehalten im Protokoll des Laufs. Sie wird genau einmal
  gebunden. **Technisch wartet die Ausführung mit `CompletableFuture`; das ist ein Mittel der Laufzeit und nicht das
  Modell (01:17).** Es gibt keinen Durchlauf über offene Schritte und kein Abfragen (ADR 0027).
- Ein Lauf ist ein Auftrag in der vorhandenen Auftragsverwaltung (`Jobs`), jeder Schritt ein Unterauftrag
  (`ChildJobs`). Abbrechen, Anhalten, Diagnose, Beobachter und das Warten bei fehlendem Netz kommen von dort. Es gibt
  keine zweite Auftragsverwaltung (#1141). Ein Ablauf mit nur einem Schritt zeigt keine Klammer.
- **Zustände einer Ausgabe (01:06): noch nicht verfügbar, gebunden, entfallen, gescheitert, abgebrochen.** Jeder
  Schritt bringt alle seine Ausgaben in genau einen Endzustand. Damit bleibt kein abhängiger Schritt hängen:
  - Eingabe entfallen (der Erzeuger wurde über „nur wenn“ übersprungen): Der Schritt entfällt ebenfalls, außer die
    Eingabe hat einen Ersatzwert.
  - Eingabe gescheitert oder abgebrochen: Der Schritt wird nicht ausgeführt und nennt die Ursache.
  - Eingabe noch nicht verfügbar: Der Schritt wartet sichtbar und belegt keinen Platz. Eine Frist ist je Schritt
    einstellbar.
- Unabhängige Zweige laufen zu Ende. Der Lauf endet als gescheitert, wenn ein Schritt gescheitert ist. Meldet eine
  Fähigkeit einen Fehler, ist das ein Scheitern.
- **Gleichzeitiges Schreiben auf dieselbe Ressource läuft kontrolliert (01:06):** Je Ressource läuft über alle Läufe
  hinweg höchstens ein Schreibschritt, in der Reihenfolge des Eintreffens. Die übrigen warten sichtbar.
- **Verschachtelte Abläufe verklemmen sich nicht (01:17):** Eine Sperre hält nur der einzelne Schreibschritt einer
  elementaren Fähigkeit, nie ein ganzer Ablauf. Braucht eine Fähigkeit mehrere Ressourcen, nimmt sie alle auf einmal
  in fester Reihenfolge.
- Je Lauf gibt es ein Protokoll, das nur wächst: Eingaben, je Schritt Start, Ende, Ausgaben, Fehler. Große Werte und
  Dateien stehen als Verweis mit Prüfsumme darin.
- Wiederaufnahme: Aufgezeichnete Variablen werden gebunden, fertige Schritte nicht wiederholt, der Rest läuft weiter.
  Ein Schreibschritt, der begonnen hat und kein aufgezeichnetes Ende hat, gilt als ungewiss und wird nie von selbst
  wiederholt. Der Benutzer entscheidet.
- Ablage als Dateien wie die übrigen Speicher. Keine Datenbank.
- Jede Grenze (Gleichzeitigkeit, Fristen, Wartezeiten) ist eine benannte Einstellung nach ADR 0036 Punkt 12.

### 11. Projekt und Artefakt

Übernommen aus dem Vorschlag von 01:17:

- Ein Ablauf gehört standardmäßig einem Projekt (ADR 0043), wie die globalen Artefakte nach ADR 0047.
- Ein freigegebener Ablauf kann ausdrücklich für alle Projekte bereitgestellt werden. Dann erscheint diese Version im
  Katalog jedes Projekts.
- Auslöser, Zielsysteme und Freigaben bleiben bei dem Projekt, in dem sie eingerichtet wurden.
- Abläufe erscheinen als eigene Art in der bestehenden Artefaktverwaltung, neben Dokument und Grafik.
- Mission Control und die Artefaktverwaltung benutzen dieselbe gespeicherte Definition. Es gibt keinen zweiten
  Speicher.

Befund am Code: `ChatArtifact.Kind` kennt heute `DOCUMENT` und `GRAPHIC`. Der Speicher (`ChatArtifactStore`) führt
Revisionen und für das Buch benannte Versionen (`editions()`, `newEdition`). Damit ist beides vorhanden, was ein
Ablauf braucht: Entwurfsstände sind Revisionen, freigegebene Versionen sind benannte Versionen.

## 12. Entscheidungen des Architekten

Diese Punkte stammen nicht aus Angelos Nachrichten:

1. JSON als Format der Definition; „nur wenn“ und „für jedes Element“ statt freier Schleifen.
2. Der Katalog entsteht als Erledigung des offenen Punkts aus ADR 0036; vorhandene Chat-Werkzeuge ziehen einzeln um.
3. `CompletableFuture` als Variable; Lauf als Auftrag, Schritt als Unterauftrag.
4. Protokoll je Lauf und die Regel für ungewisse Schreibschritte.
5. Abschnitt 6: was das Verzeichnis je Anlieferung festhält, und „Erneut verarbeiten“ von Hand. (Das Nachholen nach
   dem Start war zuerst eine Lesart des Architekten und ist seit 01:17 bestätigt.)
6. Abschnitt 8: ruhende Freigabe bei geändertem Vertragsstand einer Fähigkeit.
7. Abschnitt 10: Schreibschritte je Ressource nacheinander in der Reihenfolge des Eintreffens.
8. Eigenes Gradle-Teilprojekt `mission-control` wie `command-deck` und `proving-ground`: Modell, Prüfung, Ausführung,
   Protokoll. Ohne Swing, ohne KI, ohne MCP. Die Schnittstelle des Katalogs liegt im Core, damit Chat und Mission
   Control sie teilen.
9. Abschnitt 7: Nach einem Widerruf beginnt ein laufender Lauf keinen weiteren Schreibschritt.
10. Abschnitt 10: Sperren nur am einzelnen Schreibschritt, mehrere Ressourcen auf einmal in fester Reihenfolge.
11. Abschnitt 11: Ein anderes Projekt, das einen bereitgestellten Ablauf benutzt, erteilt seine eigene Freigabe. Bei
    „Nach Art gruppieren“ heißt die Gruppe „Abläufe“. Der Artefaktspeicher liegt im Modul der App;
    `mission-control` spricht ihn über eine Schnittstelle an.

## Geprüfte Standards

An den eigenen Seiten der Projekte nachgelesen (28.11.2026):

| Kandidat | Befund | Urteil |
| --- | --- | --- |
| Serverless Workflow (CNCF), Java-SDK | Laufzeit für Java 21 vorhanden. „By default, tasks execute sequentially in the order they are declared“, gleichzeitig nur über `fork`. Ein Start bei verfügbaren Daten kommt nicht vor. | widerspricht dem Prinzip |
| Common Workflow Language | Schritte sind Kommandozeilenprogramme. Keine Implementierung für Java in der offiziellen Liste; Rabix Bunny seit 2019 nicht gepflegt. | passt nicht |
| Nextflow | braucht Bash, auf Windows nur über WSL | passt nicht |
| Temporal | braucht einen eigenen Dienst-Prozess | passt nicht |
| Flowable 7 | BPMN mit gezeichnetem Ablauf, Java 17, Spring Boot 3 | widerspricht dem Prinzip |
| Apache Camel 4 | Nachrichtenrouten statt Variablen, Java 17 | passt nicht |
| Dexecutor | zuletzt gefundene Version von 2016 | keine Grundlage |

Keiner der geprüften Standards setzt „starte, wenn die Daten da sind“ in einer einbettbaren Java-Form um. Der Kern
entsteht deshalb mit dem JDK. Bibliotheken kommen für Teile dazu: CEL für Ausdrücke, Gson für JSON, eine
Bibliothek für die Schema-Prüfung (noch auszuwählen; sie darf Jackson nicht erzwingen).

## Verhältnis zu früheren ADRs

- ADR 0025 und 0027: Aufträge, Wiederaufnahme und Beobachter werden benutzt, nicht nachgebaut.
- ADR 0033: Der Chat behält seine Werkzeuge; neue kommen über den Katalog.
- ADR 0036: Punkt 9 kennt nur lesende Anwendungsfälle für Mensch und KI. Dieses ADR ergänzt schreibende Fähigkeiten,
  die allein über einen freigegebenen Ablauf laufen. Punkt 5 („keine Schreiboperationen auf dem Mainframe“) betrifft
  das Indexieren und bleibt.
- ADR 0043 und 0047: Ein Ablauf gehört dem Projekt und ist eine weitere Art globaler Artefakte (Abschnitt 11). Neu
  gegenüber ADR 0047 ist das Bereitstellen für alle Projekte; es gilt nur für Abläufe.

## Offen

1. Gestaltung der Perspektive (Liste, Graph, Protokoll, Freigabe) und wo ein Klick auf einen Ablauf in der Liste der
   Artefakte ihn öffnet. Entwurf durch den UX-Strang nach den Hausregeln.
2. Welche Fähigkeiten zuerst in den Katalog kommen.
3. Im Bau zu erproben: Erkennen einer vollständigen Datei und einer erneuten Anlieferung derselben Datei.

## Umsetzung (nicht freigegeben)

Kleine Schnitte in dieser Reihenfolge, jeder für sich lieferbar:

1. Katalog im Core mit ersten lesenden Fähigkeiten, Adapter für den Chat.
2. Definition als Art im Artefaktspeicher, Versionen und Prüfung.
3. Ausführung als Auftrag mit den Zuständen der Ausgaben.
4. Protokoll und Wiederaufnahme.
5. Auslöser mit dem Verzeichnis verarbeiteter Ereignisse.
6. Werkzeuge für die KI: Fähigkeiten auflisten, Ablauf lesen, als Entwurf anlegen und ändern, prüfen.
7. Schreibende Fähigkeiten mit Freigabe, Umfang und Reihenfolge je Ressource.
8. Ablauf als Fähigkeit, Aufruf durch die KI.
