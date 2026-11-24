# comic-controls

Unveränderte Kopie der Swing-/Java2D-Comic-Bibliothek `comic-controls` aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client). Die Desktop-App
(`space-harvester-app`, #103) übernimmt damit Farben, Formen, Schrift und Bedienelemente des Enterprise AI Client,
ohne dessen Oberfläche oder Fachlogik zu kopieren.

Der genaue Upstream-Commit steht in der ersten Zeile von [`UPSTREAM.sha256`](UPSTREAM.sha256); derzeit
`c0fc2bdc8eee6d0f18671f845ecb0408b0ba9519` (Knöpfe im Text der Gedankenblase, EAC #238).

## Inhalt und Lizenz

- Das Upstream-Modul `comic-controls` vollständig (`src/main` und `src/test`), Pakete
  `com.aresstack.enterpriseai.ui.comic.*` unverändert. Es hängt nur vom JDK ab (Swing, Java2D), keine weiteren
  Bibliotheken, keine Projektabhängigkeiten.
- `LICENSE`: die Lizenz des Upstreams (MIT, AresStack), mitkopiert und mitgeprüft. Sie erlaubt die Weitergabe im
  App-JAR; der Lizenztext liegt dem Release bei.

## Unverändertheit

Wie bei `enterprise-ai-sources`: `verifyUpstreamCopy` hängt an `check` und vergleicht jede Datei unter `src/` und
`LICENSE` mit `UPSTREAM.sha256`; `./sync-from-upstream.sh verify` stellt den festgehaltenen Commit neu zusammen und
vergleicht per `diff -r`. Änderungen gehören in den Upstream und kommen per

```bash
comic-controls/sync-from-upstream.sh sync <commit|tag>
```

zurück. Nur `space-harvester-app` nutzt die Bibliothek; Core und Server kennen sie nicht.
