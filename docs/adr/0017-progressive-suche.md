# ADR 0017: Progressive Suche

- Status: **Angenommen, erster Schritt umgesetzt** (Strang Q, 18.10.2026; Entscheidungen von Angelo, 18.10. 18:03
  und 18:07). Ergänzt [ADR 0013](0013-suche-ueber-den-cache.md) und [ADR 0016](0016-auswahl-beschaffung-hintergrund.md).
- Gilt für die **semantische Suche** (Haken, ADR 0013). Die Schlagwortsuche bleibt Standard und unverändert.
- Umgesetzt: `SearchOrchestrator` im Core mit regelbasierten Varianten, Wellen, Prioritätsfreigabe, Qualitätsziel
  und Budgets (Paket `application.query.progressive`, `Harvester.progressiveSearch()`).
- Offen: Varianten über ein Modell des KI-Dienstes, REST-Schnittstelle, Anbindung an die semantische Suche und die App.

## Kontext

Zu einer ganzen Frage findet die Suche der Quelle meist nichts, zu einzelnen Wörtern viel Unsinn. Der Nutzer hat
nicht ohne Grund eine Kombination gesucht (Angelo 18:03). Eine Oberfläche will zusehen, wie die Liste wächst. Ein
KI-Agent will dagegen die fertige, sortierte Liste, denn beim Streamen bekäme er nicht den besten Treffer zuerst
(Angelo 18:07). Deshalb gehört die ganze Orchestrierung in den Core, und die App ist reiner Verbraucher.

## Entscheidung

1. **Die ursprüngliche Anfrage sucht sofort** und erscheint als erste Suchanfrage in der Liste. Gleichzeitig erzeugt
   eine **austauschbare `QueryExpansionStrategy`** weitere Suchanfragen, nach abnehmender Genauigkeit:
   - zuerst mit möglichst vielen Begriffen des Nutzers,
   - dann schrittweise weniger, damit die Liste langsam wächst,
   - nie alle Wortkombinationen.

   Strategien:
   - **Regelbasiert** (`RuleBasedExpansion`): Standard und Rückfall ohne KI-Dienst.
   - **Über ein Modell des KI-Dienstes**: Ein Prompt liefert JSON `queries[text, priority]`, Priorität 1 ist die
     höchste. Die Antwort wird geprüft, Dubletten fallen weg. Es sind nur Suchbegriffe, nie CQL. Vorbild:
     askai-java8 (Branch `arch`), `SearchSuggestion`, `ScopingAssistantOutputParser`, aber mit Varianten derselben
     Frage statt Themenrichtungen.
   - Sprachanalyse.

   Die erzeugten Suchanfragen werden angezeigt, mit Priorität und Stand.
2. **Wellen statt alles sofort.** Nach der ursprünglichen Anfrage laufen die wichtigsten Varianten parallel
   (`SearchExecutionPolicy`, Wellengröße Standard 3, einstellbar). Nach jeder Welle wird neu bewertet und die Qualität
   geprüft. Sonst wären teure Anfragen schon unterwegs, wenn das Qualitätsziel erreicht ist.
3. **Freigabe nach Priorität.** Varianten laufen parallel, ihre Ergebnisse werden aber in der Reihenfolge der
   Prioritäten freigegeben: Das Ergebnis von Rang 3 wartet auf Rang 2.
   - Überschreitet eine Variante ihre Frist, gilt sie als gescheitert und gibt die nachrangigen frei. Ihre
     verspäteten Treffer werden trotzdem noch einsortiert.
   - Die sichtbare Liste ist nach Bewertung sortiert. Die Prioritätsreihenfolge gilt nur für die Freigabe.
   - Bei einem vorübergehenden Fehler gibt es einen weiteren Versuch. Eine neue Eingabe bricht die alte Suche ab.
4. **Bewertet wird immer gegen die vollständige ursprüngliche Anfrage**, per Titel und Auszug, nie gegen die
   verkürzte Variante. Zusammengeführt wird nach Quelle und Seiten-ID. Bekannte Treffer werden neu einsortiert,
   nicht ersetzt.
   - Je Freigabe geht höchstens eine Aktualisierung hinaus, damit die Liste nicht springt.
   - Die Oberfläche bleibt bedienbar. Das Öffnen eines Treffers hat Vorrang (ADR 0016).
