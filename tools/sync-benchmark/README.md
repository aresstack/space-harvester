# Benchmark Metadaten-Synchronisierung (#181)

Misst gegen eine Confluence (Data Center/Server, REST v1), was die Wege zur Bestandsermittlung eines Space kosten: flache Inventur, Seitenbaum, CQL-Änderungssuche, Teilbaum per CQL, Papierkorb. Dazu prüft es, ob ein lokaler Child- oder Merkle-Hash Anfragen spart. Nur Metadaten, keine Seiteninhalte, nacheinander mit Pause. Bei 429/503 oder Anmeldung stoppt es sofort.

```
java tools/sync-benchmark/ConfluenceSyncBenchmark.java --self-test
java tools/sync-benchmark/ConfluenceSyncBenchmark.java --base-url https://confluence.atlassian.com --probe-only --out <ordner>
java tools/sync-benchmark/ConfluenceSyncBenchmark.java --base-url https://confluence.atlassian.com --repeat 2 --out <ordner>
java tools/sync-benchmark/ConfluenceSyncBenchmark.java --help
```

- [ANWEISUNG-LOKALE-CLAUDE.md](ANWEISUNG-LOKALE-CLAUDE.md): Ablauf zum Kopieren in eine lokale Claude-Instanz, mit allen Pfaden.
- [BERICHT.md](BERICHT.md): Verfahren, Anforderungskosten, Antworten auf die fünf Fragen aus #181, Auswertungsvorlage.
- `ergebnisse/`: abgelegte Messläufe gegen confluence.atlassian.com. Läufe gegen interne Instanzen werden nicht eingecheckt.

Das Werkzeug gehört zu keinem Gradle-Modul und wird nicht ausgeliefert.
