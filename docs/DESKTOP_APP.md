# Space Harvester Desktop (#103)

Die Desktop-App ist ein eigenes ausführbares JAR (`space-harvester-app-<version>.jar`, Modul
`:space-harvester-app`). Sie nutzt die Anwendungsfälle der Bibliothek `:space-harvester-core` direkt über
`com.aresstack.spaceharvester.core.Harvester`: kein REST, kein lokaler HTTP-Server, kein Spring-Kontext, keine
Abhängigkeit auf `:space-harvester-server`. Die Weboberfläche des Dienstes (#17) bleibt davon getrennt und unverändert.
Entscheidung und Alternativen: [ADR 0008](adr/0008-desktop-app.md).

## Starten unter Windows

Voraussetzung ist Java 21 (z. B. Eclipse Temurin 21, `java -version` zeigt 21).

1. Unter [Releases](https://github.com/aresstack/space-harvester/releases) die gewünschte Version öffnen
   (Testversionen wie `v0.1.0-beta.1` sind als *Pre-release* markiert, `snapshot` ist der letzte Stand eines
   Entwicklungs-Branches) und `space-harvester-app-<version>.jar`
   sowie `SHA256SUMS` herunterladen.
2. Optional die Prüfsumme vergleichen: `certutil -hashfile space-harvester-app-<version>.jar SHA256` muss den Wert aus
   `SHA256SUMS` ergeben.
3. Starten:

```bat
java -jar space-harvester-app-<version>.jar
```

Optionen:

| Option | Bedeutung |
|---|---|
| `--config <datei>` | andere Konfigurationsdatei; ohne Angabe `SPACE_HARVESTER_APP_CONFIG` oder `%USERPROFILE%\.space-harvester\space-harvester-app.yml` |
| `--screenshot <ordner>` | rendert jeden Bereich als PNG und beendet sich (Sichtprüfung) |
| `--version` | Version ausgeben |

Beim ersten Start ohne Quelle begrüßt die Übersicht mit der Weltraumszene und **Erste Wissensquelle hinzufügen …**;
das öffnet denselben Assistenten wie **Wissensquellen › Quelle hinzufügen …** (siehe
[Quellen-Assistent](#quellen-assistent)). Alternativ legt **Einstellungen › Beispiel anlegen** eine kommentierte
Beispieldatei an und öffnet sie im Standardeditor; nach dem Speichern **Neu laden** wählen.

## Erscheinungsbild

Die beiden Markenbilder liegen unverändert als Original in
`space-harvester-app/src/main/resources/com/aresstack/spaceharvester/app/images/` und werden aus dem Klassenpfad
geladen (`ui/brand/BrandImages`), funktionieren also auch im ausführbaren JAR:

| Datei | Verwendung |
|---|---|
| `space-harvester-ship.png` (freigestellt, transparent) | Anwendungssymbol für Fenster, Taskleiste und Alt+Tab (`ApplicationIcons`, 16 bis 256 Pixel), kleines Logo links in der eigenen Titelleiste |
| `space-harvester.png` (Weltraumszene) | Willkommensansicht der Übersicht, solange keine Quelle eingerichtet ist; Dialog **Einstellungen › Über Space Harvester** |

Alle kleineren Fassungen entstehen beim Start aus den Originalen, immer gleich und ohne erzeugte Bilddateien im
Repository: auf das Schiff zuschneiden, quadratisch auffüllen, in Halbierungsschritten mit vormultipliziertem Alpha
verkleinern (keine dunklen Säume am blauen Schein). Bis 32 Pixel wird enger zugeschnitten, der schwache Schein fällt
weg und das Schiff füllt die Kachel. Das Titelleisten-Logo enthält 24, 36 und 48 Pixel für 100, 150 und 200 %
Skalierung. Kein Startbild (Splash), kein Banner im Dashboard: Mit eingerichteten Quellen gehört der Platz den Daten.
Eine ICO-Datei entsteht erst mit einem Windows-Installer; bis dahin reicht das Fenstersymbol für die Taskleiste.

Alle Fenster sind rahmenlos im Comic-Stil wie im Enterprise AI Client: Hauptfenster, Quellen-Assistent, Rückfragen,
Token-Abfrage und „Über Space Harvester“ haben statt der Windows-Titelleiste eine eigene Kopfzeile (zum Verschieben
ziehen, am Rand vergrößern) mit rundem ✕; Escape bricht ab. Das ✕ steckt in einem eigenen Halter und behält seine
quadratische Größe (24 Pixel), damit der Ring bei jeder Skalierung kreisrund bleibt. Im Hauptfenster minimiert ein
kurzer Klick, Gedrückthalten schließt. Unten rechts öffnet **AresStack** in „© 2026 AresStack“ die Release-Übersicht
https://github.com/aresstack/space-harvester/releases im Standardbrowser (beim Darüberfahren blau unterstrichen).

Sichtprüfung unter Windows: Taskleiste hell und dunkel, Anzeige 100, 150 und 200 % (Einstellungen › Anzeige ›
Skalierung, danach App neu starten), Alt+Tab, Titelleiste. Das Schiff ist schräg und breit; bei 16 Pixeln soll es als
helles Schiff mit blauen Triebwerken erkennbar sein, die Ecken bleiben durchsichtig.

## Quellen-Assistent

**Wissensquellen › Quelle hinzufügen …** legt eine Quelle in der Konfigurationsdatei an:

1. **Quelle:** zuerst Art und Adresse (Basis-URL ohne `/rest`) bzw. Ordner und Dateimuster. Daraus schlägt der
   Assistent **Anzeigename** (wie die Quelle in der App heißt) und **Technische Kennung** (Kleinbuchstaben, Ziffern,
   Bindestrich; steht in Aufträgen, Exporten und der Datei) vor, z. B. `https://confluence.example.org` →
   `confluence-example`; ist die Kennung schon vergeben, kommt `-2` dazu. Beides nur ändern, wenn nötig; selbst
   Eingetragenes bleibt stehen. Bleibt die Adresse leer, gilt die öffentliche Confluence-Hilfe von Atlassian
   `https://confluence.atlassian.com` (Platzhalter im Feld) mit „Ohne Anmeldung“: so lässt sich die App sofort
   ausprobieren.
2. **Anmeldung** (Confluence), wie im Enterprise AI Client: ohne Anmeldung, Personal Access Token oder Benutzer und
   Passwort. Token bzw. Passwort direkt eingeben; in die Datei kommt nur der Platzhalter, z. B.
   `token: ${WIKI_TOKEN}`. Den Wert legt die App unter Windows mit DPAPI verschlüsselt für das angemeldete Konto ab
   (`<Konfigurationsordner>\secrets\<kennung>.secret`, nur dieses Konto auf diesem Rechner kann ihn lesen) und setzt
   ihn beim Start selbst; eine Umgebungsvariable ist nicht nötig (eine Java-Property oder Variable gleichen Namens
   geht weiter). Der Wert gilt nur für die Anmeldung, für die er eingegeben wurde (Anmeldeart, bei Basic der
   Benutzer); wird die Datei von Hand umgestellt, fragt die App neu. Ohne Windows gilt der Wert bis zum Schließen der
   App. Auf derselben Seite das optionale **Client-Zertifikat** (mTLS): aus dem Windows-Zertifikatsspeicher mit
   **Wählen …** (nur Zertifikate mit privatem Schlüssel; Tabelle mit Alias, Inhaber,
   „Gültig bis“, Status; abgelaufene auf Wunsch) oder als PKCS#12-Datei mit Passwort-Platzhalter; Fingerabdruck
   optional zur zusätzlichen Prüfung.
3. **Proxy und Netzwerk** (Confluence), gilt für **alle Quellen** (`space-harvester.network.proxy`): die sieben Modi
   des Cores (win-proxy-java), Standard unter Windows `pac-url-powershell` (PAC-Adresse der Interneteinstellungen per
   PowerShell, wie im Enterprise AI Client; gilt auch ohne Abschnitt in der Datei), dazu PAC-Adresse, fester Proxy
   (Host und Port) und Hosts ohne Proxy; Vertrauen über ein vorhandenes `spring.ssl.bundle`. **Proxy auflösen** zeigt
   mit den gewählten Einstellungen jeden Schritt der Auflösung zur Adresse aus Schritt 1, ohne etwas zu speichern.
4. **Prüfen und speichern:** Vorschau des Eintrags (und des Proxys, wenn er sich ändert; die Prüfseite sagt dann,
   dass das alle Quellen betrifft). **Verbindung testen** startet einen eigenen Harvester nur mit der neuen Quelle in
   einem leeren Arbeitsordner und genau den gewählten Einstellungen (Quelle, Anmeldung, Proxy) und lädt die Spaces
   live; der Bestand der laufenden App bleibt unberührt. **Speichern** hängt den Eintrag an `space-harvester.sources`
   an, ersetzt bei Bedarf nur den Block `proxy:`, lässt Kommentare und den Rest der Datei unverändert, legt die vorige
   Fassung als `<datei>.bak` ab und lädt neu.

Fehlt etwas oder passt es nicht, steht der Hinweis rot direkt unter dem Feld (statt des grauen Hinweises); der Dialog
behält seine Größe, und Tippen im Feld nimmt den Hinweis wieder weg. Ergebnisse von **Verbindung testen** und
**Speichern** erscheinen auf der Prüfseite.

**Bearbeiten …** öffnet denselben Assistenten mit den Angaben der gewählten Quelle (Kennung und Art bleiben fest) und
ersetzt den Eintrag an seiner Stelle. Optionen, die der Assistent nicht abfragt (z. B. `connectTimeoutMillis`),
bleiben erhalten; enthält der Eintrag andere unbekannte Schlüssel, verweist die App auf die Datei. Steht im
bearbeiteten Eintrag ein Passwort, Token oder Zertifikatspasswort im Klartext, ersetzt der Assistent es durch den
Platzhalter. **Entfernen …** nimmt den Eintrag nach Rückfrage aus der Datei; der lokale Bestand im Datenordner bleibt
liegen. Steht irgendwo in der Datei ein Geheimnis im Klartext (jede Quelle, Vertrauensspeicher, auch nicht ausgewertete
Felder oder `${NAME:wert}`), legen Bearbeiten und Entfernen keine `.bak` an und löschen eine vorhandene; Prüfseite und
Rückfrage sagen das vorher ausdrücklich.

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
      mode: pac-url-powershell                  # Standard unter Windows; PAC-Fehler fallen nie auf DIRECT zurück
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
  `${NAME}` referenzieren. Token und Passwort gibt man im Assistenten ein; die App legt sie verschlüsselt ab (siehe
  [Quellen-Assistent](#quellen-assistent)) und setzt sie beim Start selbst. Sonst kommen Platzhalter aus
  Java-Systemeigenschaften (`-DNAME=…`), dann aus Umgebungsvariablen. Der Core liest selbst keine Umgebung.
- **Fehlt ein Token oder Passwort**, startet die Quelle nicht, die App läuft aber weiter: Wissensquellen zeigt
  „Zugangsdaten fehlen“; **Bearbeiten …** nimmt den Wert dauerhaft auf, **Zugangsdaten eingeben …** nur für die
  Sitzung.
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
| Übersicht | Was fehlt (Konfiguration, Zugangsdaten), Quellen mit geprüftem Verbindungsstand, letzte Aufträge; ohne Quelle die Willkommensansicht mit dem Einstieg in den Assistenten |
| Wissensquellen | Quelle hinzufügen, bearbeiten und entfernen (Assistent), Quelle wählen, Einstellungen ohne Geheimnisse, Fähigkeiten, Verbindung prüfen, Spaces live, Zugangsdaten für die Sitzung, Abgleich und Export starten |
| Inhalte | Seiten eines Space aus dem Bestand oder live, stückweise geladen, als Liste oder Baum, Titelfilter; Seite per ID oder Adresse öffnen; Seite mit Inhalt, Eigenschaften, Anhängen, Analyse, Originaldaten und Technik |
| Aktivitäten | Abgleiche und Exporte mit Fortschritt, Ergebnis je Kategorie, Teilfehlern, Abbruch, erneutem Abgleich und Speichern der Exportdateien |
| Exporte | Space und Formate wählen, Export starten, letzte Exporte |
| Suche | Titel- und Metadatensuche im Bestand über alle oder eine Quelle, sortiert nach Titel, Änderung oder Relevanz; „Alle Quellen“ ist die globale Suche des Core (ADR 0009), „Quellen …“ beschränkt sie auf ausgewählte Quellen, „Weitere laden“ blättert weiter |
| Einstellungen | Konfigurationsdatei, Hinweise, Ablage, Netz und Zertifikate, Embedding-Modell, Diagnose, Über Space Harvester |

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

## Akronyme im Lesetext (#115)

Der Reiter „Inhalt“ markiert die Akronyme, die die Core-Analyse `acronyms` (`AcronymProcessor`, seit Version 2 mit
allen Fundstellen `occurrences`) für genau diesen Text berechnet hat. Die App erkennt selbst nichts: Sie liest das
gespeicherte Ergebnis mit der öffentlichen Core-Funktion `PageAcronyms.read(PageKey, Enrichments.View…, PageText)`, die
auch der Enterprise AI Client nutzen kann, und markiert nur, wenn Seite und Quelle stimmen, das Ergebnis `ok` und
aktuell ist und sein `inputSha256` zum angezeigten Text passt. Positionen sind UTF-16-Zeichenpositionen in derselben
Textprojektion (`confluence-storage-text/v1`), aus der der Lesetext gebaut wird.

- Bekannte Ausschreibung: rosa hinterlegt und unterstrichen; unbekannte: grau hinterlegt und unterstrichen. Die
  Farbe allein trägt nichts: Tooltip und Zeile unter dem Text nennen „API: Application Programming Interface“ bzw.
  „REST: Ausschreibung unbekannt (steht nicht auf dieser Seite)“, dazu Häufigkeit und ersten Abschnitt.
- Tastatur: Cursor auf ein Akronym setzen zeigt die Zeile; Alt+→ / Alt+← springt zur nächsten / vorigen Fundstelle.
- Die Zeile über dem Text nennt Quelle und Version der Analyse (z. B. „Core-Analyse „acronyms“ Version 2“) oder warum
  nichts markiert ist: veraltet, fehlgeschlagen, noch nicht vorhanden (dann mit „Analyse neu berechnen“) oder
  Live-Fassung, die erst der nächste Abgleich analysiert. Ein altes Ergebnis wird nie auf neuen Text gelegt.
- Der Lesetext entsteht samt Markierungen im Hintergrund; beim Seitenwechsel wird er sofort geleert, und nur das
  Ergebnis des letzten Lade- oder Neuberechnungsauftrags erscheint. Der Prozessor schreibt höchstens 5000
  Fundstellen je Seite (Warnung in der Zeile), die Zählung bleibt vollständig.
- Der Reiter „Analyse“ zeigt das JSON unverändert; `GET …/pages/{pageId}?view=extended` liefert dasselbe Ergebnis.

**Sichtprüfung unter Windows:** Eine Seite mit „Die Application Programming Interface (API) nutzt REST. Jede API und
alle APIs melden sich per SSO (Single Sign-On) an.“ abgleichen und unter Inhalte öffnen.

1. Reiter „Inhalt“: API (3×, auch „APIs“) und SSO rosa, REST grau; die Überschriften bleiben fett, Text lässt sich
   markieren und kopieren.
2. Maus über „REST“: Tooltip „Ausschreibung unbekannt“. In den Text klicken, Alt+→ drücken: die erste Fundstelle ist
   ausgewählt, unten steht „API: Application Programming Interface · 3× auf dieser Seite“.
3. Seite in Confluence ändern, „Aktualität prüfen“: Hinweis „Live-Fassung ist noch nicht analysiert“, keine Markierung.
   Nach erneutem Abgleich und Öffnen sind die Markierungen der neuen Fassung da.
4. Eine Seite einer zweiten Quelle mit derselben Seiten-ID öffnen: nur ihre eigenen Akronyme und Ausschreibungen.
5. Reiter „Analyse“ › „Neu berechnen“: Markierungen bleiben bzw. erscheinen neu, keine der vorigen Seite.

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
| `GET …/pages/{pageId}?view=extended` (`acronyms`) | `PageAcronyms.read` | Seite › Inhalt: Akronyme markiert, Tooltip, Alt+→ | `ReaderAcronymsTest`, `PageAcronymsTest` |
| `GET /api/v1/search[?sourceId=…\|sourceIds=…]` | `PageSearch.search` (ohne Quelle global, #114) | Suche (Titel, Neueste) mit „Quellen …“ und „Weitere laden“ über den Core-Cursor, Kopfzeilensuche | `WorkspaceTest.harvestsBrowses…`, `SearchViewTest`, `MainWindowTest` |
| `GET /api/v1/search?sort=relevance` | `RelevanceSearch.search` (ein Fenster über alle Quellen) | Suche › Relevanz | `WorkspaceTest.harvestsBrowses…` |
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
| – | `Harvester.builder` mit nur der neuen Quelle, `SourceQueries.partitions` | Wissensquellen › Quelle hinzufügen …, Bearbeiten …, Entfernen … (Verbindung testen, Speichern) | `SourceWizardTest`, `ConfigFileEditorTest`, `WorkspaceTest.theWizardTests…` |
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

## Releases

Der Workflow **Desktop-App-Release** (`.github/workflows/desktop-app-release.yml`) baut bei jedem Push das JAR, führt
die App-Tests und `appJarCheck` aus, prüft `--version` und stellt das JAR mit `SHA256SUMS` und
Herkunftsnachweis (Build-Provenance-Attestierung) bereit:

| Auslöser | Ergebnis |
|---|---|
| Push auf `main` | neues Pre-Release `v<Basis>-<Kanal>.<N>`, z. B. `v0.1.0-beta.1`, `v0.1.0-beta.2`, … |
| Push auf einen anderen Branch | das rollierende Pre-Release `snapshot` wird durch diesen Stand ersetzt (JAR `…-SNAPSHOT.jar`) |
| Tag `v<Major>.<Minor>.<Patch>[-<Vorabkennung>]` auf einem `main`-Stand | Release genau mit dieser Version |
| *Run workflow* auf `main` mit Feld `tag` | wie ein Tag; der Lauf legt den Tag selbst an |

- **Basis** ist die Version aus `build.gradle` ohne `-SNAPSHOT` (derzeit `0.1.0`), **Kanal** steht als
  `RELEASE_CHANNEL` im Workflow (derzeit `beta`; davor gab es `v0.1.0-alpha.1`). `N` zählt die vorhandenen Tags des
  Kanals je Push hoch. Für den nächsten Kanal `RELEASE_CHANNEL` auf `rc` setzen, für eine neue Version die Basis in
  `build.gradle` erhöhen; die Zählung beginnt dann wieder bei 1.
- Ein reguläres Release (ohne Vorabkennung, als „Latest“ markiert) entsteht nur über einen Tag wie `v0.1.0` oder
  *Run workflow* mit `tag`.
- Versionierte Releases werden nie überschrieben. Nur `snapshot` wird ersetzt; zum Testen ein versioniertes Release
  nehmen.
- Die Version gilt nur für `:space-harvester-app` (`-PappVersion=…`); lokal:
  `./gradlew :space-harvester-app:bootJar -PappVersion=0.1.0-beta.1`.
