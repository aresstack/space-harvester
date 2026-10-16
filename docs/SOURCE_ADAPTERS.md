# Quellenadapter

Space Harvester spricht Wissensquellen nur über den quellenneutralen Port
`application.sources.HarvestSource` an. Umgesetzt wird er im Paket `source` über die Adapter aus
`enterprise-ai-sources`, einer unveränderten Kopie aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client) (#2, PR #23). Einen zweiten
Confluence-Client gibt es nicht.

## Auswahl

`ConfiguredSourceCatalog` baut aus `space-harvester.sources[*]` je Eintrag eine Quelle. `type` wählt den Adapter
(`ConfiguredSourceCatalog.TYPES`, heute nur `confluence` über `ConfluenceSourceProvider`). Unbekannte Typen,
fehlerhafte Optionen, Zertifikate oder Proxy-Einstellungen verhindern den Start, und alle Fehler werden gemeinsam
gemeldet. Konfiguration siehe README, „Quellen konfigurieren“.

## Vertrag

| Methode von `HarvestSource` | Upstream-Fähigkeit | Zweck |
|---|---|---|
| `partitions()` | `KnowledgeSourcePort.partitions()` | alle sichtbaren Spaces |
| `pageMetadata(space, cursor, limit)` | `PartitionListingSource` | alle Seiten eines Space ohne Inhalt, mit Cursor (#5) |
| `pageMetadata(pageId)` | `ResourceMetadataSource` | eine Seite ohne Inhalt, unabhängig vom Space (#6) |
| `storedPage(pageId)` | `StoredContentSource` | native Antwort Byte für Byte mit Kopfdaten aus derselben Antwort (#8) |

- Fehlt einer Quelle eine Fähigkeit, antwortet die Methode mit `SourceAccessException` `UNSUPPORTED`, nie mit
  einem leeren Ergebnis. `storedPage` ist deshalb eine Default-Methode.
- Fehler der Quelle werden auf `SourceAccessException.Reason` abgebildet: `NOT_FOUND`, `ACCESS_DENIED`,
  `UNAVAILABLE`, `INVALID_RESPONSE`, `UNSUPPORTED`. Eine Antwort über `maxResponseBytes` ist `INVALID_RESPONSE` und
  wird nie gekürzt. Die Fehlertexte enthalten keine Zugangsdaten und keine Seiteninhalte.
- Kopfdaten (`PageMetadata`) sind für alle drei Wege gleich aufgebaut; `metadataHash` (`page-metadata/v1`) und
  `attributesHash` (`page-attributes/v1`) stimmen deshalb zwischen Liste, Einzelseite und Snapshot überein.

Darauf bauen:

- `SourceQueries`: Metadaten live für die API (#5, #6)
- `snapshot.SourceSnapshots`: `PageSnapshot` aus `storedPage`, zugleich `PageContentFetcher` der
  Synchronisierung (#16)

## Einen Adapter ergänzen

1. Fehlende Fähigkeiten upstream in `source-api` als optionale Capability-Interfaces ergänzen, nicht durch
   Änderungen an `KnowledgeSourcePort.load()`.
2. Die Kopie mit `enterprise-ai-sources/sync-from-upstream.sh sync <commit>` übernehmen; die Kopie wird nie lokal
   geändert (`verifyUpstreamCopy` prüft das im Build).
3. Den Typ in `ConfiguredSourceCatalog` registrieren. `KnowledgeSourceHarvestSource` bildet jeden
   `KnowledgeSourcePort` mit seinen Fähigkeiten ab.

## Tests

- `KnowledgeSourceHarvestSourceTest`: Abbildung der Ressourcen, fehlende Fähigkeiten ergeben `UNSUPPORTED`.
- `PageMetadataApiTest`, `SinglePageMetadataApiTest`, `NativeSnapshotSourceTest`: alle Wege über echtes HTTP gegen
  die Fake-Confluence aus den Test-Fixtures der Kopie.
- Upstream: `ConfluenceStoredContentTest` und die übrigen Adaptertests laufen in `:enterprise-ai-sources:test` mit.
