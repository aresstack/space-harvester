# Anweisung für eine lokale Claude-Instanz: REST-Benchmark Synchronisierung (#181)

Diesen Text vollständig in eine lokale Claude-Code-Sitzung auf Angelos Rechner kopieren. Er ist in sich geschlossen.

---

Du führst auf diesem Rechner einen Messlauf gegen die öffentliche Atlassian-Dokumentation aus und legst das Ergebnis ab. Du änderst keinen Code, mergst nichts und lädst keine Seiteninhalte. Alles auf Deutsch.

## Wo alles liegt

| Was | Ort |
|---|---|
| Repository | https://github.com/aresstack/space-harvester |
| Zweig | `claude/rest-benchmark-sync-69fy8s` (Draft-PR, nicht mergen) |
| Werkzeug | `tools/sync-benchmark/ConfluenceSyncBenchmark.java` (eine Datei, Java 21, kein Build, keine Abhängigkeiten) |
| Hintergrund und Auswertungsvorlage | `tools/sync-benchmark/BERICHT.md` |
| Messauftrag | Issue #181, Kommentar https://github.com/aresstack/space-harvester/issues/181#issuecomment-6250887253 |
| Ergebnisordner (neu anlegen lassen) | `tools/sync-benchmark/ergebnisse/confluence-atlassian-<JJJJ-MM-TT>/` |
| Erzeugte Dateien darin | `bericht.md` (alle Tabellen), `anfragen.csv` (jede Anfrage mit Status, Bytes, ms), `snapshot-<Space>.json` (Seiten-IDs, Eltern, Versionen; keine Titel, keine Inhalte) |
| Zugriffsprüfung | `tools/sync-benchmark/ergebnisse/confluence-atlassian-<JJJJ-MM-TT>-probe/bericht.md` |

## Ablauf

1. **Repo und Zweig holen.** Wenn das Repo schon geklont ist, dort `git fetch origin claude/rest-benchmark-sync-69fy8s` und `git checkout claude/rest-benchmark-sync-69fy8s`; sonst `git clone --branch claude/rest-benchmark-sync-69fy8s https://github.com/aresstack/space-harvester.git`. Alle weiteren Befehle laufen im Wurzelordner des Repos.
2. **Java prüfen.** `java -version` muss 21 oder neuer zeigen. Sonst abbrechen und Angelo sagen, dass Java 21 fehlt.
3. **Selbsttest (ohne Netz, 1–2 Minuten).**
   `java tools/sync-benchmark/ConfluenceSyncBenchmark.java --self-test`
   Letzte Zeile muss `SELBSTTEST GRÜN` sein. Wenn nicht, abbrechen und die Ausgabe melden.
4. **Zugriffsprüfung (9 Anfragen).** Datum heute als `<JJJJ-MM-TT>` einsetzen:
   `java tools/sync-benchmark/ConfluenceSyncBenchmark.java --base-url https://confluence.atlassian.com --probe-only --out tools/sync-benchmark/ergebnisse/confluence-atlassian-<JJJJ-MM-TT>-probe`
   In der erzeugten `bericht.md` steht `Gate bestanden: **ja**` oder `**nein**`.
   - **nein:** nicht weiter messen, keine Umwege (kein anderer Proxy, kein Browser-Scraping, kein Login). Weiter mit Schritt 6 und nur die Zugriffsprüfung ablegen; das ist ein gültiges Ergebnis.
   - Hinter einem Firmenproxy ohne Direktzugang: einmal mit `--proxy <host>:<port>` wiederholen (Host und Port aus der PAC-Datei bzw. den Windows-Proxyeinstellungen). Bei TLS-Fehlern einmal mit `java -Djavax.net.ssl.trustStoreType=Windows-ROOT tools/sync-benchmark/ConfluenceSyncBenchmark.java …` wiederholen. Danach nicht weiter probieren.
