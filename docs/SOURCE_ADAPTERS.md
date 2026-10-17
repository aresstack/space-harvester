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
  `UNAVAILABLE`, `RATE_LIMITED`, `INVALID_RESPONSE`, `UNSUPPORTED`. Eine Antwort über `maxResponseBytes` ist
  `INVALID_RESPONSE` und wird nie gekürzt. Die Fehlertexte enthalten keine Zugangsdaten und keine Seiteninhalte.
- Robustheit (#13):

  | Fall in der Quelle | Verhalten | API |
  |---|---|---|
  | Netzfehler, Zeitüberschreitung, 5xx | bis `maxRetries` (Standard 2) wiederholt, exponentiell gestreut | 503 `source-unavailable` |
  | 429 | ebenso wiederholt; danach `RATE_LIMITED` | 429 `source-rate-limited` |
  | 401, Weiterleitung zur Anmeldung | nie wiederholt, `ACCESS_DENIED` | 502 `source-access-denied` |
  | 403 auf eine Einzelseite | wie eine unbekannte Seite, `NOT_FOUND`; die Space-Liste lässt sie ebenfalls aus | 404 `source-object-not-found` |
  | 404 | `NOT_FOUND` | 404 |
  | gekürzte `metadata.labels` | Attribut `confluence.labels.complete=false`, `PageMetadata.attributesComplete()` ist `false` | sichtbar in `attributes` |

  `Retry-After` wird noch nicht ausgewertet, weil der Transport keine Antwort-Header weitergibt. OTP/2FA betrifft nur
  Datei-Downloads über die Web-Sitzung, nicht die REST-Abrufe des Dienstes. Ein wiederholter Cursor bricht die
  Listung ab (`InventoryCollector`).
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
