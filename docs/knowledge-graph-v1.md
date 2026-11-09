# Vokabular Knowledge Graph v1 (JSON-LD 1.1)

Namensraum `sh:` = `https://aresstack.github.io/space-harvester/vocab/kg/v1#`, Format
`space-harvester/knowledge-graph-jsonld/v1`, Medientyp `application/ld+json`, Endung `.jsonld` (#499 Abschnitt G,
[ADR 0024](../adr/0024-aussagenanalyse-und-globaler-knowledge-graph.md)). Der vollständige `@context` steht in jeder
Exportdatei; zur Interpretation wird nichts aus dem Netz geladen. Die IRIs des Namensraums müssen nicht auflösbar sein.
Wiederverwendet werden W3C PROV-O (`prov:`), RDFS (`rdfs:`) und XSD (`xsd:`).

Das Vokabular ist versioniert: Neue Begriffe dürfen in v1 hinzukommen; Umbenennen oder Bedeutungsänderungen verlangen
`v2` (neuer Namensraum).

## Knoten-IRIs

Alle Knoten heißen `urn:space-harvester:kg:<art>:<quelle>:<seite>[:<fassung>[:<lokal>]]`; Bestandteile sind nach
RFC 3986 prozentkodiert (alles außer `A–Z a–z 0–9 - . _ ~`). `<fassung>` sind die ersten 12 Hex-Zeichen des
Eingabe-Hashs (`inputSha256`) des analysierten Lesetexts, `<lokal>` die Satz-/Token-Kennung der Analyse (`s1.t4`,
`s1.v3`). Damit sind Erwähnungen und Aussagen über Quellen, Seiten und Fassungen kollisionsfrei und stabil, solange
derselbe Text mit demselben Analysator analysiert wird.

| Art | IRI | Typ |
|---|---|---|
| Export | `urn:space-harvester:kg:export:<auftrag>` | `sh:GraphExport` |
| Seite | `…:page:<quelle>:<seite>` | `sh:Page` |
| Analysierte Fassung | `…:revision:<quelle>:<seite>:<fassung>` | `sh:PageRevision` (`prov:Entity`) |
| Analyselauf | `…:run:<quelle>:<seite>:<fassung>:<analysator>` | `sh:AnalysisRun` (`prov:Activity`) |
| Erwähnung | `…:node:<quelle>:<seite>:<fassung>:s<satz>.t<token>` | `sh:Mention` |
| Aussage | `…:node:<quelle>:<seite>:<fassung>:s<satz>.v<verb>` | `sh:Statement` |
| Beteiligung | Aussage-IRI + `:p<n>` | `sh:Participation` |
| Beleg | Aussage-IRI + `:evidence` | `sh:Evidence` |
| Nominaler Bezug | `…:node:…:<kopf>→<abhängig>` | `sh:NominalAttachment` |

## Klassen und Eigenschaften

- `sh:GraphExport`: `sh:format`, `sh:scope` (Umfang für Menschen), `prov:generatedAtTime`, `sh:includesStale`,
  `sh:complete` (alle lesbaren Seiten des Umfangs enthalten), `sh:pageCount`.
- `sh:Page`: `sh:sourceId`, `sh:pageId`, `sh:spaceKey`, `sh:title`.
- `sh:PageRevision`: `sh:revisionOf` → Seite, `sh:revision` (Version der Quelle), `sh:inputSha256`, `sh:status`
  (`CURRENT` | `STALE`), `sh:staleReason` (`text-changed`, `analyzer-changed`, `no-text`, `page-missing`).
- `sh:AnalysisRun`: `sh:analyzer`, `sh:analyzerVersion` (Adapter | Modell | Modell-Prüfsumme | Regeln),
  `prov:used` → Fassung, `prov:endedAtTime`.
- `sh:Mention` (eine Textstelle, **keine Entität**): `sh:text`, `sh:lemma`, `sh:headForm`, `sh:mentionKind`,
  `sh:unresolved` (offenes Pronomen), `sh:grammaticalCase`, `sh:sentence`, `sh:start`, `sh:end` (Zeichen im Lesetext
  der Fassung), `sh:inRevision`, `prov:wasDerivedFrom` → Fassung, `sh:lexicalKey` (normalisierte Grundform; gleiche
  Werte auf verschiedenen Seiten sind **lexikalische Kandidaten**, keine bestätigte Identität).
- `sh:Statement` (Aussage/Handlung als eigener Knoten): `sh:predicate`, `sh:predicateLemma`, `sh:voice`
  (`ACTIVE`/`PASSIVE`/…), `sh:clause` (`MAIN`/`SUBORDINATE`/…), `sh:core` (Kernsicht), `sh:finding` (Unsicherheiten
  und Hinweise), `sh:sentence`, `sh:start`, `sh:end`, `sh:verificationStatus` (immer `UNVERIFIED_PARSER_OUTPUT`),
  `sh:evidence` → Beleg, `sh:inRevision`, `prov:wasDerivedFrom` → Fassung, `prov:wasGeneratedBy` → Analyselauf.
  Rollen-Kanten zu Erwähnungen: `sh:actor`, `sh:affectedObject`, `sh:recipient`, `sh:prepositionalObject`,
  `sh:instrument`, `sh:time`, `sh:circumstance`.
- `sh:Participation` (qualifizierte Rolle, über `sh:participation`): `sh:role`, `sh:participant` → Erwähnung,
  `sh:relation` (UD), `sh:marker` (Präposition), `sh:priority`, `sh:basis` (`PARSER`, `COORDINATION`,
  `CASE_HEURISTIC`, `LEXICON`), `sh:certain`.
- `sh:Evidence`: `sh:inRevision`, `sh:sentence`, `sh:start`, `sh:end` (Satzspanne im Lesetext).
- `sh:NominalAttachment`: `sh:head`, `sh:dependent` → Erwähnungen, `sh:attachmentKind` (z. B. `GENITIVE`),
  `sh:relation`, `sh:marker`, `sh:basis`. Ein nominaler Bezug behauptet **keinen Besitz**.

## Was der Export nie enthält

- `owl:sameAs` oder andere Identitätsbehauptungen zwischen Erwähnungen.
- Seiten, Erwähnungen, Aussagen, Zähler oder IRIs, die der Aufrufer nicht lesen darf.
- Veraltete Analysen, außer sie wurden ausdrücklich gewählt (dann mit `sh:status "STALE"` und Grund).
- Den Seitentext selbst; Belege sind Spannen im Lesetext der genannten Fassung, Erwähnungen tragen ihren Wortlaut.
