# Proving Ground: vertikaler Prototyp (#1153)

**Stand:** 27.11.2026 · **Ticket:** [#1153](https://github.com/aresstack/space-harvester/issues/1153) · **Modul:** `:proving-ground`

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
| Wiedergabe | `player.TestPlayer` | Originale Playwright Java API, ohne Swing; Trace, Bildschirmfoto beim Fehler, Variablen |

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
{"pageGuid":"page@…","action":{"name":"fill","selector":"internal:role=textbox[name=\"Passwort\"i]","text":"${secret.Passwort}","signals":[]},…}
{"pageGuid":"page@…","action":{"name":"assertText","selector":"internal:role=status","text":"Gespeichert: Angelo","substring":false,"signals":[]}}
```

Warum `ActionInContext` und nicht Playwrights JSONL-Zeile: Die JSONL-Zeile ist aus `ActionInContext` abgeleitet
(zusätzlich nur ein Locator-Objekt, das aus dem Selektor folgt). Wir speichern das Original und nichts doppelt. Kein
eigenes Aktionsmodell, keine Umwandlung; unbekannte Felder späterer Playwright-Fassungen bleiben erhalten.

Fachliches (Suiten, Given/When/Then, Variablen, Benutzer, Vorbedingungen) kommt getrennt dazu und verweist auf diese
Dateien. Das WD4J-Format wird nicht übernommen; ein Import wäre eine eigene Entscheidung.

## 3. Recorder: was Playwright liefert, was wir anbinden

Playwright Core 1.62 (in playwright4j enthalten) hat einen Recorder-Programm-Modus:
`BrowserContext.enableRecorder` mit `recorderMode: "api"` öffnet kein Inspector-Fenster, sondern meldet jede Aktion als
`recorderEvent` (`actionAdded`, `actionUpdated`, `signalAdded`) mit `ActionInContext`. Playwright erledigt dabei:

- Locator-Wahl (Test-ID, Rolle, Label, Text, CSS nach Playwrights Rangfolge); Frames sind im Prototyp noch nicht geprüft
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

Passwörter: Tippt der Benutzer in ein `input[type=password]`, speichert der Recorder `${secret.<Feld>}` statt des Textes.
Die Wiedergabe löst die Variable aus `PlaybackOptions.variables` auf; Ergebnisse und Protokolle zeigen nur den Namen.
Kann der Recorder das Feld nicht mehr prüfen, gilt es vorsichtshalber als Passwort.

Pause: Playwright schaltet einen einmal angemeldeten Recorder im Programm-Modus nicht wieder ein (ein zweites
`enableRecorder` registriert die Meldungen doppelt). `Recorder.pause()/resume()` lässt Meldungen daher auf Java-Seite
weg; die Werkzeugleiste in der Seite kann die Aufnahme zusätzlich selbst anhalten.

## 4. Nachweise

| Was | Wo | Ergebnis |
|---|---|---|
| Aufnahme, Speichern, Laden, Bearbeiten, Wiedergabe grün und rot, Trace, Bildschirmfoto | `RecordAndPlayLiveTest` mit Chromium (Container, 27.11.) | bestanden |
| Dasselbe mit dem Standard-Firefox über WebDriver BiDi | CI-Job `proving-ground-firefox` | siehe PR |
| Interne Playwright-Stellen vorhanden | `RecorderBridgeContractTest` (jeder Build) | bestanden |

Die Testseite rendert wie eine kleine React-App verzögert (Formular nach dem Laden, Ergebnis nach dem Klick); die
Wiedergabe wartet über Playwrights Auto-Waiting und Web-First-Assertions, ohne `sleep`.

## 5. Grenzen und offene Punkte

- Ein `<select>` in einem `<label>` bekommt von Playwright das Label samt Optionstexten als Namen
  (`internal:label="Land -Deutschland Österreich"i`). Das ist Playwrights Locator-Wahl; ändern sich die Optionen, muss
  der Schritt angepasst werden.
- Playwright zeichnet vor einem `fill` den Klick ins Feld als eigenen Schritt auf (wie Playwright Codegen).
- Java/JUnit-Export: Playwright erzeugt Java-Code für dieselben Aktionen (`JavaLanguageGenerator`), aber nur für eine
  Sprache je Recorder. Der Player bildet die Aufrufe dieses Generators nach. Ein Export als JUnit-Datei ist offen.
- Mehrbenutzertests (ein `BrowserContext` je Benutzer), Variablen/Vorbedingungen aus WD4J, Perspektive in der App:
  folgen nach der Entscheidung über das Speicherformat.
