# libs: eigene Bibliotheken als Upstream-Kopie

Maven Central nimmt seit dem 27.11.2026 keine neuen Releases unter `com.aresstack` mehr an (Kontingent des Namespace für den
laufenden Monat erschöpft). Der Space Harvester baut die eigenen Bibliotheken deshalb selbst: Jede liegt hier als
**unveränderte Kopie** ihres Repositorys und wird als eigenes Gradle-Projekt per Composite Build eingebunden
(`includeBuild` in [`settings.gradle`](../settings.gradle)). Gradle ersetzt die Koordinaten aus
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) (zum Beispiel `com.aresstack:research4j-research-core`) durch die
Projekte der Kopie. Ein Release ist dafür nicht nötig, ausgeliefert wird nur die App.

Das Verfahren ist dasselbe wie bei den Kopien aus dem Enterprise AI Client (`enterprise-ai-sources/UPSTREAM.md`): Das
Repository der Bibliothek bleibt die Quelle, hier wird nichts geändert, ein neuer Stand kommt per Sync.

| Kopie | Upstream | Stand |
|---|---|---|
| `knowledge4j/` | [aresstack/knowledge4j](https://github.com/aresstack/knowledge4j) | erste Zeile von [`upstream/knowledge4j.sha256`](upstream/knowledge4j.sha256) |
| `playwright4j/` | [aresstack/playwright4j](https://github.com/aresstack/playwright4j) | erste Zeile von [`upstream/playwright4j.sha256`](upstream/playwright4j.sha256) |
| `research4j/` | [aresstack/research4j](https://github.com/aresstack/research4j) | erste Zeile von [`upstream/research4j.sha256`](upstream/research4j.sha256) |
| `writing4j/` | [aresstack/writing4j](https://github.com/aresstack/writing4j) | erste Zeile von [`upstream/writing4j.sha256`](upstream/writing4j.sha256) |

Nicht kopiert werden die CI des Upstreams (`.github/`) und sein Gradle-Wrapper; ein eingebundener Build läuft mit dem Gradle
des Space Harvester. Research4j und Writing4j finden Knowledge4j wie in ihren eigenen Repositorys als Nachbarordner.

Bibliotheken, deren veröffentlichte Fassung genügt, kommen weiter von Maven Central (bereits veröffentlichte Fassungen bleiben
dort abrufbar): Playwright4j, Serp4j, win-proxy-java, win-trust-java, mermaid-java, drawio-java, die DirectML-Bibliotheken.
Braucht der Space Harvester von einer davon einen unveröffentlichten Stand, kommt sie mit einer Zeile in
`sync-from-upstream.sh`, in `settings.gradle` und in der Liste im Wurzel-`build.gradle` hierher.

## Abgleich mit dem Upstream

```bash
libs/sync-from-upstream.sh sync research4j <commit|tag|branch>   # z. B. main
./gradlew build
```

`sync` klont das Repository in ein temporäres Verzeichnis, ersetzt die Kopie und schreibt `upstream/<bibliothek>.sha256` mit
dem aufgelösten Commit neu. Ein vorhandener Checkout lässt sich statt des Klons nutzen (`RESEARCH4J_DIR=<pfad>`, entsprechend
`KNOWLEDGE4J_DIR`, `WRITING4J_DIR`); er wird nicht verändert. Die Übernahme ist danach ein normaler Git-Diff im PR auf `develop`.

## Unverändertheit

- **Bei jedem Build (offline):** `verifyKnowledge4jCopy`, `verifyResearch4jCopy` und `verifyWriting4jCopy` hängen an `check`
  der Gradle-Wurzel (gemeinsame Prüfung [`gradle/upstream-copy.gradle`](../gradle/upstream-copy.gradle), dieselbe wie bei den
  übrigen Upstream-Kopien) und vergleichen jede Datei der Kopie mit ihrer SHA-256 aus dem Manifest. Eine geänderte, fehlende
  oder zusätzliche Datei lässt den Build scheitern. CRLF zählt wie LF, weil ein Git-Checkout Zeilenenden umstellen darf;
  `.gitignore` und `.gitattributes` werden mitkopiert, aber nicht geprüft (Gradle lässt sie in Dateibäumen aus).
- **Gegen den Upstream selbst:** `libs/sync-from-upstream.sh verify [<bibliothek>]` holt den festgehaltenen Commit, und
  vergleicht ihn per `diff -r` mit der Kopie.

## Mitentwickeln

Wer an einer Bibliothek arbeitet, ändert sie in ihrem eigenen Repository. Zum Ausprobieren im Space Harvester nennt
`-Paresstack.libs=<ordner>` (oder `aresstack.libs=<ordner>` in der eigenen `gradle.properties`) den Ordner mit den eigenen
Klonen `knowledge4j`, `playwright4j`, `research4j` und `writing4j`; dann baut Gradle diese statt der Kopien. Danach den Stand per `sync`
übernehmen.
