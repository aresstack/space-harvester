# Quellenadapter

Space Harvester spricht Wissensquellen nur über den quellenneutralen Port
`application.sources.HarvestSource` an. Umgesetzt wird er im Paket `source` über die Adapter aus
`enterprise-ai-sources`, einer unveränderten Kopie aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client) (#2, PR #23). Einen zweiten
Confluence-Client gibt es nicht.

## Auswahl

`ConfiguredSourceCatalog` baut aus `space-harvester.sources[*]` je Eintrag eine Quelle; `type` wählt den Adapter.
Unbekannte Typen, fehlerhafte Optionen, Zertifikate oder Proxy-Einstellungen verhindern den Start, und alle Fehler
werden gemeinsam gemeldet. Konfiguration siehe README, „Quellen konfigurieren“.

**Registrierung:** `ConfiguredSourceCatalog.DEFAULT_ADAPTERS` enthält die Typen ohne eigenen Ordner:
`ConfluenceSourceAdapter` (`confluence`), `LocalFilesSourceAdapter` (`files`), aus `EacSourceAdapters` `ftp()`,
`jes()`, `ndv()`, `sharePoint()`, `betaView()` und `outlook()` sowie `MediaWikiSources.adapter()` (`mediawiki`);
`ConfiguredSourceCatalog.TYPES` sind deren Typnamen. `git` braucht einen Ordner für die Kopien der Repositorys und
fehlt deshalb in der Liste: `Harvester.builder()` hängt `new GitSourceAdapter(<dataDirectory>/git)` an, der Dienst
eine Bean in `CacheConfiguration` (`space-harvester.cache.directory`/git, nur mit diesem Verzeichnis). Weitere Typen
hängt ein einbettendes Programm mit `Harvester.builder().sourceAdapter(adapter)` ein (#188); ein Typ gleichen Namens
aus dem Core oder `git` wird dadurch ersetzt, derselbe Typ zweimal ist ein Fehler beim Bauen. Im Dienst ist jeder Typ
eine `SourceAdapter`-Bean (`SourceConfiguration`), der Katalog bekommt alle Beans.

| `type` | Adapter | `addressOption` | `auth.type` | `options.credentialRef` | `trust-bundle` | `client-certificate` |
|---|---|---|---|---|---|---|
| `confluence` | `ConfluenceSourceAdapter` | – (`base-url`) | `none`, `basic`, `pat` | nicht erlaubt, dafür `auth` | ja | ja (`pkcs12`, `windows-my`) |
| `files` | `LocalFilesSourceAdapter` | `directory` | `none` | – | Fehler | Fehler |
| `ftp` | `EacSourceAdapters.ftp()` | `host` | `none`, `basic` | eigener Verweis bei `none` | Fehler | Fehler |
| `jes` | `EacSourceAdapters.jes()` | `host` | `none`, `basic`; Anmeldung Pflicht | eigener Verweis bei `none` | Fehler | Fehler |
| `ndv` | `EacSourceAdapters.ndv()` | `host` | `none`, `basic`; Anmeldung Pflicht | eigener Verweis bei `none` | Fehler | Fehler |
| `sharepoint` | `EacSourceAdapters.sharePoint()` | `siteUrl` | `none`, `basic` | eigener Verweis bei `none` | ja | ja |
| `betaview` | `EacSourceAdapters.betaView()` | `baseUrl` | `none`, `basic`; Anmeldung Pflicht | eigener Verweis bei `none` | ja | ja |
| `outlook` | `EacSourceAdapters.outlook()` (`ContextualSourceAdapter`) | `mailbox` | `none` | – | Fehler | Fehler |
| `mediawiki` | `MediaWikiSources.adapter()` | `apiUrl` | `none`, `basic` | nicht erlaubt, dafür `auth` | ja | ja |
| `git` | `GitSourceAdapter` | `url` | `none` | ja, nur HTTPS (#188) | siehe unten | Fehler |

„Anmeldung Pflicht“ heißt `needsCredentials = true`: `auth.type basic` oder ein eigener `options.credentialRef`.
Ein eigener Verweis (etwa `keepass:jes`) geht unverändert an den `SourceSecrets` des Programms; zusammen mit
`auth.type basic` ist er ein Fehler, weil dann `source:<id>` gilt. „Fehler“ heißt: Die Angabe verhindert den Start,
statt still übergangen zu werden. Bei `git` liest `GitSourceAdapter.open` ein `trust-bundle` für eigene Server-CAs,
die Prüfung (`check` über einen `KnowledgeSourceProviderAdapter` ohne Netz) lehnt `trust-bundle` aber mit
„trust-bundle passt nicht zu type git“ ab; auf `main` (ceb6c84) startet eine Git-Quelle mit `trust-bundle` deshalb
nicht. `auth` gibt es für `git` noch nicht.

Welche Typen ein Harvester kennt, liefert `Harvester.sourceTypes()` (im Dienst `GET /api/v1/sources/types`): je Typ `id`
(Wert von `type`), `displayName` (z. B. „Git-Repository“, „FTP“, „JES“, „NDV“, „SharePoint“, „BetaView“, „Outlook“) und
`needsCredentials`, ob der Typ Zugangsdaten verlangt (`auth.type basic` oder ein eigener `options.credentialRef`, #188),
und `addressOption`, unter welchem Schlüssel in `options` der Typ seine Adresse liest: `host` (ftp, jes, ndv), `siteUrl`
(sharepoint), `baseUrl` (betaview), `mailbox` (outlook), `apiUrl` (mediawiki), `url` (git), `directory` (files); bei
Confluence `null`, die Adresse steht dort in `base-url`. Dazu `fields`, die Eingabefelder unter `options` wie im
Quellen-Editor des Enterprise AI Client (`key`, `label`, `hint`, `kind`
TEXT|NUMBER|FLAG|DIRECTORY|SECRET_REF|CLIENT_CERTIFICATE, `required`, `advanced`, `defaultValue`; nie ein Wert aus dem
Tresor, ohne das Titelfeld des Typs, das `title` füllt, bei MediaWiki `displayName`; bei Confluence leer, weil
Basis-URL, Titel, Anmeldung und Zertifikat außerhalb von `options` stehen; bei ftp, jes, ndv, sharepoint, betaview, git
und mediawiki steht das Upstream-Feld `credentialRef` als `SECRET_REF` darin, bei mediawiki obwohl
`options.credentialRef` dort abgelehnt wird), und `authModes`, die erlaubten Werte von `auth.type` (Tabelle oben:
`none` und mit Anmeldefeld `basic`, bei Confluence auch `pat`; files, outlook und git nur `none`). Eigene Adapter
geben das über `SourceAdapter.displayName()`, `needsCredentials()`, `addressOption()`, `fields()` und `authModes()`
an; ohne eigene Angabe gelten der Typname, `false`, `null`, keine Felder und `none`. Geprüft in
`HarvesterEacSourcesTest` (`eachTypeDescribesItsFieldsAndAuthModesWithoutValues`, PR #394; Adressfelder PR #387).

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

### `KnowledgeSourceProviderAdapter`

Ein Upstream-`KnowledgeSourceProvider` wird auf zwei Wegen zum Quellentyp:

- **Ohne Netz:** `new KnowledgeSourceProviderAdapter(provider)`, ein fest gebauter Provider. Er kennt nur `auth.type
  none`; `client-certificate` und `trust-bundle` sind Fehler. So arbeiten `GitSourceAdapter` (je Prüfung bzw. Öffnen
  neu gebaut) und `ContextualSourceAdapter` (`outlook`: baut den Provider aus dem Kontext des Katalogs, für die Prüfung
  ohne Kontext).
- **Mit Netz:** `KnowledgeSourceProviderAdapter.connected(factory, credentialKey, titleField)`. Die Fabrik
  (`ConnectedProvider`) bekommt je Quelle eine `Connection(secrets, routes, tls, userAgent)`: den `SecretProvider` des
  Katalogs, die Proxy-Route je Ziel, die Socket-Fabrik aus `trust-bundle` und `client-certificate` (`null` =
  JVM-Standard) und den User-Agent. `credentialKey` ist das Upstream-Feld, in das bei `auth.type basic` der Verweis
  `source:<id>` geht (`null`: der Typ kennt keine Anmeldung, dann nur `none`); `titleField` ist das Feld für `title`
  (`null` = das des Upstream-Typs). Für Typ, Prüfung, Umfang und Adresse baut der Adapter den Provider einmal ohne
  Netz und ohne Zugangsdaten.

Ein `connected`-Typ lässt sich weiter einstellen; jede Methode liefert einen neuen Adapter und verlangt `connected`,
sonst `IllegalStateException`:

| Methode | Wirkung | Benutzt von |
|---|---|---|
| `withoutTls()` | `client-certificate` und `trust-bundle` werden abgelehnt statt still übergangen | ftp, jes, ndv |
| `withCredentialRefPassThrough()` | ein eigener Verweis unter `options.<credentialKey>` geht bei `auth.type none` an den Anbieter des Programms (#188); mit `basic` ein Fehler; ohne `credentialKey` `IllegalStateException` | ftp, jes, ndv, sharepoint, betaview |
| `requiringCredentials()` | `needsCredentials()` ist `true` | jes, ndv, betaview |
| `named(displayName)` | eigener Anzeigename statt dem des Upstream-Typs | ftp („FTP“), jes („JES“), ndv („NDV“) |

Ohne `withCredentialRefPassThrough()` ist `options.<credentialKey>` nicht erlaubt (dafür `auth`), so bei `mediawiki`.
Für beide Wege gilt: `base-url` ist ein Fehler (die Adresse steht unter `options`), `options.<Titelfeld>` ist nicht
erlaubt (dafür `title`), die Meldungen des Upstream-Typs (`validate`) nennen den Schlüssel unter `options`, und ohne
Adresse aus den Einstellungen startet die Quelle nicht. Mit Netz verhindert ein Platzhalter `${…}` ohne Wert in
`auth.password` oder `client-certificate.password` den Start; die Meldung nennt nur den Namen des Platzhalters.
`addressOption()` ist das Adressfeld des Upstream-Typs (`summaryKey`), `fields()` sind dessen Felder ohne das
Titelfeld, `authModes()` `none` und mit `credentialKey` `basic`.

Neue Typen dieser Art registriert ein Programm über `Harvester.builder().sourceAdapter(…)`; im Core stehen sie in
`ConfiguredSourceCatalog.DEFAULT_ADAPTERS` (Abschnitt Auswahl).

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

Stand #187 auf `main`: Quellen ohne Partitionen (PR #357), MediaWiki (PR #370) und die Typen git (PR #368), ftp, jes,
ndv, sharepoint (PR #372), betaview (PR #378) und outlook (PR #380) sind registriert, die Typenliste mit
Anzeigenamen (PR #375), Adressfeld (PR #387), Feldern und Anmeldearten (PR #394); Anmeldung und TLS für ftp, jes,
ndv, sharepoint und betaview kamen mit PR #390, `credentialRef` für git mit PR #377 (#188). Noch offen: `auth` und
`client-certificate` für git sowie `trust-bundle` für git durch die Prüfung (siehe Auswahl), Bereiche
(`partitions()`) für Quellen ohne Seitenliste, Freigaben für `files` in der Konfiguration des Dienstes, ein
asynchroner Transport für MediaWiki (Upstream-Naht).

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
  Seiten erscheinen auch über ihre Anhänge nirgends. Bildanhänge (Raster und SVG) stehen wie im EAC
  (`DocumentSourceFileTextExtractor` → `describe`) mit ihrer Beschreibung im Index, aber nur mit der vorhandenen aus der
  Ablage der Bildanalyse (ADR 0021, `Assets.storedDescription`, eingestelltes Modell); Indexieren ruft nie ein Modell
  auf, beschrieben wird nur auf Abruf (`Assets.describe`, danach im Index), ohne Beschreibung steht nur der Dateiname
  darin, keine OCR. Der Wissensindex (Passagen, Embeddings) bleibt Seiten vorbehalten.
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
   über die Proxy-Route je Ziel; `credentialRef` (nur HTTPS) wird je Abruf über den `SecretProvider` des Katalogs
   aufgelöst, erst aus der Konfiguration, sonst beim Anbieter des einbettenden Programms (#188); eigene Server-CAs über
   `trust-bundle` liest `open`, die Prüfung lehnt es aber ab, siehe Auswahl; `auth` noch nicht). Ebenfalls registriert über
   `EacSourceAdapters` (ftp, jes, ndv, sharepoint und betaview über `KnowledgeSourceProviderAdapter.connected`,
   outlook über `ContextualSourceAdapter` ohne Netz; eine Partition je Quelle, Optionen mit den Upstream-Schlüsseln): `source-ftp` als `ftp` (MVS-Datensätze und Member, `host`,
   `port`, `startPoints`, anonym ohne Anmeldung) und `jes` (Spool, Anmeldung Pflicht), `source-ndv` als `ndv`
   (`host`, `startPoints`, Anmeldung Pflicht) und `source-sharepoint` als `sharepoint` (`siteUrl`, über die
   Proxy-Route je Ziel, ohne Anmeldung mit der Windows-Anmeldung des JDK) sowie `source-betaview` als `betaview`
   (Listen und Reports eines Favoriten: `baseUrl`, `favoriteId` und Anmeldung Pflicht, über die Proxy-Route je
   Ziel) und `source-outlook` als `outlook` (lokale Postfachdateien .pst/.ost: `mailbox` Pflicht, `startPoints`,
   `maxDepth`, `maxResources`; ohne Zugangsdaten). Anmeldung ([ADR 0022](adr/0022-erweiterbarer-builder-und-secretprovider.md)):
   `auth.type basic` mit `username` und `password` setzt `credentialRef` auf `source:<id>`; ohne `password` fragt der
   Katalog den `SourceSecrets` des Programms (`Harvester.builder().secretProvider`) nach `source:<id>`. Ein eigener
   Verweis `options.credentialRef` (etwa `keepass:jes`) geht bei `auth.type none` unverändert an diesen Anbieter;
   zusammen mit `basic` ist er ein Fehler. SharePoint und BetaView nehmen `trust-bundle` und `client-certificate`
   (mTLS); FTP, JES und NDV kennen upstream kein TLS, dort sind beide Angaben ein Fehler. `base-url` gibt es auf
   diesem Weg nicht.
3. Den Typ in `ConfiguredSourceCatalog.DEFAULT_ADAPTERS` registrieren (ein Typ mit eigenem Ordner wie `git` in
   `Harvester.Builder` und als Bean im Dienst), für einen Upstream-Provider ohne Seitenliste über
   `KnowledgeSourceProviderAdapter` (mit Netz über `connected`, wie `MediaWikiSources` und `EacSourceAdapters`); im
   Dienst zusätzlich als `SourceAdapter`-Bean in `SourceConfiguration`.
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
- `HarvesterEacSourcesTest`: die Kopien öffnen je eine Partition; Anmeldung an einem Fake-FTP-Server mit `auth`
  aus der Konfiguration, mit Passwort vom Anbieter des Programms (`source:<id>`) und mit eigenem `credentialRef`;
  `basic` erfüllt die Pflichtangabe von JES und NDV; `credentialRef` neben `basic`, Zertifikate bei FTP/NDV und
  `base-url` verhindern den Start.
- `HarvesterGitSourceTest`: ein lokales Repository als Typ `git` durch Abgleich, Cache und Volltextsuche;
  `credentialRef` wird angenommen und erst beim Abruf aufgelöst. `GitSourceAdapterTest`: Benutzer und Token je
  Abruf frisch über den `SecretProvider`, ein Eintrag ohne Benutzer ist erlaubt, einer ohne Token nicht (#377).
- `HarvesterEacSourcesTest`: Anzeigename, `needsCredentials` und `addressOption` aller Typen in fester Reihenfolge,
  dazu `fields` und `authModes` ohne Werte (#387, #394).
- `ParityGitTest`: das Paritäts-Repository `ParityGit` (Test-Fixtures des Core, Paket der Git-Quelle, Java 8, #193)
  durch Abgleich, Cache und Suche; Tag `v1.0` zeigt den alten Stand, Commit-IDs sind fest.
- `ParityFilesTest`: der Paritätsordner `ParityFiles` (Test-Fixtures des Core, Paket der Ordner-Quelle, Java 8, #193)
  durch Abgleich, Cache und Suche; `exclude` schließt den Entwurf aus, das Bild ohne Text wird keine Seite.
- `ParityFtpTest`: der Paritäts-Server `ParityFtp` (Test-Fixtures des Core, Paket der FTP-Quelle, Java 8, #193), ein
  anonymer FTP-Server im Prozess nur auf 127.0.0.1 mit Unix-Auflistung, durch Abgleich, Cache und Suche; `maxDepth` wirkt.
- `ParitySharePointTest`: die Paritäts-Site `ParitySharePoint` (Test-Fixtures des Core, Paket der SharePoint-Quelle,
  Java 8, #193), REST im Format `odata=verbose` über den HTTP-Server des JDK nur auf 127.0.0.1; `Forms`, das Bild und die
  versteckte Bibliothek fehlen, `maxDepth` wirkt.
- `ParityBetaViewTest`: die Paritäts-Oberfläche `ParityBetaView` (Test-Fixtures des Core, Paket der BetaView-Quelle,
  Java 8, #193) über den HTTP-Server des JDK nur auf 127.0.0.1: Login mit Struts-Token und Cookie, CSRF-Token,
  Ergebnisliste, Download; Zugangsdaten kommen nur aus einem Test-Anbieter.
- `ParityJesTest`: derselbe Server `ParityFtp` im JES-Modus (`SITE FILETYPE=JES`): Jobs zu Besitzer und Jobnamen-Filter,
  Ausgabe mit Jobkarte; Zugangsdaten kommen nur aus einem Test-Anbieter.
- `HarvesterEacSourcesTest`: `ftp`, `jes`, `ndv`, `sharepoint`, `betaview` und `outlook` öffnen je eine Partition ohne Verbindung; die
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
