# ADR 0038: Indexbasierte, progressive und agentengestützte Suche

- Status: **Angenommen** für die Punkte 1 bis 6 (Entscheidungen von Angelo, 25.11.2026, festgehalten in Epic #1461
  Abschnitt C.1 und #1460). Die Agenten-Spur (Punkt 7) ist als Zielbild angenommen und **nicht umgesetzt**. Die
  Punkte unter „Offene Fragen“ sind nicht entschieden.
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
6. **Ehrliche Abdeckung.** Eine native Suche mit anschließender Bewertung einiger Kandidaten ist keine semantische
   Suche über den ganzen Bestand der Quelle. Grenzen und Abdeckung werden ausgewiesen. Indexabdeckung und
   semantische Anreicherung sind verschiedene Zustände (ADR 0039, Punkt 8).
7. **Spur 2, die Agenten-Recherche, ist optional und getrennt.**
   - Sie braucht keine Wissensquelle. Freigaben liegen am Verbindungsprofil des Live-Dienstes, getrennt für Chat und
     Suchleiste, beide standardmäßig aus. Ohne KI-Freigabe gibt es keine Agenten-Suche.
   - Normale Suchtreffer erscheinen, ohne auf einen Agenten zu warten.
   - Agenten-Ergebnisse stehen in einem eigenen, gekennzeichneten Block mit Herkunft, tatsächlichen Fundstellen,
     Laufstatus, Zeit- und Werkzeugbudget, Abbruch und Fehlern. Es gibt keine gemeinsame Relevanzskala mit Spur 1.
   - Ein Agent läuft nie beim Tippen oder bei einer Tippkorrektur, nur bei einer abgesendeten oder ausdrücklich
     gestarteten Suche, mit den geltenden Daten- und Modellfreigaben.
   - Die manuelle Suche im Deck bleibt ohne KI möglich.
8. **Bedienung.** Es gibt keine erzwungene Auswahl „Index oder progressiv“, solange Fähigkeiten, Suchoptionen und
   Beschaffungsschalter dasselbe ausdrücken. Bestehende Konfigurationen behalten ohne neue Angaben ihr Verhalten.

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
| 0017 Schritt 4: „Vorläufige Treffer der Quellen (`found`) gibt es semantisch nicht“ | gilt | **offen**, siehe F38-1 |
| 0017 Punkt 5: Modus `ALL_VARIANTS` „etwa für einen Recherche-Agenten“ | gilt | **gilt unverändert.** Gemeint ist ein Agent als *Verbraucher* von Spur 1 (er wartet auf die fertige Liste). Die Agenten-Recherche aus Punkt 7 ist etwas anderes: Sie ruft Werkzeuge der Live-Dienste auf. |

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| Gemeinsame Pipeline für indexierte und nicht vollständig indexierte Quellen | auf main (bestand, #1462 ergänzt die Regeln je Quelle) |
| Prefetch je Quelle in `KeywordSearch` und `SemanticPreparation` | auf main (#1462) |
| Vorläufige native Funde bei ausgeschaltetem Prefetch | auf main (#1462) |
| Temporäre Kandidatenbewertung ohne dauerhafte Ablage | offen, eigener Folgeschnitt laut #1460 |
| Agenten-Spur (Freigaben am Verbindungsprofil, eigener Ergebnisblock) | **nicht umgesetzt**, im Code gibt es dafür nichts |

## Offene Fragen an Angelo

- **F38-1:** Semantische Suche (Haken) über eine Quelle mit ausgeschaltetem Prefetch und ohne lokalen Bestand:
  Sollen ihre nativen Funde dort als vorläufige Funde erscheinen (wie in der Schlagwortsuche), oder bleibt es bei
  ADR 0017, dass es semantisch keine vorläufigen Funde gibt und die Quelle dann schlicht nichts beiträgt?
- **F38-2:** Agenten-Recherche in der Suchleiste: Ist sie beschlossen (mit eigener Freigabe neben dem Chat), oder
  ist die Entscheidung „nur im Chat“ und die Suchleiste eine spätere Möglichkeit? Epic #1461 nennt beides
  („zusätzliche explizite Freigabe für die Suchleiste“, „Chat-only als empfohlene Nutzung“).
- **F38-3:** Temporäre Kandidatenbewertung ohne dauerhafte Ablage (Inhalt kurz lesen, bewerten, verwerfen), wenn
  die Beschaffung aus ist: Soll das gebaut werden, oder ist es nur als Möglichkeit vorgemerkt?

## Folgen

- Es bleibt bei einer Suchpipeline und einem Index. Neue Quellen mit nativer Suche brauchen keine eigene Suchlogik.
- Eine Suche über gemischte Bestände zeigt bestätigte Treffer und vorläufige Funde nebeneinander. Die Oberfläche
  muss den Unterschied erklären.
- Die Agenten-Spur baut auf den UI-unabhängigen Live-Werkzeugen aus ADR 0036 auf und kommt erst danach.
