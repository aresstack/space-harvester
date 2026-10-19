# Quellenadapter

Space Harvester spricht Wissensquellen nur über den quellenneutralen Port
`application.sources.HarvestSource` an. Umgesetzt wird er im Paket `source` über die Adapter aus
`enterprise-ai-sources`, einer unveränderten Kopie aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client) (#2, PR #23). Einen zweiten
Confluence-Client gibt es nicht.

## Auswahl

`ConfiguredSourceCatalog` baut aus `space-harvester.sources[*]` je Eintrag eine Quelle. `type` wählt den Adapter
(`ConfiguredSourceCatalog.TYPES`, heute `confluence` über `ConfluenceSourceProvider`, `files` über
`LocalFilesSourceProvider` und `mediawiki` über `MediaWikiSourceProvider`, dazu `git` über `GitSourceAdapter` mit
den Kopien der Repositorys unter `<dataDirectory>/git`, im Dienst unter `space-harvester.cache.directory`/git und nur
mit diesem Verzeichnis, sowie `ftp`, `jes`, `ndv` und `sharepoint` über `EacSourceAdapters`; weitere Typen hängt ein einbettendes Programm mit `Harvester.builder().sourceAdapter` ein). Unbekannte Typen,
fehlerhafte Optionen, Zertifikate oder Proxy-Einstellungen verhindern den Start, und alle Fehler werden gemeinsam
gemeldet. Konfiguration siehe README, „Quellen konfigurieren“.

## Vertrag

| Methode von `HarvestSource` | Upstream-Fähigkeit | Zweck |
|---|---|---|
| `partitions()` | `KnowledgeSourcePort.partitions()` | alle sichtbaren Spaces |
| `pageMetadata(space, cursor, limit)` | `PartitionListingSource` | alle Seiten eines Space ohne Inhalt, mit Cursor (#5) |
| `pageMetadata(pageId)` | `ResourceMetadataSource` | eine Seite ohne Inhalt, unabhängig vom Space (#6) |
| `storedPage(pageId)` | `StoredContentSource` | native Antwort Byte für Byte mit Kopfdaten aus derselben Antwort (#8) |
| `attachments(pageId)` | `AttachmentListSource` | alle Anhänge einer Seite als Metadaten, auch nicht eingebettete, mit allen Listenantworten Byte für Byte; nie Dateibytes (#9) |
| `attachmentPreview(attachment, preview, maxBytes)` | `PageViewSource#loadAttachmentPreview` | Vorschau oder PDF-Konvertierung eines Anhangs durch die Quelle, Fähigkeit `ATTACHMENT_PREVIEWS` (#183) |

- Fehlt einer Quelle eine Fähigkeit, antwortet die Methode mit `SourceAccessException` `UNSUPPORTED`, nie mit
  einem leeren Ergebnis. `storedPage` und `attachments` sind deshalb Default-Methoden.
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
- `SourceQueries.attachments`: Anhangsliste live für die API (#9)
- `snapshot.SourceSnapshots`: `PageSnapshot` aus `storedPage`, zugleich `PageContentFetcher` der
  Synchronisierung (#16)

## Quellen ohne Partitionen (#187)

Die meisten Quellen des Enterprise AI Client (MediaWiki, Git, FTP/JES, NDV, Outlook, SharePoint, BetaView) haben
keine `PartitionListingSource`, sondern nur `KnowledgeSourcePort.discover`/`load`. Der Core liest sie wie die
Indexierung dort (`IndexKnowledgeUseCase.indexSource`: ein `discover` im Umfang der Quelle, dann `load` je Ressource):

- **Umfang:** `SourceAdapter.OpenedSource.discovering(port, scope)`; `KnowledgeSourceProviderAdapter` öffnet einen
  Upstream-`KnowledgeSourceProvider` wie `KnowledgeSourceManagement.open` (`validate`, Adresse aus `locator` und
  `KnowledgeSourceType.addressFor`, `open`, `scope`). Einstellungen stehen unter `options` mit den Schlüsseln des
  Upstream-Typs, `base-url` gibt es auf diesem Weg nicht. Ein Typ mit Netz (`KnowledgeSourceProviderAdapter.connected`)
  baut seinen Provider je Quelle mit Route, TLS und Zugangsdaten des Katalogs (siehe MediaWiki); ohne kennt der Typ
  weder Anmeldung noch Zertifikate. Hat der Port doch eine `PartitionListingSource`, gilt diese, der Umfang wird nicht
  benutzt.
- **Partition:** genau eine je Quelle, Schlüssel = Quellen-ID, Name = Titel der Quelle, ohne Startseite.
  Bereiche des Ports (`partitions()`) gibt es auf diesem Weg nicht.
- **Seiten-ID:** die Ressourcen-ID als Base64url ohne Auffüllung (`wiki:wiki.intern/w/Start` → `d2lraTp3aWtp…`),
  zurückgelesen nur in genau dieser Kodierung, sonst `UNSUPPORTED`; `resourceId` bleibt die Ressourcen-ID. Eltern
  (`parentId`) werden ebenso kodiert, daraus entsteht der Seitenbaum im Cache.
- **Seitenliste** (`pageMetadata(partition, cursor, limit)`): der erste Stapel ruft immer `discover` auf, Folgestapel
  lesen aus derselben Discovery (Cursor `d1.<Versatz>.<Fingerabdruck>`). Liefert die Quelle für einen Folgestapel einen
  anderen Stand, endet die Listung mit `UNAVAILABLE` statt zwei Stände zu mischen. Höchstzahl und Tiefe bestimmt der
  Umfang der Quelle (`SourceScope.maxResources`, `maxDepth`).
- **Einzelseite und Inhalt** (`pageMetadata(pageId)`, `storedPage`): über `load`. Abgelegt wird der Lesetext des
  Adapters (`KnowledgeDocument.text()`) als UTF-8, Medientyp `text/markdown`, Format `knowledge-document`
  (`NativePayload.DOCUMENT_TEXT`); Kopfdaten aus demselben Laden. Eine Weiterleitung (Wiki-Redirect) ist für die
  angefragte Seite `NOT_FOUND`, wie im Enterprise AI Client gilt sie nur unter ihrem Ziel. `NOT_FOUND` bestätigt keine
  Löschung (`confirmsDeletion` bleibt `false`).
- **Suche:** Volltext über den Cache (#180) mit `KnowledgeDocumentTextProjector` (`knowledge-document-text/v1`,
  Text unverändert), ebenso für Anreicherungen und Embeddings; eine Quelle mit `SearchableKnowledgeSource` sucht
  zusätzlich selbst, mit denselben Seiten-IDs. Titelsuche ohne eigene Suche über die Discovery; Adressen sind
  Ressourcen-IDs und Bookmarks der Quelle (`<schema>:<locator>/…`, `<schema>://<locator>/…`).
- **Export:** wie jede Partition; Markdown, Word und PDF lesen den Lesetext als Blöcke (Überschriften, Absätze,
  Listen, Codeblöcke), statt auf fehlendes Storage-Format hinzuweisen.
- **Fehler:** nur die Art (`Quelle: NOT_FOUND` …), ohne die Meldung des Adapters, die Seitentitel, Pfade oder Jobnamen
  nennen kann.

Die Rechte je Seite gelten wie bei jeder Quelle über die `AccessPolicy`; nicht lesbare Seiten fehlen in Listen,
Suche und Export.

**Freigegebene Ordner** für `type: files`: `new LocalFilesSourceAdapter(() -> new LocalFileGrants(allowAll, roots))`,
eingehängt über `Harvester.builder().sourceAdapter`, wie `LocalFileAccessPolicy`/`FileAccessConfig` im Enterprise AI
Client. Das Verzeichnis der Quelle ist immer freigegeben; ein Link hinaus führt nur in einen freigegebenen Ordner,
sonst `ACCESS_DENIED`. Gelesen wird die Freigabe bei jedem Zugriff. Ohne Angabe gilt wie bisher nur das Verzeichnis.

### MediaWiki (`type: mediawiki`)

`MediaWikiSourceProvider` aus `source-mediawiki` (unveränderte Kopie, `enterprise-ai-sources`), registriert über
`MediaWikiSources.adapter()` (`KnowledgeSourceProviderAdapter.connected`) im Core (`ConfiguredSourceCatalog`) und als
Bean in `SourceConfiguration` des Dienstes; verdrahtet wie `AdapterAssembly.sourceProviders` im Enterprise AI Client.
Seiten ab den Startpunkten per Breitensuche über die Wiki-Links (`discover`), Inhalt aus `action=parse` als Klartext
mit Markdown-Überschriften (`load`), Revision aus `touched` und `lastrevid`, Weiterleitungen aufgelöst. Ressourcen-ID
`wiki:<host[:port]/pfad>/<Titel>`, Quellenadresse `wiki://<host[:port]/pfad>`.

```yaml
space-harvester:
  sources:
    - id: intranet-wiki
      type: mediawiki
      title: Intranet-Wiki            # wird displayName
      auth:                           # weglassen = anonym
        type: basic                   # Benutzer und Passwort, etwa ein Bot-Passwort
        username: harvester-bot
        password: ${WIKI_BOT_PASSWORD}
      trust-bundle: wiki-ca           # optional, wie bei confluence
      client-certificate:             # optional (mTLS), wie bei confluence
        enabled: true
        type: pkcs12
        path: /etc/space-harvester/wiki-client.p12
        alias: harvester
        password: ${WIKI_CERT_PASSWORD}
      options:
        apiUrl: https://wiki.intern.example/w/api.php   # Pflicht; Basis-URL oder api.php
        startPoints: Hauptseite,Handbuch                # Pflicht; kommagetrennte Seitentitel
        maxDepth: "2"                                   # Linkebenen ab den Startpunkten, Standard 1
        maxResources: "500"                             # Höchstzahl Seiten je Lauf, Standard 500
        linkNamespaces: "0"                             # verfolgte Namensräume, Standard 0
        requiresLogin: "true"                           # Standard: true mit auth basic
        connectTimeoutMillis: "15000"
        readTimeoutMillis: "30000"
        userAgent: …                                    # Standard: User-Agent des Dienstes
```

- **Anmeldung** wie im Enterprise AI Client: `auth.type basic` setzt `credentialRef` auf `source:<id>`; der Adapter
  fordert Benutzer und Passwort erst beim Login über `SecretBackedMediaWikiCredentialsProvider` an (`action=login`,
  sonst `clientlogin`), löscht sie danach und meldet sich bei abgelaufener Sitzung genau einmal neu an. Fehlt das
  Passwort, kommt es vom `SourceSecrets` des Programms (#188, `Harvester.builder().secretProvider`). `pat` gibt es für
  MediaWiki nicht; `options.credentialRef` und `options.displayName` sind gesperrt (dafür `auth` und `title`). Falsche
  Zugangsdaten lassen den Abgleich mit `ACCESS_DENIED` scheitern.
- **Netz:** HTTP macht der Upstream-Adapter selbst, synchron über `HttpURLConnection` mit eigenem Cookie-Speicher je
  Quelle, wie im Enterprise AI Client. Jede Verbindung bekommt die Route des Katalogs (`ProxyRoutes`, PAC je Ziel-URL);
  eine nicht bestimmbare Route (PAC fehlt oder scheitert) bricht die Anfrage mit `UNAVAILABLE` ab, nie still DIRECT.
  TLS je Quelle: Server-Vertrauen aus `trust-bundle` (sonst `space-harvester.network.tls`), Client-Zertifikat aus
  `client-certificate` mit erzwungenem Alias (`ClientCertificates` der Kopie, dieselben Felder und Prüfungen wie bei
  `confluence`); ein unbrauchbares Zertifikat verhindert den Start. Die Anmeldung am Proxy (#189) gilt wie für alle
  `HttpURLConnection`-Wege prozessweit (`ProxyAuthenticator`). Anders als bei `confluence` bekommt jeder Host, zu dem
  das Wiki weiterleitet, dasselbe Zertifikat angeboten; Weiterleitungen von HTTPS auf HTTP und Formulare an einen
  anderen Origin lehnt der Adapter ab.
- **Nicht asynchron:** Einen WebClient-Transport wie `WebClientConfluenceTransport` kann der MediaWiki-Adapter nicht
  aufnehmen, seine Transport-Naht (`MediaWikiTransport`) ist paketintern. Das braucht eine öffentliche Naht im
  Upstream.
- **Rechte:** Seiten, die das Konto des Wikis nicht lesen darf (`permissiondenied`, `readapidenied`), liefert `load`
  als `ACCESS_DENIED`; findet die Discovery sie über einen Link, fehlen sie in Katalog, Suche und Export, der Lauf
  bestätigt den Stand ohne sie und endet mit `SYNC_PARTIAL`. Die `AccessPolicy` des Aufrufers gilt zusätzlich.
- **Protokoll:** Der Crawler der Kopie meldet übersprungene Seiten und Linkfehler mit Seitentitel; sein
  `java.util.logging`-Logger steht deshalb auf `SEVERE`. Der Login meldet nur die Site (`host/pfad`), nie Benutzer,
  Passwort, Token oder Cookies.
- **Suche:** `SearchableKnowledgeSource` (Wiki-Suche der Action-API) zusätzlich zum Volltext des Cache.
- Ohne `base-url` kennt der Katalog kein Ziel der Quelle: die Prüfung auf doppelte Ziele und die Route in der
  Auftragsdiagnose fehlen für `mediawiki`.

Noch offen in #187: die übrigen Quelltypen (je Typ ein PR), Bereiche (`partitions()`) für Quellen ohne Seitenliste,
Freigaben in der Konfiguration des Dienstes, ein asynchroner Transport für MediaWiki (Upstream-Naht).

## Dokumente: Typerkennung und Text (#183)

Erkennung und Extraktion kommen wie die Adapter aus der Kopie (`deigma/document-tika`, `DocumentSourceFileTextExtractor`,
Ablauf wie in der Composition Root des Enterprise AI Client): `DocumentExtraction.detector()` erkennt nach Endung und
Angabe, sonst mit Apache Tika am Inhalt; `DocumentExtraction.registry()` liest Markdown und Klartext direkt, alles
andere (PDF, Word, Excel, PowerPoint, OpenDocument, RTF, E-Mail) über Tika. Tika läuft in Version 3.3 statt 2.9.1
wie im Upstream, weil der Space Harvester PDFBox 3 und POI 5.5 nutzt; flexmark ist ausgeschlossen wie dort.
`application.assets.DocumentText` ist der Port, `source.TikaDocumentText` die Umsetzung; nur sie kennt die Kopie.

| Grenze | Wert | Folge |
|---|---|---|
| Größe je Datei | 20 MiB (`TikaDocumentText.DEFAULT_MAX_BYTES`, wie ADR 0002 und `maxFileMegabytes`) | kein Text, `document-text-too-large` |
| Laufzeit je Datei | 30 s, eigener Thread, danach unterbrochen | `document-text-timeout`, später erneut |
| gleichzeitige Läufe | 2; ein hängender Lauf hält seinen Platz | `document-text-busy`, später erneut |
| Textlänge | 1 000 000 Zeichen, wie der Volltextindex je Seite | gekürzt, `truncated` |

- **Lokale Dateien** (`type: files`): `LocalFilesSourceAdapter` übergibt Erkennung und Extraktoren an den Provider.
  Den Lesetext für Volltextindex und Anreicherungen bildet `LocalFilesTextProjector` aus dem abgelegten Original:
  Textdateien wie bisher als UTF-8 (`file-text/v1`), Dokumente über Tika (`file-document-text/v1`). Ein defektes,
  verschlüsseltes oder zu großes Dokument hat keinen Text; eine Zeitüberschreitung lässt einen vorhandenen
  Indexeintrag stehen.
- **Anhänge im AssetStore:** Beim Ablegen prüft `Assets` den Typ am Inhalt (Magic Bytes). Ein Binärformat mit
  Signatur (PDF, Office, Bild …) ersetzt eine abweichende Angabe der Quelle, ein erkannter Typ ersetzt eine fehlende
  oder nichtssagende (`application/octet-stream`); Textformate (XML, JSON, Klartext) und bloße Behälter (ZIP, OLE2)
  ändern die Angabe nicht, damit etwa draw.io-Daten Diagramme bleiben. Danach richtet sich `AssetKind`. Dokumente
  (`AssetKind.OTHER`) bekommen beim Ablegen über `harvest` ihren Text als Anreicherung an der Datei (`AssetText`, je
  Inhalt und Extraktion unter `texts/` im Asset-Verzeichnis, mit dem Inhalt entfernt); `Assets.text` liefert ihn
  oder liest ihn nach. Fehler halten das Ablegen nicht auf und erscheinen nur als fester Code im Protokoll.
- **Dateiname als Hinweis:** Wie der Enterprise AI Client (`TikaContentDetector` bekommt dort immer den Dateinamen)
  fragt `DocumentText.detect(fileName, content)` mit dem Namen nach, wenn der Inhalt den Typ offenlässt: ohne Signatur
  gilt der Typ nach Name, aus Klartext wird nur ein Textformat (`notizen.md` → `text/markdown`), aus einem ZIP nur
  OOXML/OpenDocument, aus OLE2 nur ein altes Office-Format. Eine Signatur gilt immer vor der Endung; danach wählt
  `Assets.chooseMediaType` wie bisher zwischen Quelle und Inhalt.
- **Vorschau und PDF-Konvertierung:** `HarvestSource.attachmentPreview` mit `AttachmentPreview` `DOCUMENT`
  (`convert`, etwa ein Office-Dokument als PDF), `POSTER`, `THUMBNAIL`; für Confluence über
  `PageViewSource#loadAttachmentPreview` der Kopie (`/rest/documentConversion/latest/conversion/<art>/<id>/<version>`,
  `ConfluenceRestClient#conversionPath`), mit frisch gelesenen Anhangsangaben, nur PDF oder Bild, 202 = `UNAVAILABLE`.
  `Assets.preview` sucht ohne Art wie der Lesebereich des Enterprise AI Client (`previewOrder`: Dokument, Poster,
  Vorschaubild) die erste verfügbare; Verweigerung, Anmeldung und Drosselung beenden die Suche. Die Vorschau ist eine
  eigene Asset-Variante (`preview-<art>-<Anhang>`, Version `source-preview-1:<Version des Anhangs>`,
  `Assets.PREVIEW_RENDITION`), abgelegt nur mit `sync` und nie ohne Version; Medientyp nach den Bytes. REST:
  `GET …/assets/{attachmentId}/preview?kind=…` mit derselben Rechteprüfung wie `…/content`.
- **Anhangstexte im Volltextindex:** wie im Enterprise AI Client (`SourceAttachmentReader` mit
  `DocumentSourceFileTextExtractor`, Composition Root): Jeder Anhang ist ein eigenes Dokument mit der Seite als Eltern
  im `LucenePageTextIndex` (Schema `page-text-index/v2`; ein v1-Index wird leer neu angelegt und füllt sich wieder),
  Dateiname als Titel, Text aus `DocumentText` bzw. bei Bildern eine vorhandene Beschreibung; ohne lesbaren Text steht
  nur der Dateiname darin (EAC: `# Titel`). Grenzen wie oben und `space-harvester.assets.max-size`. In den Index kommt
  nur Abgelegtes, also beim Ablegen (`harvest`, auch für schon abgelegte Anhänge) und auf Abruf mit `sync`
  (`…/text`, `POST …/description`); was die vollständige Anhangsliste nicht mehr nennt, fällt bei der nächsten Ernte
  heraus, mit der Seite alle ihre Anhänge. Die Schlagwortsuche liefert je Seite ihr bestes Dokument: ein Treffer im
  Anhang ist die Seite mit `matchedIn: attachment` und `attachment {attachmentId, title}`, Auszug aus dem Anhang. Er
  wird wie jeder Treffer seitenweise live mit `metadata` und `content` geprüft (ADR 0014, Punkt 4); nicht lesbare
  Seiten erscheinen auch über ihre Anhänge nirgends. Abweichung vom EAC: Bilder werden beim Lesen nicht neu
  beschrieben, sondern nur mit vorhandener Beschreibung indiziert; der Wissensindex (Passagen, Embeddings) bleibt
  Seiten vorbehalten.
- **REST:** `GET …/assets/{attachmentId}/text` liefert den Text mit Extraktion, Version und `truncated`; Rechte wie
  `…/content` (Freigabe `content`, nicht sichtbare Seiten 404, abgelegt und indiziert nur mit `sync`). Fehler als
  `document-text-*` (`unsupported` 415, `failed`/`too-large` 422, `busy` 429, `timeout`/`not-configured` 503).

## Einen Adapter ergänzen

1. Fehlende Fähigkeiten upstream in `source-api` als optionale Capability-Interfaces ergänzen, nicht durch
   Änderungen an `KnowledgeSourcePort.load()`.
2. Die Kopie mit `enterprise-ai-sources/sync-from-upstream.sh sync <commit>` übernehmen; die Kopie wird nie lokal
   geändert (`verifyUpstreamCopy` prüft das im Build).
   Registriert: `source-git` als Typ `git` (`GitSourceAdapter`, ein Ref, aktueller Stand; Optionen `url` (HTTPS oder
   lokaler Pfad), `ref`, `startPoints`, `include`, `exclude`, `maxFileMegabytes`, `maxResources`, `maxDepth`; HTTPS
   über die Proxy-Route je Ziel; ohne `credentialRef`, `auth` und eigene Zertifikate bis #188, also nur öffentliche und
   lokale Repositorys). Ebenfalls registriert über `EacSourceAdapters` (`ContextualSourceAdapter`, eine Partition je
   Quelle, Optionen mit den Upstream-Schlüsseln): `source-ftp` als `ftp` (MVS-Datensätze und Member, `host`,
   `startPoints`, anonym ohne `credentialRef`) und `jes` (Spool, `credentialRef` Pflicht), `source-ndv` als `ndv`
   (`host`, `startPoints`, `credentialRef` Pflicht) und `source-sharepoint` als `sharepoint` (`siteUrl`, über die
   Proxy-Route je Ziel, ohne `credentialRef` mit der Windows-Anmeldung des JDK). `credentialRef` löst der
   `SecretProvider` des Katalogs auf, also der Anbieter des einbettenden Programms (#188); `auth`, `base-url` und eigene
   Zertifikate gibt es auf diesem Weg nicht.
3. Den Typ in `ConfiguredSourceCatalog` registrieren, für einen Upstream-Provider ohne Seitenliste über
   `KnowledgeSourceProviderAdapter` (mit Netz über `connected`, wie `MediaWikiSources`).
   `KnowledgeSourceHarvestSource` bildet jeden `KnowledgeSourcePort` mit seinen Fähigkeiten ab, ohne `PartitionListingSource` über `discover`/`load` (siehe oben).

## Tests

- `KnowledgeSourceHarvestSourceTest`: Abbildung der Ressourcen, fehlende Fähigkeiten ergeben `UNSUPPORTED`; Quellen
  ohne Partitionen mit `InMemoryKnowledgeSource` aus den Fixtures der Kopie (eine Discovery je Listung, fremder Cursor,
  Änderung während der Listung, Weiterleitung, Fehlertexte, Titelsuche, Adressen).
- `HarvesterDiscoveredSourceTest`: dieselbe Fake-Quelle ohne Partitionen und ohne Suche über
  `KnowledgeSourceProviderAdapter` durch Abgleich, Katalog, Cache, Volltextsuche und Markdown-Export; nicht lesbare
  Seiten fehlen in Liste und Suche.
- `HarvesterMediaWikiTest`: `type: mediawiki` über echtes HTTP gegen die Fake-Wiki der Kopie (`FakeMediaWikiServer`
  hinter `WikiFront`, der Anfragen zählt und Leserechte je Seite nachbildet): Abgleich, Katalog, Cache, Volltextsuche,
  Markdown-Export; Login mit Zugangsdaten aus der Konfiguration und vom Anbieter des Programms, ohne Passwort oder
  Titel im Protokoll; falsche Zugangsdaten; nicht lesbare Seiten (Wiki und `AccessPolicy`) fehlen in Liste und Suche;
  Prüfung der Einstellungen.
- `MediaWikiNetworkTest`: PAC-Route je Ziel (Proxy, DIRECT), fehlendes PAC ohne Direktverbindung, mTLS mit
  `trust-bundle` auch durch den Proxy-Tunnel, unbrauchbare Zertifikate verhindern den Start.
- `HarvesterGitSourceTest`: ein lokales Repository als Typ `git` durch Abgleich, Cache und Volltextsuche;
  `credentialRef` wird abgelehnt.
- `HarvesterEacSourcesTest`: `ftp`, `jes`, `ndv` und `sharepoint` öffnen je eine Partition ohne Verbindung; die
  Prüfung des Upstream-Typs meldet fehlende Pflichtangaben beim Start.
- `LocalFileGrantsTest`: Links aus dem Verzeichnis nur in freigegebene Ordner, Freigaben ohne Neustart.
- `PageMetadataApiTest`, `SinglePageMetadataApiTest`, `NativeSnapshotSourceTest`: alle Wege über echtes HTTP gegen
  die Fake-Confluence aus den Test-Fixtures der Kopie.
- `TikaDocumentTextTest`, `LocalFilesTextProjectorTest`, `AssetDocumentTextTest`: Text aus zur Laufzeit erzeugten
  Dokumenten (`DocumentFixtures`: DOCX, XLSX, PPTX, PDF, ODT, RTF), Erkennung am Inhalt gegen eine falsche Angabe,
  Grenzen; `HarvesterFullTextTest` findet Word und PDF lokaler Dateien im Volltextindex.
- `AttachmentPreviewTest`: Vorschauen über die Confluence-Kopie mit der Fake-Confluence (Pfad der
  Dokumentkonvertierung, Reihenfolge, Ablage je Version, Anmeldeseite, 202, Grenze); `AttachmentPreviewApiTest`: die
  Route über echtes HTTP, Rechte wie beim Inhalt, nicht lesbare Seiten bleiben unsichtbar.
- Upstream: `ConfluenceStoredContentTest`, die MediaWiki-Tests (`MediaWikiKnowledgeSourceTest`,
  `MediaWikiHttpIntegrationTest`, Vertragstest …), `GitKnowledgeSourceTest`, `GitKnowledgeSourceContractTest` und die
  übrigen Adaptertests laufen in `:enterprise-ai-sources:test` mit.
