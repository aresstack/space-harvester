# ADR 0017: Progressive Suche

- Status: **Angenommen, im Core umgesetzt** (Strang Q, 18.10.2026; Entscheidungen von Angelo, 18.10. 18:03
  und 18:07). Ergänzt [ADR 0013](0013-suche-ueber-den-cache.md) und [ADR 0016](0016-auswahl-beschaffung-hintergrund.md).
- Gilt für die **semantische Suche** (Haken, ADR 0013). Die Schlagwortsuche bleibt Standard und unverändert.
- Umgesetzt: `SearchOrchestrator` im Core mit regelbasierten Varianten, Wellen, Prioritätsfreigabe, Qualitätsziel
  und Budgets (Paket `application.query.progressive`, `Harvester.progressiveSearch()`).
- Umgesetzt: Varianten über ein Modell des KI-Dienstes, REST-Schnittstelle (`/api/v1/search/progressive`).
- Umgesetzt: Anbindung an die semantische Suche im Core (`SearchProfile.Ranking`, `SemanticVariantSearch`,
  `Harvester.searchProfile()`).
- Umgesetzt: `ranking` über REST (`keyword`/`semantic`, `semantic-search-unavailable` ohne Embedding-Modell).
- Offen: in der App eine Anzeige, ob semantisch gesucht wird.

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

## Umsetzung (zweiter Schritt: Varianten vom Modell)

`AiQueryExpansion` lässt das Chat-Modell des KI-Dienstes (`ai.chat-model`, `POST /chat/completions` über den
kopierten `OpenAiCompatibleChatAdapter` des Enterprise AI Client) Varianten derselben Frage formulieren. Die
Reihenfolge geht nach abnehmender Genauigkeit: zuerst möglichst viele Begriffe des Nutzers, dann schrittweise weniger. Das Modell liefert nur Suchbegriffe, keine CQL und keine Operatoren, als JSON
`{"queries":[{"text":…,"priority":1},…]}`.

- **Strenge Prüfung.** Die Antwort muss gültiges JSON sein; erlaubt ist das erste Objekt, auch hinter Text oder in
  einem Codeblock. Höchstens `maxVariants` Varianten mit je 200 Zeichen werden übernommen. Doppelte Varianten fallen
  weg, ohne Rücksicht auf Groß- und Kleinschreibung oder Leerraum, ebenso die ursprüngliche Anfrage selbst. Varianten
  mit Abfragesyntax werden verworfen und nicht repariert: Anführungszeichen, Klammern, `= ~ : * |`,
  `AND`/`OR`/`NOT` und CQL-Felder. Die ursprüngliche Anfrage bleibt Priorität 0 und sucht sofort, das Modell läuft
  parallel dazu.
- **Rückfall auf `RuleBasedExpansion`.** Er greift, wenn kein Chat-Modell konfiguriert ist, bei einem Fehler, bei
  einer unbrauchbaren Antwort und nach 6 s. Die 6 s liegen unter der Frist von 10 s, die der Orchestrator auf
  Varianten wartet.
- **Einstellung `SearchProfile.expansion`.** `AI` ist der Standard, `RULES` nutzt nur die Regeln. Ohne Chat-Modell
  wirkt `AI` wie `RULES`; `Harvester.searchProfile()` meldet in dem Fall gleich `RULES`. Der Harvester folgt der
  wirksamen Konfiguration und baut die Verbindung je Einstellungsstand einmal.
- **Datenschutz.** An den Dienst geht nur der Suchtext des Nutzers, nie Inhalt einer Quelle. Deshalb gilt dafür
  `external-processing` nicht, denn diese Einstellung regelt Quelleninhalte. Frage, Antwort und Key erscheinen in
  keinem Log und keiner Meldung; Fehler nennen nur Art und Status. Der Aufruf nutzt dieselbe Proxy-Route
  (fail-closed), dasselbe Vertrauen und denselben Bearer-Token wie die übrigen Aufrufe.

## Umsetzung (dritter Schritt: REST)

Der Server verdrahtet den `SearchOrchestrator` wie der Harvester: die `KeywordSearch` als `VariantSearch`, Varianten
vom Chat-Modell der wirksamen Konfiguration mit den Regeln als Rückfall, bewertet vom Cross-Encoder
(`ai.rerank-model`). Er schließt vor Schlagwortsuche und Cache, ohne laufende Suchanfragen zu unterbrechen.

- `POST /api/v1/search/progressive` mit `q` und den Filtern der Schlagwortsuche; die Werte des `SearchProfile` sind
  einzeln überschreibbar (`mode` `quality`/`all-variants`, `maxVariants`, `minRelevantHits`, `minScore`,
  `timeBudgetMs` bis 120 s, `searchBudget`, `limitPerSearch`, `expansion` `ai`/`rules`). Ohne `wait` 202 mit dem
  Stand, für eine Oberfläche, die zusieht. Mit `wait: true` wartet die Anfrage bis zum Abschluss und liefert die
  fertige, sortierte Liste mit `reason`, für einen Agenten. Delegierte Benutzer warten immer, weil ihre Anmeldung den
  Aufruf nicht überdauert.
- `GET /api/v1/search/progressive/{id}` liefert unterwegs den letzten Stand aus den Ereignissen (`variants`,
  sortierte `hits`, `reason: null`), danach das Ergebnis. `DELETE` bricht ab (`cancelled`).
- Jede Suche gehört ihrem Aufrufer (Art, Kennung und durchreichender Client); ein anderer bekommt 404 wie bei einer
  unbekannten. Höchstens 256 Suchen sind bekannt, beendete 5 Minuten lang; ist der Platz voll, weicht die älteste
  beendete, laufen alle, antwortet der Dienst mit 429.
