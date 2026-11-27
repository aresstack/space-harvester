# Übergabe an AskAI

## Empfohlene Einbaureihenfolge

1. Modul als `:web-search-providers` in das AskAI-Multiprojekt übernehmen.
2. `research-agent-runtime` nur gegen `WebSearchProvider`, `WebSearchRequest` und `WebSearchResult` koppeln.
3. Die vorhandenen Brave-, Bright-Data- und DataForSEO-Bindings durch die drei Adapter dieses Moduls ersetzen.
4. Die AskAI-Einstellungs-UI auf die drei konkreten Config-DTOs mappen.
5. Secrets ausschließlich über die `save...(configuration, char[])`-Methoden schreiben.
6. Provider nach einer Konfigurationsänderung kontrolliert neu erzeugen.
7. Completion-Callbacks vor Swing-Zugriff auf den EDT dispatchen.

## Bewusste Grenzen

- Keine kostenpflichtigen Live-Integrationstests.
- Kein KeePass-Adapter; `SecretKeyProvider` ist die spätere Austauschnaht.
- Keine allgemeine Proxy-/PAC-Integration; diese gehört in den gemeinsamen AskAI-HTTP-Unterbau.
- Bright Datas serverseitiger Async-Jobmodus ist im mitgelieferten Mapper zunächst auf Google begrenzt. Das normale asynchrone AHC-Transportmodell funktioniert bei allen Bright-Data-Engines.
- DataForSEO `offset` wird nicht generisch abgebildet. DataForSEO crawlt ab Rang 1 bis zur konfigurierten Tiefe.
- Das Composite dedupliziert URLs, führt aber bewusst kein semantisches Reranking durch.