5. **Das Ende hängt von der Qualität ab** (`SearchQualityPolicy`, austauschbar). Die erste Umsetzung: mindestens N
   Treffer mit einer Bewertung über einer Schwelle. Die Schnittstelle bringt schon Abdeckung und Erkenntnisgewinn der
   letzten Welle mit. Rohe Werte eines Cross-Encoders oder von Lucene sind nicht kalibriert; Schwellen sind deshalb
   Einstellungen des Suchprofils, mit Vorsicht gewählt.
   - Zeit- und Abfragebudget begrenzen die Suche.
   - Modus `QUALITY` ist Standard. `ALL_VARIANTS` arbeitet alle Varianten ab, etwa für einen Recherche-Agenten.
6. **Zwei Zugriffe auf denselben Vorgang.** `searchAsync` liefert nach Abschluss die stabile, sortierte Liste mit
   Abschlussgrund. Ereignisse (`ProgressiveSearchListener`) melden unterwegs Suchanfragen, die neu geordnete Liste
   und den Abschluss.
   - Abschlussgründe: `QUALITY_REACHED`, `ALL_VARIANTS_DONE`, `BUDGET_EXHAUSTED`, `CANCELLED`, `FAILED`.
   - Eine Suche, die an Zeit oder Budget endet, behauptet nie, das Qualitätsziel erreicht zu haben.
   - „Abgeschlossen“ heißt: Die Strategie hat ihre Arbeit beendet. Es heißt nicht, dass jede Seite untersucht wurde.
7. **Der Core kennt keine Oberfläche, keine Schlüsselverwaltung und keine Zugangsdaten.** Modell, Reranker und
   Quellen kommen über Schnittstellen (`QueryExpansionStrategy`, `HitScorer`, `VariantSearch`). Rechte prüft die
   Schlagwortsuche: Was der Aufrufer nicht sehen darf, erscheint weder als Treffer noch in einer Zahl. Suchtexte und
   Seiten-IDs kommen nie ins Log.

## Umsetzung (erster Schritt)

| Baustein | Aufgabe |
| --- | --- |
| `SearchOrchestrator` | Ein Vorgang je `searchAsync`; ursprüngliche Anfrage und Varianten, Wellen, Freigabe, Zusammenführung, Abschluss |
| `QueryExpansionStrategy` / `RuleBasedExpansion` | Varianten: alle Begriffe, dann je Schritt den schwächsten weglassen (gewöhnliches Wort vor Name vor Kennung), zuletzt die stärksten einzeln; linear, höchstens `maxVariants` |
| `SearchExecutionPolicy` | Wellengröße 3, Parallelität 3, Frist 10 s je Anfrage, ein weiterer Versuch |
| `SearchQualityPolicy` | `minimumRelevantHits(N, Schwelle)`; `never()` für `ALL_VARIANTS` |
| `SearchProfile` | Modus, `maxVariants` 8, N = 5, Schwelle 0,5, Zeitbudget 30 s, höchstens 12 Anfragen, 20 Treffer je Anfrage |
| `HitScorer` | Bewertung gegen die ursprüngliche Anfrage; im Harvester der Cross-Encoder (`ExcerptPriority.scores`), nur für freigegebene Quellen |
| `VariantSearch` | Eine Suchanfrage; im Harvester die `KeywordSearch` mit vorläufigen Treffern und Laden in Rangfolge (ADR 0016) |

Laufende Suchanfragen werden nicht unterbrochen, nur nicht mehr abgewartet, denn ein Interrupt mitten im Lesen eines
Lucene-Index schlösse dessen Dateikanäle. Ohne Reranker gibt es keine Bewertung. Dann erreicht der Modus `QUALITY`
sein Ziel nie und endet mit `ALL_VARIANTS_DONE` oder `BUDGET_EXHAUSTED`.

## Folgen

- Desktop-App und Agenten nutzen denselben Dienst; die App braucht keine eigene Suchlogik.
- Nächste Schritte sind die Modellstrategie über den KI-Dienst, REST (ein Agent wartet auf das Ergebnis, die App
  bekommt Ereignisse) und die Anbindung an die semantische Suche.
