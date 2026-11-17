# Fachglossar mit Begriffsableitung: Schnittstelle für den Glossar-Reiter (ADR 0032, FL5b)

Für den Fable-Strang, der den Glossar-Reiter der App baut. Der Core-Teil liegt in
`space-harvester-core/src/main/java/com/aresstack/spaceharvester/application/glossary/` und ist über den `Harvester`
erreichbar: `glossary()`, `glossaryClassification()`, `glossaryOverview()`. Die Oberfläche rechnet nichts selbst:
Einträge, Einordnung, Wichtigkeit, Belege und Zahlen kommen fertig aus dem Core.

## Bausteine

| Baustein | Aufgabe | gespeichert? |
|---|---|---|
| `Glossary` | Einträge des Fachglossars (Nutzerdaten): Suchen, Bestätigen, Ablehnen, Einordnen, Erläutern | ja (`glossary/glossary.json`) |
| `GlossaryClassification` | Auftrag `GLOSSARY` „Fachbegriffe ermitteln“: Phase „Begriffe ableiten“ (Regeln, ohne Modell), danach Phase mit Modell | schreibt Vorschläge ins Glossar |
| `GlossaryOverview` | Teilzahlen der Wichtigkeit (Liste) und Einzelheiten je Begriff, je Aufrufer gerechnet | nein |

## Ein Eintrag: `GlossaryEntry`

| Feld | Anzeige |
|---|---|
| `term`, `normalized` | Begriff im Wortlaut; `normalized` ist der Schlüssel zum Verknüpfen mit der Liste der Teilzahlen |
| `scope` | Geltungsbereich: global, Quelle oder Space |
| `origin` | wer angelegt hat: `MANUAL` (Mensch), `RULE` (Regel), `AI` (Modell); ändert sich nie |
| `status` | `PROPOSED`, `CONFIRMED`, `REJECTED` |
| `classification` | Einordnung: `category` (`FACHLICH`, `TECHNISCH`, `ROLLE`, `UNKLAR`), `by` (`RULE`, `AI`, `MANUAL`), `reason` (Kennung wie `TECHNICAL_HEAD`, `TECHNICAL_SHAPE`, `ROLE_HEAD`; nie Freitext, Text dazu in der App) oder `null` (alte Einträge: als `UNKLAR` zeigen) |
| `supportedBy` | welche Läufe den Begriff zuletzt nannten (`RULE`, `AI`); leer bei manuellen Einträgen |
| `evidence` | bis zu drei Belegstellen (Seite, Position, Hash); nur lesbare, der Core filtert |
| `definitionProposal` | Beleg einer Begriffsbestimmung („X ist ein Y“) als Position oder `null`; bleibt leer, bis der Parser den Artikel am Rollennomen kennt |
| `evidenceStale` | „Beleg veraltet“: ein bestätigter Eintrag, den kein Lauf mehr stützt |
| `explanation` | Erläuterung, schreibt nur der Mensch |
| `revision` | für optimistisches Sperren: bei jeder Änderung mitgeben |

Vorrang der Einordnung: Mensch vor KI vor Regel. Die KI ersetzt eine Regel-Einordnung nur bei `UNKLAR`; nach
„Einordnen“ durch den Menschen ändert kein Lauf sie mehr.

## Lesen

```java
Glossary.Page entries(AccessContext caller, Glossary.Search search)
GlossaryEntry entry(AccessContext caller, String id)
List<GlossaryStore.AuditEntry> history(AccessContext caller, String id)
```

- `Glossary.Search(q, status, origin, sourceId, spaceKey, startsWith, limit, cursor, categories)`. `categories`:
  Menge oder `null` für alle; ohne Einordnung zählt `UNKLAR`. **Der Reiter fragt standardmäßig ohne `TECHNISCH`**
  (`FACHLICH`, `ROLLE`, `UNKLAR`); der Filter „Technik“ fragt nur `TECHNISCH`.
