# Seitenmodell `page-snapshot/v1`

Ein Snapshot ist eine Seite so, wie die Quelle sie in **einer** Antwort geliefert hat: die native Payload Byte für
Byte und die daraus gelesenen Kopfdaten (#8, ADR 0002). Das Modell kennt keine Confluence-Typen; Confluence ist nur
der erste Lieferant. Java: `application.snapshot.PageSnapshot` (`PageSnapshot.SCHEMA`).

## Zwei Ebenen

| Ebene | Inhalt | Garantie |
|---|---|---|
| Native Payload | HTTP-Body nach Transportdekodierung, z. B. Confluence-REST-JSON mit `body.storage` | byte-genau, nie aus DTOs rekonstruiert; ohne Header, Cookies, Zugangsdaten |
| Kopfdaten (`PageMetadata`) | ID, Titel, Space, Elternseite, Version, Änderungszeit, URL, Attribute | aus derselben Antwort gelesen; nur zum Steuern, ersetzen die Payload nie |

Das Modell ist flach: Die Hierarchie steckt nur in `parentPageId`, Bäume entstehen getrennt aus den IDs (#27).

## Felder

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `schema` | ja | `page-snapshot/v1` |
| `sourceId` | ja | konfigurierte Quelle; Seiten-IDs gelten nur je Quelle |
| `pageId` | ja | ID der Seite in der Quelle (Confluence-Content-ID) |
| `resourceId` | ja | quellübergreifende ID, z. B. `confluence:wiki.example.org/page/123` |
| `title` | ja | Titel, leer statt `null` |
| `spaceKey` | ja | Space bzw. Partition zum Zeitpunkt des Abrufs |
| `parentPageId` | nein | Elternseite; `null` für eine Wurzelseite |
| `version` | nein | Versionskennung der Quelle; `null`, wenn unbekannt |
| `modifiedAt` | nein | letzte Änderung laut Quelle |
| `url` | nein | Adresse der Seite in der Quelle |
| `attributes` | ja | weitere Angaben der Quelle, nach Schlüssel sortiert, z. B. `confluence.labels`, `confluence.createdBy`; ein Attribut `<name>.complete=false` heißt: `<name>` war gekürzt |
| `metadataHash` | ja | `sha256:<hex>` über die Kopfdaten ohne Attribute, Schema `page-metadata/v1` (`MetadataHash`) |
| `attributesHash` | ja | `sha256:<hex>` über `attributes`, Schema `page-attributes/v1` (`AttributesHash`) |
| `retrievedAt` | ja | wann die Quelle geantwortet hat |
| `payload.mediaType` | ja | Medientyp ohne Parameter, z. B. `application/json` |
| `payload.nativeFormat` | ja | Format und Abfrageform; ein anderer Wert heißt: nicht vergleichbar |
| `payload.rawSha256` | ja | `sha256:<hex>` über genau die Payload-Bytes |
| `payload.bytes` | ja | die Payload |
| `warnings[]` | ja | siehe unten; leer, wenn nichts fehlt |

### Die drei Hashes

- `rawSha256` beweist, dass eine gespeicherte Payload unverändert ist. Er ist **kein** Änderungsnachweis der Seite:
  Confluence kann dieselbe Version unterschiedlich serialisieren.
- `metadataHash` ändert sich bei Titel, Space, Elternseite, Version oder Änderungszeit.
- `attributesHash` ändert sich bei Labels und anderen Attributen, unabhängig von der Version. Ist ein Attribut
  unvollständig, taugt er nicht als Änderungsnachweis für dieses Attribut.

### Warnungen

`warnings[]` sind Objekte `{"code": …, "detail": …}` (`SnapshotWarning`). Ein Snapshot mit Warnungen bleibt gültig.
Sie werden aus den Kopfdaten abgeleitet und deshalb mit ihnen gespeichert, ohne eigenes Feld im Cache.

| `code` | `detail` | Auslöser |
|---|---|---|
| `attributes-incomplete` | Attributname, z. B. `confluence.labels` | Die Quelle hat das Attribut gekürzt geliefert (Confluence: `metadata.labels` mit `_links.next`) |

Unvollständige Anhangslisten meldet die Anhangsliste selbst (`complete`, `warnings`, #9), nicht der Seiten-Snapshot.

## Beispiel

Logische Darstellung, wie Export und API die Felder benennen. Die Payload steht dort als Base64 oder getrennt, nie
umgeschrieben:

```json
{
  "schema": "page-snapshot/v1",
  "sourceId": "confluence",
  "pageId": "123456",
  "resourceId": "confluence:wiki.example.org/page/123456",
  "title": "Architektur",
  "spaceKey": "DEV",
  "parentPageId": "123400",
  "version": "7",
  "modifiedAt": "2026-10-15T10:30:00Z",
  "url": "https://wiki.example.org/pages/viewpage.action?pageId=123456",
  "attributes": {
    "confluence.createdAt": "2026-09-01T07:00:00Z",
    "confluence.createdBy": "autorin",
    "confluence.labels": "api,architektur",
    "confluence.labels.complete": "false"
  },
  "metadataHash": "sha256:5b0c…",
  "attributesHash": "sha256:91fe…",
  "retrievedAt": "2026-10-16T15:00:00Z",
  "payload": {
    "mediaType": "application/json",
    "nativeFormat": "confluence-rest-content+json;expand=ancestors,body.storage,history,history.lastUpdated,metadata.labels,space,version",
    "rawSha256": "sha256:0f3a…",
    "bytes": "eyJpZCI6IjEyMzQ1NiIsInR5cGUiOiJwYWdlIiwi…"
  },
  "warnings": [
    { "code": "attributes-incomplete", "detail": "confluence.labels" }
  ]
}
```

## Ansichten

- **Nativ:** `GET /api/v1/sources/{sourceId}/pages/{pageId}` liefert die Payload Byte für Byte, `ETag` = `rawSha256`.
- **Container:** `?view=extended` (#27) trennt `data` (die Payload als JSON, nicht byte-genau), `metadata` (Kopfdaten,
  Hashes, Herkunft) und `enrichments`. Eigene Angaben stehen nie in `data`.

## Enrichments

Enrichments (#29) liegen getrennt vom Snapshot (`application.enrichment.Enrichment`, `EnrichmentStore`): je Seite und
Prozessor ein Ergebnis mit `processorId`, `processorVersion`, `inputSha256`, `revision`, `status`, `payloadJson` und
`computedAt`. `freshness(inputSha256, version)` ist `STALE`, sobald sich Eingabe oder Prozessorversion geändert haben;
ein veraltetes Ergebnis wird nicht als aktuell ausgeliefert. Enrichments verändern die Payload nie, und ihr Fehlen
macht den Snapshot nicht ungültig. Bildbeschreibungen hängen an der Bildressource, nicht an der Seite (#19).

## Versionierung

- Neue optionale Felder oder Warnungscodes bleiben `v1`; Leser ignorieren Unbekanntes.
- Eine geänderte Bedeutung eines Felds oder Hashes ergibt `page-snapshot/v2`. Die Hash-Schemas haben eigene
  Versionen und werden nie umdefiniert.
- Eine andere Abfrageform der Quelle ändert `nativeFormat`, nicht das Schema.
