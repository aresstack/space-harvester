# Lokaler MCP-Zugriff auf Programmanalysen

Der Desktop-Endpunkt nutzt den bereits geöffneten Wissensbestand und die gemeinsame Programmsuche. Er ist standardmäßig ausgeschaltet und öffnet weder eine zweite Lucene-Datenbank noch eine Verbindung zu FTP oder NDV.

## Starten und stoppen

Unter **Einstellungen → Allgemein → Lokaler MCP-Zugriff auf Programmanalysen** den Zugriff verwalten. Nur lesbare, aktivierte und für KI explizit freigegebene FTP- und NDV-Quellen stehen zur Auswahl. Die Quellen müssen ausdrücklich für diese Endpunkt-Laufzeit ausgewählt werden. Der Dialog zeigt URL und Authorization-Header zur Übernahme in einen lokalen MCP-Client an.

Der Server bindet ausschließlich an IPv4-Loopback (127.0.0.1), an einen vom Betriebssystem vergebenen Port. Das zufällige Bearer-Token gilt nur bis zum Stoppen oder Schließen der aktuellen Runtime. Keine Freigabe über LAN, Internet oder Browser (Origin-Header werden abgelehnt). Keine Weitergabe des Tokens an fremde Dienste. Der Endpunkt erzeugt keine dauerhaften Seitenfreigaben und respektiert die aktuelle Quellenfreigabe bei jeder Anfrage.

## Transport und Werkzeuge

Streamable HTTP, JSON-RPC 2.0, MCP-Version 2025-11-25; zusätzlich ältere Streamable-HTTP-Versionen 2025-06-18 und 2025-03-26. JSON-Antworten, keine SSE-Verbindung und keine Session-ID. Native lokale Clients mit konfigurierbarem Authorization-Header verwenden. Die neue MCP-Protokollgeneration 2026-07-28, OAuth und Remote-Betrieb sind nicht implementiert.

| Werkzeug | Zweck |
|---|---|
| program_sources | Verfügbare, aktuell freigegebene Quellen-IDs |
| program_search | Suchmodi der gemeinsamen Programmsuche; Sprache/Quelle, Suchtext, Offset, Limit |
| program_analysis | Einzelne Abschnitte einer aktuellen technischen Analyse anhand Quellen-ID, Page-ID und rawSha256 |

Für program_analysis zunächst ohne section die verfügbaren Abschnitte abfragen. Für COBOL stehen unter anderem elements, calls, references, diagnostics, symbols, operations, readSites, procedures, nodes, edges, dataLimitations und controlLimitations zur Verfügung. procedures liefert Metadaten samt Index, nodes und edges verwenden den nullbasierten procedure-Index. JCL und Natural liefern die für sie gespeicherten Abschnitte. Offset beginnt bei 0, Limit maximal 50 pro Anfrage. Ein zu großer Antwortkörper wird explizit abgelehnt; kleinere Seiten anfordern. Keine Gesamtbegrenzung der Trefferzahl, keine Live-Analyse.

Beispielargumente für program_search:

```json
{"language":"JCL","mode":"CANDIDATES","query":"BOOKING","limit":10,"offset":0}
```

Zielkandidaten sind ausschließlich typgerechte Namensbeziehungen, keine Laufzeitbindungen. Die Fundstellen und Belege enthalten die genaue Dokumentidentität, Quellposition und Payload-Prüfsumme. Veraltete oder nicht mehr lesbare Belege werden zurückgewiesen. Jede Abfrage liest den aktuellen Bestand; während einer Änderung ist keine transaktionale Mehrdokument-Snapshot-Garantie gegeben. Fachliche KI-Hypothesen sind über diesen Endpunkt nicht abrufbar.

## Prüfung

ProgramMcpServerTest testet den echten Loopback-HTTP-Endpunkt mit gespeicherten Lucene-Analysen, Authentifizierung, Origin-Sperre, Quellenauswahl, typgerechte Treffer, Seitenbildung, COPY-Abhängigkeiten und Freigabeentzug.