- Ohne `status` kommen Vorschläge und bestätigte, `status=REJECTED` zeigt die Sperrliste. Sortiert nach Suchwert,
  seitenweise über `nextCursor`.
- Sichtbarkeit macht der Core: Einträge der Regel und der KI ohne lesbaren Beleg erscheinen gar nicht; Belege und
  `definitionProposal` nur, wenn der Aufrufer die Seite lesen darf.

### Teilzahlen und Sortierung nach Wichtigkeit: `GlossaryOverview`

```java
GlossaryOverview.Overview importance(AccessContext caller, String sourceId, String spaceKey, BooleanSupplier cancelled)
Optional<GlossaryDerivation.Term> term(AccessContext caller, String sourceId, String spaceKey, String term,
                                       BooleanSupplier cancelled)
```

- `importance` liefert je Begriff des Geltungsbereichs (`sourceId` `null` = alle lesbaren Seiten, `spaceKey` nur mit
  `sourceId`) eine `Row`: `term`, `normalized`, `importance` (`pages`, `mentions`, `asActor`, `asAffected`,
  `asBearer`), `category`, `rule`, ohne Belege, schon nach Wichtigkeit sortiert (Seitenstreuung, dann Handelnder, dann
  Erwähnungen). Dazu `pages` und `stalePages`.
- `term` liefert die Einzelheiten eines Begriffs: Verhalten (`acts`, `undergoes`; verneinte Verben getrennt),
  Eigenschaften (`properties`), Ist-Beziehungen (`isA`, `instances`), je mit Anzahl, Herkunft (Original/Vorstufe) und
  bis zu drei Belegen. Nur Original zählt für Wichtigkeit; Vorstufe getrennt zeigen.
- Gerechnet wird nur über die Seiten mit Aussagenanalyse, die der Aufrufer lesen darf; nichts wird gespeichert, kein
  Modell, keine Quelle. Laufzeit wie die Ableitung selbst (`GlossaryDerivationTest`, 5000 Seiten rund 0,8 s im
  Container), je Aufruf einmal über alle Seiten des Geltungsbereichs. Im Hintergrund rufen (`BusyOverlay`), `cancelled` beim Abbrechen setzen
  (`CancellationException`, nichts bleibt zurück).

**Zusammenführen im Reiter:** Glossareinträge des Geltungsbereichs über `entries` lesen (alle Seiten), die Liste
`importance` desselben Geltungsbereichs einmal holen und über `normalized` verknüpfen. Sortiert wird nach der
Wichtigkeit der Liste; Einträge ohne Zeile (manuelle oder ohne aktuelle Analyse) haben Wichtigkeit 0 und stehen
hinten, alphabetisch. Begriffe der Liste ohne Glossareintrag zeigt der Reiter nicht (sie wurden nicht vorgeschlagen:
kein Seitenbeleg oder über `max-terms`). Ein Klick auf einen Eintrag holt `term(...)` für die Einzelheiten im Overlay.

## Ändern

```java
GlossaryEntry categorize(AccessContext caller, String id, GlossaryDerivation.Category category, Long expectedRevision)
GlossaryEntry confirm(AccessContext caller, String id, Long expectedRevision)
GlossaryEntry reject(AccessContext caller, String id, Long expectedRevision)
GlossaryEntry restore(AccessContext caller, String id, Long expectedRevision)
GlossaryEntry explain(AccessContext caller, String id, String explanation, Long expectedRevision)
Optional<GlossaryEntry> remove(AccessContext caller, String id, Long expectedRevision)
GlossaryEntry add(AccessContext caller, Glossary.NewEntry entry)
```

Fehler: `AccessDeniedException` ohne `sync` auf den Geltungsbereich, `GlossaryConflictException` bei veralteter
`revision` (neu lesen, Hinweis zeigen), unbekannte oder verborgene Einträge wie „nicht gefunden“.

## Auftrag „Fachbegriffe ermitteln“

