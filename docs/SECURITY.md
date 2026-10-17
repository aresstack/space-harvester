# Zugriffsschutz der API

**Stand:** 16.10.2026 · Issue [#14](https://github.com/aresstack/space-harvester/issues/14), Referenz für
Delegation [#24](https://github.com/aresstack/space-harvester/issues/24), Sicherheitsrahmen im
[Implementierungsplan](IMPLEMENTATION_PLAN.md#6-sicherheitsrahmen-vor-produktiver-ausgabe).

Zwei Grenzen bleiben getrennt:

1. **Ernte:** Wie sich der Harvester bei Confluence anmeldet (`auth`, `client-certificate` je Quelle). Was dieses
   technische Konto lesen kann, ist **keine** Freigabe für irgendeinen Aufrufer.
2. **Ausgabe:** Wer etwas aus dem Harvester lesen darf. Darum geht es hier.

## Betriebsarten

| `space-harvester.security.mode` | Verhalten |
| --- | --- |
| `clients` (Standard) | Jede Anfrage an `/api/**` verlangt `Authorization: Bearer <token>` eines konfigurierten technischen Clients (sonst 401). Was er lesen darf, bestimmt seine Korpusfreigabe (sonst 403). Ohne Clients verweigert die API jeden Datenzugriff. |
| `development` | Keine Anmeldung, alles frei. Startet **nur** mit aktivem Spring-Profil `local`; sonst bricht der Start ab. Der Dienst warnt beim Start im Log. |

Ohne Anmeldung erreichbar sind nur `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**` und die Swagger UI:
Probes, Build-Info und die API-Beschreibung, keine Daten aus Quellen.

## Technische Clients und Korpusfreigabe

```yaml
space-harvester:
  security:
    clients:
      - id: knowledge-etl
        # SHA-256 des Tokens, nicht das Token selbst:  printf %s "$TOKEN" | sha256sum
        token-sha256: ${KNOWLEDGE_ETL_TOKEN_SHA256}
        grants:
          - source: confluence
            spaces: ["DEV", "ARCH"]
            access: [metadata]
          - source: confluence
            spaces: ["*"]          # alle Spaces der Quelle, auch künftige
            access: [metadata, content, export]
```

- **Token:** Ein zufälliges Geheimnis je Client (z. B. `openssl rand -base64 32`). Konfiguriert wird nur sein
  SHA-256-Wert (64 Hex-Zeichen, optional mit Präfix `sha256:`). Jeder Client braucht ein eigenes Token.
- **`access`:** unabhängige Arten, keine schließt eine andere ein.
  - `metadata`: Existenz und Verwaltungsdaten: Quellen, Spaces, Seiten-IDs, Titel, Elternbeziehungen, Baumknoten,
    Versionen, Hashes, Suchtreffer. Schon ein Titel kann vertraulich sein.
  - `content`: native Payload (`body.storage` in der Confluence-JSON-Antwort) und alles daraus Abgeleitete:
    Enrichments, Bildbeschreibungen, Converter-Ergebnisse.
  - `export`: Exportjobs starten, abbrechen, ihren Status lesen, Artefakte herunterladen.
  - `sync`: Abgleich eines Space mit der Quelle starten, abbrechen, seinen Status lesen. Gibt keinen Lesezugriff;
    das Delta nennt Zähler, Seiten-IDs nur zusätzlich mit `metadata`.
- **`spaces`:** Space-Keys genau so, wie die Quelle sie liefert, oder allein `"*"`.
- Der Start bricht ab bei: Klartext statt Hash, doppeltem Token oder doppelter Client-ID, Freigabe für eine nicht
  konfigurierte Quelle, leerer `spaces`- oder `access`-Liste, `"*"` neben Space-Keys.

## Antworten

| Fall | Antwort |
| --- | --- |
| Kein oder unbekanntes Token | 401 `unauthenticated`, `WWW-Authenticate: Bearer` |
| Quelle oder Space nicht freigegeben, auch unbekannte Quellen-ID | 403 `access-denied`, ohne IDs oder Gründe; die Quelle wird nicht gefragt |
| Einzelne Seite in einem nicht freigegebenen Space | 404 `source-object-not-found`, genau wie für eine unbekannte Seite, damit die Antwort keine Existenz verrät |
| Listen (Quellen, Spaces) | nur die freigegebenen Einträge |

Problem Details nennen keine Tokens, Zugangsdaten oder Meldungen der Quelle. Tokens erscheinen weder in Logs noch in
Antworten; eine Anmeldung mit unbekanntem Token loggt nur Methode und Pfad.

## Für neue Endpunkte (Stränge E und G)

Controller erhalten den Aufrufer als Parameter `@Parameter(hidden = true) AccessContext caller` und prüfen
ausschließlich über `application.access.AccessGuard`:

- **Vor dem Lesen**, wenn der Umfang bekannt ist: `access.require(caller, ResourceScope.space(sourceId, spaceKey),
  AccessKind.METADATA)`; für Export `AccessKind.EXPORT`, für Rohinhalte `AccessKind.CONTENT`.
- **Listen, Bäume, Suchtreffer:** nach dem Lesen mit `access.filter(...)` beschneiden, bevor etwas die API verlässt.
  Ein Baum darf keine Knoten aus nicht freigegebenen Spaces zeigen, auch nicht als IDs.
- **Einzelne Seite, deren Space erst nach dem Lesen feststeht:** `access.requireConcealed(...)`, dann antwortet die
  API wie bei einer unbekannten Seite. Aus dem Cache ist der Space bekannt: `ResourceScope.page(sourceId,
  spaceKey, pageId)`.
- **Inhalt:** `access.canRead(caller, sourceId, spaceKey, pageId)` (Art `content`).
- Ein Cache-Treffer, eine gespeicherte Revision oder `refresh=true` ersetzt nie die Prüfung. Die Berechtigung zum
  Aktualisieren ist von der zur Ausgabe getrennt.
- Tests mit Fakes: `new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW)` bzw. `DENY`; für
  HTTP-Tests `TestClient.register(registry, "<sourceId>")` und `TestClient.authenticated(request)`.

## Durchgereichte Endnutzer-Anmeldung (#24, Teil C)

Ein vorgelagerter Dienst (Wissens- oder MCP-Dienst) kann die Anmeldung eines Endnutzers bei der Quelle durchreichen.
Die Anfrage läuft dann mit den Rechten dieses Endnutzers, begrenzt durch die Korpusfreigabe des Clients. Der
Harvester braucht dafür kein Login-UI.

```http
GET /api/v1/sources/confluence/spaces/DEV/pages HTTP/1.1
Authorization: Bearer <Token des technischen Clients>
X-Delegated-Source: confluence
X-Delegated-Authorization: Bearer <Confluence-Token des Endnutzers>
```

```yaml
space-harvester:
  security:
    clients:
      - id: assistant
        token-sha256: "sha256:…"
        grants:
          - source: confluence
            spaces: ["*"]
            access: [metadata, content]
        delegation: [confluence]      # darf für diese Quelle Endnutzer durchreichen
    delegation:                       # optional, Standardwerte
      identity-ttl: 5m                # Benutzerkennung je Token
      decision-ttl: 60s               # Sichtbarkeit je Token und Space bzw. Seite
      max-entries: 100000
```

- **Anmeldung:** Der Client meldet sich wie immer an. Ist er für die genannte Quelle zur Durchreichung freigegeben,
  fragt der Harvester die Quelle mit dem Endnutzer-Token, wer er ist (`DelegatedSourceAccess.identify`). Der
  Aufrufer ist dann `AccessContext.endUser(userId, clientId, credential)` der Art `END_USER`.
- **Rechte:** `DelegatedAccessPolicy` gibt nur frei, was die Korpusfreigabe des Clients **und** die Quelle mit dem
  Endnutzer-Token erlauben (`DelegatedSourceAccess.canSee` je Space bzw. Seite). Die Lesbarkeit für das Erntekonto
  oder ein Cache-Treffer ersetzen diese Prüfung nie. Listen und Bäume werden je Seite beschnitten, auch die
  Live-Metadatenliste eines Space. Eine nicht sichtbare Seite antwortet wie eine unbekannte.
- **Nur Lesen:** Endnutzer bekommen nur `metadata` und `content`. Exporte und andere Aufträge laufen ohne Anfrage
  weiter, können kein Endnutzer-Token tragen und bleiben technischen Clients vorbehalten.
- **Token:** gilt nur für die genannte Quelle und geht nur an sie. Es wird nicht gespeichert, nicht geloggt und nie
  als Erntezugang verwendet. Zwischengespeichert werden nur die Benutzerkennung und Entscheidungen, jeweils unter
  einem SHA-256-Fingerprint aus Quelle und Token.
- **Entzug:** Entzogene Rechte wirken spätestens nach `decision-ttl`; `0s` fragt die Quelle jedes Mal. Abgelehnte
  Tokens und unklare Antworten werden nicht zwischengespeichert.
- **Fail closed:**

  | Fall | Antwort |
  |---|---|
  | Client nicht zur Durchreichung freigegeben, Quelle fremd, ein Header fehlt | 403 `delegation-not-allowed` |
  | Quelle lehnt das Endnutzer-Token ab | 401 `delegated-token-rejected` |
  | Quelle antwortet unklar oder nicht | 503 `delegation-unavailable` |
  | Space oder Seite für den Endnutzer nicht sichtbar | wie ohne Freigabe (403 bzw. 404) |

  In keinem Fall läuft die Anfrage stattdessen mit den Rechten des Clients weiter.
- **Start:** Nennt ein Client unter `delegation` eine Quelle ohne passende `DelegatedSourceAccess`-Implementierung,
  startet der Dienst nicht.
- **Confluence:** `ConfluenceDelegatedAccess` fragt mit dem Endnutzer-Token `GET /rest/api/user/current`
  (Kennung `userKey`, sonst `username`), `GET /rest/api/content/{id}?expand=space` und `GET /rest/api/space/{key}`,
  über den Transport der Quelle mit derselben Proxy-Route und demselben mTLS wie beim Ernten (#47). Der PAT der
  Quelle wird dabei nicht gesendet. Gesendet werden nur `Authorization` und `Accept`, ohne Body, Cookies und
  Weiterleitungen, mit höchstens 10 s je Anfrage. IDs und Keys mit anderen Zeichen als Buchstaben, Ziffern, `_`,
  `-` und führendem `~` werden nicht angefragt. Ein Browser-Login am Harvester ist nicht vorgesehen (Entscheidung
  zu #24: Variante A).

## Abdeckung von #14

| Anforderung aus #14 | Umsetzung | Nachweis |
| --- | --- | --- |
| Unauthentifizierte/unberechtigte Zugriffe gesperrt | `mode=clients` Standard, jede `/api`-Route verlangt Bearer-Token | `ApiAccessTest`, `ApiRouteCoverageTest` (alle registrierten Routen, auch künftige) |
| Nicht freigegebene Quellen/Spaces nicht ausgeliefert | `CorpusReleasePolicy`, `AccessGuard` fail closed | `ApiAccessTest`, `PageQueryApiTest`, `ApiRouteCoverageTest` |
| Rohinhalt nur mit ausdrücklicher Freigabe | Art `content` getrennt von `metadata`; Einzelseite verlangt `content`, der Export künftig zusätzlich | `PageQueryApiTest` (Metadaten-Client erhält 403); Export: `ExportsApiTest` (kommt mit #49) |
| Baum, Metadaten, Enrichments, Converter gleich geschützt | Baum und Listen filtern je Seite; Enrichments/Converter zählen zu `content` | `PageQueryApiTest`, `ApiRouteCoverageTest` |
| Starten/Abbrechen von Jobs, Downloads geschützt; nur eigene Jobs | `export` + `content` je Space, Eigentümer `kind:principal`, fremder Export 404 | `ExportsApiTest` (kommt mit #49) |
| Sichere Artefaktpfade | Whitelist der Dateinamen, Normalisierung und Präfixprüfung im Dateischreiber | `FileSystemExportArtifactWriterTest` (kommt mit #43) |
| Sync-Aufträge nur mit Freigabe, nur eigene | `sync` je Space, Seiten-IDs im Delta nur zusätzlich mit `metadata`, fremder Sync 404 | Sync-API-Tests (kommt mit #54), `ApiRouteCoverageTest` |
| Grenzen für gleichzeitige Jobs | `JobLimits` (gleichzeitig, wartend, Laufzeit, Aufbewahrung); darüber 429 | Job-Tests (#39/#45) |
| Keine frei wählbaren Basis-URLs (SSRF) | Quellen nur über konfigurierte `sourceId`; keine Route nimmt URLs an | Routenliste, `ApiRouteCoverageTest` |
| Keine Secrets in Antworten/Logs, bereinigte Fehler | Problem Details mit festen Texten; Token nur als Hash; Log ohne Token | `ApiAccessTest`, `SourcesApiTest` |
| Lokaler Modus getrennt, produktiv nicht offen | `mode=development` nur mit Profil `local` | `SecurityStartupTest` |

`ApiRouteCoverageTest` ruft jede Route mit Platzhalter-IDs auf. Er beweist also nur, dass ohne Token und ohne Freigabe
nichts herauskommt; dass ein fremder, aber existierender Auftrag verborgen bleibt, prüfen die Tests der jeweiligen
Route mit einem echten Auftrag eines anderen Clients (`ExportsApiTest`, Sync-API-Tests).

## Durchsicht von main (2026-10-17)

Durchgesehen nach dem Zusammenführen aller Stränge: alle Routen unter `/api` (Seiten, Baum, Suche, Metadaten,
Anreicherungen, Confluence-Fassade, Exporte samt Downloads, Syncs), die Weboberfläche `/ui/`, Logs, Fehlerantworten,
Job-Fehler, Exporte, Lucene-Felder, Dateipfade und Anfragegrößen. Nachgeprüft nach ihrem Merge: die Anhangsliste (#9,
#76) und die Converter-Ausgaben der Exporte (#30, #68).

**Ohne Befund**

- Jede Route verlangt ein Token und prüft über den `AccessGuard`; Listen, Baum, Suche, Fassade und Live-Metadaten
  filtern je Seite, eine verweigerte Seite antwortet wie eine fehlende. Exporte und Syncs prüfen den Eigentümer, ein
  fremder Auftrag ist 404. Endnutzer bekommen nur `metadata` und `content`.
- Keine Zugangsdaten in Logs, `toString()`, Antworten, Exporten oder dem Lucene-Index; Fehlerantworten haben feste
  Texte. Das Endnutzer-Token liest nur der Quellenadapter (ArchUnit).
- Dateien: Export-IDs und Dateinamen nur nach Whitelist, Normalisierung mit Präfixprüfung, Rohdateien mit `CREATE_NEW`;
  Index- und Exportverzeichnisse nur aus der Konfiguration. Keine Route nimmt eine URL zum Abrufen an (kein SSRF).
- Größen: `limit` höchstens 100, Suchbegriffe und Filter begrenzt, Baum nach Tiefe und Knotenzahl, Antworten der Quelle
  höchstens 20 MiB (Endnutzer-Prüfung 1 MiB), Weiterleitungen nur zum selben Host.
- `/ui/`: eigene Filterkette nur für `/ui`, `/ui/**`, strenge CSP (`default-src 'self'`, `object-src 'none'`,
  `frame-ancestors 'none'`), `Referrer-Policy: no-referrer`, kein `innerHTML`, Token nur im `sessionStorage`. API-
  Antworten tragen `nosniff`, `Cache-Control: no-store` und `X-Frame-Options: DENY`.

- Anhangsliste `GET …/pages/{pageId}/attachments`: verlangt `metadata`, verdeckt wie die Einzelseiten-Metadaten (ohne
  Freigabe für alle Spaces erst nach dem Lesen mit dem Space aus der Quelle geprüft, sonst 404); die Seiten-ID prüft der
  Adapter, die Liste ist auf `MAX_ATTACHMENTS` begrenzt und gehört nachweislich zur angefragten Seite. Sie liefert nur
  Metadaten und die Adresse in der Quelle, keine Inhalte und keine Download-Freigabe.
- Converter Markdown, Word, PDF (#79): kein XXE (jsoup ohne DTD/Entitäten), kein Nachladen externer Ressourcen,
  keine Zugangsdaten; Word escaped Text und Attribute, externe Beziehungen nur für `http(s)`/`mailto`-Links, feste
  Zip-Einträge; PDF ohne JavaScript-Aktionen, Links nur `http(s)`/`mailto`.
- Lokale Dateiquelle (#77): Wurzel nur aus der Konfiguration, IDs kanonisch Base64url und normalisiert, realer Pfad muss
  in der Wurzel liegen, Symlinks führen nicht hinaus; keine Anmeldedaten; Rechte wie bei jeder Quelle über den
  `AccessGuard`.
- Converter: ein Format wird erst nach der Export-Freigabe angenommen, unbekannte ergeben 400; Dateinamen nur nach
  Whitelist, nie überschreibend (`CREATE_NEW`), Größe begrenzt (`max-converted-bytes`), Download nur für den Eigentümer
  mit `export` und `content`.

**Behoben**

| Befund | Behebung |
|---|---|
| Job-Fehler übernahmen die Meldung des Transports (interne URLs, Hosts, Proxy-Route) in Status und Job-Store | fester Text je Grund (`SOURCE_<REASON>`), `JobsTest` |
| Ein Client konnte alle Auftragsplätze belegen | `space-harvester.jobs.max-open-per-owner` (Standard 4), darüber 429 |
| Anreicherungen neu berechnen (schreibt in den Index) ging mit reiner Lesefreigabe, auch für Endnutzer | verlangt zusätzlich `sync` für den Space, `EnrichmentApiTest` |
| Fassade: unbegrenztes `start` ließ eine Anfrage den ganzen Space lesen und prüfen | `start` höchstens 10 000, darüber 400 |
| Keine Tomcat-Grenzen für Formular- und verworfene Bodies | `max-http-form-post-size` 64 KB, `max-swallow-size` 1 MB |
| Config-Server-Zugang in der URL möglich | `docs/CONFIGURATION.md`: Zugang nur über `username`/`password` |
| Markdown-Export: Backticks im Inline-Code oder in der Code-Sprache beendeten den Code früher, Seiteninhalt wurde rohes HTML; `javascript:`/`data:`-Links und eingebettete externe Bilder (laden beim Öffnen nach) | Zaun länger als jede Backtick-Folge, Sprache nur `[A-Za-z0-9_+.#-]`, Links nur `http(s)`, `mailto`, Seiten- und Anhangsverweise, externe Bilder nur als Link; `MarkdownConverterTest` |
| PDF-Export: ein langes Wort ohne Leerzeichen kostete quadratisch Rechenzeit; tiefe Verschachtelung oder sehr viele Spalten ließen den Export scheitern | jedes Zeichen nur einmal gemessen, Mindestbreite 40 pt; `PdfConverterTest` |
| Export-Downloads (künftig auch Markdown, PDF, Word mit Seiteninhalt) ohne CSP | `Content-Security-Policy: sandbox; default-src 'none'` neben `attachment` und `nosniff`, `ExportsApiTest` |
| Exporte konnten die Platte füllen; die Grenze je Client begrenzte nur gleichzeitige Aufträge | `space-harvester.export.max-total-bytes` (Standard 20 GiB) über alle Artefakte, Start sonst 507 `export-storage-full`, laufender Export `EXPORT_STORAGE_FULL`, nie abgeschnitten (`FileSystemExportArtifactWriterTest`) |

**Bewusst offen**

- `parentPageId` einer sichtbaren Seite nennt auch einen Elternteil, den ein Endnutzer in Confluence nicht sehen darf
  (nur die ID, kein Titel oder Inhalt). Confluence vererbt Leseeinschränkungen auf Unterseiten, der Fall ist daher
  selten. Der Baum blendet solche Elternteile schon aus.
- Die Fassade lädt bis zu 100 Payloads je Antwort; eine Bytegrenze je Antwort fehlt noch.
- Der Export-Dateischreiber folgt symbolischen Links im eigenen Verzeichnis; ausnutzbar nur mit Schreibrecht dort.
- PDF: Seitenbaum und Objekte wachsen im Speicher vor `max-converted-bytes` (Streams cacht PDFBox in einer
  temporären Datei); eine Grenze für Seiten oder Tabellen fehlt noch;
  sehr tiefe Verschachtelung im Storage-Format kann den Parser per `StackOverflowError` abbrechen, was nur den Auftrag
  beendet.
- Lokale Dateiquelle, in Arbeit bei Strang A (upstream enterprise-ai-client#178): Dateien in versteckten Verzeichnissen
  (`.git/…`) werden gelistet, bis 2000 MB je Datei ganz gelesen (im Harvester künftig Standard 20 MB, höchstens 64 MB),
  der absolute Serverpfad steht in den Metadaten.
- Space-Routen antworten ohne Freigabe mit 403 statt 404; die Prüfung läuft vor jeder Suche, verrät also nicht, ob der
  Space existiert.

## Noch offen

- Durchreichung: Sammelprüfung großer Listen per CQL statt je Seite; Live-Test gegen Confluence (#24). Ein
  Browser-Login am Harvester ist bewusst nicht vorgesehen (Variante A).
- Artefakte bleiben nach Ablauf der Auftragsaufbewahrung auf der Platte, sind dann aber über die API nicht mehr erreichbar; Aufräumen gehört zu #11/#12.