- Ungültige Angaben ergeben 400 (`invalid-request`). Eine Quelle ohne Freigabe liefert nichts; scheitert jede
  Suchanfrage, endet die Suche mit `failed`.

## Umsetzung (vierter Schritt: semantische Suche)

ADR 0013 trennt Schlagwortsuche und semantische Suche, ohne Fusion. Der Haken „Semantische Suche“ startete bisher die
progressive Suche, aber jede Suchanfrage lief über die Schlagwortsuche (BM25), nie über die Vektoren. Jetzt wählt
`SearchProfile.ranking` die Suche für alle Suchanfragen eines Vorgangs:

- **`KEYWORD`** (Standard von `SearchProfile.defaults()`): unverändert die `KeywordSearch`, bewertet vom `HitScorer`.
- **`SEMANTIC`**: `SemanticVariantSearch` über `RelevanceSearch`. Eine Variante **findet nur**: Die Vorbereitung
  (`SemanticPreparation`) sucht mit dem Text der Variante in den Quellen, lädt die Funde mit den Rechten des Aufrufers
  in den Cache und berechnet fehlende Embeddings. **Treffer kommen allein aus der Vektorsuche mit der ursprünglichen
  Anfrage** (`RelevanceSearch.search(…, text, discovery, …)`), mit Live-Prüfung und Rechten wie bei `sort=relevance`.
  So wächst mit jeder Variante der Bestand im Cache, und die Rangfolge bleibt die Nähe zur ganzen Frage.
- **Bewertung** ist dann die Kosinusähnlichkeit zur ursprünglichen Anfrage (`VariantSearch.Outcome.scores`); der
  Cross-Encoder bewertet nicht, damit keine Werte verschiedener Skalen gemischt werden. `minScore` gilt für diese
  Ähnlichkeit, das Qualitätsziel ist also auch ohne Rerank-Modell erreichbar. Innerhalb einer Suchanfrage ordnet ein
  eingestellter Cross-Encoder wie bisher nur das Fenster der `RelevanceSearch`.
- Vorläufige Treffer der Quellen (`found`) gibt es semantisch nicht. Ausgeschlossene Spaces (`excludeSpaceKeys`)
  lädt schon die Vorbereitung nicht, und sie sind keine Kandidaten der Vektorsuche; erst danach wird auf das Limit
  gekürzt, sodass erlaubte Treffer es füllen. Was der
  Aufrufer nicht lesen darf, erscheint weder als Treffer noch als Wert. Der Auszug ist das ähnlichste Stück des
  Lesetexts im Cache.
- **Kein stiller Rückfall** (ADR 0011): `searchAsync` mit `SEMANTIC` ohne Embedding-Modell wirft
  `SemanticSearchUnavailableException`, bevor etwas gesucht wird. Vorher prüft `VariantSearch.requireAvailable` die
  Rechte am genannten Umfang (fail-closed wie `sort=relevance`): Wer eine genannte Quelle nicht sehen darf, bekommt
  `AccessDeniedException` und erfährt nicht, ob ein Modell eingerichtet ist. Fällt der Dienst unterwegs aus, scheitern die
  Suchanfragen; scheitern alle, endet die Suche mit `FAILED`.
- **`Harvester.searchProfile()`** ist das Profil des Hakens: `SEMANTIC`, sobald die wirksame Konfiguration ein
  Embedding-Modell hat, sonst `KEYWORD`, wie `expansion` ohne Chat-Modell `RULES` meldet. Der Haken der App wird so
  ohne Änderung an der App semantisch. Weil jede Suchanfrage erst lädt und einbettet, hat sie dann
  `SemanticVariantSearch.VARIANT_TIMEOUT` (20 s) und die Suche `SemanticVariantSearch.TIME_BUDGET` (60 s); beides setzt `SemanticVariantSearch.profile()`.
  `search.semantic` prüft der Core weiterhin nicht, wie bei `sort=relevance`.

## Umsetzung (fünfter Schritt: semantisch über REST)

Der Server verdrahtet `SemanticVariantSearch` über seine `RelevanceSearch` im `SearchOrchestrator`, wie der Harvester.

- `POST /api/v1/search/progressive` nimmt `ranking`: `keyword` (Standard, wie bisher) oder `semantic`. Semantisch
  gilt `SemanticVariantSearch.profile()`, also 20 s je Suchanfrage und 60 s Zeitbudget; `timeBudgetMs` und die
  übrigen Angaben überschreiben das weiterhin einzeln. Ein anderer Wert ergibt 400 (`invalid-request`).
- Der Stand nennt `ranking`; semantisch ist `score` die Kosinusähnlichkeit zu `q`.
- Ohne Embedding-Modell antwortet der Dienst sofort mit 503 `semantic-search-unavailable`, wie `sort=relevance`,
  und startet keine Suche. Der Hinweis im `detail` nennt die Schlagwortsuche ohne `ranking=semantic` statt der
  Titelsuche von `sort=relevance`, nie den Suchtext. Es gibt keinen stillen Rückfall.
- Rechte prüft die `RelevanceSearch` je Suchanfrage wie bei `sort=relevance`; die Route und ihre Rechteprüfung
  bleiben unverändert.

## Folgen

- Desktop-App und Agenten nutzen denselben Dienst; die App braucht keine eigene Suchlogik.
- Über REST wartet ein Agent auf das Ergebnis, eine Oberfläche fragt den Stand ab.
- Mit Haken und Embedding-Modell sucht die progressive Suche semantisch über die Vektoren, ohne Fusion mit der
  Schlagwortsuche, in der App über den Haken, über REST mit `ranking: semantic`. Nächster Schritt ist die Anzeige in
  der App.
