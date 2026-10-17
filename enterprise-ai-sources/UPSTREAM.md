# enterprise-ai-sources

Unveränderte Kopie der Confluence-Quelle aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client). Der Space Harvester baut
damit keinen eigenen Confluence-Client, sondern nutzt denselben Code wie der Enterprise AI Client.

Der genaue Upstream-Commit steht in der ersten Zeile von [`UPSTREAM.sha256`](UPSTREAM.sha256); derzeit
`1e8bf8c43401f0a460d92f80abaa9cdf3c405c03`, der Merge von enterprise-ai-client#179 (Anhang-Downloads mit
Grenze des Aufrufers, fremde Weiterleitungen per `foreignFileRedirects` abschaltbar; davor #178, #177, #176, #18) auf `main`.

## Inhalt

| Upstream-Modul | Inhalt hier |
|---|---|
| `astu` | nur die 15 Klassen, die die Module unten transitiv brauchen (Liste in `sync-from-upstream.sh`) |
| `adyton` | vollständig: `SecretProvider`, `SecretRef`-Auflösung |
| `proasteion/platform/network` | vollständig: `HttpRoutePort` |
| `source-api` | vollständig: `KnowledgeSourcePort`, `KnowledgeSourceProvider`, `PageNavigationSource`, `PageViewSource`, `PartitionListingSource`, Test-Fixtures |
| `proasteion/emporion/holkas/source-confluence` | vollständig: `ConfluenceRestClient`, `ConfluenceKnowledgeSource`, `FakeConfluence`-Fixtures |
| `proasteion/emporion/deigma` | vollständig: Port für Dateityp-Erkennung und Text-Extraktion (nur JDK) |
| `proasteion/emporion/deigma/document-tika` | nur `SimpleContentDetector` (Erkennung nach Endung, nur JDK), ohne Apache Tika |
| `proasteion/emporion/holkas/source-localfiles` | vollständig: `LocalFilesSourceProvider`, `LocalFilesKnowledgeSource` (#18) |
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
