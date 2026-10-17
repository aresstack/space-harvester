# ADR 0008: Desktop-App als Swing-Anwendung direkt auf der Core-Bibliothek

- Status: **Angenommen** (Strang L, 17.10.2026; Auftrag von Angelo in #103).
- Bedienung, Konfiguration und Rückverfolgbarkeit: [docs/DESKTOP_APP.md](../DESKTOP_APP.md).

## Kontext

Für die Live-Abnahme (#7, #31, #33/#34, #89) braucht Angelo eine eigenständige Desktop-Anwendung, die alles kann, was
der Dienst kann, ohne den Dienst zu starten. #103 verlangt: eigenes ausführbares JAR, direkte Nutzung der Core-
Anwendungsfälle ohne REST und ohne Spring-Boot-Sidecar, Comic-Design des Enterprise AI Client, aber eigene
Benutzerführung; die Weboberfläche (#17) bleibt getrennt.

## Entscheidung

1. **Modul `:space-harvester-app`** hängt nur an `:space-harvester-core` und `:comic-controls`, nie an
   `:space-harvester-server`. Einstieg ist `core.Harvester` (ADR 0006); die App baut keine Anwendungsfälle selbst.
2. **Swing mit kopierten Comic-Controls.** `:comic-controls` ist eine unveränderte Kopie des Moduls
   `comic-controls` aus `aresstack/enterprise-ai-client` (MIT), festgehalten wie `enterprise-ai-sources`
   (`UPSTREAM.md`, `UPSTREAM.sha256`, `sync-from-upstream.sh verify` in der CI). Gleiche Bedienelemente, Farben und
   Fensterform wie der Client, ohne dessen Anwendungsschichten.
3. **Konfiguration wie der Dienst.** Die App liest YAML mit den Schlüsseln des Dienstes (`space-harvester:`,
   `spring.ssl.bundle`) per SnakeYAML ohne Spring; Platzhalter `${NAME:Standard}` aus Systemeigenschaften und Umgebung.
   Fehlende Geheimnisse fragt sie für die Sitzung ab und hält sie nur im Speicher. Key Vault und Config Server bleiben
   Spring-Profile des Dienstes; ihre Werte erreichen die App über Umgebungsvariablen.
4. **Zugriff wie die Controller.** Die App setzt `LocalOperatorPolicy` (nur der App-Aufrufer, nur konfigurierte
   Quellen) und prüft in `Workspace` dieselben `AccessKind`s wie die REST-Controller, einschließlich verdeckter Seiten.
5. **Packaging per Spring-Boot-Plugin, Laufzeit ohne Spring.** `bootJar` legt die Bibliotheken als verschachtelte JARs
   ab (dieselbe Lösung, mit der GraalJS für PAC im Dienst läuft); zur Laufzeit startet nur `SpaceHarvesterApp.main`.
6. **Eigener Datenordner** (`~/.space-harvester/data`): Lucene sperrt ihn; App und Dienst teilen ihn nicht.
7. **Auftragsliste der App.** `Jobs` kennt keine Liste je Eigentümer; die App merkt sich ihre Auftrags-IDs in
   `<data>/app/activities.txt` und fragt den Zustand beim Core ab.

## Alternativen

- **JavaFX oder Compose:** eigene Laufzeit und Pakete je Betriebssystem, kein vorhandenes Comic-Design. Verworfen.
- **App als Client des Dienstes über REST:** widerspricht #103 (kein Sidecar, keine REST-Aufrufe).
- **Comic-Controls als Maven-Abhängigkeit:** das Modul ist nicht veröffentlicht; eine Kopie mit Prüfsummen hält den
  Stand fest, wie bei `enterprise-ai-sources`.

## Folgen

- Ein zweiter Bedienweg auf denselben Anwendungsfällen; neue Core-Funktionen brauchen eine App-Aktion und eine Zeile
  in der Rückverfolgbarkeitstabelle.
- Das JAR ist groß (ONNX Runtime, GraalJS, PDFBox), weil es alles enthält, was der Core braucht.
- Offen: Bildbeschreibung von Anhängen (#19) und ein Assistent, der Quellen in die YAML-Datei schreibt.
