# Confluence-kompatible Read-only-Fassade (#31)

Neben der eigenen API unter `/api/v1` beantwortet der Harvester ausgewählte **lesende** Endpunkte der
Confluence-REST-API aus seinem Cache. Ein Werkzeug, das Confluence liest, kann so gegen den Cache statt gegen die
Instanz laufen.

- **Basis-URL:** `/api/compat/confluence/{sourceId}`. Ein Client konfiguriert sie statt `https://<confluence>`; der
  Pfad `/rest/api/…` folgt dahinter wie bei Confluence.
- **Bezug:** Confluence Data Center, REST API `/rest/api` (Version 1). Grundlage ist die Antwort, die der Harvester
  beim Ernten selbst abgerufen hat (ADR 0002).
- **Referenzversion: Confluence Data Center 10.2 LTS** (Entscheidung Angelo, 17.10.2026, #31), OpenAPI-Referenz
  [v1020](https://developer.atlassian.com/server/confluence/rest/v1020/). Sie ist fest hinterlegt und wird nicht
  automatisch durch eine neuere ersetzt; die Version der produktiven Instanz kommt später als zweiter Satz dazu.
- **Stand:** kompatibel für die dokumentierte Teilmenge, gegen die Atlassian-Referenz geprüft, **noch nicht live
  gegen eine echte Instanz verifiziert**. Die Gesamtgrößengrenze der Listen gehört zu #90 und ist hier nicht gelöst.
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
| `GET /rest/api/content/{id}/child/page` | **unterstützt** | siehe unten; andere `child/{type}` (`comment`, `attachment`) nicht |
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
| `status=current` | wie ohne `status` (auch mehrfach angegeben) |
| `status` mit anderem Wert, `version` | 400; der Cache hält nur die aktuelle Fassung |
| `xoauth_requestor_id` | ignoriert; es gelten immer die Rechte des angemeldeten Clients |
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
| `ids`, `postingDay` | 400; ignoriert ergäben sie eine Liste, die nicht filtert, was der Client verlangt |
| `parentVersion` (nur `child/page`) | nur `0` (Standard, aktuelle Fassung); jeder andere Wert ergibt 400 |
| `xoauth_requestor_id` | ignoriert, wie bei der Einzelseite |
| andere Parameter | ignoriert |

- **Reihenfolge:** aufsteigend nach Content-ID als Zeichenkette (`"1000"` vor `"999"`). Confluence legt die
  Reihenfolge nicht fest; Clients, die sich auf eine bestimmte verlassen, sind nicht abgedeckt.
- **Kinder:** direkte Kinder im aktuellen Space der Elternseite. Die Elternseite muss mit Inhalt im Cache liegen und
  sichtbar sein, sonst 404.
- **`_links`:** `base` ist die Basis-URL der Fassade, `context` ihr Pfad; `next` (nur wenn weitere Seiten folgen),
  `prev` (nur ab `start` > 0, mit `start` − `limit`, mindestens 0) und `self` sind relativ zu `base`, wie bei
  Confluence. Die Beispiele der Referenz zeigen absolute Links; echte Instanzen liefern relative.
- **`totalCount`:** fehlt. Die Referenz führt das Feld optional; es zu füllen hieße, jede sichtbare Seite des Space auf
  Rechte zu prüfen.
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
{"statusCode": 404, "data": {"authorized": true, "valid": true, "allowedInReadOnlyMode": true, "errors": [],
 "successful": false}, "message": "No content found in the Space Harvester cache", "reason": "Not Found"}
```

Die Referenz beschreibt `data.errors` als Objekt; Confluence DC liefert eine Liste, und so auch die Fassade. Die
Referenz dokumentiert nur 200 und 404; 304, 400, 401, 403 und 503 sind Ergänzungen der Fassade.

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

- `ConfluenceCompatContractTest` gegen den Auszug der Referenz 10.2.0 (siehe unten): Jeder dokumentierte
  Query-Parameter der drei Endpunkte ist eingeordnet (unterstützt, abgelehnt, ignoriert) und verhält sich so; ein
  Parameter, den die Referenz neu aufnimmt, lässt den Test scheitern. Einzelseite, Listenhülle und Fehlerkörper
  passen zu den Schemas `Content`, Listenantwort und `RestError`; `limit` hat den dokumentierten Standard;
  `start`, `limit`, `next`, `prev` und `self` blättern ohne Lücken und Dopplungen durch Liste und Kinder.
- `ConfluenceCompatApiTest`: Bytevergleich der Antwort mit der gespeicherten Confluence-Antwort für verschiedene
  `expand`-Kombinationen, Ablehnung nicht gespeicherter Erweiterungen, Fehlerkörper, 304, Rechte und 405; Hülle der
  Listen mit unveränderten Payloads, `next`-Link, Kinder, Titelfilter und Ablehnung unvollständiger Listen.
- `ContentListingTest`: `start`/`limit` über mehrere interne Stapel, Obergrenze, Titel, Kinder, Seiten ohne
  Freigabe, unvollständige Abgleiche, Seiten nur mit Kopfdaten und das Bytebudget je Antwort.
- `ApiRouteCoverageTest`: Die Fassade verlangt wie jede Route unter `/api` eine Anmeldung und gibt ohne Freigabe
  nichts heraus.
- Offen: Vergleich gegen eine echte Confluence-Data-Center-Instanz (Abschnitt „Live-Vergleich“); #31 bleibt bis zu
  Angelos Lauf offen.

## Vertragstests gegen die Atlassian-Referenz

Atlassian veröffentlicht je Data-Center-Version eine OpenAPI-Referenz. Ein Auszug daraus (GET-Operationen der drei
Endpunkte und alle über `$ref` erreichbaren Schemas, ohne Beschreibungstexte) liegt versioniert im Repository:

- `space-harvester-server/src/test/resources/confluence-contract/version.txt` nennt die Version, gegen die der Build testet (`10.2.0`).
- `space-harvester-server/src/test/resources/confluence-contract/<version>/openapi-extract.json` ist der Auszug dieser Version, erzeugt mit
  `tools/confluence-contract/extract.py`.

Der Build hängt nicht von der Atlassian-Seite ab. Der Workflow `.github/workflows/confluence-contract.yml` (wöchentlich,
von Hand und bei Änderungen an Auszug oder Werkzeug) meldet, ob die Referenz erreichbar ist, und vergleicht jeden
Auszug mit der veröffentlichten Fassung; ändert Atlassian die Referenz, wird er rot, ohne den Auszug zu ersetzen.

In der IDE: `ConfluenceCompatContractTest` wie jeden anderen Test starten, auf der Kommandozeile
`./gradlew :test --tests '*ConfluenceCompatContractTest'`.

Weitere Version (z. B. die der produktiven Instanz) hinzunehmen, ohne Code zu ändern:

```bash
v=10.2.0   # Version wie in der Atlassian-URL
curl -sSf -o /tmp/confluence-$v.json https://dac-static.atlassian.com/server/confluence/$v.swagger.v3.json
mkdir -p space-harvester-server/src/test/resources/confluence-contract/$v
python3 tools/confluence-contract/extract.py /tmp/confluence-$v.json \
    > space-harvester-server/src/test/resources/confluence-contract/$v/openapi-extract.json
```

Der Workflow vergleicht dann beide Auszüge. Gegen welche Version der Test läuft, bestimmt `version.txt`; der Wechsel
ist eine reine Fixture-Änderung (Version in `version.txt` und `theReferenceVersionIsPinned`).

## Live-Vergleich gegen die eigene Instanz

Die Referenz beschreibt die API, nicht jede Instanz; Atlassian weist selbst darauf hin, dass dokumentierte
Antworten von echten abweichen können. Der Live-Vergleich mit
[`tools/confluence-compat-check/check.py`](../tools/confluence-compat-check/README.md) stellt dieselben Anfragen an die
eigene Confluence-Instanz und an die Fassade und listet die Unterschiede. Er läuft im Unternehmensnetz, nach dem
Live-Test von PAT, mTLS und PAC (#33/#34, [MTLS.md](MTLS.md)) und dem Probe-Bericht (#7).

1. **Harvester vorbereiten.** Einen Space mit dem Harvester vollständig abgleichen
   (`POST /api/v1/sources/{sourceId}/spaces/{key}/syncs`, Status `completed`). Für den Vergleich einen technischen
   Client mit `metadata`- und `content`-Freigabe für diesen Space verwenden.
2. **Seiten wählen.** Zwei bis fünf Seiten aus diesem Space (darunter eine mit Kindern), eine Content-ID, die es nicht
   gibt (Standard `999999999999`), und, wenn vorhanden, eine Seite mit Leseeinschränkung, die das Konto des PAT nicht
   sehen darf.
3. **Zugang setzen,** nur über Umgebungsvariablen:

   | Variable | Bedeutung |
   |---|---|
   | `CONFLUENCE_BASE` | Basis-URL mit Kontextpfad, z. B. `https://confluence.example.org/confluence` |
   | `CONFLUENCE_PAT` | Personal Access Token (alternativ `CONFLUENCE_USER` und `CONFLUENCE_PASSWORD`) |
   | `CONFLUENCE_CLIENT_CERT`, `CONFLUENCE_CLIENT_KEY`, `CONFLUENCE_CLIENT_KEY_PASSWORD` | Clientzertifikat für mTLS als PEM; aus PKCS#12 mit `openssl pkcs12 -in client.p12 -out client.pem` (Zertifikat und Schlüssel in einer Datei, dann nur `CONFLUENCE_CLIENT_CERT`). Ein Zertifikat aus dem Windows-Speicher vorher als PFX exportieren. |
   | `CONFLUENCE_CA_FILE` | eigene CA als PEM, falls die Instanz kein öffentlich vertrauenswürdiges Zertifikat hat |
   | `CONFLUENCE_PROXY` | Proxy als URL, z. B. `http://proxy.example.org:8080`. PAC wertet das Skript nicht aus: den Proxy nehmen, den die PAC-Datei für die Confluence-Adresse nennt (`space-harvester` loggt ihn auf DEBUG, MTLS.md). Ohne Variable geht die Verbindung direkt. |
   | `HARVESTER_BASE` | Basis-URL der Fassade, z. B. `http://localhost:8080/api/compat/confluence/confluence` |
   | `HARVESTER_TOKEN` | Bearer-Token des technischen Clients |

4. **Ausführen:**

   ```bash
   python3 tools/confluence-compat-check/check.py --page 123456 --page 234567 --space DEV --parent 123456 \
       --restricted-page 345678 > vergleich.md
   ```

   Optional `--title "<exakter Titel>"` für den Titelfilter, `--page-size` (Standard 25) und `--max-pages`
   (Standard 500 je Liste).

5. **Lesen.** Der Bericht trennt erwartete Abweichungen (dieses Dokument: `_links` und `_expandable`, zusätzliche
   gespeicherte Erweiterungen, Reihenfolge der Listen, 400 für nicht gespeicherte Erweiterungen) von Zeilen mit
   `UNERWARTET`. Er nennt Feldpfade, Statuscodes, Anzahlen und Content-IDs, keine Feldwerte, Titel, Texte oder
   Zugangsdaten. Typische unerwartete Zeilen und ihre Ursache:
   - `version.number` verschieden: Die Seite wurde seit dem Abgleich geändert; neu abgleichen und wiederholen.
   - Seiten nur bei Confluence: nicht geerntet, ohne Freigabe für den Client oder seit dem Abgleich neu.
   - Benutzerbezogene Felder verschieden: Die Fassade spiegelt das Dienstkonto zum Erntezeitpunkt.
   - Eingeschränkte Seite in der Fassade sichtbar: Die Fassade prüft die Freigaben des Harvesters, nicht die
     Confluence-Einschränkungen des aufrufenden Kontos (SECURITY.md); Freigaben des Clients prüfen.
   - Abweichende Fehlerkörper: Form von `data` oder `reason`; hier nachtragen.

6. **Ergebnis melden.** Den Bericht (nach kurzer Durchsicht) in #31 anhängen. Was sich als echte Abweichung
   herausstellt, wird in diesem Dokument nachgetragen oder in der Fassade behoben.