5. **Messung (nur wenn Gate „ja“).** Kalt- und Warmlauf, Spaces klein/mittel/groß wählt das Werkzeug selbst:
   `java tools/sync-benchmark/ConfluenceSyncBenchmark.java --base-url https://confluence.atlassian.com --repeat 2 --out tools/sync-benchmark/ergebnisse/confluence-atlassian-<JJJJ-MM-TT>`
   Standards, nicht ändern: Seitengröße 50, 1 Anfrage/s, höchstens 400 Anfragen je Verfahren und Space, 3000 je Lauf, Änderungsfenster 30 Tage. Das dauert bis etwa eine Stunde; laufen lassen.
6. **Ablegen.**
   - Den Ergebnisordner bzw. Probe-Ordner committen und auf denselben Zweig pushen: `git add tools/sync-benchmark/ergebnisse/` · `git commit -m "Benchmark #181: Messlauf confluence.atlassian.com <JJJJ-MM-TT>"` · `git push origin claude/rest-benchmark-sync-69fy8s`. Kein Rebase, kein Force-Push, nichts mergen.
   - Den Inhalt von `bericht.md` als Kommentar in Issue #181 posten (z. B. `gh issue comment 181 --repo aresstack/space-harvester --body-file <Pfad zur bericht.md>`), dazu eine Zeile vorweg: „Messlauf confluence.atlassian.com vom <Datum>, Commit <Hash> auf claude/rest-benchmark-sync-69fy8s“. Ohne `gh` oder ohne Recht: Angelo den Pfad nennen, er postet selbst.
7. **Melden.** Angelo in zwei, drei Sätzen sagen: Gate ja/nein, gewählte Spaces mit Seitenzahl, ob der Lauf vollständig oder abgebrochen ist, Commit und Link zum Kommentar.

## Wenn etwas schiefgeht

- **429, 503, Anmeldeseite, Weiterleitung, 401/403 oder Antwort ohne JSON:** Das Werkzeug stoppt von selbst und schreibt den Grund als `**Abbruch:** …` oben in `bericht.md`. Nicht erneut starten, nicht Pause oder Deckel verändern, keine Zugangsdaten verwenden. Den Teilbericht wie in Schritt 6 ablegen; ein Abbruch ist ein Messergebnis.
- **„nein (Deckel)“ in der Tabelle:** gewollt bei großen Spaces, nicht mit höherem Deckel wiederholen.
- **Netzwerkfehler mitten im Lauf:** höchstens einen weiteren Versuch des ganzen Schritts 5, sonst Teilbericht ablegen.
- **Sonstige Fehler (Java-Ausnahme, Kompilierfehler):** Ausgabe vollständig an Angelo melden, nichts am Werkzeug ändern.

## Was das Werkzeug tut und nicht tut

- Es fragt nur Metadaten ab (`version`, `ancestors`, `childTypes.page`), nie `body.*`, keine Anhänge, schreibt nichts in Confluence und arbeitet strikt nacheinander.
- Für confluence.atlassian.com läuft es anonym. **Kein PAT und keine Anmeldung verwenden.**
- Es misst: 0 Zugriff/Version/Änderungssignale, A flache Inventur, B Seitenbaum (`depth=root` + `child/page`), C CQL `lastmodified`, D Teilbaum (CQL `ancestor`/`parent` gegen die Rekursion), F Papierkorb (`status=trashed`, Verweigerung ist erwartet), E lokaler Merkle-Hash (nur Simulation).

## Optional, nur auf Angelos ausdrücklichen Wunsch: eigene Instanz

Gegen eine Firmeninstanz nur, wenn Angelo es verlangt und Basis-URL, Spaces und PAT-Datei selbst nennt:
`java -Djavax.net.ssl.trustStoreType=Windows-ROOT tools/sync-benchmark/ConfluenceSyncBenchmark.java --base-url <URL mit Kontextpfad> --pat-file <Datei mit Token in Zeile 1> --spaces <K1,K2,K3> --repeat 2 --out <Ordner außerhalb des Repos>`
Diese Ergebnisse **nicht committen und nicht posten** (interne Seiten-IDs); Angelo entscheidet, was er davon teilt. Verschiebungen messen: Lauf, eine Seite in einem Test-Space verschieben, zweiter Lauf mit zusätzlich `--compare <erster Ordner>/snapshot-<Space>.json`.
