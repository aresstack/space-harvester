# Stand und Status-quo-Sicht: Schnittstelle für die Anzeige (ADR 0032, FL6)

Für den Fable-Strang, der die Anzeige baut. Der Core-Teil liegt in
`space-harvester-core/src/main/java/com/aresstack/spaceharvester/application/standing/`, erreichbar über
`Harvester#statusQuo()`. Die Oberfläche rechnet nichts selbst: Stand, Gründe, Sortierung und Zahlen kommen fertig aus
dem Core. Wörter für Archiv und Entwurf werden ab PR 1b einstellbar; bis dahin gilt `StandingWords.DEFAULT`.

## Lesen: `StatusQuo#view`

```java
StatusQuoView view(AccessContext caller, PageSelection selection, BooleanSupplier cancelled)
StatusQuoView view(AccessContext caller, Predicate<PageKey> pages, BooleanSupplier cancelled)
```

- Grundlage ist die Auswahl links im Baum (ADR 0029, `Holdings#resolve`), wie bei Aussagen und Begriffen.
- Reine Auswertung: speichert nichts, ruft weder Parser noch KI noch die Quelle. 3000 Seiten mit 60 000 Aussagen
  brauchen im Container rund 0,1 s; trotzdem im Hintergrund rufen (`BusyOverlay`), `cancelled` beim Abbrechen setzen
  (dann `CancellationException`, nichts bleibt zurück).
- Neu lesen, wenn sich etwas ändert: nach `StatusQuo#subscribe` (Entscheidungen) und wenn ein Auftrag „Aussagen
  analysieren“ endet. Kein Polling (ADR 0027).

### `StatusQuoView`

| Feld | Inhalt |
|---|---|
| `entries` | alle Aussagen der Auswahl, schon sortiert: `GILT`, `GEPLANT`, `UNKLAR`, `UEBERHOLT`, dann Seite, Satz, Stelle |
| `states` | Zustandsaussagen je Träger (`BearerStates`: Grundform, Einträge), Träger mit den meisten geltenden Zuständen zuerst |
| `pages` | Stand je Seite bzw. Anhang (`PageStanding`) |
| `counts` / `certainCounts` | Zahlen je Stand über alle bzw. nur über sichere Aussagen (jeder Stand ist enthalten, auch 0) |
| `staleDecisions` | Entscheidungen zu Aussagen, die es in der aktuellen Analyse nicht mehr gibt (Text geändert, Vorstufe entzogen): anzeigen mit „neu entscheiden“ oder „löschen“ |

### `StatusQuoEntry` (eine Zeile)

| Feld / Methode | Anzeige |
|---|---|
| `standing().standing()` | Stand. `UEBERHOLT` blass und hinten, **nie ausblenden** |
| `standing().reason()` | fester Grund (`StandingReason`), für Tooltip bzw. Overlay; Texte in der App |
| `standing().basis()` | woher: Mensch, Seite, Zeitbezug, „Vorstufe (KI)“, keine Merkmale |
| `standing().mark()` | bei Seitengründen: welches Wort an welcher Seite gegriffen hat (`PageMark`: Label, eigener Titel oder Titel eines Vorfahren, `pageId`) |
| `negation()`, `modality()` | **immer sichtbar** mitgeben („gilt: muss nicht …“), sonst kehrt die Anzeige den Sinn um |
| `kind()` | `ACTION` (Handlung) oder `STATE` (Zustand mit Träger) |
| `subject()` | Wortlaut des Handelnden bzw. Trägers |
| `uncertain()` | unsicher gekennzeichnet anzeigen; zählt nicht für den Stand der Seite |
| `origin()`, `stageFlag()` | Herkunft Original/Vorstufe und Kennzeichen der Vorstufe als eigenes Feld. **Weicht das Kennzeichen vom Stand ab** (etwa Kennzeichen Planung bei Stand „gilt“, weil das Original selbst „gilt“ sagt), zeigt die Anzeige das Kennzeichen sichtbar daneben |
| `stateChange()` | Befund „Zustandswechsel“ („wird zuständig“), nur als Hinweis zeigen |
| `decision()` | Entscheidung des Menschen mit `revision`, `decidedBy`, `decidedAt` oder `null` |
| `analysisStale()` | Analyse der Seite ist veraltet: wie im Reiter „Aussagen“ kennzeichnen |
| `statement()` | die gespeicherte Aussage (Beteiligte, Spanne für den Beleg im Lesetext) |

