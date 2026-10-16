# enterprise-ai-sources

Unveränderte Kopie der Confluence-Quelle aus
[aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client). Der Space Harvester baut
damit keinen eigenen Confluence-Client, sondern nutzt denselben Code wie der Enterprise AI Client.

**Stand:** Commit `814318aed98e9d35b8142fe1fb797b55b77aa158` (`main`, nach Release `v0.80.0`)

## Inhalt

| Upstream-Modul | Inhalt hier |
|---|---|
| `astu` | nur die 15 Klassen, die die Module unten transitiv brauchen (Liste in `sync-from-upstream.sh`) |
| `adyton` | vollständig: `SecretProvider`, `SecretRef`-Auflösung |
| `proasteion/platform/network` | vollständig: `HttpRoutePort` |
| `source-api` | vollständig: `KnowledgeSourcePort`, `KnowledgeSourceProvider`, `PageNavigationSource`, `PageViewSource`, Test-Fixtures |
| `proasteion/emporion/holkas/source-confluence` | vollständig: `ConfluenceRestClient`, `ConfluenceKnowledgeSource`, `FakeConfluence`-Fixtures |

Pakete und Klassennamen bleiben wie im Upstream (`com.aresstack.enterpriseai.*`), damit sich Änderungen
1:1 übernehmen lassen. Die Upstream-Tests (JUnit 4) laufen bei `./gradlew build` mit.

## Regeln

- Keine Änderungen direkt in diesem Subprojekt. Fehler werden im Upstream behoben und dann neu kopiert.
- Space-Harvester-eigener Code (Spring, Konfiguration, SPI) liegt im Hauptprojekt unter
  `com.aresstack.spaceharvester`, nicht hier.

## Abgleich mit dem Upstream

```bash
git clone https://github.com/aresstack/enterprise-ai-client ../enterprise-ai-client
enterprise-ai-sources/sync-from-upstream.sh ../enterprise-ai-client
./gradlew build
```

Danach den Commit oben aktualisieren. Braucht eine neue Upstream-Fassung weitere `astu`-Klassen, scheitert
die Kompilierung; die fehlenden Klassen kommen dann in die Liste im Skript.
