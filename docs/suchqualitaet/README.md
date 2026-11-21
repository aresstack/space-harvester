# Suchqualität messen (Q1)

Q1 weist Verbesserungen der Suche objektiv nach: Ein fester Testbestand echter Recherchefragen mit erwarteten
Fundstellen wird über genau den Suchweg des Chats gestellt (progressive Suche im Bereich, Profil wie eingestellt), und
gezählt wird, ob und wo die erwartete Seite steht. An der Suche selbst ändert Q1 nichts. Ziel 2 (Korrektheit der
Aussagen) ist ein eigener Schritt und hier noch nicht beschrieben.

Code: `space-harvester-core`, Paket `application.quality`. Die App stellt den Lauf als Auftrag bereit (Q1b).

## Grenzen

- Echte Fragen, Titel, Adressen und Seiten-IDs kommen nie ins Repository, nicht in Logs, nicht in Auftragsmeldungen
  oder die Statuszeile. Testbestand und Ergebnisse liegen lokal beim Benutzer.
- Im Repository liegen nur das Werkzeug, dieses Format und das erfundene Beispiel
  [`beispiel-fragen.tsv`](beispiel-fragen.tsv).
- Der Bericht (`.md`) enthält nur Fragen-IDs und Zahlen. Wortlaut und Titel stehen nur in der Ergebnisdatei (`.tsv`)
  neben dem Testbestand.

## Testbestand `fragen.tsv`

Textdatei, Tabulator als Trenner, UTF-8 (mit oder ohne BOM) oder UTF-16 mit BOM (Excel „Unicode-Text“). Zeilen mit `#`
am Anfang und leere Zeilen werden übergangen; die erste übrige Zeile sind die Spaltenköpfe, die Reihenfolge der
Spalten ist frei.

| Spalte | Pflicht | Inhalt |
|---|---|---|
| `id` | ja | eindeutig, Buchstaben, Ziffern, `.`, `-`, `_`, höchstens 40 Zeichen (`F01`). Steht als einziges Merkmal der Frage im Bericht. |
| `frage` | ja | die Frage so, wie man sie dem Chat stellt; höchstens 200 Zeichen (Grenze der Suche) |
| `bereich` | ja | `*` = alles; `quelle` (ID der Wissensquelle); `quelle:SPACE`; `quelle:seite:<id>` oder eine Seitenadresse; Zusatz `+unterseiten` nur bei einer Seite. Mehrere mit `|` |
| `erwartet` | ja, außer Art `keine` | Fundstelle(n): `quelle:<pageId>` oder Seitenadresse aus Browser oder App; mehrere mit `|` |
| `art` | nein | mit `,`: `einfach` (Vorgabe), `verteilt`, `akronym`, `synonym`, `keine` |
| `bemerkung` | nein | frei, wird nicht ausgewertet |

Arten:
- `einfach`: Antwort auf einer Seite, Frage mit den Wörtern der Seite.
- `verteilt`: Antwort über mehrere Seiten; zusätzlich zählt der Anteil der erwarteten Seiten unter den ersten 10.
- `akronym`: mehrdeutige Abkürzung.
- `synonym`: Frage nutzt ein anderes Wort als die Seite.
- `keine`: Die Antwort steht nirgends; `erwartet` bleibt leer.

Seitenadressen löst die App über die eingetragenen Quellen auf (`PageUrls`: `pageId=`, `/spaces/K/pages/ID`, `/x/…`).
Eine Zeile, die sich nicht lesen oder auflösen lässt, ist ein Zeilenfehler mit Nummer und Grund; der Lauf misst die
übrigen Fragen. Doppelte IDs sind Zeilenfehler.

Vorschlag für den ersten Bestand (40 Fragen): 16 einfach, 8 verteilt, 6 akronym, 5 synonym, 5 keine; mindestens 5
erwartete Seiten bewusst nicht lokal gespeichert.

## Ablage

