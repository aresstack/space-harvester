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
| `GET /rest/api/content?spaceKey=…&type=page` | **unterstützt** | siehe unten |
| `GET /rest/api/content/{id}/child/page` | **unterstützt** | siehe unten |
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

### `GET /rest/api/content?spaceKey=…` und `GET /rest/api/content/{id}/child/page`

Die Hülle ist die von Confluence: `results`, `start`, `limit`, `size`, `_links`. Jedes Element von `results` ist die
gespeicherte Antwort von `/rest/api/content/{id}` der Seite Byte für Byte, mit denselben Abweichungen wie die
Einzelseite; nur die Hülle schreibt der Harvester.

| Parameter | Verhalten |
|---|---|
| `spaceKey` | Pflicht für `/content`; ohne ihn 400 (eine Liste über alle Spaces gibt es nicht) |
| `type` | nur `page` (Standard); `blogpost` und andere ergeben 400 |
| `title` | nur Seiten mit genau diesem Titel (Groß-/Kleinschreibung zählt) |
| `status` | nur `current` |
| `start` | übersprungene sichtbare Seiten, Standard 0; negativ ergibt 400 |
| `limit` | Standard 25, höchstens 100; größere Werte werden wie bei Confluence auf 100 gesenkt, `limit` in der Antwort nennt den verwendeten Wert; negativ ergibt 400 |
| `expand` | wie bei der Einzelseite, für jede Seite der Antwort geprüft |
| andere Parameter | ignoriert |

- **Reihenfolge:** aufsteigend nach Content-ID als Zeichenkette (`"1000"` vor `"999"`). Confluence legt die
  Reihenfolge nicht fest; Clients, die sich auf eine bestimmte verlassen, sind nicht abgedeckt.
- **Kinder:** direkte Kinder im aktuellen Space der Elternseite. Die Elternseite muss mit Inhalt im Cache liegen und
  sichtbar sein, sonst 404.
- **`_links`:** `base` ist die Basis-URL der Fassade, `context` ihr Pfad, `next` (nur wenn weitere Seiten folgen) und
  `self` sind relativ zu `base`, wie bei Confluence.
- **Rechte:** Die Liste verlangt die `metadata`-Freigabe für den Space; Seiten ohne `content`-Freigabe fehlen ohne
  Hinweis und zählen bei `start` nicht mit, wie bei Confluence.
- **Vollständigkeit:** Ist der letzte Abgleich des Space unvollständig (`incomplete`) oder liegt eine Seite der Liste
  nur mit Kopfdaten im Cache, antwortet die Route mit 503 statt einer Liste, die vollständig aussähe. Ändert sich der
  Cache während des Lesens zweimal hintereinander, ebenfalls 503.
- **Antwortgröße:** Die Payloads einer Antwort zusammen sind auf `space-harvester.compat.max-response-bytes`
  (Standard 32 MiB) begrenzt. Ist das Budget erreicht, enthält die Antwort weniger Seiten als `limit`, `size` nennt die
  tatsächliche Zahl und `next` zeigt auf den Rest; Confluence darf ebenfalls weniger als `limit` liefern. Die erste
  Seite kommt immer mit, ihre Größe begrenzt schon der Abruf (20 MiB).
- **Stand zwischen Aufrufen:** Wie bei Confluence kann sich die Liste zwischen zwei Aufrufen mit verschiedenem
  `start` ändern; ein Stapel stammt immer aus einem Stand.

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
| 400 | `expand`, `status`, `version`, `type`, fehlender `spaceKey` oder negatives `start`/`limit`, die der Cache nicht bedienen kann |
| 401 | ohne gültiges Token (Problem Details wie überall unter `/api/**`) |
| 403 | keine Freigabe für diese Quelle bzw. diesen Space oder unbekannte Quellen-ID |
| 404 | Seite oder Space nicht im Cache, Seite nur mit Kopfdaten im Cache, nicht sichtbar oder in anderem Format gespeichert |
| 503 | Cache nicht eingerichtet oder nicht lesbar, Liste nur unvollständig lieferbar |

`message` nennt nie Inhalte der Seite.

## Geprüft

- `ConfluenceCompatApiTest`: Bytevergleich der Antwort mit der gespeicherten Confluence-Antwort für verschiedene
  `expand`-Kombinationen, Ablehnung nicht gespeicherter Erweiterungen, Fehlerkörper, 304, Rechte und 405; Hülle der
  Listen mit unveränderten Payloads, `next`-Link, Kinder, Titelfilter und Ablehnung unvollständiger Listen.
- `ContentListingTest`: `start`/`limit` über mehrere interne Stapel, Obergrenze, Titel, Kinder, Seiten ohne
  Freigabe, unvollständige Abgleiche, Seiten nur mit Kopfdaten und das Bytebudget je Antwort.
- `ApiRouteCoverageTest`: Die Fassade verlangt wie jede Route unter `/api` eine Anmeldung und gibt ohne Freigabe
  nichts heraus.
- Offen: Vergleich gegen eine echte Confluence-Data-Center-Instanz (gleiche Seite direkt und über die Fassade).