Der Beleg bleibt der Originalsatz: Klick auf eine Zeile springt wie im Reiter „Aussagen“ an die Stelle im Lesetext.

### `PageStanding`

Das Ergebnis sind die **Zahlen** `counts` und `certainCounts`; das Etikett `standing` steht nur daneben. `basis`
nennt, ob ein Mensch (`DECISION`), die Seite selbst (`PAGE`: `marks`) oder die Mehrheit der sicheren Aussagen
(`STATEMENTS`, Grund `MAJORITY` oder `NO_MAJORITY` bei Gleichstand) entschieden hat.

## Regeln in Kürze

1. Entscheidung des Menschen zur Aussage gewinnt immer.
2. Vorstufe (KI): Stammen die Merkmale aus dem vereinfachten Satz und sagt der Zeitbezug nichts Eigenes, entscheidet
   das Kennzeichen (Planung → geplant, verworfen → überholt, Tatsache/Bedingung → gilt).
3. Zeitbezug (FL2): gilt, geplant, früher → überholt, verworfen → überholt.
4. Die Seite (Label, eigener Titel, Titel eines Vorfahren, Entscheidung zur Seite) zieht nur Aussagen mit, deren „gilt“
   der stille Vorgabewert ist (Zeitbezug GILT, Kennzeichen Tatsache oder Bedingung): überholt → überholt, Entwurf → unklar. Geplantes auf einer überholten Seite wird unklar. Sagt der Satz
   selbst geplant, früher oder verworfen, gewinnt der Satz.
5. Verneinung und Verbindlichkeit ändern den Stand nie.
6. Ungültige und vom Menschen abgelehnte Aussagen gehören nicht in die Sicht (eine Stelle:
   `StandingRules#effectiveQuality`).

## Schreiben: Entscheidungen des Menschen

```java
StandingDecision decide(AccessContext caller, PageKey page, String statementId, Standing standing, Long expectedRevision)
boolean clear(AccessContext caller, PageKey page, String statementId, Long expectedRevision)
List<StandingDecisionStore.AuditEntry> history(AccessContext caller, PageKey page, String statementId)
Subscription subscribe(Runnable listener)
```

- `statementId == null` heißt: die Seite selbst. Eine Entscheidung zur Seite bleibt über neue Fassungen gültig; eine zur
  Aussage gilt nur für genau diese Fassung (sonst erscheint sie unter `staleDecisions`).
- Lesen wie die Seite, Ändern braucht `sync` (wie geprüfte Fakten). `expectedRevision` ist die zuletzt gelesene Revision
  (0 ohne Entscheidung); passt sie nicht, kommt `StandingConflictException` mit der aktuellen Revision.
- `UnknownStandingTargetException`: Seite nicht analysiert oder nicht sichtbar, oder Aussage nicht in der aktuellen
  Analyse.
- Die Prüfung einer Aussage (bestätigt/abgelehnt, FL4) kommt später in denselben Eintrag; die Oberfläche plant dafür
  keinen zweiten Mechanismus.

## Vorschlag für die Oberfläche (Entscheidung beim Fable-Strang und Angelo)

- Ort: eigener Reiter neben „Aussagen“ zur Auswahl im Baum; oben rechts in der Tab-Zeile Filter (Stand, Handlung/Zustand,
  nur sichere) mit ✕ und die Zahlen je Stand.
