# enterprise-ai-sources

Unveränderte Kopie der Confluence-, MediaWiki-, Git-, FTP/JES-, NDV- und SharePoint-Quelle, der Dokument-Extraktion und des
Embedding-Adapters aus [aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client). Der Space Harvester baut
damit keinen eigenen Confluence-Client, sondern nutzt denselben Code wie der Enterprise AI Client.

Der genaue Upstream-Commit steht in der ersten Zeile von [`UPSTREAM.sha256`](UPSTREAM.sha256); derzeit
`065530010155ad03be68a3f9bf0d9964566e9087`, der Merge von enterprise-ai-client#222 (draw.io-Diagramme mit drawio-java statt
JGraphX: `DrawioDiagramRenderer` als Adapter auf das Original-draw.io-JavaScript auf GraalJS, Meldungen nur mit Fehlercode und
Fähigkeit; im Space Harvester liefern App und Dienst `drawio-java-stencils` mit, die Tests dieses Moduls haben es im
Testklassenpfad); davor `89002c11632fb7c3ab7f8a86ed5826600f7ad150`, der Merge von enterprise-ai-client#221 (Outlook: fehlendes Datenfeld eines Anhangs im angehängten Element ist ein Fehler, unlesbare Anhangstabelle ebenso; Teile ohne Datei mit dem Warncode `entries-not-readable`) auf `main`; davor `42b047aa`, der Merge von enterprise-ai-client#220 (Outlook-Anhänge, ADR 0026 Paket OL1: `OutlookKnowledgeSource` ist `AttachmentListSource` und `PageViewSource`, Anhangskennung aus Postfachdatei, Nachricht und `getAttachNum`, Dateibytes als Strom, angehängte Elemente als `message/rfc822`; Testpostfach `PstTestMailbox` als Fixture) auf `main`; davor `fb85f380`, der Merge von enterprise-ai-client#219 (Zwei-Faktor-Sitzung: fehlende Datei
`/attachmentnotfound.action` ist `NOT_FOUND`, nach einer Einmalcode-Abfrage keine neue Sitzung über die REST-Anmeldung, der
Code geht an die Sitzung seiner Seite) auf `main`; davor `133fb6b4`, der Merge von enterprise-ai-client#218 (Upstream-Patches 12 und 13: `<img>` mit leerem `src` in der Lese-Ansicht nimmt `data-image-src` oder entfällt; Anhänge ohne Größengrenze in einen Strom, `PageViewSource#streamAttachmentFile`) auf `main`; davor
`7a5ee718` (#217, Upstream-Patches 01–11, darunter `AttachmentSearchSource` für den Anhangskatalog über die Confluence-Suche), davor `9fb91c32` (#192 Änderungssuche und Papierkorb je Space, `ChangeListingSource`). Derselbe Stand enthält schon
`document-tika`; für #183 und für MediaWiki, Git, FTP/JES, NDV und SharePoint (#187) kamen nur weitere Module und Klassen in die
Auswahl, ohne neuen Upstream-Commit.

## Inhalt

| Upstream-Modul | Inhalt hier |
|---|---|
| `astu` | nur die Klassen, die die Module unten transitiv brauchen (Liste in `sync-from-upstream.sh`) |
| `adyton` | vollständig: `SecretProvider`, `SecretRef`-Auflösung |
| `proasteion/platform/network` | vollständig: `HttpRoutePort` |
| `source-api` | vollständig: `KnowledgeSourcePort`, `KnowledgeSourceProvider`, `PageNavigationSource`, `PageViewSource`, `PartitionListingSource`, Test-Fixtures |
| `proasteion/emporion/holkas/source-confluence` | vollständig: `ConfluenceRestClient`, `ConfluenceKnowledgeSource`, `FakeConfluence`-Fixtures |
| `proasteion/emporion/deigma` | vollständig: Port für Dateityp-Erkennung und Text-Extraktion (nur JDK) |
| `proasteion/emporion/deigma/document-tika` | vollständig (#183): `TikaContentDetector`, `TikaResourceExtractor`, `DocumentExtraction`, `MarkdownTextExtractor`, `PlainTextExtractor`, `SimpleContentDetector`; Apache Tika 3.3.2 statt 2.9.1 (PDFBox 3 und POI 5.5 des Space Harvester), flexmark ausgeschlossen wie im Upstream |
| `astu/acropolis` | nur `SourceFileTextExtractor` und für dessen Umsetzung `ImageAnalysis`, `ImageAnalysisCache`, `SourceImageAnalysisUseCase` (#183); `navigation/SectionLabeler`, `ChatSectionLabeler`: Benennung der Themenabschnitte der Dokumentnavigation über den Chat-Port (#181) |
| `proasteion/application` | nur `DocumentSourceFileTextExtractor`: Text aus Dateien einer Quelle wie im Enterprise AI Client (#183); `SecretBackedMediaWikiCredentialsProvider`: Anmeldung am Wiki über den `SecretProvider` wie in `AdapterAssembly` (#187) |
| `proasteion/emporion/holkas/source-localfiles` | vollständig: `LocalFilesSourceProvider`, `LocalFilesKnowledgeSource` (#18) |
| `proasteion/emporion/holkas/source-mediawiki` | vollständig (#187): `MediaWikiSourceProvider`, `MediaWikiKnowledgeSource`, `MediaWikiSiteConfig`, `MediaWikiCredentials(Provider)`, paketintern Action-API-Client, Crawler, HTML-Text, `UrlConnectionMediaWikiTransport`; Fixtures `FakeMediaWikiTransport`, `FakeMediaWikiServer`. Braucht nur Gson und jsoup, die schon da sind |
| `proasteion/emporion/holkas/source-git` | vollständig (#187): `GitSourceProvider`, `GitKnowledgeSource` (ein Ref, aktueller Stand, `PageNavigationSource`), JGit 5.13 wie im Upstream; im Core registriert als `git` |
| `proasteion/emporion/holkas/source-ftp` | vollständig (#187): `FtpSourceProvider`, `FtpKnowledgeSource` (MVS-Datensätze und Member), `JesSourceProvider`, `JesKnowledgeSource` (Spool), Apache Commons Net 3.9.0 wie im Upstream; im Core registriert als `ftp` und `jes` |
| `proasteion/emporion/holkas/source-ndv` | vollständig (#187): `NdvSourceProvider`, `NdvKnowledgeSource` (Natural Development Server, NATSPOD/PAL aus MainframeMate), ohne weitere Abhängigkeit; im Core registriert als `ndv` |
| `proasteion/emporion/holkas/source-sharepoint` | vollständig (#187): `SharePointSourceProvider`, `SharePointKnowledgeSource` (Dokumentbibliotheken über die REST-API, Extraktion über deigma), ohne weitere Abhängigkeit; im Core registriert als `sharepoint` |
| `proasteion/emporion/holkas/source-betaview` | vollständig (#187): `BetaViewSourceProvider`, `BetaViewKnowledgeSource` (Listen und Reports, HTTP-Ablauf aus MainframeMate), ohne weitere Abhängigkeit; im Core registriert als `betaview` |
| `proasteion/emporion/holkas/source-outlook` | vollständig (#187): `OutlookSourceProvider`, `OutlookKnowledgeSource` (PST/OST, Auswahl und Text aus MainframeMate; Anhänge über `AttachmentListSource` und `PageViewSource`, enterprise-ai-client#220 und #221), `OutlookMessageWriter` (angehängte Elemente als RFC-822), Fixture `PstTestMailbox` (schreibt kleine PST-Dateien im Test), java-libpst 0.9.3 wie im Upstream; im Core registriert als `outlook` |
| `embedding-api`, `embedding-openai` | vollständig: `EmbeddingPort`, `OpenAiCompatibleEmbeddingAdapter` für `POST /embeddings` (ADR 0011) |
| `chat-api`, `chat-openai` | vollständig: `ChatCompletionPort`, `OpenAiCompatibleChatAdapter` für `POST /chat/completions`, Suchvarianten (ADR 0017) |
| `model-api` | nur `ModelCatalogPort`, `ModelCatalogException` |
| `model-kipitz` | nur der Modellkatalog für `GET /models`: `KipitzModelCatalogAdapter`, `KipitzModelCatalogConfig`, `KipitzModelListParser`, `KipitzConnections` |
| `LICENSE` | die Lizenz des Upstreams (MIT, AresStack), mitkopiert und mitgeprüft |

Pakete und Klassennamen bleiben wie im Upstream (`com.aresstack.enterpriseai.*`), damit sich Änderungen
1:1 übernehmen lassen. Die Upstream-Tests (JUnit 4) laufen bei `./gradlew build` mit.

## Unverändertheit

- **Bei jedem Build (offline):** `verifyUpstreamCopy` hängt an `check` und vergleicht jede Datei unter `src/`
  sowie `LICENSE` mit der SHA-256 aus `UPSTREAM.sha256`. Eine geänderte, fehlende oder zusätzliche Datei lässt
  den Build scheitern.
- **Gegen den Upstream selbst:** `./sync-from-upstream.sh verify` holt den festgehaltenen Commit aus GitHub,
  stellt die Auswahl neu zusammen und vergleicht sie per `diff -r` mit der Kopie. Damit lässt sich auch
  nachweisen, dass `UPSTREAM.sha256` selbst nicht angepasst wurde.

## Abgrenzung zum Space Harvester

- In diesem Subprojekt wird nichts geändert. Fehler werden im Upstream behoben und per Sync übernommen.
- Space-Harvester-eigener Code (Spring, Konfiguration, die quellenunabhängige SPI) liegt im Hauptprojekt unter
  `com.aresstack.spaceharvester`. Das Subprojekt kennt das Hauptprojekt nicht.
- Im Hauptprojekt darf nur `com.aresstack.spaceharvester.source..` die kopierten Klassen verwenden;
  `LayeringTest` (ArchUnit) prüft das. `api`, `application` und `output` sehen nur die eigene SPI.

## Abgleich mit dem Upstream

```bash
enterprise-ai-sources/sync-from-upstream.sh sync <commit|tag>   # z. B. v0.81.0
./gradlew build
```

`sync` klont den Upstream in ein temporäres Verzeichnis, kopiert die Auswahl, schreibt `UPSTREAM.sha256` mit
dem aufgelösten Commit neu und lässt einen vorhandenen lokalen Checkout unberührt
(`ENTERPRISE_AI_CLIENT_DIR=<pfad>` nutzt einen solchen statt des Klons). Die Übernahme ist danach ein normaler
Git-Diff im PR. Braucht eine neue Upstream-Fassung weitere `astu`-Klassen, scheitert die Kompilierung; die
fehlenden Klassen kommen dann in die Liste im Skript.