```java
List<JobStatus> start(AccessContext caller, String sourceId, String spaceKey)
boolean available()       // ob ein Lauf möglich ist; in der App immer (Regelphase)
boolean modelAvailable()  // ob die Phase mit Modell möglich ist
```

- Je Quelle ein Auftrag `GLOSSARY` in der Auftragsliste. Phase 1 „Begriffe ableiten“: ohne Modell, abbrechbar,
  Teilergebnisse erscheinen sofort im Glossar. Phase 2 „Fachbegriffe ermitteln“ nur mit Modell und KI-Freigabe der
  Quelle.
- Startet auch ohne Modell und ohne Freigabe. Ereignisse mit fester Kennung (`JobEvent.code()`), ohne Text zu
  deuten:
  - `GLOSSARY_RULES_DONE`: Zahlen der Regelphase (Seiten, Begriffe, vorgeschlagen, zurückgestellt, neu, geändert,
    entfallen, veraltet) im Text.
  - `GLOSSARY_MODEL_SKIPPED:NO_MODEL` oder `GLOSSARY_MODEL_SKIPPED:NOT_RELEASED`: Modellphase bewusst ausgelassen;
    in der Auftragsliste **gelb** (Ampel: bewusst ausgelassen), nie rot. Sie löscht nichts.
- Abbruch lässt die Teilergebnisse stehen; entfernt oder als veraltet markiert wird erst im Abgleich am Ende.

## Beobachter (kein Polling, ADR 0027)

- `Glossary#subscribe(Runnable)`: jede Änderung am Glossar (Lauf, Mensch, „Daten bereinigen“). Danach die sichtbare
  Seite neu lesen; die Rückgabe abmelden, wenn der Reiter schließt.
- Teilzahlen (`importance`) neu holen, wenn ein Auftrag „Aussagen analysieren“ oder `GLOSSARY` endet
  (`Jobs#subscribe`), nicht zeitgesteuert.

## Einstellungen

Die feste Wortliste steht im Core (`glossar-regeln.tsv`). Unter `space-harvester.glossary.rules.*` (beide Loader,
siehe `docs/CONFIGURATION.md`): `max-terms` (Standard 500) und je Art `technical-heads`, `technical-words`,
`role-heads`, `role-words` die Listen `add` und `exclude`. Köpfe brauchen mindestens 5 Buchstaben; ein falsches Wort
ist ein Problem, die übrigen gelten. Sie wirken beim nächsten Lauf bzw. beim nächsten Lesen der Teilzahlen. Für die
Seite Einstellungen: Tabelle je Art mit Zeilenaktionen, wie bei anderen mehrwertigen Einstellungen.

## Grenzen

- Belege der Regel nur aus Seiten, nicht aus Anhängen (Positionen eines Anhangs passen nicht auf das Belegformat).
- Belege der Regel dürfen von Seiten ohne KI-Freigabe stammen; dem Modell (FL5c) gehen nur Belege freigegebener Seiten.
- `definitionProposal` bleibt leer, bis das Artikel-Merkmal des Parsers auf main ist.
- Wichtigkeit wird nicht gespeichert; sie gilt je Aufrufer (was er lesen darf).

## REST im Dienst (`/api/v1/glossary`)

- Einträge tragen zusätzlich `category`, `categoryBy`, `categoryReason`, `definitionProposal`, `supportedBy`;
  `origin` kann `rule` sein.
- Filter `category` (mehrfach oder mit Kommas): `fachlich`, `technisch`, `rolle`, `unklar`.
- `PUT /{id}/category` mit `{"category": "...", "revision": n}`: Einordnung durch den Menschen.
- Der Dienst hat keinen Wissensgraphen: dort gibt es keine Regelphase und keine Teilzahlen. `POST /classify` braucht
  im Dienst weiter ein Chat-Modell (503 `ai-unavailable`); „Begriffe ableiten“ ohne Modell läuft nur in der App.
