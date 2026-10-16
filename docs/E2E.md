# Space Harvester ausprobieren: Ende-zu-Ende-Beispiele

Diese Anleitung führt die Kernoperationen der API lokal vor, ohne echte Confluence und ohne Zugangsdaten. Eine
Fake-Confluence mit festen Testdaten ersetzt die Instanz; der Dienst läuft mit der Demo-Konfiguration
[`config/application-demo.yml`](../config/application-demo.yml).

Jeder Aufruf unten ist geprüft: `DocumentedExamplesTest` liest diese Datei, führt jedes `curl` gegen den Dienst mit
derselben Konfiguration aus und vergleicht die Antwort mit dem Auszug darunter. Steht im Auszug ein `status`, muss der
HTTP-Status ihm entsprechen, sonst 200. Felder, die vom Zeitpunkt oder Port abhängen (`retrievedAt`, `url`,
`resourceId`), fehlen in den Auszügen.

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
| `DEMO` | 1001 Architektur | 1000 | Version 7, Info-Makro, eingebettetes Bild `diagramm.png` als Anhang, zwei Labels |
| `DEMO` | 1002 Schnittstellen | 1001 | Code-Makro |
| `DEMO` | 1003 Betrieb | 1000 | |
| `DEMO` | 2000 Archiv | – | zweite Wurzelseite außerhalb des Baums der Startseite |
| `DEMO` | 2001 Altes Konzept | 2000 | |
| `OPS` | 3000 Runbook | – | Space ohne Freigabe für `demo-client` |

## 1. Quellen und Spaces

Konfigurierte Quellen, ohne Verbindungsdaten:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources"
```

```json
[{ "id": "demo", "type": "confluence", "title": "Demo-Confluence" }]
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

## 5. Cache, Baum und Export

Seitenliste, Baum und native Einzelseite lesen aus dem eingebetteten Lucene-Cache. Solange er nicht eingerichtet ist
([#26](https://github.com/aresstack/space-harvester/issues/26)), antworten diese Routen ehrlich mit
`cache-unavailable`, nie mit leeren oder scheinbar frischen Daten:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/sources/demo/spaces/DEMO/tree"
```

```json
{
  "status": 503,
  "type": "https://github.com/aresstack/space-harvester/problems/cache-unavailable"
}
```

Sobald Cache, Ernte und Export angebunden sind, ergänzt dieser Abschnitt den vollständigen Ablauf: Space ernten,
Seite, Liste und Baum abfragen, erneut ernten (nur Änderungen), Export starten, Auftrag abfragen und Artefakt prüfen
([#15](https://github.com/aresstack/space-harvester/issues/15)).