- Liste der Aussagen nach Stand gruppiert, Überholtes blass am Ende; darunter oder als Unterreiter „Zustände“ je Träger.
- Aktionen zu einer Zeile (Stand setzen, Entscheidung löschen, Protokoll) erst im Overlay am Mausklick; Ja/Nein-Rückfrage
  beim Löschen.
- Wortlisten (ab PR 1b) unter Einstellungen und auf der Abschlussseite des Assistenten.

## Leseempfehlung: `ReadingAdvice#recommend` (PR 2)

`Harvester#readingAdvice().recommend(caller, selection, topic, cancelled)` liefert eine `ReadingList`: die Seiten der
Auswahl in der Reihenfolge, in der sie sich zum Lesen lohnen. Mit `topic` (Begriff, ohne Groß/Klein, Grundform oder
Wortlaut) nur Seiten, die das Thema erwähnen. Reine Auswertung, abbrechbar (`CancellationException`), speichert nichts,
liest das Änderungsprotokoll nur. Keine KI.

- `ReadingList`: `topic`, `topTerms` (häufigste Begriffe der Auswahl nach Zahl der Seiten, mindestens zwei Seiten,
  höchstens 10), `changesSince` (Beginn des Zeitraums für „kürzlich geändert“, 90 Tage), `pages`.
- `Recommendation`: `page`, `score` (= Summe der Teilzahlen), `parts`, `standing` (der `PageStanding` der Status-quo-Sicht).
- `Part`: `reason`, `count`, `points` (= count × Punkte je Grund), `terms` (nur bei `TOP_TERMS`). Teilzahlen mit 0 fehlen.

| Grund (`ReadingReason`) | Punkte | Wann |
|---|---|---|
| `CURRENT_STATEMENTS` | +1 je Aussage | sichere Aussage mit Stand „gilt“ |
| `OUTDATED_STATEMENTS` | −1 je Aussage | sichere Aussage mit Stand „überholt“ |
| `TOP_TERMS` | +2 je Begriff | häufigster Begriff der Auswahl kommt auf der Seite vor |
| `TOPIC_MENTIONS` | +1 je Erwähnung | gewähltes Thema |
| `RECENT_CHANGE` | +3 | Änderungsprotokoll: neu oder Inhalt geändert in den letzten 90 Tagen |
| `PAGE_OUTDATED` | −10 | Seite überholt (Label, Titel, Lage oder Entscheidung) |
| `PAGE_DRAFT` | −5 | Seite Entwurf oder vom Menschen als geplant/unklar eingestuft |
| `STALE_ANALYSIS` | −2 | Analyse passt nicht mehr zum Text |

Die Punkte stehen als Konstanten in `ReadingAdvice`; die Anzeige zeigt jede Teilzahl mit Grund (etwa als Overlay am
Mausklick auf die Summe), nie nur die Summe. Verweise zwischen Seiten und Aufrufe (Analytics) zählen noch nicht: Der
Core hat dafür keinen abgelegten Bestand.

Bekannte Schwäche bis zur Einordnung durch FL5: Unter den häufigsten Begriffen (`topTerms`, `TOP_TERMS`) stehen auch
Allerweltsnomen (etwa „Seite“, „Fall“). Schlüssel und Rangfolge der Begriffe kommen künftig aus der einen Zählstelle
von FL5 (`GlossaryDerivation`), sobald diese auf main ist. Das Änderungsprotokoll wird nur für die Quellen der Auswahl
gelesen, und nur für ihren Space, wenn die Auswahl einer Quelle in genau einem Space liegt.

Vorschlag für die Oberfläche: Unterreiter „Leseempfehlung“ neben der Status-quo-Sicht, Themenfeld mit ✕ oben rechts
(Vorschläge aus `topTerms`), Liste mit Titel, Summe und Stand; Klick öffnet die Seite.
