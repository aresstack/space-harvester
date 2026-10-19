# ADR 0015: Konfiguration zur Laufzeit

- Status: **Angenommen** (18.10.2026; Vorgabe von Angelo in #181, issuecomment-6251484855, Abschnitt
  „Zur Laufzeit ändern, ohne Jobs zu verlieren“).
- Bezüge: Paket `application.configuration` im Core (`ConfigurationManager`, `HarvesterConfiguration`,
  `ConfigurationListener`, `Swappable`), `AiServiceSettings` (ADR 0011), [ADR 0013](0013-suche-ueber-den-cache.md),
  [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md), [CONFIGURATION.md](../CONFIGURATION.md).

## Kontext

Modelle, KI-Dienst, Suchoptionen, Zeitplan und Netzwerk sollen sich ändern lassen, ohne das Programm neu zu
starten und ohne laufende Aufträge zu verlieren. Heute geht das nicht:

- **Desktop:** `AiSettingsPanel.save()` ruft `AppContext.reload()` auf; das baut `AppRuntime` vollständig neu und
  beendet laufende Aufträge.
- **Server:** `AiProperties` bindet `space-harvester.ai.*` beim Start, `AiConfiguration` baut Embedding-Prozessor
  und Reranker mit `@ConditionalOnProperty`. Eine geänderte YAML ändert daran nichts; auch ein Refresh über Spring
  Cloud Config bindet nur Properties neu, baut aber weder bedingte Beans noch Core-Objekte neu.

Desktop und Server sollen dasselbe Modell benutzen, ohne dass der Core Spring kennt (ADR 0010).

## Entscheidung

1. **Ein typisierter, versionierter Schnappschuss.** `HarvesterConfiguration` fasst zusammen, was zur Laufzeit
   wechseln darf: KI-Dienst mit Adresse, API-Key und Modellen je Funktion (Chat, Embeddings, Reranker), Suche
   (Haken „Semantische Suche“, Standard aus; Reranking; Beschaffung der Treffer; Aktualisierungsstrategie je Seite
   `RefreshStrategy`: immer Version prüfen (Standard), zeitbasiert ohne bzw. mit Versionsprüfung), Änderungsquellen
   der Synchronisierung (Webhooks, Nachholen beim Start, Zeitplan, vor jeder Suche; alle optional und standardmäßig
   aus, Webhooks nie Voraussetzung, weil ihre Registrierung Administratorrechte braucht), Netzwerk (Proxy, TLS) und
   Speicherort. Modelle gelten
   **global für alle Quellen**. `ConfigurationSnapshot` trägt dazu eine fortlaufende Version und den
   Aktivierungszeitpunkt.
2. **Ein Dienst im Core, kein Spring.** `ConfigurationManager` liest den wirksamen Schnappschuss (`current()`),
   prüft eine neue Konfiguration (`validate`, verständliche `ConfigurationProblem(key, message)` je Feld, ohne den
   abgelehnten Wert zu nennen) und aktiviert sie (`apply`). Server und App sind Adapter: Sie bauen eine
   `HarvesterConfiguration` (am einfachsten über `fromProperties`, das alle Fehler sammelt) und übergeben sie.
3. **Zweiphasig und atomar.** Komponenten, die Instanzen austauschen (KI-Client, Embeddings, Reranker, Transport,
   Zeitplan, Konnektoren), melden sich als `ConfigurationListener` an. Erst bereiten alle vor (`prepare`: neue
   Instanzen bauen und prüfen, etwa ob der Dienst das Modell kennt), dann tauschen alle aus (`commit`). Lehnt einer
   ab, rollen die übrigen zurück (`rollback`), und die bisherige Version bleibt wirksam. Aktivierungen laufen
   nacheinander; `current()` ist ohne Sperre lesbar.
4. **Laufende Aufträge behalten ihre Konfiguration.** Wer eine Aufgabe beginnt, nimmt den aktuellen Schnappschuss
   bzw. hält eine Instanz über `Swappable.acquire()`. `Swappable.swap` gibt die alte Instanz erst frei, wenn das
   letzte Lease endet. Neue Anfragen und Aufträge bekommen die neue.
5. **Nicht zur Laufzeit: der Speicherort.** Eine Änderung von `cache.directory` nimmt der Dienst an, meldet sie als
   `restartRequired` und lässt den bisherigen Ort wirksam, bis das Programm kontrolliert neu startet. Dasselbe gilt
   für grundlegende Datenbankeinstellungen, sobald es sie gibt.
6. **Embeddings: Modell- oder Dimensionswechsel versioniert den Vektorindex.** `ConfigurationChange.vectorIndex()`
   meldet den Wechsel von einer `VectorIndexVersion(model, dimensions)` zur nächsten. Die Anreicherung `embedding`
   trägt Modell und Dimension schon in ihrer Version; Vektoren einer anderen Version gelten als veraltet und werden im
   Hintergrund aus dem Cache neu berechnet, **ohne Inhalte erneut von der Quelle zu laden**. Die Neuberechnung hängt
   Strang Q als Beobachter an.
7. **Quellen** wechseln weiter über `Harvester.sourceManagement()`, das ihre Konnektoren schon zur Laufzeit
   austauscht; der Dienst führt dafür kein zweites Modell ein.
