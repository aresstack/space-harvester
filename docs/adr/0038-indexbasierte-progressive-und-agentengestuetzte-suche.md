# ADR 0038: Indexbasierte, progressive und agentengestützte Suche

- Status: **Angenommen** für die Punkte 1 bis 6 (Entscheidungen von Angelo, 25.11.2026, festgehalten in Epic #1461
  Abschnitt C.1 und #1460). Die Agenten-Spur (Punkt 7) ist als Zielbild beschlossen, auch für die Suchleiste, und
  **nicht umgesetzt**. Die Antworten auf die Rückfragen vom 26.11.2026 (14:21 und 14:41) sind eingearbeitet.
- Schreibt [ADR 0013](0013-suche-ueber-den-cache.md), [ADR 0016](0016-auswahl-beschaffung-hintergrund.md) und
  [ADR 0017](0017-progressive-suche.md) fort. Was dort steht und hier nicht ausdrücklich geändert wird, gilt weiter.
- Quellen: Epic #1461 (C.1), #1460 (Abschnitt 3), PR #1462; Angelos Zusammenfassung vom 26.11.2026 (Abschnitt 6).
- Bezüge: [ADR 0037](0037-quellenumfang-nutzungssteuerung-beschaffung.md) (Suchumfang, Beschaffungsregeln je Quelle),
  [ADR 0036](0036-gemeinsame-systemzugriffsarchitektur.md) (Live-Dienste, Freigaben),
  [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md) (Werkzeuge des Chats).

## Kontext

Mit ADR 0037 gibt es Quellen, die bewusst nicht vollständig indexiert sind und deren Beschaffung ausgeschaltet sein
kann. Dazu kommen Live-Dienste (JES, BetaView, freie Datei-, FTP- und NDV-Recherche), die gar keinen Bestand haben.
Zur Wahl standen drei eigenständige Suchmaschinen. Das ist verworfen.

## Entscheidung

1. **Drei fachliche Varianten, zwei technische Spuren.**

   | Variante | Was sie tut | Spur |
   | --- | --- | --- |
   | 1 Indexsuche | Sucht im vorhandenen Volltext- bzw. Vektorindex des Caches | Spur 1 |
   | 2 Progressive Quellsuche | Nutzt die native Suche einer nicht vollständig indexierten Quelle, bildet Varianten der Frage, wählt Kandidaten, lädt bei Bedarf und bewertet gegen die ursprüngliche Frage | Spur 1 |
   | 3 Agentengestützte Live-Recherche | Ein Agent setzt die Frage in erlaubte, auch mehrstufige Werkzeugabfragen um (Jobname, Zeitraum, Report, Spool) | Spur 2 |

2. **Spur 1 ist die bestehende gemeinsame Pipeline.** Varianten 1 und 2 sind keine konkurrierenden Strategien:
   `KeywordSearch`, `SearchOrchestrator`, `RelevanceSearch`/`SemanticPreparation` und die vorhandenen Ports für
   Rangfolge und Beschaffung bleiben die eine Pipeline. `QueryExpansionStrategy` mit `RuleBasedExpansion` und
   `AiQueryExpansion` (ADR 0017) wird wiederverwendet. Für eine einfache native Suche gibt es keinen neuen Agenten.
   Indexierte und nicht indexierte Bereiche derselben Quelle lassen sich im selben Suchumfang kombinieren.
3. **Was ausdrücklich nicht kommt:**
   - keine Reciprocal Rank Fusion und keine Fusion von Bewertungen verschiedener Skalen (ADR 0013, Punkt 1, bleibt),
   - keine Wiederaufnahme der hybriden Suche aus #181,
   - keine zweite dauerhafte Indexdatenbank,
   - kein verpflichtender temporärer RAM-Index.
4. **Erweiterung der Anfrage und dauerhafte Beschaffung sind unabhängige Entscheidungen.** Die progressive Suche
   darf Varianten bilden, auch wenn für die Quelle nichts dauerhaft geladen werden darf. Ob geladen wird, bestimmen
   allein die Regeln der Quelle (ADR 0037, Punkte 7 und 8).
5. **Native Funde bleiben sichtbar, auch ohne Beschaffung.** Ist der Prefetch für eine Quelle aus, erscheinen ihre
   nativen Funde weiterhin als eindeutig **vorläufige**, rechtegeprüfte Funde (`KeywordSearch.Results.found()`),
   auch wenn die Quelle keinen lokalen Indexstand hat. Sie werden nicht automatisch geladen und nicht bestätigt.
   Öffnen und gezieltes Lesen sind davon getrennt und bleiben möglich. Ein nativer Fund ist nie einem lokalen
   Volltext- oder Vektortreffer gleichgestellt.

   **Das gilt auch für die semantische Suche:** Native Funde solcher Quellen erscheinen dort
   getrennt als vorläufig und nicht semantisch bewertet, mit der Erklärung, dass der Inhalt nicht im semantischen
   Index liegt. Sie werden mit den Vektortreffern nie zu einer Rangfolge vermischt. Das schreibt ADR 0017 (vierter
   Schritt) fort.
6. **Ehrliche Abdeckung.** Eine native Suche mit anschließender Bewertung einiger Kandidaten ist keine semantische
   Suche über den ganzen Bestand der Quelle. Grenzen und Abdeckung werden ausgewiesen. Indexabdeckung und
   semantische Anreicherung sind verschiedene Zustände (ADR 0039, Punkt 8).
7. **Spur 2, die Agenten-Recherche, ist optional und getrennt.**
   - Sie braucht keine Wissensquelle. Freigaben liegen am Verbindungsprofil des Live-Dienstes, getrennt für Chat und
     Suchleiste, beide standardmäßig aus. Ohne KI-Freigabe gibt es keine Agenten-Suche.
   - **Die Agenten-Recherche in der Suchleiste gehört zum Zielbild** (beschlossen), nicht nur die im Chat. Sie ist
     standardmäßig aus und hat ihre eigene Freigabe.
   - Normale Suchtreffer erscheinen, ohne auf einen Agenten zu warten.
   - Agenten-Ergebnisse stehen in einem eigenen, gekennzeichneten Block mit Herkunft, tatsächlichen Fundstellen,
     Laufstatus, Zeit- und Werkzeugbudget, Abbruch und Fehlern. Es gibt keine gemeinsame Relevanzskala mit Spur 1.
   - Ein Agent läuft nie beim Tippen oder bei einer Tippkorrektur, nur bei einer abgesendeten oder ausdrücklich
     gestarteten Suche, mit den geltenden Daten- und Modellfreigaben.
   - Die manuelle Suche im Deck bleibt ohne KI möglich.
8. **Bedienung.** Es gibt keine erzwungene Auswahl „Index oder progressiv“, solange Fähigkeiten, Suchoptionen und
   Beschaffungsschalter dasselbe ausdrücken. Bestehende Konfigurationen behalten ohne neue Angaben ihr Verhalten.
9. **Temporäre Kandidatenbewertung ohne dauerhafte Ablage** (Inhalt kurz lesen, bewerten, verwerfen) bleibt als
   Erweiterung möglich und wird jetzt nicht gebaut. Wird sie später gebaut, gilt: begrenzte
   Zahl von Kandidaten, begrenzte Inhalte und Laufzeit, keine dauerhafte Indexierung ohne Beschaffungsfreigabe,
   erkennbar als vorläufig, kein Ersatz für die Berechtigungsprüfung. Alle diese Grenzen sind Einstellungen
   (ADR 0036, Punkt 12).
10. **Eine laufende progressive Suche ist ein Auftrag mit Zustand, kein Cursor.** Ein Navigationscursor sagt nur,
    wo eine Auflistung weitergeht, und ist zustandslos (ADR 0040). Eine progressive Suche hat laufende
    Teilaufgaben, Ergebnisse, Fortschritt und Abbruch. Ihr Register darf deshalb bleiben, mit sauber modelliertem
    Lebenszyklus:
    - Zuerst wird geprüft, ob `ProgressiveSearches` in der vorhandenen Auftragsinfrastruktur aufgehen kann.
    - Getrennt eingestellt werden die Zahl gleichzeitig laufender Suchen, die Zahl wartender Suchen und die
      Aufbewahrung fertiger Ergebnisse. Keine dieser Grenzen steht fest im Code.
    - Eine laufende Suche verschwindet nie, weil ein anderer Benutzer eine neue startet. Sind die Ressourcen
      ausgeschöpft, wartet die neue Suche nachvollziehbar oder wird ausdrücklich abgewiesen.

## Verhältnis zu ADR 0013, 0016 und 0017

| Stelle | Bisher | Jetzt |
| --- | --- | --- |
| 0013 Punkt 1: zwei Suchen, nie gemischt, keine Fusion | gilt | **gilt unverändert** (Punkt 3) |
| 0013 Punkt 2: „Treffer der Antwort kommen nur aus dem Cache“ | seit 0016 Punkt 1 gibt es daneben vorläufige Treffer, die geladen und dann bestätigt werden | **präzisiert:** *Bestätigte* Treffer kommen nur aus dem Cache. Vorläufige native Funde dürfen dauerhaft vorläufig bleiben, wenn die Quelle nicht laden darf (Punkt 5). Das erweitert die Cache-first-Regel. |
| 0013 Punkt 2: begrenzter Worker lädt Kandidaten in den Cache | für jede Quelle | **nur für Quellen, deren Regeln den Prefetch erlauben** (`KeywordSearch.allowsPrefetch`) |
| 0013 Punkt 3: mit Haken werden fehlende Embeddings berechnet | für jede geladene Seite | **nur für Quellen, deren Regeln den Prefetch erlauben**; sonst sucht die semantische Suche dort nur im vorhandenen Bestand |
| 0016 Punkt 1: Funde sofort als vorläufige Treffer | gilt | **gilt unverändert** und ist die Grundlage für Punkt 5 |
| 0016 Punkt 3: Laden in Rangfolge bis „abgeschlossen“ | für alle ausgewählten Funde | **nur für Funde aus Quellen mit erlaubtem Prefetch**; die übrigen bleiben vorläufig |
| 0016 Punkt 5: Hintergrundfüllung standardmäßig an | global, je Quelle abschaltbar | **gilt unverändert**; `background-fill` und `prefetch-hits` sind je Quelle unabhängig (ADR 0037) |
| 0016: `FindingCataloging` merkt Funde als Kopfdaten vor | für jede Quelle | **gilt unverändert für die Kopfdaten**, unabhängig vom Prefetch. Ob der Inhalt später geladen wird, entscheiden die Beschaffungsregeln der Quelle (ADR 0037, Punkt 8). Der Code überspringt das Erfassen heute bei ausgeschaltetem Prefetch; das ist ein bestätigter Fehler. |
| 0017: `SearchOrchestrator`, Wellen, Freigabe nach Priorität, Qualitätsziel | gilt | **gilt unverändert** und ist Variante 2 |
| 0017 Schritt 4: „Vorläufige Treffer der Quellen (`found`) gibt es semantisch nicht“ | gilt | **fortgeschrieben:** Für Quellen ohne Beschaffung erscheinen native Funde getrennt als vorläufig und unbewertet (Punkt 5). Für alle anderen Quellen gilt Schritt 4 weiter. |
| 0017 Punkt 5: Modus `ALL_VARIANTS` „etwa für einen Recherche-Agenten“ | gilt | **gilt unverändert.** Gemeint ist ein Agent als *Verbraucher* von Spur 1 (er wartet auf die fertige Liste). Die Agenten-Recherche aus Punkt 7 ist etwas anderes: Sie ruft Werkzeuge der Live-Dienste auf. |

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| Gemeinsame Pipeline für indexierte und nicht vollständig indexierte Quellen | auf main (bestand, #1462 ergänzt die Regeln je Quelle) |
| Prefetch je Quelle in `KeywordSearch` und `SemanticPreparation` | auf main (#1462) |
| Vorläufige native Funde bei ausgeschaltetem Prefetch | auf main (#1462) |
| Vorläufige native Funde in der semantischen Suche | **nicht umgesetzt**, beschlossen (F38-1) |
| Register laufender progressiver Suchen mit einstellbaren Grenzen (Punkt 10) | **weicht ab:** feste Größe 256 und 5 Minuten. Eine laufende Suche wird schon heute nie verdrängt; ist das Register voll, wird die neue Suche abgewiesen (`CapacityExceededException`) |
| Temporäre Kandidatenbewertung ohne dauerhafte Ablage | nicht gebaut, bleibt Erweiterung (F38-3) |
| Agenten-Spur (Freigaben am Verbindungsprofil, eigener Ergebnisblock) | **nicht umgesetzt**, im Code gibt es dafür nichts |

## Geklärt am 26.11.2026

Alle Punkte sind entschieden. Die Antworten stammen vom 26.11.2026 (14:21, 14:38 und 14:41); Angelo hat sie aus
dem ursprünglichen Chat übermittelt und am 26.11.2026, 14:41, alle Empfehlungen bestätigt.

| Frage | Antwort | Stand |
| --- | --- | --- |
| F38-1: Semantische Suche über Quellen ohne Beschaffung | Native Funde getrennt als vorläufig, unbewertet, keine Fusion; ADR 0017 wird fortgeschrieben | **Beschlossen**, in Punkt 5 |
| F38-2: Agenten-Recherche in der Suchleiste | Gehört zum Zielbild, standardmäßig aus, eigene Freigabe | **Beschlossen**, in Punkt 7 |
| F38-3: Temporäre Kandidatenbewertung | Bleibt Erweiterung, wird jetzt nicht gebaut | **Beschlossen**, in Punkt 9 |
| F38-4: Register laufender progressiver Suchen | Bleibt als Auftrag mit Zustand; Auftragsinfrastruktur prüfen; Grenzen einstellbar und getrennt nach laufend, wartend, aufbewahrt | **Beschlossen**, in Punkt 10 |

## Grenzen und Limits

Nach ADR 0036, Punkt 12. Stand main `69835ca8`. Die Budgets der progressiven Suche lassen sich je REST-Anfrage
überschreiben; ihre Standardwerte sind trotzdem fest im Code.

| Grenze | Standard | Einstellung | In der Oberfläche |
| --- | --- | --- | --- |
| Anfragen an die native Suche je Frage | 4, 1 bis 8 | `space-harvester.search.discovery.max-queries` | nicht geprüft |
| Wörter einer Frage, ab denen sie zerlegt wird | 6 | **fest im Code** (`SearchTerms.MAX_QUERY_WORDS`) | nein |
| Treffer je Anfrage, Tiefe, Länge des Suchtexts | 100, 1000, 200 Zeichen | **fest im Code** (`KeywordSearch.MAX_LIMIT`, `MAX_DEPTH`, `MAX_TEXT_LENGTH`) | nein |
| Wartezeit auf die native Suche einer Quelle | 5 s | **fest im Code**; nur über den Builder (`searchTimeout`) | nein |
| Wartezeit auf nachgeladene Treffer | 5 s | **fest im Code** (`SearchHitPrefetch.DEFAULT_WAIT`) | nein |
| Parallele Rechteprüfungen | 6 | **fest im Code** (`KeywordSearch.PARALLEL_CHECKS`) | nein |
| Gemerkte Suchen, verfolgte Nachladungen | 64, 256 | **fest im Code** (`KeywordSearch.REMEMBERED_SEARCHES`, `SearchHitPrefetch.MAX_TRACKED`) | nein |
| Progressive Suche: Varianten, Mindesttreffer, Mindestwert, Zeitbudget, Suchbudget, Treffer je Suche | 8, 5, 0,5, 30 s, 12, 20 | je Anfrage wählbar; Standard **fest im Code** (`SearchProfile`) | nein |
| Progressive Suche: Wellengröße, Wartezeit je Variante, Wiederholungen | 3, 10 s, 1 | **fest im Code** (`SearchExecutionPolicy`) | nein |
| Semantische Variantensuche: Wartezeiten | 20 s, 60 s | **fest im Code** (`SemanticVariantSearch`) | nein |
| KI-Erweiterung der Frage: Wartezeit, Länge je Variante, Länge der Frage, Größe der Antwort (**Größenlimit**) | 6 s, 200 Zeichen, 2000 Zeichen, 64 KiB | **fest im Code** (`AiQueryExpansion`) | nein |
| Ausweitung über Begriffsbeziehungen | 3 | **fest im Code** (`SearchExpansion.MAX_EXPANSIONS`) | nein |
| Server: gleichzeitig laufende progressive Suchen, Aufbewahrung, größtes Zeitbudget | 256, 5 Minuten, 2 Minuten | **fest im Code** (`ProgressiveSearches`, `ProgressiveSearchController`) | nein |

Die Agenten-Spur (Punkt 7) nennt ein Zeit- und Werkzeugbudget. Sie ist nicht gebaut; die Budgets entstehen als
Einstellungen.

## Folgen

- Es bleibt bei einer Suchpipeline und einem Index. Neue Quellen mit nativer Suche brauchen keine eigene Suchlogik.
- Eine Suche über gemischte Bestände zeigt bestätigte Treffer und vorläufige Funde nebeneinander. Die Oberfläche
  muss den Unterschied erklären.
- Die Agenten-Spur baut auf den UI-unabhängigen Live-Werkzeugen aus ADR 0036 auf und kommt erst danach.
