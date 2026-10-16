# Zugriffsschutz der API

**Stand:** 16.10.2026 · Issue [#14](https://github.com/aresstack/space-harvester/issues/14), Referenz für
Delegation [#24](https://github.com/aresstack/space-harvester/issues/24), Sicherheitsrahmen im
[Implementierungsplan](IMPLEMENTATION_PLAN.md#6-sicherheitsrahmen-vor-produktiver-ausgabe).

Zwei Grenzen bleiben getrennt:

1. **Ernte:** Wie sich der Harvester bei Confluence anmeldet (`auth`, `client-certificate` je Quelle). Was dieses
   technische Konto lesen kann, ist **keine** Freigabe für irgendeinen Aufrufer.
2. **Ausgabe:** Wer etwas aus dem Harvester lesen darf. Darum geht es hier.

## Betriebsarten

| `space-harvester.security.mode` | Verhalten |
| --- | --- |
| `clients` (Standard) | Jede Anfrage an `/api/**` verlangt `Authorization: Bearer <token>` eines konfigurierten technischen Clients (sonst 401). Was er lesen darf, bestimmt seine Korpusfreigabe (sonst 403). Ohne Clients verweigert die API jeden Datenzugriff. |
| `development` | Keine Anmeldung, alles frei. Startet **nur** mit aktivem Spring-Profil `local`; sonst bricht der Start ab. Der Dienst warnt beim Start im Log. |

Ohne Anmeldung erreichbar sind nur `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**` und die Swagger UI:
Probes, Build-Info und die API-Beschreibung, keine Daten aus Quellen.

## Technische Clients und Korpusfreigabe

```yaml
space-harvester:
  security:
    clients:
      - id: knowledge-etl
        # SHA-256 des Tokens, nicht das Token selbst:  printf %s "$TOKEN" | sha256sum
        token-sha256: ${KNOWLEDGE_ETL_TOKEN_SHA256}
        grants:
          - source: confluence
            spaces: ["DEV", "ARCH"]
            access: [metadata]
          - source: confluence
            spaces: ["*"]          # alle Spaces der Quelle, auch künftige
            access: [metadata, content, export]
```

- **Token:** Ein zufälliges Geheimnis je Client (z. B. `openssl rand -base64 32`). Konfiguriert wird nur sein
  SHA-256-Wert (64 Hex-Zeichen, optional mit Präfix `sha256:`). Jeder Client braucht ein eigenes Token.
- **`access`:** unabhängige Arten, keine schließt eine andere ein.
  - `metadata`: Existenz und Verwaltungsdaten: Quellen, Spaces, Seiten-IDs, Titel, Elternbeziehungen, Baumknoten,
    Versionen, Hashes, Suchtreffer. Schon ein Titel kann vertraulich sein.
  - `content`: native Payload (`body.storage` in der Confluence-JSON-Antwort) und alles daraus Abgeleitete:
    Enrichments, Bildbeschreibungen, Converter-Ergebnisse.
  - `export`: Exportjobs starten, abbrechen, ihren Status lesen, Artefakte herunterladen.
- **`spaces`:** Space-Keys genau so, wie die Quelle sie liefert, oder allein `"*"`.
- Der Start bricht ab bei: Klartext statt Hash, doppeltem Token oder doppelter Client-ID, Freigabe für eine nicht
  konfigurierte Quelle, leerer `spaces`- oder `access`-Liste, `"*"` neben Space-Keys.

## Antworten

| Fall | Antwort |
| --- | --- |
| Kein oder unbekanntes Token | 401 `unauthenticated`, `WWW-Authenticate: Bearer` |
| Quelle oder Space nicht freigegeben, auch unbekannte Quellen-ID | 403 `access-denied`, ohne IDs oder Gründe; die Quelle wird nicht gefragt |
| Einzelne Seite in einem nicht freigegebenen Space | 404 `source-object-not-found`, genau wie für eine unbekannte Seite, damit die Antwort keine Existenz verrät |
| Listen (Quellen, Spaces) | nur die freigegebenen Einträge |

Problem Details nennen keine Tokens, Zugangsdaten oder Meldungen der Quelle. Tokens erscheinen weder in Logs noch in
Antworten; eine Anmeldung mit unbekanntem Token loggt nur Methode und Pfad.

## Für neue Endpunkte (Stränge E und G)

Controller erhalten den Aufrufer als Parameter `@Parameter(hidden = true) AccessContext caller` und prüfen
ausschließlich über `application.access.AccessGuard`:

- **Vor dem Lesen**, wenn der Umfang bekannt ist: `access.require(caller, ResourceScope.space(sourceId, spaceKey),
  AccessKind.METADATA)`; für Export `AccessKind.EXPORT`, für Rohinhalte `AccessKind.CONTENT`.
- **Listen, Bäume, Suchtreffer:** nach dem Lesen mit `access.filter(...)` beschneiden, bevor etwas die API verlässt.
  Ein Baum darf keine Knoten aus nicht freigegebenen Spaces zeigen, auch nicht als IDs.
- **Einzelne Seite, deren Space erst nach dem Lesen feststeht:** `access.requireConcealed(...)`, dann antwortet die
  API wie bei einer unbekannten Seite. Aus dem Cache ist der Space bekannt: `ResourceScope.page(sourceId,
  spaceKey, pageId)`.
- **Inhalt:** `access.canRead(caller, sourceId, spaceKey, pageId)` (Art `content`).
- Ein Cache-Treffer, eine gespeicherte Revision oder `refresh=true` ersetzt nie die Prüfung. Die Berechtigung zum
  Aktualisieren ist von der zur Ausgabe getrennt.
- Tests mit Fakes: `new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW)` bzw. `DENY`; für
  HTTP-Tests `TestClient.register(registry, "<sourceId>")` und `TestClient.authenticated(request)`.

## Delegierter Zielmodus (#24, Teil C)

Später prüft eine weitere `AccessPolicy` mit Aufrufern der Art `END_USER` die aktuelle Leseberechtigung bei
Confluence mit dem delegierten Token dieses Benutzers (etwa `GET /rest/api/content/{id}?expand=version` ohne Body).
Nur bestätigter Erfolg gibt frei; 401/403/404, Login-Redirect, Timeout und unklare Antworten ergeben `UNDETERMINED`
bzw. `DENY`, und der `AccessGuard` verweigert. Controller und Anwendungsfälle ändern sich dafür nicht. Die heutige
`CorpusReleasePolicy` verweigert `END_USER` grundsätzlich.

## Noch offen

- OAuth-2.0-Delegation und Benutzeranmeldung (#24, Teil C), optional Azure Key Vault (#22).
- Rechteprüfung in den neuen Cache-, Baum-, Such- und Exportendpunkten beim Anbinden von E und G.
- Grenzen für gleichzeitige Jobs und Downloads, sichere Artefaktpfade (mit #11/#12).