8. **Eine geänderte Datei allein löst nichts aus.** Der Server übernimmt eine neue Konfiguration nur über einen
   ausdrücklichen Weg (geschützter Endpunkt `POST /api/v1/configuration` oder Refresh über Spring Cloud Config, der
   an den Dienst weiterreicht); einen ungeschützten Actuator-Refresh gibt es nicht. Die App übergibt die gespeicherte
   Konfiguration aus ihren Einstellungen und dem Einrichtungsassistenten an denselben Dienst; beide bearbeiten
   dieselben typisierten Einstellungen.

## Folgen

- Desktop-Assistent und Einstellungen (Strang L) brauchen kein eigenes Konfigurationsmodell und keine
  Neukonstruktion von `AppRuntime` mehr.
- `Harvester.builder()` bekommt den Dienst übergeben (Strang Q, kleiner Eingriff) und meldet intern seine Beobachter
  an; bis dahin wirkt ein Wechsel nur auf Komponenten, die sich selbst anmelden.
- Die Berechtigungsregel ändert sich nicht: Nur die endgültige Ergebnisliste der Cache-Suche wird je Benutzer
  geprüft, und nur bei delegierter Anmeldung ohne eigene Zugangsdaten (#181). Modelle und Reranker bekommen keine
  zusätzliche Prüfung.
- `ProxySettings.Authentication` vergleicht das Passwort als Array nach Identität; ein unverändertes Proxy-Passwort
  kann daher als Änderung gelten und Transporte unnötig neu bauen. Das ist harmlos und wird mit dem
  Transport-Beobachter bereinigt.

## Nachtrag 19.10.2026: Netz-Umstellung zur Laufzeit (#424, #446)

Proxy, PAC, TLS und Proxy-Anmeldung (`network.*`) wechseln ohne Neustart. `ConfiguredSourceCatalog.reconfigure`
setzt Routen, Anmeldung und Vertrauen neu und öffnet alle Quellen neu. Für laufende Aufträge gilt Punkt 4 auch hier:

- **Laufende Abrufe überleben die Umstellung.** Die alten Transporte schließen sanft (`closeGracefully`). Die Pools
  verworfener Clients schließt `WebClientConfluenceTransport.retire` erst nach `pendingAcquireTimeout`
  (`connectTimeout + readTimeout`). Ein sofortiges `disposeLater` beendete Anfragen, die noch auf eine Verbindung
  warteten, mit `PoolShutdownException`. Nach Ablauf der Frist kann keine Anfrage mehr warten, laufende Verbindungen
  enden nach ihrer Antwort. Eine Anfrage, die beim sanften Schließen noch ihre Route wählt, bekommt einen eigenen
  Client, der danach ebenso zurückgezogen wird.
- **`close()` bleibt hart.** Es schließt auch nach `closeGracefully()` sofort alle Pools, auch solche, die in `retire`
  auf ihre Frist warten. Danach bekommt keine Anfrage mehr einen Client.
- **Zielprüfung nur bei geänderten Einstellungen.** `reconfigure` öffnet die Quellen mit ihren unveränderten
  Einstellungen neu und prüft sie nicht erneut gegen die übrigen Quellen. Sonst blieben Quellen mit demselben Ziel
  (beim Start nur eine Warnung) mit altem Vertrauen und alten Routen stehen. Geänderte Quelleinstellungen prüft
  `replace(config)` wie bisher.
- **BASIC im HTTPS-Tunnel beim Start.** Das JDK liest `jdk.http.auth.tunneling.disabledSchemes` nur einmal. Server
  und App rufen deshalb `ProxyAuthenticator.allowBasicTunneling()` vor allen Verbindungen auf. So gilt auch eine erst
  zur Laufzeit eingerichtete Proxy-Anmeldung für `HttpURLConnection`. Eine schon gesetzte Property bleibt.
- **Bewusst nicht aufgeräumt:** Jede Umstellung lässt die vorigen asynchronen Transporte der Quellen in
  `AsyncSnapshotTransports` liegen, je Quelle ein Scheduler samt Evictor-Thread; ihre Pools sind schon verworfen.
  Sie zu schließen bräche Aufträge, die die alte Quelleninstanz noch halten. Umstellungen sind selten und manuell,
  und laufende Aufträge abzubrechen wöge schwerer als die liegen gebliebenen Threads.

## Nachtrag 19.10.2026: Neue Prozessorversionen ohne Handarbeit

Hebt ein Update die Version eines lokalen Prozessors (ohne Netz: `acronyms`, `outline`, `entities`, `properties`,
`activities`, `relations`, `terms`, ohne Embedding-Modell auch `passages` und `navigation`), rechnet der Start seine
älteren Ergebnisse einmal aus dem Cache neu (`VersionRecomputation`), Seite für Seite in einem eigenen Thread und ohne
den Start aufzuhalten. Sonst zählten sie nicht mehr, etwa im Begriffsindex, der nur die aktuelle Version von `terms`
liest, und kämen erst seitenweise zurück. Nur gespeicherte Ergebnisse einer anderen Version von Prozessoren, die die
Seite bearbeiten; ein fehlendes Ergebnis entsteht wie bisher beim nächsten Abgleich. Prozessoren über den KI-Dienst
bleiben bei der Neuberechnung nach Freigabe (Embeddings) und der Suche. Das Log nennt nur Quelle und Anzahl.
