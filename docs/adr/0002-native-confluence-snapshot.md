# ADR 0002: Native Confluence-Payload für Snapshots

- Status: **Teilweise entschieden** (Angelo, 16.10.2026, siehe „Entscheidungen“ am Ende): Größengrenze und
  Anhangsliste stehen fest. Die mit „prüfen“ markierten Punkte, darunter `metadata.properties`, entscheidet erst der
  Probe-Bericht ([tools/confluence-probe](../../tools/confluence-probe/README.md)) für 5–10 echte Seiten. #7 bleibt
  bis dahin offen; der native Seitenabruf (#46) ersetzt diese Prüfung nicht.
- Issue: [#7](https://github.com/aresstack/space-harvester/issues/7); Folgearbeit: Upstream-`StoredContentSource`,
  [#8](https://github.com/aresstack/space-harvester/issues/8), [#9](https://github.com/aresstack/space-harvester/issues/9),
  [#26](https://github.com/aresstack/space-harvester/issues/26).

## Kontext

Raw-First (Plan, Abschnitt 2): Der Harvester bewahrt die empfangene JSON-Antwort einer Seite byte-genau (HTTP-Body
nach Transportdekodierung) und rekonstruiert sie nie aus DTOs. Grundlage ist `body.storage`; gerenderte Ansichten
(`body.view`, `body.export_view`) sind benutzerabhängig und nicht Teil des Exports. Offen war, welche `expand`-Felder
und welche weiteren Abrufe ein möglichst vollständiger nativer Export braucht, welche Felder volatil sind und wie groß
eine Antwort werden darf.

„Vollständig“ heißt vollständig gegenüber den abgerufenen REST-Ressourcen, nicht ein Backup von Confluence.

## Entscheidung (Vorschlag für V1)

### 1. Ein Abruf je Seite für den Snapshot

```
GET /rest/api/content/{id}?expand=body.storage,version,space,ancestors,metadata.labels,history,history.lastUpdated
```

| Feld | Warum |
|---|---|
| `body.storage` | Der native Inhalt mit allen Makros, Parametern und Referenzen. |
| `version` | Revision (`number`), Zeitpunkt, Autor, Kommentar, `minorEdit`. Kommt aus **derselben Antwort** wie der Body, damit Inhalt und Revision zusammenpassen; keine zweite Anfrage für die Revision. |
| `space` | Space-Key, -ID und -Typ der Seite zum Zeitpunkt des Abrufs. |
| `ancestors` | Vollständige Elternkette; der Baum (#27) entsteht trotzdem aus `parentId` des Listings. |
| `metadata.labels` | Labels; dieselben Werte wie im `attributesHash` (`page-attributes/v1`). |
| `history`, `history.lastUpdated` | Erstellung (`createdDate`, `createdBy`) und letzte Änderung. |

Bewusst **nicht** im Snapshot:

- `body.view`, `body.export_view`, `body.styled_view`, `body.editor`: gerendert, benutzerabhängig, nicht nativ.
- `metadata.currentuser`: je abrufendem Benutzer verschieden, macht Snapshots verschiedener Konten unvergleichbar.
- `restrictions.*`: Rechte gehören zur Zugriffskontrolle (#14/#24) und werden getrennt von der Version geprüft
  (Plan, Abschnitt 5).
- `children`, `descendants`, `container`: Baum und Space kommen aus dem Listing bzw. aus `space`.
- `metadata.properties` (Content-Properties, oft Plugin-Daten): **nach der Probe entscheiden**, nicht blind in den
  Abruf aufnehmen. Sind sie für unsere Seiten relevant, werden sie möglichst nativ erhalten, bei Bedarf als
  getrennte Ressource (Abschnitt 2).

Die verwendete `expand`-Liste wird mit dem Snapshot gespeichert (z. B. als Teil von `nativeFormat`), sodass eine
spätere Änderung der Liste als anderes Snapshot-Schema erkennbar ist und alte Payloads nicht stillschweigend als
gleichwertig gelten.

### 2. Zusatzabrufe als eigene native Ressourcen

Was die Seitenantwort nicht enthält, wird – wenn überhaupt – als **eigene** Antwort mit eigenem Hash gespeichert und
nie in die Seiten-Payload hineingemischt.

| Lücke | Abruf | V1 |
|---|---|---|
| Anhänge, auch die nicht im Storage referenzierten | `/rest/api/content/{id}/child/attachment?expand=version,container,metadata.mediaType` (paginiert) | **Ja, entschieden:** vollständige Anhangsliste als eigene native Metadatenressource je Seite über #9; keine Binärdateien (Download später #19). |
| Kommentare, auch Inline-Kommentare (`ac:inline-comment-marker` verweist nur darauf) | `/rest/api/content/{id}/child/comment?expand=body.storage,version&depth=all` | nein; als bekannte Lücke dokumentieren. |
| Mehr Labels als das eingebettete `limit` | `/rest/api/content/{id}/label` (paginiert) | Kürzung erkennen (`_links.next` in `metadata.labels`) und nachladen oder den Snapshot als unvollständig kennzeichnen, nie stillschweigend. |
| Ältere Versionen einer Seite | `/rest/api/content/{id}/history`, `?status=historical&version=n` | nein. |
| Blogposts | `type=blogpost` | nein; V1 erntet Seiten (`type=page`) wie das Listing. |
| Inhalte, die Makros einbinden (Include, Excerpt-Include, Jira) | – | nein; nur die Referenz im Storage bleibt erhalten. |

### 3. Volatile Felder und was der Inhalts-Hash bedeutet

Dieselbe Revision liefert nicht garantiert dieselben Bytes:

- `_links.base`, `_links.self` und alle absoluten URLs hängen vom angefragten Hostnamen ab (Alias, Load-Balancer).
- Benutzerobjekte (`version.by`, `history.createdBy`) ändern sich mit Anzeigename, Profilbild
  (`profilePicture.path`) oder Deaktivierung des Kontos.
- `_expandable` hängt von der `expand`-Liste ab.
- Die Reihenfolge der JSON-Schlüssel ist in der Praxis stabil, aber nicht Teil des API-Vertrags.

Folge: `rawSha256` (#8) beweist, dass ein gespeicherter Snapshot unverändert ist; er ist **kein**
Änderungsdetektor. Änderungen erkennt die Synchronisierung (#16) weiter über `version.number`, `metadataHash` und
`attributesHash`. Einen zweiten, über normalisierte Felder gebildeten „Inhalts-Hash“ führen wir in V1 nicht ein
(prüfen, ob die Probe stabile Bytes für dieselbe Revision zeigt).

### 4. Größengrenze

**Entschieden:** 20 MiB als konfigurierbarer Standard je Quelle (`options.maxResponseBytes`). Der Upstream-Transport
liest höchstens so viel und meldet größere Antworten als `ResponseTooLargeException` (`INVALID_RESPONSE`, im
Harvester `SourceAccessException` mit demselben Grund). Eine zu große Seite wird **nie gekürzt** gespeichert, sondern
als fehlgeschlagen geführt; der Lauf gilt dann nicht als vollständig (#16). Die tatsächliche Größenverteilung zeigt
die Probe.

### 5. Umsetzung upstream

Optionale Fähigkeit `StoredContentSource` in `source-api` (Plan, M2), neben `load()` und `PartitionListingSource`:
liefert für eine lokale ID die Antwort-Bytes, Revision (`version.number` aus denselben Bytes), `retrievedAt`,
`nativeFormat` (inklusive `expand`-Liste) und `sha256`. Sie nutzt den vorhandenen Transport (`maxResponseBytes`,
Anmeldung, mTLS/Proxy aus #33/#34) und **keinen zweiten REST-Client**. Auth-Header, Cookies und Secrets stehen nie in
der Payload; die Payload ist der Antwortkörper allein.

### 6. Quellenneutraler Vertrag im Harvester (Vertragsfenster 1)

Paket `application.snapshot`, frei von Lucene-, Spring- und Confluence-Typen, damit Persistenz (#26), Synchronisierung
(#16) und Abfragen (#27) parallel dagegen bauen:

- `PageKey(sourceId, pageId)`: Identität ohne Space, weil die Seiten-ID ein Verschieben überdauert.
- `NativePayload`: Bytes (immer als Kopie), `mediaType`, `nativeFormat`, `rawSha256` = `sha256:<hex>` über die Bytes.
- `PageSnapshot(key, metadata, metadataHash, attributesHash, payload, retrievedAt)`: Kopfdaten (`PageMetadata`) und
  Payload aus derselben Antwort, Hashes nach `page-metadata/v1` und `page-attributes/v1` wie in der Metadatenliste.
- `PageHeader`: die aktuellen Kopfdaten getrennt von der Payload. Die Synchronisierung aktualisiert sie ohne
  Body-Abruf, wenn sich nur Labels/Attribute oder Titel, Space und Elternseite bei gleicher Revision ändern; die
  Kopfdaten des Snapshots bleiben dabei die seiner Payload.
- `SnapshotRepository`: `save` (Snapshot und Kopfdaten), `saveHeader` (nur Kopfdaten), `find`, `findHeader`,
  `remove`. `remove` nur, wenn ein bestätigt vollständiger Abgleich die Seite vermisst und eine Einzelabfrage sie als
  nicht vorhanden oder nicht sichtbar bestätigt; ein Umzug in einen anderen Space ist kein Entfernen. Abfrage-Ports für
  Seitenlisten, Baum und Suche kommen mit #27/#28 dazu.
- Vertragstest `SnapshotRepositoryContract` (Testquellen): jede Umsetzung erbt ihn; er prüft Bytegleichheit auch für
  Bytes, die kein UTF-8 sind, Upsert, getrennte Kopfdaten, Trennung je Quelle, Entfernen und Neustart. `InMemorySnapshotRepository` dient
  anderen Strängen als Fake.

Die Anbindung an `StoredContentSource` (nach Sync der Upstream-Kopie) und an die Quellen-SPI folgt in #8.

## Bestandsaufnahme Makros und Referenzen

Wird aus dem Probe-Bericht übernommen. Vorab bekannte Element- und Referenzarten im Storage-Format, die #9 und die
nachgelagerte Wissensverarbeitung berücksichtigen müssen: `ac:structured-macro` (mit `ac:parameter`,
`ac:rich-text-body`, `ac:plain-text-body`), `ac:image`, `ac:link`, `ac:emoticon`, `ac:task-list`, `ac:layout`,
`ac:placeholder`, `ac:inline-comment-marker`, `ac:adf-extension`; Referenzen `ri:page`, `ri:blog-post`,
`ri:attachment`, `ri:url`, `ri:user`, `ri:space`, `ri:content-entity`, `ri:shortcut`.

## Sicherheit

Die native Payload enthält Makroparameter, eingebettete Inhalte und Benutzerkennungen, die in der normalen Ansicht
nicht sichtbar sind. Snapshots sind daher schutzbedürftig wie die Seite selbst (#14, #24) und erscheinen weder in
Logs noch in Fehlermeldungen.

## Entscheidungen (Angelo, 16.10.2026)

| Frage | Entscheidung |
|---|---|
| 20-MiB-Grenze | Ja, als konfigurierbarer Standard je Quelle. Größere Antworten nie abschneiden, sondern als unvollständig bzw. fehlgeschlagen behandeln (Abschnitt 4). |
| `metadata.properties` | Nach der echten Probe entscheiden, nicht blind in `expand` aufnehmen. Wenn relevant, möglichst nativ erhalten, bei Bedarf als getrennte Ressource. |
| Anhangsliste | Ja, in V1 als separate Metadatenressource über #9, auch nicht eingebettete Anhänge, ohne Binärdateien. |

Offen bis zum Probe-Bericht (5–10 echte Seiten): Bestandsaufnahme der Makros und Referenzen, gekürzte Labels,
fehlende REST-Daten, Reproduzierbarkeit (Abschnitt 3) und `metadata.properties`. Danach wird dieser ADR auf
„Entschieden“ gesetzt und #7 geschlossen.
