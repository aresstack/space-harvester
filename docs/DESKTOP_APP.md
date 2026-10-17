# Space Harvester Desktop (#103)

Die Desktop-App ist ein eigenes ausführbares JAR (`space-harvester-app-<version>.jar`, Modul
`:space-harvester-app`). Sie nutzt die Anwendungsfälle der Bibliothek `:space-harvester-core` direkt über
`com.aresstack.spaceharvester.core.Harvester`: kein REST, kein lokaler HTTP-Server, kein Spring-Kontext, keine
Abhängigkeit auf `:space-harvester-server`. Die Weboberfläche des Dienstes (#17) bleibt davon getrennt und unverändert.
Entscheidung und Alternativen: [ADR 0008](adr/0008-desktop-app.md).

## Starten unter Windows

Voraussetzung ist Java 21 (z. B. Eclipse Temurin 21, `java -version` zeigt 21).

```bat
java -jar space-harvester-app-<version>.jar
```

Optionen:

| Option | Bedeutung |
|---|---|
| `--config <datei>` | andere Konfigurationsdatei; ohne Angabe `SPACE_HARVESTER_APP_CONFIG` oder `%USERPROFILE%\.space-harvester\space-harvester-app.yml` |
| `--screenshot <ordner>` | rendert jeden Bereich als PNG und beendet sich (Sichtprüfung) |
| `--version` | Version ausgeben |

Beim ersten Start ohne Datei führt **Wissensquellen › Quelle hinzufügen …** durch die Einrichtung (siehe
[Quellen-Assistent](#quellen-assistent)). Alternativ legt **Einstellungen › Beispiel anlegen** eine kommentierte
Beispieldatei an und öffnet sie im Standardeditor; nach dem Speichern **Neu laden** wählen.

## Quellen-Assistent

**Wissensquellen › Quelle hinzufügen …** legt eine Quelle in der Konfigurationsdatei an:

1. **Art:** Confluence (Data Center/Server) oder lokaler Ordner, dazu ID (Kleinbuchstaben, Ziffern, Bindestrich) und Titel.
2. **Adresse:** Basis-URL ohne `/rest` bzw. Ordner und Dateimuster.
3. **Anmeldung** (Confluence): Personal Access Token, Benutzer und Passwort oder ohne. In die Datei kommt nur der
   Platzhalter der Umgebungsvariable, z. B. `token: ${WIKI_TOKEN}`. Ein hier eingegebener Wert gilt nur für den Test
   und bis zum Schließen der App.
4. **Zertifikate und Netz** (Confluence): Client-Zertifikat aus dem Windows-Zertifikatsspeicher oder als PKCS#12-Datei,
   jeweils mit Alias (Fingerabdruck optional zur zusätzlichen Prüfung; PKCS#12-Passwort nur als Umgebungsvariable), Vertrauen über ein vorhandenes
   `spring.ssl.bundle`. Der Proxy gilt für alle Quellen und bleibt in der Datei (`space-harvester.network.proxy`).
5. **Prüfen und speichern:** Vorschau des Eintrags. **Verbindung testen** startet einen eigenen Harvester nur mit der
   neuen Quelle in einem leeren Arbeitsordner und lädt die Spaces live; der Bestand der laufenden App bleibt
   unberührt. **Speichern** hängt den Eintrag an `space-harvester.sources` an, lässt Kommentare und den Rest der Datei
   unverändert, legt die vorige Fassung als `<datei>.bak` ab und lädt neu.

Ändern oder Entfernen einer Quelle geschieht weiter in der Datei (**Einstellungen › Datei öffnen**).

## Konfiguration

Die Datei verwendet dieselben Schlüssel wie der Dienst (`space-harvester:` und `spring.ssl.bundle`, siehe
[README](../README.md), [CONFIGURATION.md](CONFIGURATION.md), [MTLS.md](MTLS.md)), dazu `space-harvester-app:`.
Eine Server-`application.yml` lässt sich also weitgehend übernehmen. Schlüssel werden wie bei Spring locker
verglichen (`base-url`, `baseUrl`, `base_url`).

```yaml
space-harvester-app:
  data-directory: D:/SpaceHarvester/daten     # Standard: %USERPROFILE%\.space-harvester\data

space-harvester:
  sources:
    - id: confluence
      type: confluence
      title: Confluence
      base-url: https://confluence.example.org
      auth:
        type: pat                               # none | basic | pat
        token: ${CONFLUENCE_TOKEN}
      client-certificate:                       # optional, mTLS zusätzlich zur Anmeldung
        enabled: true
        type: windows-my                        # pkcs12 | windows-my
        thumbprint: ${CONFLUENCE_CLIENT_THUMBPRINT}
      trust-bundle: firma                       # optional, Name unter spring.ssl.bundle
  network:
    proxy:
      mode: pac-url-windows-settings            # wie beim Dienst; PAC-Fehler fallen nie auf DIRECT zurück
    async-transport: web-client

spring:
  ssl:
    bundle:
      pem:
        firma:
          truststore:
            certificate: D:/SpaceHarvester/firma-ca.pem
```

- **Geheimnisse** (Passwort, Token, Zertifikatspasswort) nie in die Datei schreiben, sondern als Platzhalter
  `${NAME}` referenzieren. Platzhalter kommen aus Java-Systemeigenschaften (`-DNAME=…`), dann aus
  Umgebungsvariablen. Unter Windows dauerhaft: `setx CONFLUENCE_TOKEN "…"`, danach die App neu starten.
- **Fehlt ein Token oder Passwort**, startet die Quelle nicht, die App läuft aber weiter: Wissensquellen zeigt
  „Zugangsdaten fehlen“, und **Zugangsdaten eingeben …** fragt den Wert verdeckt ab. Er bleibt nur bis zum Schließen
  der App im Speicher und wird nie gespeichert.
- **Fehler in der Datei** verhindern den Start nicht: Die betroffene Quelle fehlt, Übersicht und Einstellungen nennen
  Quelle und Feld. YAML-Fehler nennen nur die Zeile, nie den Inhalt.
- **Azure Key Vault** und **Config Server** sind Spring-Profile des Dienstes; die App liest sie nicht direkt.
  Werte aus Key Vault kommen als Umgebungsvariable in die App (z. B. Startskript mit
  `az keyvault secret show --query value`).
- **Datenordner:** Bestand (Lucene), Aufträge und Analysen liegen unter `data-directory/cache`, Exporte unter
  `data-directory/exports`, Anhänge unter `data-directory/assets`. Ein laufender Dienst und die App dürfen nicht
  denselben Datenordner verwenden (Lucene sperrt ihn).
- **Protokoll:** `%USERPROFILE%\.space-harvester\logs\space-harvester-app.log` (sieben Tage), anderer Ort über
  `SPACE_HARVESTER_LOG_DIR`. Geheimnisse erscheinen dort nie.

## Bereiche

| Bereich | Inhalt |
|---|---|
| Übersicht | Was fehlt (Konfiguration, Zugangsdaten), Quellen mit geprüftem Verbindungsstand, letzte Aufträge |
| Wissensquellen | Quelle hinzufügen (Assistent), Quelle wählen, Einstellungen ohne Geheimnisse, Fähigkeiten, Verbindung prüfen, Spaces live, Zugangsdaten für die Sitzung, Abgleich und Export starten |
| Inhalte | Seiten eines Space aus dem Bestand oder live, stückweise geladen, als Liste oder Baum, Titelfilter; Seite per ID oder Adresse öffnen; Seite mit Inhalt, Eigenschaften, Anhängen, Analyse, Originaldaten und Technik |
| Aktivitäten | Abgleiche und Exporte mit Fortschritt, Ergebnis je Kategorie, Teilfehlern, Abbruch, erneutem Abgleich und Speichern der Exportdateien |
| Exporte | Space und Formate wählen, Export starten, letzte Exporte |
| Suche | Titel- und Metadatensuche im Bestand über alle oder eine Quelle, sortiert nach Titel, Änderung oder Relevanz |
| Einstellungen | Konfigurationsdatei, Hinweise, Ablage, Netz und Zertifikate, Embedding-Modell, Diagnose |

Lange Arbeit (Netz, Lucene, Export) läuft immer im Hintergrund; die Oberfläche zeigt „läuft …“ und bleibt bedienbar.
Fehler erscheinen als verständliche Meldung mit Hinweis; Technische Details (Fehlerart, Code) lassen sich aufklappen,
Ursachenketten mit möglichen Geheimnissen nie.

**Raw-First:** Originaldaten zeigt die App byte-genau als Text (JSON eingerückt nur in der Anzeige) und speichert sie
mit „Original speichern …“ unverändert. „Aktualität prüfen“ ist `refresh=true`: echte Prüfung gegen die Quelle. Ein
Abgleich heißt nur „vollständig“, wenn der Core die Generation bestätigt hat; sonst zeigt Aktivitäten den Grund.

**Zugriff:** Die App läuft im Vertrauensbereich des angemeldeten Windows-Benutzers. Sie setzt eine eigene
`AccessPolicy` (`LocalOperatorPolicy`): freigegeben ist nur der App-Aufrufer und nur für konfigurierte Quellen. Alle
Prüfungen der REST-Controller (metadata, content, export, sync; verdeckte Seiten unbekannter Spaces) laufen in
`Workspace` genauso; was die Quelle selbst verweigert, bleibt verweigert.

## Rückverfolgbarkeit REST → Anwendungsfall → App → Test

| REST (Dienst) | Anwendungsfall (Core) | App-Aktion | Test |
|---|---|---|---|
| `GET /api/v1/sources` | `SourceQueries.sources` | Wissensquellen, Übersicht | `WorkspaceTest.harvestsBrowses…` |
| `GET …/sources/{id}/spaces` | `SourceQueries.partitions` | Verbindung prüfen, Space-Auswahl | `WorkspaceTest.harvestsBrowses…`, `…onlyConfiguredSourcesAreReachable` |
| `GET …/spaces/{key}/pages/metadata` | `SourceQueries.pageMetadata` | Inhalte › Live | `WorkspaceTest.harvestsBrowses…` |
| `GET …/pages/{pageId}/metadata` | `SourceQueries.pageMetadata` | Seite › Eigenschaften (live) | `MainWindowTest` (Navigation) |
| `GET …/pages/{pageId}/attachments` | `SourceQueries.attachments` | Seite › Anhänge | `WorkspaceTest.attachmentsAre…` |
| `GET …/spaces/{key}/pages` | `PageQueries.pages` | Inhalte › Bestand | `WorkspaceTest.harvestsBrowses…`, `…readsLocalFiles…` |
| `GET …/spaces/{key}/tree` | `PageQueries.tree` | Inhalte › Struktur | `WorkspaceTest.harvestsBrowses…` |
| `GET …/pages/{pageId}` (`refresh`, `view=extended`) | `PageQueries.page`, `Enrichments.view` | Seite öffnen, Aktualität prüfen, Analyse | `WorkspaceTest.harvestsBrowses…` |
| `GET …/pages?url=` | `PageQueries.pageByUrl` | Inhalte › Seite öffnen (Adresse) | `WorkspaceTest.pagesOpenByAddress…` |
| `POST …/pages/{pageId}/enrichments` | `Enrichments.recompute` | Seite › Analyse › Neu berechnen | `WorkspaceTest.pagesOpenByAddress…` |
| `GET /api/v1/search` | `PageSearch.search` | Suche (Titel, Neueste), Kopfzeilensuche | `WorkspaceTest.harvestsBrowses…`, `MainWindowTest` |
| `GET /api/v1/search?sort=relevance` | `RelevanceSearch.search` | Suche › Relevanz | `WorkspaceTest.harvestsBrowses…` |
| `POST …/spaces/{key}/syncs` | `SpaceKeyCheck`, `SpaceSyncJobs.start` | Synchronisieren, Erneut abgleichen | `WorkspaceTest.*`, `MainWindowTest` |
| `GET /api/v1/syncs/{id}` | `SpaceSyncJobs.status` | Aktivitäten | `WorkspaceTest.harvestsBrowses…` |
| `POST /api/v1/syncs/{id}/cancel` | `SpaceSyncJobs.cancel` | Aktivitäten › Abbrechen | `WorkspaceTest.aRunningSyncCanBeCancelled` |
| `POST …/spaces/{key}/exports` | `Exports.start` | Exporte › Export starten | `WorkspaceTest.harvestsBrowses…` |
| `GET /api/v1/exports/{id}` | `Exports.artifact` | Aktivitäten › Ergebnis | `WorkspaceTest.harvestsBrowses…` |
| `GET /api/v1/exports/{id}/artifact/**` | `Exports.artifactFile` | Aktivitäten › Dateien speichern … | `WorkspaceTest.harvestsBrowses…` |
| `POST /api/v1/exports/{id}/cancel` | `Jobs.cancel` | Aktivitäten › Abbrechen | – |
| `GET …/pages/{pageId}/assets` | `Assets.assets` | Seite › Anhänge | `WorkspaceTest.attachmentsAre…` |
| `POST …/pages/{pageId}/assets/harvest` | `Assets.harvest` | Seite › Anhänge › Alle ablegen | `WorkspaceTest.attachmentsAre…` |
| `GET …/assets/{id}/content` | `Assets.content` | Seite › Anhänge › Speichern … | `WorkspaceTest.attachmentsAre…` (byte-genau) |
| `GET/POST …/assets/{id}/description` | `Assets.describe` | noch nicht (braucht Vision-Modell, #19) | – |
| `GET /api/v1/info` | – (Dienst-Metadaten) | Einstellungen › Diagnose: App-, Java- und Systemversion, Analysen, Exportformate | `MainWindowTest` |
| `/api/compat/confluence/**` | Fassade | entfällt: REST-Kompatibilität für Fremdprogramme | – |
| – | `Harvester.builder` mit nur der neuen Quelle, `SourceQueries.partitions` | Wissensquellen › Quelle hinzufügen … (Verbindung testen, Speichern) | `SourceWizardTest`, `ConfigFileEditorTest`, `WorkspaceTest.theWizardTests…` |
| – | – | Konfiguration, Platzhalter, fehlende Zugangsdaten | `AppSettingsLoaderTest`, `PlaceholdersTest`, `WorkspaceTest.aSourceWithoutToken…` |
| – | Auftragsliste der App | Aktivitäten nach Neustart | `WorkspaceTest.theActivityListSurvivesARestart` |

„–“ in der Testspalte heißt: Die Aktion ist gebaut, aber noch nicht automatisch getestet.

## Bauen und prüfen

```bash
./gradlew :space-harvester-app:test :space-harvester-app:bootJar
java -jar space-harvester-app/build/libs/space-harvester-app-<version>.jar --version
xvfb-run -a java -jar space-harvester-app/build/libs/space-harvester-app-<version>.jar --config app.yml --screenshot shots
```

Die CI baut das JAR, startet `--version` und rendert die Bereiche unter Xvfb (`appJarCheck`).