- Testbestand: ein Ordner nach Wahl, Vorgabe `<Ausgabeordner>\Suchqualität\`.
- Ergebnis im selben Ordner, benannt nach Testbestand und Zeit: `<name> – JJJJ-MM-TT HH-MM.md` (Bericht) und `.tsv`
  (Ergebnisdatei, Grundlage für Vergleich und Fortsetzen).

## Was gezählt wird

Je Frage wird zuerst festgehalten, ob jede erwartete Seite **lokal gespeichert** ist; die Suche lädt Funde in den Cache,
der Zustand vor der Frage zählt. Dann läuft die Suche bis zum Endergebnis. Jede Seite zählt einmal, an ihrem ersten
Platz (wie der Chat die Treffer übernimmt).

- **Rang** jeder erwarteten Fundstelle (1 = erster Treffer, `–` = nicht in der Endliste), bester Rang.
- **Treffer unter den ersten 5, im Chat-Kontext, unter den ersten 10**: mindestens eine erwartete Fundstelle darunter.
  Der Chat-Kontext ist die Zahl der Treffer, die der Chat ins Modell gibt; die App übergibt sie aus
  `ChatKnowledge.MAX_SOURCES`.
- **MRR**: Mittel aus 1/bester Rang, nicht gefunden = 0.
- **Verteilt**: Anteil der erwarteten Fundstellen unter den ersten 10.
- **Laufzeit** bis zum Endergebnis und bis eine erwartete Fundstelle zum ersten Mal in einer vorläufigen Rangliste
  stand.
- Abschlussgrund der Suche, Zahl der Varianten, Quellen ohne Antwort.
- Eine gescheiterte Suche zählt als nicht gefunden und erscheint mit der Klasse des Fehlers.

### Art `keine`: „zu Recht nichts Brauchbares“

Eine Frage der Art `keine` geht nicht in Treffer@k und MRR ein. Gezählt wird, ob die Suche zu Recht nichts Brauchbares
liefert: **richtig ist sie, wenn unter den Treffern im Chat-Kontext keiner eine Bewertung mindestens in Höhe der
Schwelle `minScore` des eingestellten Suchprofils hat.** Das ist dieselbe Schwelle, an der die progressive Suche ihr
Qualitätsziel misst („relevanter Treffer“); die Skala hängt am Profil (Schlagwort: `HitScorer`, semantisch:
Kosinusähnlichkeit) und steht im Kopf des Ergebnisses. Zusätzlich stehen Trefferzahl und beste Bewertung in der
Ergebnisdatei.

### Summen

Gesamt und getrennt nach Art sowie nach „erwartete Seiten lokal“ / „nicht vollständig lokal“: Treffer@5, @Kontext,
@10, MRR, Laufzeit Median, 90-%-Quantil und Maximum. So trennt der Bericht „die Suche findet es nicht“ von „die Seite
ist nicht im Bestand“.

### Vergleich

Mit einem früheren Ergebnis (`.tsv`) zeigt der Bericht die Kennzahlen vorher/jetzt und je Frage, ob sie besser,
schlechter, neu oder entfallen ist (besser = kleinerer bester Rang; bei `keine` „zu Recht leer“). Vergleichbar sind
Läufe mit gleichem Testbestand und gleichem Bestand: Weil die Suche Funde lädt, misst ein zweiter Lauf einen wärmeren
Cache. Die Spalte `lokal` zeigt, ob der Zustand gleich war; für einen Vergleich vor/nach einem Umbau empfiehlt sich
je ein Aufwärmlauf vorher.

## Ergebnisdatei (`.tsv`)

Kopfzeilen `# schluessel=wert` (`version`, `profil`, `start`, `testbestand`, `kontext`, `schwelle`, `fragen`,
`abgebrochen`), dann eine Zeile je Frage mit den Spalten `id, art, erwartet, raenge, lokal, bester_rang, top5,
top_kontext, top10, recall10, dauer_ms, erster_fund_ms, abschluss, varianten, quellen_aus, treffer, bester_wert,
brauchbar_kontext, fehler, gefunden, titel, frage`. Listen mit `|`, nicht gefunden `-`, lokal `j`/`n`.
