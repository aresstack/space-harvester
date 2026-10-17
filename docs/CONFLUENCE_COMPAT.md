# Confluence-kompatible Read-only-Fassade (#31)

Neben der eigenen API unter `/api/v1` beantwortet der Harvester ausgewählte **lesende** Endpunkte der
Confluence-REST-API aus seinem Cache. Ein Werkzeug, das Confluence liest, kann so gegen den Cache statt gegen die
Instanz laufen.

- **Basis-URL:** `/api/compat/confluence/{sourceId}`. Ein Client konfiguriert sie statt `https://<confluence>`; der
  Pfad `/rest/api/…` folgt dahinter wie bei Confluence.
- **Bezug:** Confluence Data Center, REST API `/rest/api` (Version 1). Grundlage ist die Antwort, die der Harvester
  beim Ernten selbst abgerufen hat (ADR 0002). Ein Vergleich gegen eine echte Instanz steht noch aus; bis dahin gilt
  nur, was unten als geprüft steht.
- **Versionszusage:** Die Fassade ist nicht Teil der Versionszusage von `/api/v1`. Unterstützte Endpunkte werden
  ergänzt, aber nicht stillschweigend in ihrer Semantik geändert; jede Änderung steht in dieser Datei.

## Grundsätze

- **Nur aus dem Cache.** Nichts wird an Confluence weitergereicht, auch nicht bei einem Cache-Miss.
- **Original statt Nachbau.** Antworten sind die gespeicherten Bytes der Confluence-Antwort. Unbekannte Felder,
  Reihenfolge und Zahlenformat bleiben erhalten; nichts wird aus DTOs zurückserialisiert.
- **Nichts erfinden.** Was der Cache nicht hält (eine nicht abgerufene Erweiterung, eine ältere Version), wird mit
  400 abgelehnt, statt eine Antwort zu bauen, die Confluence so nicht gegeben hätte.
- **Gleiche Rechte wie die eigene API.** Anmeldung per `Authorization: Bearer <token>` eines technischen Clients;
  die Seite verlangt die `content`-Freigabe für ihren Space (`AccessGuard`, docs/SECURITY.md). Ohne Freigabe für die
  Seite antwortet die Fassade wie Confluence mit 404, ununterscheidbar von einer fehlenden Seite.
- **Nur lesen.** Schreibende Methoden gibt es nicht (405).

## Kompatibilitätsmatrix

| Confluence-Endpunkt | Stand | Abweichung von Confluence |
|---|---|---|
| `GET /rest/api/content/{id}` | **unterstützt** | siehe unten |
| `GET /rest/api/content?spaceKey=…&type=page` | geplant | |
| `GET /rest/api/content/{id}/child/page` | geplant | |
| `GET /rest/api/space`, `/rest/api/space/{key}` | nicht unterstützt | Spaces stehen nicht als Confluence-Antwort im Cache |
| `GET /rest/api/content/search` (CQL) | nicht unterstützt | eigene Suche: `GET /api/v1/search` |
| `GET /rest/api/content/{id}/child/attachment`, Downloads | nicht unterstützt | Anhänge folgen mit #9 |
| `GET /rest/api/content/{id}/history`, `…/version`, `…/restriction` | nicht unterstützt | |
| alle schreibenden Methoden | nicht angeboten | 405 |

Jeder hier nicht genannte Pfad unter der Basis-URL ergibt 404.

### `GET /rest/api/content/{id}`

| Parameter | Verhalten |
|---|---|
| ohne `expand` | gespeicherte Antwort |
| `expand` | gespeicherte Antwort, wenn **jeder** angefragte Pfad in der gespeicherten Abrufform enthalten ist. `a` ist durch `a.b` gedeckt (wie bei Confluence erweitert `body.storage` auch `body`). Gespeichert wird beim Ernten `ancestors,body.storage,history,history.lastUpdated,metadata.labels,space,version`. Jede andere Erweiterung, z. B. `body.view`, `body.export_view`, `children.page`, `descendants`, `restrictions`, ergibt 400 und nennt die fehlenden und die verfügbaren Pfade. |
| `status=current` | wie ohne `status` |
| `status` mit anderem Wert, `version` | 400; der Cache hält nur die aktuelle Fassung |
| andere Parameter | ignoriert |

Abweichungen, die sich aus „Original statt Nachbau“ ergeben:

- Die Antwort enthält immer alle gespeicherten Erweiterungen, auch nicht angefragte. Confluence liefert bei einem
  kleineren `expand` weniger Felder und dafür mehr Einträge in `_expandable`.
- `_links` (`base`, `self`, `webui`) zeigen auf die Confluence-Instanz, aus der die Seite stammt, nicht auf den
  Harvester.
- Benutzerbezogene Felder spiegeln den Abruf durch das Dienstkonto des Harvesters zum Erntezeitpunkt.
- Die Fassung ist die des letzten Abgleichs. `X-Cache-Generation` und `X-Cache-State` (`confirmed`, `incomplete`,
  `stale`) sagen, wie aktuell sie ist; `ETag` ist `rawSha256` der Payload, `If-None-Match` ergibt 304.

### Statuscodes und Fehlerkörper

Fehler haben die Form, die Confluence-Clients kennen:

```json
{"statusCode": 404, "data": {"authorized": true, "valid": true, "errors": [], "successful": false},
 "message": "No content found in the Space Harvester cache", "reason": "Not Found"}
```

| Status | Fall |
|---|---|
| 200 | gespeicherte Antwort |
| 304 | unverändert gegenüber `If-None-Match` |
| 400 | `expand`, `status` oder `version`, die der Cache nicht bedienen kann |
| 401 | ohne gültiges Token (Problem Details wie überall unter `/api/**`) |
| 403 | keine `content`-Freigabe für diese Quelle oder unbekannte Quellen-ID |
| 404 | Seite nicht im Cache, nur mit Kopfdaten im Cache, nicht sichtbar oder in anderem Format gespeichert |
| 503 | Cache nicht eingerichtet oder nicht lesbar |

`message` nennt nie Inhalte der Seite.

## Geprüft

- `ConfluenceCompatApiTest`: Bytevergleich der Antwort mit der gespeicherten Confluence-Antwort für verschiedene
  `expand`-Kombinationen, Ablehnung nicht gespeicherter Erweiterungen, Fehlerkörper, 304, Rechte und 405.
- `ApiRouteCoverageTest`: Die Fassade verlangt wie jede Route unter `/api` eine Anmeldung und gibt ohne Freigabe
  nichts heraus.
- Offen: Vergleich gegen eine echte Confluence-Data-Center-Instanz (gleiche Seite direkt und über die Fassade).
