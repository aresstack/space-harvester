# Proving Ground: Prototyp und Testmodell (#1153)

**Stand:** 28.11.2026 · **Ticket:** [#1153](https://github.com/aresstack/space-harvester/issues/1153) · **Modul:** `:proving-ground`

Der Prototyp prüft die technische Voruntersuchung aus Angelos Auftrag vom 27.11.2026: Firefox über `:browser-runtime`
starten, mit dem originalen Playwright-Recorder aufzeichnen, die strukturierten Playwright-Aktionen speichern, laden
und bearbeiten, über die Playwright Java API abspielen und das Ergebnis mit Assertions und Trace auswerten. Eine
Oberfläche gibt es noch nicht (Perspektive kommt danach). WD4J (`Miguel0888/WD4J`) ist nur fachliche Referenz.

## 1. Aufbau

```text
proving-ground ──► browser-runtime ──► Playwright Java 1.62 + playwright4j (Playwright Core 1.62 in GraalJS)
```

| Teil | Klasse | Aufgabe |
|---|---|---|
| Recorder | `recorder.Recorder` | Startet den originalen Playwright-Recorder im Programm-Modus, sammelt die Schritte, hält Passwörter heraus |
| Interne Anbindung | `recorder.RecorderBridge` | Einzige Stelle mit Playwright-Interna (siehe 3) |
| Testfall | `model.TestCase`, `model.TestStep` | Schritte im Playwright-Format, Bearbeiten (ändern, einfügen, verschieben, löschen, Assertions ergänzen) |
| Speicher | `store.TestCaseFile` | JSONL-Datei wie Playwrights eigenes JSONL (siehe 2) |
| Wiedergabe | `player.TestPlayer`, `player.PlaywrightCode` | Spielt den Java-Code ab, den Playwrights Generator beim Aufnehmen schreibt (mit dem JDK übersetzt, über Playwright4j ausgeführt); Trace, Bildschirmfoto beim Fehler, Variablen (siehe 2a) |
| Ausdrücke | `expressions.*` | `{{name}}`, `{{funktion(arg;{{verschachtelt}})}}`, aus WD4J übernommen (siehe 6) |
| Testmodell | `model.TestProject`, `TestSuite`, `TestCaseEntry`, `Scope`, `StepBinding`, `TestUser` | Fachliches aus WD4J (siehe 6) |
| Projektdatei | `store.ProjectFile` | `projekt.json` neben den JSONL-Dateien |
| Ablauf | `run.ProjectRunner` | Hooks in WD4J-Reihenfolge, Vorbedingungen, je Benutzer ein Browserkontext, Prüfungen |

Der Testlauf startet seinen Browser mit `BrowserRuntime.ephemeral(headless, null, false)`: Wegwerfprofil und ohne die
Firefox-Einstellung „POST erneut senden immer annehmen“, damit ein Test nie still ein Formular erneut abschickt. Den
Kontext legt Proving Ground selbst mit den originalen `Browser.NewContextOptions` an (Viewport, Downloads, Storage
State, Sprache). Die Websuche startet unverändert mit der Einstellung.

## 2. Speicherformat (Vorschlag)

Eine Datei je Testfall, aufgebaut wie Playwrights `JsonlLanguageGenerator`: erste Zeile Kopf, danach eine Zeile je
Aktion. Eine Aktionszeile ist Playwrights `ActionInContext` genau so, wie der Recorder sie meldet, nur ohne den
ARIA-Schnappschuss der Seite zum Aufnahmezeitpunkt (den lässt auch Playwrights JSONL weg; bei `assertSnapshot` bleibt
er, dort ist er der Sollwert).

```text
{"provingGround":1,"name":"Anmeldung","playwright":"1.62.0"}
{"pageGuid":"page@…","action":{"name":"navigate","url":"http://…/","signals":[]},"startTime":…}
{"pageGuid":"page@…","action":{"name":"fill","selector":"internal:role=textbox[name=\"Name\"i]","text":"Angelo","signals":[]},…}
{"pageGuid":"page@…","action":{"name":"fill","selector":"internal:role=textbox[name=\"Passwort\"i]","text":"{{secret.Passwort}}","signals":[]},…}
{"pageGuid":"page@…","action":{"name":"assertText","selector":"internal:role=status","text":"Gespeichert: Angelo","substring":false,"signals":[]}}
```

Ergänzungen zu Playwrights Format sind `stepId`, eine feste ID je Schritt, und `code` (siehe 2a). Fachliches (Given/When/Then,
Variablen, Benutzer) verweist darüber auf die Aktion; Einfügen, Löschen, Verschieben und Bearbeiten ändern sie nicht.

Warum `ActionInContext` und nicht Playwrights JSONL-Zeile: Die JSONL-Zeile ist aus `ActionInContext` abgeleitet
(zusätzlich nur ein Locator-Objekt, das aus dem Selektor folgt). Wir speichern das Original und nichts doppelt. Kein
eigenes Aktionsmodell, keine Umwandlung; unbekannte Felder späterer Playwright-Fassungen bleiben erhalten.

### 2a. Playwright spielt ab (Angelo 28.11.2026: nur Java, kein Node.js)

JSONL ist bei Playwright nur ein Ausgabeformat des Recorders; Playwright selbst spielt es nicht ab. Deshalb läuft der
Recorder mit Playwrights Java-Generator (`Recorder.PLAYWRIGHT_LANGUAGE = "java"`), und jede Aktionszeile trägt
zusätzlich `code`: den Java-Code, den Playwright zu dieser Aktion schreibt, etwa
`page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Speichern")).click();`. Abgespielt wird genau
dieser Code: `PlaywrightCode` setzt um jeden Schritt eine Methode, übersetzt alles mit dem Java-Compiler des JDK
(`javax.tools`) und führt es über die Playwright-Java-API aus, hinter der Playwright4j Playwright Core in der JVM
(GraalJS) betreibt. Es gibt keine eigene Nachbildung der Aktionen und kein Node.js.

- Seiten werden über die Seiten-ID der Aufnahme gebunden, nicht über Playwrights Variablennamen (`page`, `page1`).
- Zeichenketten mit `{{...}}` werden erst beim Abspielen aufgelöst (`v.apply("…")`); Passwörter stehen auch im Code nur
  als `{{secret.<Feld>}}`.
- Popup, Download und Dialog meldet der Recorder erst nach der Aktion; ihr Code wird dann so umschlossen, wie
  Playwrights `JavaLanguageGenerator` es schreibt (`Page page1 = page.waitForPopup(() -> { … });`).
- Wird Text, Adresse oder Wert eines Schritts geändert, folgt der Code; ein anderer Selektor verwirft ihn (neu
  aufnehmen). Ein Schritt ohne Code oder mit nicht übersetzbarem Code schlägt allein fehl und sagt warum.
- Zum Abspielen braucht die App ein JDK (der Compiler); sie läuft ohnehin auf JDK 21.

Fachliches (Suiten, Given/When/Then, Variablen, Benutzer, Vorbedingungen) kommt getrennt dazu und verweist auf diese
Dateien. Das WD4J-Format wird nicht übernommen; ein Import wäre eine eigene Entscheidung.

## 3. Recorder: was Playwright liefert, was wir anbinden

Playwright Core 1.62 (in playwright4j enthalten) hat einen Recorder-Programm-Modus:
`BrowserContext.enableRecorder` mit `recorderMode: "api"` öffnet kein Inspector-Fenster, sondern meldet jede Aktion als
`recorderEvent` (`actionAdded`, `actionUpdated`, `signalAdded`) mit `ActionInContext`. Playwright erledigt dabei:

- Locator-Wahl (Test-ID, Rolle, Label, Text, CSS nach Playwrights Rangfolge); in Frames steht der Frame-Pfad im Selektor
  (`… >> internal:control=enter-frame >> …`, `buildFullSelector` in Core 1.62). Der Fehlerbericht
  microsoft/playwright#34157 (Frame-Pfad fehlte im JSONL) betrifft 1.62 daher nicht; der Live-Test belegt es mit einem iFrame
- Zusammenfassen der Eingaben: eine `fill`-Aktion je Feld (`shouldMergeAction`: gleiche Aktion UND gleicher Selektor).
  Der WD4J-Fehler in `mergeInputEvents()` (zwei Felder direkt nacheinander, das erste geht verloren) kann so nicht
  auftreten; der Live-Test prüft das.
- Navigationen und Weiterleitungen (eigene `navigate`-Schritte nur, wenn sie nicht aus einem Klick folgen),
  Popups/neue Tabs, Downloads und Dialoge als Signale am auslösenden Schritt
- Werkzeugleiste in der Seite: Aufnahme an/aus, Locator wählen, Text/Wert/Sichtbarkeit/ARIA-Schnappschuss prüfen

Die Playwright Java API hat dafür keinen öffentlichen Aufruf. `RecorderBridge` kapselt deshalb als einzige Klasse:
`enableRecorder`/`disableRecorder` über das paketinterne `ChannelOwner.sendMessage` und das Mitlesen der
`recorderEvent`-Nachrichten am `Transport` der Verbindung (jede Nachricht geht unverändert an Playwright weiter).
`RecorderBridgeContractTest` prüft bei jedem Build, dass Playwright Core und Playwright Java diese Stellen noch haben.
**Versionsabhängigkeit:** Playwright Java und Core 1.62.0. Bei einem Playwright-Update zuerst diesen Test und den
Live-Test laufen lassen.

Passwörter: Tippt der Benutzer in ein `input[type=password]`, speichert der Recorder `{{secret.<Feld>}}` statt des Textes.
Im Projektlauf ist das das Passwort des Benutzers, als der der Schritt läuft, aus dem Tresor (KeePass); ein einzelner
Testfall nimmt es aus `PlaybackOptions.variables`. Ergebnisse und Protokolle zeigen nur den Namen.
Kann der Recorder das Feld nicht mehr prüfen (Element oder Tab weg), gilt es vorsichtshalber als Passwort. Fehlermeldungen
der Wiedergabe ersetzen den Wert jeder `secret.*`-Variable durch ihren Namen.

Pause: Playwright schaltet einen einmal angemeldeten Recorder im Programm-Modus nicht wieder ein (ein zweites
`enableRecorder` registriert die Meldungen doppelt). `Recorder.pause()/resume()` lässt Meldungen daher auf Java-Seite
weg (eine in der Pause begonnene Eingabe wird nach der Pause ein eigener Schritt und überschreibt keinen früheren); die Werkzeugleiste in der Seite kann die Aufnahme zusätzlich selbst anhalten.

## 4. Nachweise

| Was | Wo | Ergebnis |
|---|---|---|
| Aufnahme, Speichern, Laden, Bearbeiten, Wiedergabe grün und rot, Trace, Bildschirmfoto | `RecordAndPlayLiveTest` mit Chromium (Container, 27.11.) | bestanden |
| Dasselbe mit dem Standard-Firefox über WebDriver BiDi | CI-Job `proving-ground-firefox` | siehe PR |
| Interne Playwright-Stellen vorhanden | `RecorderBridgeContractTest` (jeder Build) | bestanden |
| Testmodell mit zwei Benutzern, OTP, Vorbedingung, Prüfungen | `ProjectRunLiveTest` (Chromium im Container, Firefox in CI) | siehe PR |

Die Testseite rendert wie eine kleine React-App verzögert (Formular nach dem Laden, Ergebnis nach dem Klick); die
Wiedergabe wartet über Playwrights Auto-Waiting und Web-First-Assertions, ohne `sleep`.

## 5. Grenzen und offene Punkte

- Ein `<select>` in einem `<label>` bekommt von Playwright das Label samt Optionstexten als Namen
  (`internal:label="Land -Deutschland Österreich"i`). Das ist Playwrights Locator-Wahl; ändern sich die Optionen, muss
  der Schritt angepasst werden.
- Playwright zeichnet vor einem `fill` den Klick ins Feld als eigenen Schritt auf (wie Playwright Codegen).
- Java/JUnit-Export: Playwright erzeugt Java-Code für dieselben Aktionen (`JavaLanguageGenerator`), aber nur für eine
  Sprache je Recorder. Der Player bildet die Aufrufe dieses Generators nach. Ein Export als JUnit-Datei ist offen.
- Bildschirmfoto-Vergleich als Prüfung und die Perspektive in der App folgen.
- Ein im Recorder getippter Text mit `{{` wird bei der Wiedergabe als Ausdruck gelesen (wie in WD4J).

## 6. Testmodell (aus WD4J)

Angelos Vorgabe vom 28.11.: Given/When/Then, Vorbedingungen, Variablen, Templates und Mehrbenutzertests bleiben; die
Zuordnung zu den Aktionen bleibt beim Einfügen, Löschen und Verschieben stabil; Zugangsdaten werden erst zur Laufzeit
aufgelöst und nie gespeichert. WD4J darf vereinfacht werden.

```text
tests/
  projekt.json             Suiten, Testfälle, Hooks, Vorbedingungen, Benutzer (nur Verweise), Fachliches je stepId
  <suite>/<fall>.jsonl     Playwright-Aktionen eines Testfalls (Abschnitt 2)
```

| WD4J | Hier | Anmerkung |
|---|---|---|
| `RootNode`, `TestSuite`, `TestCase` | `TestProject`, `TestSuite`, `TestCaseEntry` | der Testfall verweist auf seine JSONL-Datei |
| `beforeAll`/`beforeEach`/`before`, `templates`, `afterEach`/`afterAll`/`after` mit aktiv, Beschreibung, Validator | `Scope` mit Einträgen `ScopeEntry {name, expression, enabled, description, check}` | auf Testfall-Ebene ist `beforeAll` WD4Js `before`, `afterEach` WD4Js `after` |
| Validator-Typen | `Check` | `regex`, `fullregex`, `contains`, `equals`, `starts`, `ends`, `range`, `len`; `!` verneint, `\|\|` trennt Alternativen |
| `TestAction`: Typ, Benutzer, Beschreibung, Mindestdauer | `StepBinding` je `stepId` | ohne Eintrag: Then bei Assertions, sonst When; Benutzer des Testfalls |
| `Precondition` mit eigener Aktionsliste | Verweis `<suite>/<fall>` auf einen Testfall | jeder Testfall kann Vorbedingung sein; Zyklen werden abgewiesen |
| Ausdrucksmaschine | `expressions.*` | übernommen; Funktionen kommen aus dem Kontext statt aus einer globalen Registry |
| `UserRegistry` (Passwort verschlüsselt in der Datei) | `TestUser {name, vaultEntry}` + `Credentials` | Login, Passwort, OTP-Geheimnis nur im Tresor, gelesen erst beim Schritt |
| 2FA-Werkzeug (googleauth) | `{{otp()}}`, `run.Totp` | RFC 6238; Base32 oder `otpauth://`-URI mit `digits`/`period`/`algorithm` |
| Werkzeuge Navigation, Bildschirmfoto, JS | `{{js('…')}}`, `{{text('selektor')}}`, `{{url()}}`, `{{title()}}` | über die Playwright-API auf der aktuellen Seite |

Ablauf (`ProjectRunner`): Projekt `beforeAll` → Suite `beforeAll` → je Testfall: Projekt/Suite `beforeEach`, Testfall
`beforeAll`/`beforeEach` → Vorbedingungen (Projekt, Suite, Testfall; jeweils mit ihren eigenen, tiefste zuerst, jede
einmal, nie der Testfall selbst) → Schritte → Testfall/Suite/Projekt `afterEach` → Suite `afterAll` → Projekt `afterAll`.
Variablen werden von innen nach außen gesucht (Testfall vor Suite vor Projekt), Templates bei jeder Verwendung neu
aufgelöst. Ein fehlgeschlagener Before-Eintrag hält auf, was davon abhängt; Prüfungen danach laufen trotzdem.

Benutzer: Jeder Benutzer bekommt je Testfall einen eigenen `BrowserContext` (eigene Cookies, frischer Zustand), geöffnet
erst beim ersten Schritt dieses Benutzers; je Benutzer entsteht ein Trace. Eingebaute Variablen: `{{user}}` (Name im
Projekt), `{{user.login}}` (Login aus dem Tresor), `{{secret.<Feld>}}` (Passwort aus dem Tresor). Jeder ausgegebene
geheime Wert und Einmalcode wird in Meldungen und Prüfwerten durch `{{secret}}` ersetzt.

Nachweis: `ProjectRunLiveTest` (zwei Benutzer, Anmeldung mit Passwort und Einmalcode als Vorbedingung, Benutzerwechsel,
Prüfungen über die Seite, Fehlerfall ohne Passwort in der Meldung) und `ProjectRunnerTest` (Reihenfolge ohne Browser).
