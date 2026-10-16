# ADR 0003: Eingebetteter Lucene-Cache und Ressourcenindex

- Status: **Entschieden** (Strang C, 16.10.2026; Grundsatz „nur Lucene, kein Datenbankserver“ von Angelo)
- Issue: [#26](https://github.com/aresstack/space-harvester/issues/26); umgesetzt in #48, #57, #60. Nutzer des Caches:
  #27, #28, #29, #30, #31.

## Kontext

Der Harvester soll Seiten nicht bei jeder Anfrage neu aus Confluence holen. Er braucht einen dauerhaften Speicher für
die Originalbytes jeder Seite, ihre Kopfdaten, ihren Revisionsverlauf und den Stand der Synchronisierung, und einen
Index für Space-, Eltern- und Titelabfragen. Ein Datenbankserver, PostgreSQL oder H2 kommen nicht in Frage; der
Dienst soll headless und als Bibliothek einbindbar bleiben.

## Entscheidung

### 1. Ein Lucene-Index als einziger Speicher

`persistence.lucene` hält zwei Indizes unter `space-harvester.cache.directory`: `pages/` (Schema `page-cache/v3`,
`PageCacheSchema`) und `jobs/` (Schema `job-store/v1`). Die Anwendung kennt nur ihre Ports (`SnapshotRepository`,
`PageCacheReader`, `PageSearchIndex`, `SyncBaselineStore`, `JobStore`); Lucene kommt außerhalb des Adapters nicht vor
(ArchUnit-`LayeringTest`). Die Schemaversion steht in den Commit-Daten; ein Index mit anderem Schema oder ein
beschädigter Index verhindert den Start und wird nie stillschweigend ersetzt.

### 2. Dokumentarten in `pages/`

| Art | Schlüssel | Inhalt |
|---|---|---|
| `page` | `sourceId` + `pageId` | Aktuelle Kopfdaten exakt indexiert (`spaceKey`, `parentId`, `version`, `modifiedAt`, `metadataHash`, `attributesHash`, `rawSha256`), Titelwörter und Sortierschlüssel für #28, der letzte Snapshot mit den Originalbytes als binäres `StoredField` |
| `revision` | `sourceId` + `pageId` + Revision | Verlauf je Revision ohne Payload |
| `space` | `sourceId` + `spaceKey` | Kopf der zuletzt bestätigten Generation (Nummer, Zeitpunkt, veraltete und fehlende Seiten) |
| `baseline` | `sourceId` + `spaceKey` (`baselineOf`), je Seite ein Dokument mit der `pageId` | Stand je Seite der bestätigten Generation; die Generationsnummer steht nur im `space`-Dokument, eine neue Bestätigung ersetzt alle Einträge des Space |
| `source` | `sourceId` | Suchgeneration `s<n>` der Quelle |

Hierarchie entsteht aus `parentId`; es gibt kein verschachteltes Seitendokument. Gleiche Feldnamen haben im ganzen
Index denselben Feldtyp, daher die Präfixe `revision.`, `space.`, `baseline.`, `source.`.

### 3. Atomarität und Wiederherstellung

Ein `IndexWriter` mit `commitOnClose=false`; Leser sehen über einen `SearcherManager` nur Commits. Ein
Synchronisierungslauf schreibt in einem `Batch` und bestätigt seine Generation mit demselben Commit. Ein Abbruch oder
Absturz vorher lässt den zuletzt bestätigten Stand stehen; eine Generation muss genau auf die bestätigte folgen.
Relationale ACID-Eigenschaften über mehrere Indizes verspricht der Cache nicht. Sicherung, Löschen und Wiederaufbau:
README, Abschnitt „Lucene-Cache“.

### 4. Payload

Die Originalbytes der Antwort, nie aus DTOs zurückserialisiert, geprüft gegen `rawSha256` beim Lesen; Grenze
`space-harvester.cache.max-payload-size`, Standard 20 MiB (ADR 0002); größere Antworten werden abgewiesen, nie gekürzt. Header, Cookies und Tokens stehen nie
im Index. Große Assets (#9, #19) kommen später in einen dateibasierten Speicher, nicht in Lucene.

### 5. Wiederverwendung aus `enterprise-ai-client`

Geprüft: `anagraphai` (`LuceneRawKnowledgeIndex`, `LuceneTextIndex`). Übernommen werden die Muster: ein offener
`IndexWriter` mit `SearcherManager`, Lucene nur im Adapter hinter einem Port, Schemaversion im Index. Der Code selbst
wird nicht übernommen: Er baut auf Lucene 8.11 und Java 8, committet jedes `put` einzeln und kennt keine
Generationen, während der Harvester Lucene 10 nutzt und einen Lauf atomar bestätigen muss. Die Abhängigkeit auf den
ganzen Enterprise AI Client entfällt damit.

### 6. Enrichments und Vektoren später, getrennt von der Payload

Enrichments (#29) werden eigene, versionierte Dokumente (`enrichment`), nie Felder der Seite und nie eine Änderung der
Payload. Schlüssel: `sourceId` + `pageId` + Prozessor + Prozessorversion; der Eingabe-Hash (`rawSha256` oder der
Hash der Textprojektion) zeigt, ob ein Ergebnis veraltet ist. Embeddings und Reranking gehören wie in Corenth
(Pinakes neben Anagraphai) in ein eigenes, aus dem Cache abgeleitetes Register mit eigenem Port. Der Seitenschlüssel
und `rawSha256` reichen dafür als Bezug; heute wird nichts davon angelegt.

## Folgen

- Ein Schemawechsel heißt Index löschen und neu ernten, solange es kein Release gibt; danach braucht jede neue
  Version eine Migration oder einen dokumentierten Wiederaufbau.
- Suchfelder liegen auf den Seitendokumenten, damit Treffer und Cache-Generation aus einem Commit stammen. Das weicht
  von Corenths getrenntem lexikalischem Register ab und reicht für Titel- und Metadatensuche; eine Volltextsuche
  mit eigenem Analyzer kann das ändern.
- Ein Prozess je Verzeichnis (`write.lock`).
