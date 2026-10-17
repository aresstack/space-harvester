# Space Harvester ausprobieren: Ende-zu-Ende-Beispiele

Diese Anleitung führt die Kernoperationen der API lokal vor, ohne echte Confluence und ohne Zugangsdaten. Eine
Fake-Confluence mit festen Testdaten ersetzt die Instanz; der Dienst läuft mit der Demo-Konfiguration
[`config/application-demo.yml`](../config/application-demo.yml).

Jeder Aufruf unten ist geprüft: `DocumentedExamplesTest` liest diese Datei, führt jedes `curl` gegen den Dienst mit
derselben Konfiguration aus und vergleicht die Antwort mit dem Auszug darunter. Steht im Auszug ein `status`, muss der
HTTP-Status ihm entsprechen, sonst 200 (202 beim Start eines Auftrags mit `POST`). Felder, die vom Zeitpunkt, vom
Port oder vom einzelnen Lauf abhängen (`retrievedAt`, `url`, `resourceId`, IDs von Aufträgen), fehlen in den Auszügen.
Aufrufe mit `$SYNC_ID` oder `$EXPORT_ID` wiederholt der Test, bis der Auftrag den gezeigten Stand erreicht hat; von Hand
also gegebenenfalls kurz warten und erneut abfragen. Für die Zuweisungen braucht es [`jq`](https://jqlang.org/).

## Starten

In einem Terminal die Fake-Confluence auf `http://127.0.0.1:8090/wiki`:

```bash
./gradlew demoConfluence
```

In einem zweiten Terminal den Dienst mit dem Profil `demo`:

```bash
SPRING_PROFILES_ACTIVE=demo ./gradlew bootRun
```

Die Demo-Konfiguration richtet die Quelle `demo` (Anmeldung `none`) und den technischen Client `demo-client` ein. Er
darf im Space `DEMO` Metadaten, Inhalte und Exporte lesen, aber nicht den Space `OPS`. Sein Token ist nur für diese
Demo und steht deshalb offen hier:

```bash
TOKEN=demo-token-nur-lokal
```

## Testdaten

| Space | Seite | Elternseite | Besonderheit |
|---|---|---|---|
| `DEMO` | 1000 Start | – | Startseite des Space, Label `handbuch` |
| `DEMO` | 1001 Architektur | 1000 | Version 7, Info-Makro, Bild `diagramm.png` und draw.io-Diagramm `architektur` als Verweise auf Anhänge, zwei Labels |
| `DEMO` | 1002 Schnittstellen | 1001 | Überschrift, Akronyme mit Ausschreibung, Code-Makro |
| `DEMO` | 1003 Betrieb | 1000 | |
| `DEMO` | 2000 Archiv | – | zweite Wurzelseite außerhalb des Baums der Startseite |
| `DEMO` | 2001 Altes Konzept | 2000 | für die Endnutzerin Anna nicht sichtbar (Abschnitt 12) |
| `OPS` | 3000 Runbook | – | Space ohne Freigabe für `demo-client` |

## 1. Quellen und Spaces

Konfigurierte Quellen, ohne Verbindungsdaten. Neben der Fake-Confluence liest die Quelle `handbuch` das Verzeichnis
[`config/demo-handbuch`](../config/demo-handbuch) (Abschnitt 13):

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources"
```

```json
[
  { "id": "demo", "type": "confluence", "title": "Demo-Confluence" },
  { "id": "handbuch", "type": "files", "title": "Demo-Dateien" }
]
```

Spaces der Quelle. `OPS` fehlt, weil der Client dafür keine Freigabe hat:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces"
```

```json
{
  "sourceId": "demo",
  "spaces": [{ "key": "DEMO", "name": "Demo-Handbuch" }]
}
```

## 2. Seitenmetadaten sammeln

Metadaten aller Seiten eines Space, live aus der Quelle, ohne Inhalte und stapelweise. Der erste Stapel mit drei
Seiten:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/pages/metadata?limit=3"
```

```json
{
  "sourceId": "demo",
  "spaceKey": "DEMO",
  "origin": "live",
  "metadataHashSchema": "page-metadata/v1",
  "attributesHashSchema": "page-attributes/v1",
  "pages": [
    {
      "pageId": "1000", "title": "Start", "spaceKey": "DEMO", "parentPageId": null, "version": "3",
      "modifiedAt": "2026-10-01T06:15:00Z",
      "attributes": { "confluence.labels": "handbuch" },
      "metadataHash": "sha256:a419d08feb0598dfd1380af7ff122ec0e655b54faf7d3e9ec2f1bb066b0c64ce",
      "attributesHash": "sha256:012802654ab58dc09810bfb70ed54e396fd47317638a695569b289d071a89047"
    },
    {
      "pageId": "1001", "title": "Architektur", "parentPageId": "1000", "version": "7",
      "modifiedAt": "2026-10-12T08:30:00Z",
      "attributes": { "confluence.labels": "architektur,handbuch" },
      "metadataHash": "sha256:174e58f1d8a8a6a00f8bee7e458270fdf77f6b3df558c7bae7b6ec0ada1254d1"
    },
    { "pageId": "1002", "title": "Schnittstellen", "parentPageId": "1001" }
  ],
  "nextCursor": "p1.REVNTw.3",
  "complete": false
}
```

`nextCursor` geht unverändert in den nächsten Aufruf, bis er `null` ist und `complete` auf `true` steht:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/pages/metadata?limit=3&cursor=p1.REVNTw.3"
```

```json
{
  "pages": [
    { "pageId": "1003", "title": "Betrieb", "parentPageId": "1000" },
    { "pageId": "2000", "title": "Archiv", "parentPageId": null },
    { "pageId": "2001", "title": "Altes Konzept", "parentPageId": "2000" }
  ],
  "nextCursor": null,
  "complete": true
}
```

Dieselben Metadaten ergeben bei jedem Lauf dieselben Hashes. Die Bedeutung von `metadataHash` und `attributesHash`
beschreibt das [README](../README.md#api-übersicht).

## 3. Einzelne Seite

Metadaten einer Seite über die kanonische Route ohne Space-Key:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/pages/1001/metadata"
```

```json
{
  "sourceId": "demo",
  "origin": "live",
  "page": {
    "pageId": "1001", "title": "Architektur", "spaceKey": "DEMO", "parentPageId": "1000", "version": "7",
    "metadataHash": "sha256:174e58f1d8a8a6a00f8bee7e458270fdf77f6b3df558c7bae7b6ec0ada1254d1",
    "attributesHash": "sha256:1217f4b9e06ac74a4e734be4e20d2c9a4de57ff1d8e232ed8715ebbc8d3fc30b"
  }
}
```

## 4. Fehlerfälle

Fehler sind Problem Details (`application/problem+json`). Eine Seite, die es in der Quelle nicht gibt:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/pages/9999/metadata"
```

```json
{
  "status": 404,
  "type": "https://github.com/aresstack/space-harvester/problems/source-object-not-found",
  "sourceId": "demo"
}
```

Ein Space ohne Freigabe:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/OPS/pages/metadata"
```

```json
{
  "status": 403,
  "type": "https://github.com/aresstack/space-harvester/problems/access-denied"
}
```

Ohne Token:

```bash
curl -s "http://localhost:8080/api/v1/sources"
```

```json
{
  "status": 401,
  "type": "https://github.com/aresstack/space-harvester/problems/unauthenticated"
}
```

## 5. Space ernten

Die Ernte (Abgleich) lädt alle Seiten eines Space mit ihren nativen Rohdaten in den eingebetteten Lucene-Cache
(`build/demo/cache`). Sie läuft als Auftrag: Der Start antwortet sofort mit 202, die `id` führt zum Status.

```bash
SYNC_ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/syncs" | jq -r .id)
```

```json
{ "sourceId": "demo", "spaceKey": "DEMO" }
```

Status des Auftrags; beim ersten Lauf sind alle sechs Seiten neu. Erst ein vollständiger Lauf bestätigt eine neue
Cache-Generation:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/syncs/$SYNC_ID"
```

```json
{
  "state": "completed",
  "summary": {
    "inventory": "complete", "listed": 6, "added": 6, "unchanged": 0, "removed": 0,
    "generation": 1, "complete": true
  }
}
```

Ein zweiter Lauf vergleicht Revisionen und Hashes und lädt nur geänderte oder neue Seiten. Bei unveränderten
Demo-Daten lädt er keinen Inhalt nach:

```bash
SYNC_ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/syncs" | jq -r .id)
```

```json
{ "sourceId": "demo", "spaceKey": "DEMO" }
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/syncs/$SYNC_ID"
```

```json
{
  "state": "completed",
  "progress": { "written": 0 },
  "summary": { "added": 0, "contentChanged": 0, "unchanged": 6, "generation": 2, "complete": true }
}
```

## 6. Seiten und Baum aus dem Cache

Flache Seitenliste aus dem Cache, nach `pageId` sortiert, mit Cache-Stand und `rawSha256` je Seite:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/pages?limit=3"
```

```json
{
  "origin": "cache",
  "cache": { "generation": "g2", "state": "confirmed" },
  "pages": [
    { "pageId": "1000", "title": "Start", "parentPageId": null },
    {
      "pageId": "1001", "title": "Architektur", "parentPageId": "1000", "version": "7",
      "metadataHash": "sha256:174e58f1d8a8a6a00f8bee7e458270fdf77f6b3df558c7bae7b6ec0ada1254d1",
      "rawSha256": "sha256:2d267daaca7418f4a5eaab7fafc376b814643fc514e96a57476e6a17eb977e01"
    },
    { "pageId": "1002", "title": "Schnittstellen", "parentPageId": "1001" }
  ],
  "complete": false
}
```

Der Seitenbaum derselben Generation, mit beiden Wurzelseiten:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/tree"
```

```json
{
  "cache": { "generation": "g2", "state": "confirmed" },
  "nodeCount": 6,
  "depth": 3,
  "roots": [
    { "id": "1000", "children": [
      { "id": "1001", "children": [{ "id": "1002", "children": [] }] },
      { "id": "1003", "children": [] }
    ] },
    { "id": "2000", "children": [{ "id": "2001", "children": [] }] }
  ],
  "anomalies": []
}
```

Die native Seite kommt Byte für Byte so zurück, wie Confluence sie geliefert hat, im internen Storage-Format
(`body.storage`), nicht als gerenderte Ansicht. Bilder und draw.io-Diagramme stehen darin nur als Verweis auf ihren
Anhang (`ri:attachment`, Makro `drawio`); ihre Bytes gehören nicht zur Seite. `ETag` ist ihr `rawSha256`; Revision
und Cache-Stand stehen in den Headern `X-Page-Revision`, `X-Cache-Generation` und `X-Cache-State` (mit `curl -i`
sichtbar):

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/pages/1001"
```

```json
{
  "id": "1001", "type": "page", "title": "Architektur",
  "space": { "key": "DEMO" },
  "version": { "number": 7, "when": "2026-10-12T10:30:00.000+02:00" },
  "ancestors": [{ "id": "1000" }],
  "body": { "storage": { "representation": "storage" } }
}
```

## 7. Export

Der Export schreibt die bestätigte Generation als `pages.ndjson` (eine Seite je Zeile), `tree.json`, die Rohdaten unter
`raw/` und `manifest.json`. Auch er ist ein Auftrag:

```bash
EXPORT_ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/exports" | jq -r .id)
```

```json
{ "sourceId": "demo", "spaceKey": "DEMO" }
```

`artifact.complete` ist nur `true`, wenn die Generation bestätigt ist und keine Seite fehlt:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/exports/$EXPORT_ID"
```

```json
{
  "state": "completed",
  "progress": { "written": 6, "failed": 0 },
  "artifact": { "complete": true }
}
```

Das Manifest nennt Generation, Zählungen und die Hashes der Dateien:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/exports/$EXPORT_ID/artifact/manifest.json"
```

```json
{
  "schemaVersion": "space-harvester-export/v1",
  "sourceId": "demo",
  "spaceKey": "DEMO",
  "complete": true,
  "cache": { "generation": "g2", "state": "CONFIRMED" },
  "counts": { "pages": 6, "failedPages": 0 },
  "failures": []
}
```

Den Baum gibt es im Export als eigene Datei, ohne Seiteninhalte:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/exports/$EXPORT_ID/artifact/tree.json"
```

```json
{
  "roots": [
    { "id": "1000", "children": [
      { "id": "1001", "children": [{ "id": "1002", "children": [] }] },
      { "id": "1003", "children": [] }
    ] },
    { "id": "2000", "children": [{ "id": "2001", "children": [] }] }
  ]
}
```

Mit `format` entstehen aus denselben Seiten zusätzliche Dateien, aus dem Storage-Format abgeleitet: `markdown`
(`pages.md`), `json` (`pages.json`), `docx` und `pdf`. Das Raw-First-Artefakt bleibt unverändert daneben:

```bash
FORMAT_EXPORT_ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/exports?format=markdown&format=json" | jq -r .id)
```

```json
{ "sourceId": "demo", "spaceKey": "DEMO" }
```

Das Manifest nennt unter `conversions` je Format Datei, Converter und den Hash der Eingabe:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/exports/$FORMAT_EXPORT_ID/artifact/manifest.json"
```

```json
{
  "complete": true,
  "conversions": [
    { "format": "markdown", "file": "pages.md", "converter": "markdown/1" },
    { "format": "json", "file": "pages.json", "converter": "json-array/1" }
  ]
}
```

## 8. Anhänge einer Seite

Die Anhangsliste ist eine eigene Metadatenressource, live aus der Quelle. Sie enthält keine Binärdaten; Bilder und
Diagramme der Seite bleiben Verweise:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/pages/1001/attachments"
```

```json
{
  "sourceId": "demo",
  "pageId": "1001",
  "origin": "live",
  "complete": true,
  "warnings": [],
  "attachments": [
    { "attachmentId": "att1001", "title": "diagramm.png", "mediaType": "image/png", "fileSize": 15, "version": "1" },
    { "attachmentId": "att1002", "title": "architektur", "mediaType": "application/vnd.jgraph.mxfile", "fileSize": 158, "version": "1" }
  ]
}
```

## 9. Anreicherungen

Nach jedem bestätigten Abgleich berechnet der Dienst Anreicherungen aus der Payload im Cache, als eigene Ergebnisse mit
Eingabe-Hash neben dem Original. `view=extended` liefert sie mit der Seite; hier Akronyme und Gliederung von 1002:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/pages/1002?view=extended"
```

```json
{
  "origin": "cache",
  "metadata": { "page": { "pageId": "1002", "title": "Schnittstellen" } },
  "enrichments": [
    {
      "processor": "acronyms",
      "status": "ok",
      "freshness": "current",
      "result": { "acronyms": [
        { "acronym": "API", "count": 1, "expansion": "Application Programming Interface", "section": "REST-Schnittstelle" },
        { "acronym": "JSON", "count": 1, "expansion": null },
        { "acronym": "REST", "count": 1, "expansion": null }
      ] }
    },
    {
      "processor": "outline",
      "status": "ok",
      "freshness": "current",
      "result": { "words": 9, "sections": [{ "heading": "REST-Schnittstelle", "level": 2, "words": 9 }] }
    }
  ]
}
```

## 10. Suche

Die Suche über Titel und Metadaten läuft auf dem Index, nie live:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/search?sourceId=demo&title=architektur"
```

```json
{
  "sourceId": "demo",
  "origin": "cache",
  "hits": [
    { "page": { "pageId": "1001", "title": "Architektur", "spaceKey": "DEMO", "parentPageId": "1000" }, "cache": { "state": "confirmed" } }
  ],
  "nextCursor": null,
  "complete": true
}
```

## 11. Confluence-kompatible Fassade

Ein vorhandener Confluence-Client kann statt der echten Instanz `http://localhost:8080/api/compat/confluence/demo`
als Basis-URL nutzen. Lesende Endpunkte kommen aus dem Cache, mit den gespeicherten Original-Payloads; nichts geht an
Confluence weiter ([docs/CONFLUENCE_COMPAT.md](CONFLUENCE_COMPAT.md)):

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/compat/confluence/demo/rest/api/content/1001?expand=body.storage,version"
```

```json
{
  "id": "1001",
  "type": "page",
  "title": "Architektur",
  "version": { "number": 7 },
  "body": { "storage": { "representation": "storage" } }
}
```

Kinder und Seitenlisten in der Hülle von Confluence:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/compat/confluence/demo/rest/api/content/1001/child/page"
```

```json
{ "start": 0, "limit": 25, "size": 1, "results": [{ "id": "1002", "title": "Schnittstellen" }] }
```

Was der Cache nicht hält, lehnt die Fassade ab, statt es zu erfinden:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/compat/confluence/demo/rest/api/content/1001?expand=body.view"
```

```json
{
  "status": 400,
  "statusCode": 400,
  "message": "expand=body.view ist im Cache nicht gespeichert; verfügbar: ancestors,body.storage,history,history.lastUpdated,metadata.labels,space,version"
}
```

## 12. Anfragen mit den Rechten eines Endnutzers

Ein vorgelagerter Dienst kann das Confluence-Token eines Endnutzers durchreichen; dann sieht die Anfrage nur, was
dieser Endnutzer in Confluence sehen darf, begrenzt durch die Freigabe des Clients ([docs/SECURITY.md](SECURITY.md)).
`demo-client` darf das für die Quelle `demo`. Die Fake-Confluence kennt die Endnutzerin Anna mit dem Token
`anna-token-nur-lokal`; Anna darf Seite 2001 nicht sehen. Der Baum aus dem Cache ist für sie beschnitten:

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Delegated-Source: demo" -H "X-Delegated-Authorization: Bearer anna-token-nur-lokal" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/tree"
```

```json
{
  "nodeCount": 5,
  "roots": [
    { "id": "1000", "children": [
      { "id": "1001", "children": [{ "id": "1002", "children": [] }] },
      { "id": "1003", "children": [] }
    ] },
    { "id": "2000", "children": [] }
  ]
}
```

Seite 2001 antwortet für Anna wie eine unbekannte Seite, für den Client allein dagegen normal:

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Delegated-Source: demo" -H "X-Delegated-Authorization: Bearer anna-token-nur-lokal" "http://localhost:8080/api/v1/sources/demo/pages/2001/metadata"
```

```json
{ "status": 404, "type": "https://github.com/aresstack/space-harvester/problems/source-object-not-found" }
```

Ein Token, das die Quelle ablehnt, ergibt 401. Die Anfrage läuft nie stattdessen mit den Rechten des Clients weiter:

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Delegated-Source: demo" -H "X-Delegated-Authorization: Bearer falsch" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/tree"
```

```json
{ "status": 401, "type": "https://github.com/aresstack/space-harvester/problems/delegated-token-rejected" }
```

Endnutzer dürfen nur lesen; Exporte und Abgleiche bleiben technischen Clients vorbehalten:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "X-Delegated-Source: demo" -H "X-Delegated-Authorization: Bearer anna-token-nur-lokal" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/exports"
```

```json
{ "status": 403, "type": "https://github.com/aresstack/space-harvester/problems/access-denied" }
```

## 13. Lokale Dateien als zweite Quelle

Quellentypen sind austauschbar: Die Quelle `handbuch` (Typ `files`) liest `config/demo-handbuch`, und Abgleich, Cache,
Baum und Export laufen über dieselben Routen. Das Verzeichnis ist ein Space mit der Quellen-ID als Schlüssel:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/handbuch/spaces"
```

```json
{ "sourceId": "handbuch", "spaces": [{ "key": "handbuch" }] }
```

```bash
FILES_SYNC_ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/handbuch/spaces/handbuch/syncs" | jq -r .id)
```

```json
{ "sourceId": "handbuch", "spaceKey": "handbuch" }
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/syncs/$FILES_SYNC_ID"
```

```json
{ "state": "completed", "summary": { "added": 2, "complete": true, "generation": 1 } }
```

Die Seiten-ID ist der relative Pfad als Base64url (`start.md` → `c3RhcnQubWQ`); das Original ist die Datei Byte für
Byte (`GET …/pages/c3RhcnQubWQ` liefert `text/markdown`):

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/handbuch/spaces/handbuch/pages"
```

```json
{
  "origin": "cache",
  "pages": [
    { "pageId": "YmV0cmllYi9iZXJlaXRzY2hhZnQubWQ", "title": "bereitschaft.md", "spaceKey": "handbuch", "parentPageId": null },
    { "pageId": "c3RhcnQubWQ", "title": "start.md", "spaceKey": "handbuch", "parentPageId": null }
  ]
}
```

Geänderte, neue, verschobene und nicht ladbare Seiten sowie unvollständige Exporte prüft `HarvestWalkingSkeletonIntegrationTest`. Die
Demo setzt man mit `rm -rf build/demo` zurück.
