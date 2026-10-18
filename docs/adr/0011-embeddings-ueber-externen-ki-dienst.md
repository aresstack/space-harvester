# ADR 0011: Embeddings nur über einen externen KI-Dienst

- Status: **Angenommen** (Strang Q, 18.10.2026; Auftrag von Angelo, 18.10. 00:12). Ersetzt
  [ADR 0004](0004-embeddings-und-reranking.md) beim Modell; Anreicherung, Vektorablage und Kosinus-Reranking aus
  ADR 0004 gelten weiter.
- Bezüge: #89 (Relevanzsuche), #114 (globale Suche), [ADR 0009](0009-globale-mehrquellensuche.md),
  [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md) (Beschaffungs-Use-Case, Schlagwortsuche, Live-Prüfung).

## Kontext

Bis hierher rechnete ein lokales ONNX-Modell (`multilingual-e5-small`) die Embeddings im Prozess. Das hat drei
Nachteile: Das Jar trägt eine große native Laufzeit, der Betrieb muss Modelldateien verteilen, und die Qualität ist an
ein kleines Modell gebunden. Angelo hat entschieden: Embeddings werden über das Netz gerechnet, über dieselbe
Schnittstelle wie im Enterprise AI Client. Das lokale Modell muss raus.

Die semantische Suche bleibt experimentell, hinter einer Checkbox in der App und hinter `sort=relevance` im Server.
Die Standardsuche ist die Schlagwortsuche über die native Suche der Quellen (ADR 0010, Punkt 9).

## Entscheidung

1. **Kein lokales Modell im Core.** `OnnxEmbeddingProvider`, die ONNX-Einstellungen, der Workflow für das
   Referenzmodell und die Abhängigkeiten ONNX Runtime und DJL-Tokenizer sind entfernt. Lokale Modelle gehören nicht in
   den Core.
2. **Ein Dienst, eine Schnittstelle.** Der KI-Dienst ist ein OpenAI-kompatibler Endpunkt mit `GET /models` und
   `POST /embeddings`. Der Core nutzt dafür die unveränderten Adapter aus dem Enterprise AI Client
   (`OpenAiCompatibleEmbeddingAdapter` aus `embedding-openai`, `KipitzModelCatalogAdapter` aus `model-kipitz`; Kopie
   in `enterprise-ai-sources`). Eingebunden wird er über `source.ai.RemoteAi`:
   - dieselbe Proxy-Route je Ziel wie die Quellen (`HttpRoutePort`: PAC, Proxy, fail-closed; ein PAC-Fehler wird nie
     zu DIRECT);
   - Vertrauen aus `trust-bundle` oder der JVM;
   - Bearer-Token je Anfrage, Zeitgrenzen, Stapel und eine Prüfung der Vektordimension durch den Adapter.
3. **Konfiguration als Properties.** `AiServiceSettings` mit den Namen unter `space-harvester.ai.`:
   `base-url`, `api-key`, `chat-model`, `embedding-model`, `embedding-dimensions`, `timeout` (Standard `PT30S`),
   `trust-bundle` und `external-processing-sources`.
   - Der Server bindet sie per Spring (`AiProperties`).
   - Die App liest sie aus ihrer Datei und nimmt den API-Key zur Laufzeit aus dem Tresor (`withApiKey`).
   - Der Core liest keine Umgebungsvariablen und keine Dateien. Der API-Key kommt als Wert oder aus einem
     Secret-Speicher, nie im Klartext in einer Datei und nicht aus KeePass. Er erscheint nie in `toString`, Logs oder
     Fehlermeldungen.
   - Die Dimension steht in keinem Modellkatalog. Sie ist Pflicht für Embeddings; `Harvester.aiEmbeddingDimension`
     misst sie einmal mit einem festen Text. `Harvester.aiModels` listet die Modelle mit ihren Kategorien
     (`embedding`, `chat` …) für die Auswahl in der Oberfläche.
4. **Eigene Freigabe für die Übertragung.** Ein Leserecht erlaubt einem Benutzer, eine Seite zu sehen. Es erlaubt
   nicht, ihren Inhalt an einen fremden Dienst zu schicken. Deshalb gilt:
   - Inhalte gehen nur an den konfigurierten, vom Betrieb freigegebenen Dienst (`base-url`), nie an einen anderen.
   - Die Freigabe gilt je Wissensquelle: `external-processing-sources` nennt die Quellen, deren Seiteninhalte an den
     Dienst gehen dürfen. Standard ist leer, also geht kein Inhalt hinaus und keine Seite bekommt Embeddings.
     `EmbeddingProcessor.appliesTo` setzt das durch, auch beim ausdrücklichen Neuberechnen.
   - Embeddings sind abgeleitete, schutzbedürftige Daten wie der Inhalt selbst: Sie verlangen `content`, liegen nur
     im gemeinsamen Index (`enrichments/`) und erscheinen nur an Treffern, deren Seite der Aufrufer lesen darf.
   - Suchanfragen gehen an den Dienst, sobald jemand die semantische Suche benutzt; die Checkbox in der App ist diese
     Zustimmung.
