# ADR 0013: Suche über den Cache, Schlagwort oder semantisch, keine Fusion

- Status: **Angenommen** (Strang Q, 18.10.2026; Entscheidung von Angelo, 18.10. 11:22). Ändert
  [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md) Punkt 9 (Schlagwortsuche) und
  [ADR 0011](0011-embeddings-ueber-externen-ki-dienst.md) bei der Relevanzsuche; nimmt die hybride Suche aus #181
  zurück.
- Bezüge: #169 (Haken „Semantische Suche“ in der App), #180 (Volltextindex), #181 (Zerlegung, Reranking),
  [ADR 0012](0012-core-als-gemeinsame-bibliothek.md) (Funktionsparität).

## Kontext

Mit #181 Teil 1 entstand eine hybride Relevanzsuche: Volltext-, Titel- und Vektorkandidaten, per Reciprocal Rank
Fusion zusammengeführt wie im Enterprise AI Client. Angelo hat das verworfen: „Schlagwortsuche standardmäßig.
Semantische Suche ausschließlich per Haken. Beide nach vorheriger Cache-Befüllung. Keine RRF-Fusion.“ Das
Zusammenführen von Ergebnislisten sollte gerade wegfallen, weil ohnehin alles im Cache gesucht wird.

## Entscheidung

1. **Zwei Suchen, nie gemischt.** Standard ist die Schlagwortsuche über den Volltextindex des Caches (BM25). Die
   semantische Suche läuft nur mit dem Haken (App) bzw. `sort=relevance` (Server) und sucht nur über die Vektoren im
   Cache. Ergebnislisten werden nicht zusammengeführt: keine Reciprocal Rank Fusion, keine Volltext- oder
   Titelkandidaten in der semantischen Suche. Das ist eine gewollte Abweichung vom Enterprise AI Client.
2. **Erst in den Cache, dann suchen.** Die native Suche der Quelle dient nur dem Finden: Ihre Treffer sind Kandidaten,
   die ein begrenzter Worker seitenweise mit den Rechten des Aufrufers in den Cache lädt. Treffer der Antwort kommen nur
   aus dem Cache. Solange noch geladen wird, sagt die Antwort, dass sie unvollständig ist. Ein vollständiger Abgleich
   ist nie Voraussetzung.
3. **Mit Haken zusätzlich Embeddings.** Ohne Haken wird nichts eingebettet. Mit Haken berechnet die Suche fehlende
   oder veraltete Embeddings der geladenen Seiten, dann sucht sie einmal semantisch. Zerlegung und Reranker gelten nur
   hier.
4. **Frische nach Zeit.** Jede Seite trägt den Zeitpunkt ihres Inhaltsabrufs (`fetchedAt`), getrennt von der letzten
   Prüfung (`lastCheckedAt`). Innerhalb eines einstellbaren Zeitfensters gilt die Seite ohne Rückfrage bei der Quelle
   als frisch. Die Versionsprüfung lässt sich abschalten; dann entscheidet allein das Zeitfenster, ob die Seite neu
   abgerufen wird. Gleiche Versionen werden nicht erneut heruntergeladen.
5. **Rechte unabhängig von Frische.** Mit delegierter Anmeldung prüft der Cache mit der Identität des Benutzers; die
   Zugangsdaten werden nie im Hintergrund ohne Bindung an den Aufruf wiederverwendet. Ohne delegierte Anmeldung gibt
   der gemeinsame Cache nichts frei, was die Zugriffspolicy dem Aufrufer nicht erlaubt.

## Folgen

- `RelevanceSearch` hat nur noch Vektorkandidaten; `ReciprocalRankFusion`, `Harvester.Builder.rankFusion` und
  `space-harvester.relevance` sind entfernt. `reranker` heißt `embedding-cosine@…`, mit Cross-Encoder `…+cross-encoder@…`.
- Die Schlagwortsuche wird auf die Cache-Pipeline umgebaut (Finden, Laden, Suchen im Volltextindex); der Vorlauf der
  Treffer wird ein seitenweiser Worker. Frische-Einstellungen und der Haken als Anfrageschalter folgen in eigenen PRs.
- docs/PARITY.md führt die fehlende Fusion als gewollte Abweichung.

## Fortschreibung: suchgetriebenes Finden (18.10.2026)

Das Finden folgt dem Enterprise AI Client (`ResearchQueries`, `SearchDrivenIndexingUseCase`, `ExcerptRanking`):
Eine Frage mit mehr als sechs Wörtern geht als wörtliche Begriffe und Schlüsselwörter an die native Suche jeder
Quelle (`SearchTerms`, höchstens `space-harvester.search.discovery.max-queries`, Standard 4), parallel; die Funde
werden je Quelle reihum ohne Dopplung zusammengeführt. Der Cursor zählt Positionen in dieser Zusammenführung; weil
jede Anfrage in jedem Stapel ab dem ersten Treffer tiefer gesucht wird, ist sie ein Anfang der nächsten. Mit
Cross-Encoder bewertet `ExcerptPriority` Titel und Auszug der Funde und bestimmt nur die Ladereihenfolge
(`space-harvester.search.excerpt-ranking`, Standard an): Sie verwirft nichts, fällt bei jedem Fehler auf die
Reihenfolge der Quellen zurück und schickt wie `ModelReranker` nur Texte aus Quellen mit Freigabe für externe
Verarbeitung. Die Punkte 1 und 3 gelten weiter für die Treffer: Ihre Rangfolge kommt allein aus BM25 über den
Cache, ohne Fusion; der Cross-Encoder ordnet in der Schlagwortsuche nur das Laden, nie die Treffer.
Rechte prüft weiterhin die Suche an den Kandidaten und an der Ergebnisliste des Caches; die Bewertung fügt keine
eigene Prüfung hinzu.