5. **Asynchron und begrenzt.** Embeddings rechnet ein Hintergrund mit einem Thread und höchstens 1000 wartenden
   Seiten (`Enrichments.background`). Ist er voll, wird die Seite übersprungen und beim nächsten Abgleich oder Abruf
   nachgeholt. Lesen, Schlagwortsuche, Abgleich und Export warten nie auf den Dienst. Nur `recompute` rechnet auf
   ausdrücklichen Wunsch im aufrufenden Thread. Ein ausgefallener Dienst ergibt ein `failed`-Ergebnis dieser einen
   Anreicherung, sonst nichts.
6. **`sort=relevance` bleibt der einzige Weg.** Es gibt keinen neuen Endpunkt. Ohne eingerichteten Dienst, oder wenn
   er die Anfrage nicht einbetten kann, antwortet die Suche mit `semantic-search-unavailable` (503), auch mit `title`.
   Es gibt keinen stillen Rückfall auf die Titelsuche: Wer die Relevanzsuche wählt, soll nicht unbemerkt eine andere
   Suche bekommen.
7. **Jeder Relevanztreffer wird vor der Auslieferung geprüft wie das Lesen einer Seite.** Ein Metadatenabruf mit der
   Identität des Aufrufers (`expand=space,version`) liefert Recht und Version in einem Schritt.
   - Gesperrte Seiten fallen ohne Spur weg; dann werden weitere Kandidaten geprüft, bis das Ergebnis voll ist oder die
     Kandidaten aus sind.
   - Ist die Version neuer, wird die Seite nachgeladen, im Cache abgelegt und ihr Vektor neu berechnet (im
     Hintergrund); bis dahin trägt der Treffer `freshness: stale`.
   - Der Index bleibt gemeinsam für alle Benutzer; es gibt keine Caches je Benutzer.
   - Umsetzung im nächsten Schritt von Strang Q; bis dahin prüft die Suche die Rechte gegen die Zugriffspolicy und
     markiert veraltete Vektoren wie bisher.

## Zielablauf

1. **Schlagwortsuche** über die native Suche der Quellen (ADR 0010, Punkt 9).
2. **Treffer prüfen und in den Cache legen:** Recht und Version je Treffer live, fehlende oder neuere Seiten über den
   Beschaffungs-Use-Case nachladen.
3. **Embeddings** für Seiten ohne oder mit veraltetem Vektor im Hintergrund berechnen, nur für freigegebene Quellen.
4. **Semantische Suche im gemeinsamen Cache:** Vektorsuche über die so vorbereiteten Seiten, jeder Treffer wieder mit
   Live-Prüfung (Punkt 7).

Damit wird die heutige Mischung aus Titel- und Vektortreffern unnötig; sie bleibt, bis Schritt 2 und 3 stehen.

**Erweiterungspunkt Schlagwortgenerierung.** Für Schritt 1 kann ein Chat-Modell (`chat-model`) aus einer Frage
gezielt Schlagworte erzeugen, wie es `KnowledgeResearch`, `LexicalDiscoveryStrategy` und `ResearchQueries` im
Enterprise AI Client tun. Das ist bewusst noch nicht gebaut und im Javadoc von `RelevanceSearch` als Erweiterung
beschrieben. Auch dafür gilt Punkt 4: Fragen gehen an den Dienst, Seiteninhalte nur aus freigegebenen Quellen.

## Folgen

- Das Jar ist deutlich kleiner; der Betrieb verteilt keine Modelldateien mehr.
- Ohne Dienst gibt es keine semantische Suche. Alles andere funktioniert unverändert.
- Vorhandene Embeddings des lokalen Modells werden nicht mehr gefunden: Die Prozessorversion enthält die
  Modellkennung, die Vektorsuche nimmt nur Vektoren genau dieser Version. Neue Vektoren entstehen beim nächsten
  Abgleich oder Abruf.
- Die alten Schlüssel `space-harvester.embedding.*` gelten nicht mehr. Die App meldet einen vorhandenen Block als
  Hinweis; der Server ignoriert ihn.
