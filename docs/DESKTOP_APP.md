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

Die App öffnet mit der Suche. Ohne Quelle begrüßt **Status** mit der Weltraumszene und **Erste Wissensquelle hinzufügen …**;
das öffnet denselben Assistenten wie **Wissensquellen › Quelle hinzufügen …** (siehe
[Quellen-Assistent](#quellen-assistent)). Alternativ legt **Einstellungen › Beispiel anlegen** eine kommentierte
Beispieldatei an und öffnet sie im Standardeditor; nach dem Speichern **Neu laden** wählen.

## Erscheinungsbild

Die beiden Markenbilder liegen unverändert als Original in
`space-harvester-app/src/main/resources/com/aresstack/spaceharvester/app/images/` und werden aus dem Klassenpfad
geladen (`ui/brand/BrandImages`), funktionieren also auch im ausführbaren JAR:

| Datei | Verwendung |
|---|---|
| `space-harvester-ship.png` (freigestellt, transparent) | Anwendungssymbol für Fenster, Taskleiste und Alt+Tab (`ApplicationIcons`, 16 bis 256 Pixel), kleines Logo links in der eigenen Titelleiste, Sprecher im Einrichtungsassistenten |
| `space-harvester.png` (Weltraumszene) | Willkommensansicht unter Status, solange keine Quelle eingerichtet ist; Dialog **Einstellungen › Über Space Harvester** |

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
   (Host und Port); unter **Erweitert** (zugeklappt) die **Proxy-Ausnahmen** (`non-proxy-hosts`) als Tabelle mit
   Hinzufügen und Entfernen: Diese Hosts werden direkt und nicht über den Proxy angesprochen. **Vertrauen** (je Quelle): **Wie für alle Quellen** (Standard, kein `trust-bundle`;
   es gilt `space-harvester.network.tls`, siehe unten) oder ein vorhandenes `spring.ssl.bundle`. Das frühere
   eingebaute Bundle `windows` gilt als „wie für alle Quellen“; beim nächsten Speichern fällt es aus der Datei. **Proxy auflösen** zeigt
   mit den gewählten Einstellungen jeden Schritt der Auflösung zur Adresse aus Schritt 1, ohne etwas zu speichern.
4. **Prüfen und speichern:** Vorschau des Eintrags (und des Proxys, wenn er sich ändert; die Prüfseite sagt dann,
   dass das alle Quellen betrifft). **Verbindung testen** ist „Verbindung testen“ des Core (`SourceManagement.test`,
   #188): in einem eigenen Harvester in einem leeren Arbeitsordner mit genau den gewählten Einstellungen (Quelle,
   Anmeldung, Proxy); die Meldung kommt vom Core („Verbunden, 3 Spaces sichtbar“ oder die Art des Fehlers, nie Werte
   der Einstellungen); der Bestand der laufenden App bleibt unberührt. **Speichern** hängt den Eintrag an `space-harvester.sources`
   an, ersetzt bei Bedarf nur den Block `proxy:`, lässt Kommentare und den Rest der Datei unverändert, legt die vorige
   Fassung als `<datei>.bak` ab und lädt neu.

Fehlt etwas oder passt es nicht, steht der Hinweis rot direkt unter dem Feld (statt des grauen Hinweises); der Dialog
behält seine Größe, und Tippen im Feld nimmt den Hinweis wieder weg. Ergebnisse von **Verbindung testen** und
**Speichern** erscheinen auf der Prüfseite.

**Bearbeiten …** öffnet denselben Assistenten mit den Angaben der gewählten Quelle (Kennung und Art bleiben fest) und
ersetzt den Eintrag an seiner Stelle; zeigt die Quelle danach auf ein anderes Ziel (Art oder Adresse), entfernt der
Core ihre bisherigen Daten (`SourceManagement.change`). Optionen, die der Assistent nicht abfragt (z. B. `connectTimeoutMillis`),
bleiben erhalten; enthält der Eintrag andere unbekannte Schlüssel, verweist die App auf die Datei. Steht im
bearbeiteten Eintrag ein Passwort, Token oder Zertifikatspasswort im Klartext, ersetzt der Assistent es durch den
Platzhalter. **Entfernen …** nimmt den Eintrag nach Rückfrage aus der Datei; danach bricht der Core die offenen Aufträge der
Quelle ab und löscht ihren Bestand (Seiten, Suchindex, Analysen, Bilder; `SourceManagement.remove`), fertige Exporte
bleiben. Steht irgendwo in der Datei ein Geheimnis im Klartext (jede Quelle, Vertrauensspeicher, auch nicht ausgewertete
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
      trust-bundle: windows                     # optional: windows oder Name unter spring.ssl.bundle
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
- **Fehler in der Datei** verhindern den Start nicht: Die betroffene Quelle fehlt, Status und Einstellungen nennen
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
| Suche | Eine Seitenadresse (jede Form der Quelle, etwa `/display/KEY/Titel` oder `viewpage.action?pageId=…`), ein Confluence-Bookmark oder ein Lesezeichen `space-harvester://<Quelle>/page/<ID>` im Suchfeld öffnet die Seite direkt über die Adressauflösung des Core (`Harvester.addresses().resolve`, #178; passt sie in mehreren Quellen, stehen die Seiten zur Wahl in der Liste); sonst reine Schlagwortsuche über den Cache (`KeywordSearch`, [ADR 0013](adr/0013-suche-ueber-den-cache.md)): die native Suche jeder Quelle entdeckt passende Seiten, der Core legt sie mit den Rechten des Aufrufers in den Cache, die Treffer kommen aus dessen Volltextindex; Spalten Titel, Quelle, Space, Auszug (aus dem Cache), Geändert; je Stapel 25 Treffer statt des Höchstwerts des Core; die Funde der Quellen stehen sofort als vorläufige Treffer in der Liste (`Results.ranked()`: geladene zuerst, dann die vorläufigen nach Titel und Auszug bewertet, ADR 0016), während der Core die besten in den Cache lädt; die Aktionszeile am unteren Rand zeigt sofort beim Start einen Balken über die volle Breite, unbestimmt bis der Core die Gesamtzahl kennt, dann Balken und Zähler aus `progress(id)` („12 von 25 Seiten im Cache · 7 Treffer“) mit „Abbrechen“ (`cancel(id)`); die Liste bleibt dabei bedienbar, die App fragt den Stand alle 400 ms ab und stellt bei jeder neu geladenen Seite denselben Stapel erneut, nie den nächsten; geladene Treffer rücken nach vorn, kein Treffer verschwindet, frühere Stapel bleiben oben, die Auswahl bleibt; meldet der Core `done`, steht dort „Die Suche ist abgeschlossen“ (nach „Abbrechen“ „Laden abgebrochen“); danach verschwindet der Balken; eine neue Eingabe bricht das Laden ab; weitere Stapel holt „Weitere laden“, das erst nach dem Laden und nur bei weiteren Ergebnissen erscheint; keine Synchronisierung nötig; unter der Liste je Quelle „keine eigene Suche“ bzw. „nicht erreichbar (Grund)“ und „unvollständig“, statt still keine Treffer; die eine Suchleiste steht nur im Bereich Suche, rechts daneben das Häkchen „Semantische Suche (experimentell)“ (Vorgabe aus) und ein Info-Symbol mit den Erklärungen als Tooltip: erst aktivierbar, wenn der KI-Dienst mit Embedding-Modell und Dimension eingerichtet ist, sonst mit dem Hinweis „KI-Dienst unter Einstellungen → KI einrichten“ als Tooltip; angehakt sucht es mit der Relevanzsuche des Core (`sort=relevance`) über die bereits eingebetteten Seiten im Cache, ein Block nach Ähnlichkeit ohne „Weitere laden“, Spalte Auszug zeigt die Ähnlichkeit; den Umfang wählt der Explorer links: ohne Auswahl „In: Alle Wissensquellen“ (globale Suche des Core ohne `sourceIds`, ADR 0009), eine gewählte Quelle sucht mit `sourceId`, mehrere (Strg-Klick) mit `sourceIds`, ein gewählter Space oder eine Seite beschränkt auf diesen Space; die Zeile unter der Suchleiste nennt den Umfang („In: Beta › DEV“), **Überall suchen** hebt die Auswahl auf; „Weitere laden“ blättert weiter |
| Wissensquellen verwalten | Die Verwaltungszentrale für Bestand und Synchronisierung, rechts oben neben den Einstellungen; navigiert und gelesen wird im Explorer. Je Quelle eine Karte mit Verbindungszeichen und Symbolknöpfen: Stift öffnet die Einstellungen, Papierkorb entfernt nach Rückfrage (die KI-Freigabe der Quelle verschwindet mit), ⋯ mit **Bearbeiten …** (Quellenformular), **Verbindung prüfen …** (lädt die Spaces live und zeigt die Verbindungsdiagnose des Core als Stufenliste: Proxy-Route, Namensauflösung, TCP-Verbindung, TLS, Anmeldung, API, je mit Zustand, Meldung und bei Warnung oder Fehler dem Hinweis, was zu tun ist; `SourceManagement.diagnose`, #189), Herunterladen, **Abschalten …**/**Einschalten** (abgeschaltet ruht die Quelle: nicht durchsucht, gelesen oder abgeglichen, laufende Aufträge brechen ab, Einstellungen und Bestand bleiben; `space-harvester-app.disabled-sources`, im Core `SourceManagement.setEnabled`) und Zugangsdaten für die Sitzung. Rechts Textreiter: **Spaces** mit Filter (Doppelklick oder Enter öffnet unter Inhalte), Spalten **Katalog** (Unbekannt, Teilbestand, Erfasst) und **Bekannt / im Index** (bekannte Seiten gegen Seiten mit Inhalt, dazu veraltete und zurückgehaltene; `Harvester.catalog`, aus dem Cache ohne Quellenzugriff), Häkchen je Space und rundem Export-Knopf je Zeile; am unteren Rand **Ausgewählte herunterladen …** und **Alle herunterladen …**. Alle drei öffnen Herunterladen mit den Spaces vorbelegt und starten nichts; darüber die Zeile des Space mit dem Kreispfeil **Bestand abgleichen** (vollständiger Abgleich des gewählten Space, mit Häkchen aller angehakten, je Space ein Auftrag, sofort angewendet: neue Seiten aufnehmen, geänderte aktualisieren, verschobene neu zuordnen, bestätigt gelöschte entfernen, nicht mehr sichtbare aus der Suche nehmen; Betriebsart `sync-cache`: unbekannte Seiten nur in den Katalog, gecachte bei neuer Version neu laden; das Ergebnis zeigt die Auftragskarte mit Neu, Nur im Katalog, Veraltet, Inhalt geändert, Verschoben, Entfernt, Zurückgehalten und Endgültig bereinigt), **Initialisieren** (nur den Seitenkatalog anlegen, `catalog-only`, ohne Inhalte), **Änderungen prüfen** (Änderungssuche seit dem letzten Lauf, `listing=changes`; ohne Änderungssuche der Quelle gleicht der Core den Bestand ab) und **Strategie …** (öffnet den Reiter Synchronisierung); „Herunterladen“ beschafft den JSON-Datenbestand und gleicht nichts ab. **Synchronisierung**: was für die Quelle gilt, in drei Bereichen: Erkennung (über die Suche immer; automatische Änderungssuche per CQL, regelmäßige Bestandsinventur nur mit Metadaten und Webhooks, letztere nur im Server, standardmäßig aus; von Hand der Kreispfeil), Aktualisierung (Aktualisierung gespeicherter Seiten wie unter Einstellungen › Cache) und Bereinigung (Löschung bestätigt: sofort entfernt; nicht mehr sichtbar: sofort aus der Suche, Rohdaten bis zur Frist zurückgehalten; Abgleich fehlgeschlagen: Bestand unverändert); eigene Regeln stehen in Tinte mit „(Regel dieser Quelle)“, sonst gedämpft „(wie global)“. **Regeln dieser Quelle …** setzt je Feld eine eigene Regel oder lässt es wie global (leer bzw. „Wie global“): automatische Änderungssuche und regelmäßige Bestandsinventur als Abstand (mindestens 1m), Betriebsart (Nur katalogisieren, Cache synchronisieren, Vollständig synchronisieren), Nachholen beim Start, Abgleich vor jeder Suche, Webhooks, Aktualisierung gespeicherter Seiten mit Frist und die Frist für gesperrte Seiten; in der Datei `space-harvester.sync.sources.<Quelle>.<Feld>` (`interval`, `inventory-interval`, `mode`, `catch-up-on-start`, `before-search`, `events`, `refresh-strategy`, `max-age`, `withheld-retention`), wie `SourceSyncOverride` im Core; Entfernen der Quelle entfernt auch ihre Regeln. Die Regeln wirken, sobald die Laufzeit sie an den Core übergibt; **Gemeinsame Werte …** öffnet den Dialog aus Einstellungen › Cache, **Einstellungen** ohne Geheimnisse (Anzeigename, Adresse und Ordner direkt mit dem Stift, Enter übernimmt, Escape verwirft; Token oder Passwort per Dialog, unter Windows verschlüsselt gespeichert; Anmeldung, Client-Zertifikat, **Serverzertifikate prüfen** und Proxy je in einem eigenen kleinen Dialog: Client-Zertifikat oben die Herkunft (keins, Windows-Zertifikatsspeicher, Datei), darunter die Auswahl aus dem Windows-Speicher, Alias und Fingerabdruck kommen aus dem gewählten Zertifikat; Serverzertifikate prüfen wie für alle Quellen oder mit einem konfigurierten Bundle, die bisherige Wahl bleibt; Proxy gilt für alle Quellen und ist auch unter Einstellungen erreichbar; dort auch **Proxy-Benutzer** und **Proxy-Passwort** für Proxys mit Anmeldung (BASIC, #189: in der Datei nur `auth.username`, das Passwort unter Windows verschlüsselt im Tresor unter `secrets/network`, sonst nur für die Sitzung; ein `auth.password` in der Datei wird nicht gelesen) und unter **Erweitert** ein eigenes **PowerShell-Skript** bzw. **VBScript** zur PAC-Ermittlung (`pac-discovery-script`, nur bei diesen beiden Modi)), **Fähigkeiten**. Laufen Aufträge, fragt jedes Übernehmen vorher nach, denn die Quellen starten dabei neu und laufende Aufträge brechen ab |
| Inhalte | An jeder Seite **Lesezeichen kopieren** (stabile Adresse mit Version, `PageBookmark` des Core). Wissensquelle → Space → Seiten → Seiteninhalt (siehe „Inhalte“ unten): Seiten sofort nach der Space-Wahl, aus dem Bestand oder ohne Bestand live, Liste oder Seitenbaum (beide ohne Bestand), Suchfeld; eine gewählte Seite lädt sofort; keine Synchronisierung nötig; Seite per ID oder Adresse öffnen; eine aus der Suche oder über eine Adresse geöffnete Seite wählt ihren Space mit, die Seitenliste links zeigt ihn; Seite mit den Reitern Leseansicht (Standard; ein gewählter Reiter bleibt beim Wechsel der Seite), Text, Eigenschaften, Anhänge, Erweitert. Leseansicht (gerendertes HTML der Quelle aus dem Core, Bilder aus der Bildablage, Mermaid als einmal gezeichnete und abgelegte Grafik des Core, fehlende Bilder rot markiert „[Bild fehlt: Name]“, Klick auf ein Bild öffnet die Bildergalerie mit ◀/▶; bei eingebundenen Inhalten ohne nachgewiesene Leserechte nur ein Hinweis), Eigenschaften, Anhängen und Erweitert (Technische Angaben, Analyse, Originaldaten, Aktualität prüfen, Original speichern) |
| Herunterladen | Quelle und einen oder mehrere Spaces wählen (oft vorbelegt aus Wissensquellen), „Herunterladen“: je Space ein Export-Auftrag, der die Seiten einmal als JSON-Datenbestand beschafft (keine Formatwahl), ab zehn Spaces mit Rückfrage. Ein Abgleich vorher ist nicht nötig, fehlende Seiten liest der Download selbst. Darunter die letzten Downloads. Hieß bis beta.37 „Exportieren“; „Exportieren“ ist künftig der Knopf unter Aufträge, der aus dem heruntergeladenen JSON-Datenbestand PDF, Word, Markdown oder JSON in den Ausgabeordner erzeugt |
| Aufträge | Abgleiche, Exporte und die Folgeaufträge, die der Kern selbst einplant (Anreicherung nach jedem bestätigten Abgleich oder Einlesen, „Bilder beschaffen“ nach jedem Download: „Bilder beschaffen · X von Y Bildern geprüft, Z abgelegt“), mit Fortschritt je Phase (Export: „Seiten abrufen · X von Y Seiten geladen oder geprüft“, dann „Seiten exportieren · X von Y exportiert“; Anreicherung: „Anreichern: Prozessor (i/n) · X von Y Schritten“, Schritte = Seiten × Prozessoren), Ergebnis je Kategorie, Teilfehlern, Abbruch, erneutem Abgleich; ein fertiger Download zeigt oben die Karte **Exportieren als …** („Export · Space“, „N Seiten · JSON-Datenbestand vorhanden“, „Bilder: werden beschafft / vollständig / unvollständig“, solange sie noch beschafft werden mit dem Hinweis, dass Dokumente dann noch ohne Bilder entstehen; Exportieren geht in jedem Stand, Formatwahl PDF-Dokument, Word-Dokument, Markdown, JSON, Knopf **Exportieren**, „Ausgabeordner: Downloads“ mit **Ordner öffnen**). Exportieren erzeugt die Datei aus dem vorhandenen Datenbestand direkt im Ausgabeordner (`<Space>-<Export-ID>.<Endung>`, nie überschrieben), ohne Dateidialog und ohne die Quelle erneut zu lesen; die Rückmeldung nennt den Dateinamen und die Zahl der Hinweise („…docx gespeichert in Downloads, 3 Hinweise“); **3 Hinweise anzeigen** klappt die Liste auf (Seite, fehlendes Bild, fehlendes Diagramm, fehlender Anhang oder ausgelassener Inhalt, mit Grund wie „keine Berechtigung“). Das Ergebnis des Auftrags nennt Seiten, Datenbestand und den Stand der Bilder; **Diagnose ansehen** (bei gescheiterten Aufträgen **Fehler diagnostizieren**) springt unter Status zu genau diesem Auftrag. Der Fortschritt steht am unteren Rand. **Löschen …** entfernt den gewählten beendeten Auftrag samt Diagnose, **Abgeschlossene Aufträge löschen …** alle beendeten nach Rückfrage; laufende bleiben (erst abbrechen), Dateien im Ausgabeordner und der Seitenbestand bleiben |
| Status | Diagnosezentrum: oben nur, was fehlt (Konfiguration, Zugangsdaten); darunter die jüngsten 500 Diagnoseereignisse der Aufträge ([DIAGNOSTICS.md](DIAGNOSTICS.md)) und die letzte Verbindungsprüfung je Quelle, neueste zuerst, mit Zeit, Schwere, Komponente, Phase, Code, HTTP-Status, Proxy-Route, TLS-Befund, Auftrag und Meldung. Filter **Alle**, **Fehler** (Fehler und Warnungen), **Netzwerk**, **Aufträge**, Zeitraum und Freitext (auch Auftrags-ID); ein gewähltes Ereignis zeigt unten alle Befunde und die Ausnahmekette, **Kopieren** übernimmt sie, **Löschen** entfernt es. **Protokoll bereinigen …** löscht die Ereignisse beendeter Aufträge älter als eine Stunde, einen Tag, eine Woche oder alle; Ereignisse laufender Aufträge bleiben. Alles ist maskiert (`Redaction`), Geheimnisse erscheinen nie. Ohne Quelle die Willkommensansicht mit dem Einstieg in den Assistenten |
| Einstellungen | Reiter wie im Enterprise AI Client; jede Änderung schreibt `space-harvester-app.yml`. KI-Dienst, Modelle, Freigaben je Quelle, Aktualisierungsstrategie, Frist für gesperrte Seiten und Synchronisierung samt Regeln je Quelle übernimmt der laufende Harvester über seinen `ConfigurationManager` ohne Neustart (ADR 0015, `Harvester.builder().configuration`); laufende Aufträge laufen weiter. Quellen, Abschalten, Proxy, Serverzertifikate, Ablage, Zerlegung für Embeddings und neue Zugangsdaten einer Quelle starten die Laufzeit neu. **Einrichtungsassistent** wie `SetupWizardPanel` im Enterprise AI Client, mit dem Space-Harvester-Schiff in der Sprechblase statt Merlin und der Schrittleiste oben: **Wissensquellen** (die eingerichteten Quellen mit Status „Eingerichtet · Prüfung ausstehend/Verbindung erfolgreich/fehlgeschlagen“, **Quelle hinzufügen …** und **Bearbeiten …** öffnen das Quellenformular), **Netzwerk** (Proxy, Serverzertifikate, **Verbindung testen** baut je Quelle eine echte Verbindung auf und meldet „Verbindung erfolgreich“ oder „Verbindung fehlgeschlagen“ mit der Stufe aus `SourceManagement.diagnose`), **KI-Dienst** (optional), **Modelle** (lädt beim Betreten die Modelle des Dienstes und wählt wie `ModelPreselection` im Enterprise AI Client vor: eine angebotene Wahl bleibt, sonst das Vorzugsmodell aus `setup-models.properties`, sonst das erste passende), **Abschluss** mit der Übersicht der gespeicherten Einstellungen und ihres Prüfstands. Die Schrittleiste zeigt den aktuellen Schritt hervorgehoben, grün nur Eingerichtetes und Geprüftes (Quellen erreichbar, Dienst mit API-Key, Embedding-Modell mit Dimension), sonst neutral; Ansehen allein setzt kein Häkchen. Jeder Schritt nutzt dieselben Bausteine wie die Reiter und speichert sofort. **Quellenformular** (dasselbe in Wissensquellen und im Assistenten, ein Formular statt eines Assistenten): Art (Confluence oder Ordner), Adresse, Anzeigename, Kennung; Anmeldung (ohne, Token, Benutzer und Passwort; Geheimnis nur im Tresor bzw. für die Sitzung, in der Datei nur der Platzhalter), Client-Zertifikat (Windows-Speicher oder PKCS#12-Datei mit Passwort-Platzhalter) und Serverzertifikate dieser Quelle, Haken **Inhalte dieser Quelle dürfen an den KI-Dienst gehen (Embeddings, Reranking)** (Vorgabe aus; `space-harvester.ai.external-processing-sources`; ohne KI-Dienst sichtbar mit dem Hinweis, dass er erst mit einem Dienst wirkt), **Verbindung testen** vor dem Speichern und **Speichern**; Proxy und Netzwerk stehen nicht darin. Er öffnet sich beim ersten Start, bis „Fertig“, „Später“ oder ✕ `space-harvester-app.setup-completed: true` in die Datei schreibt; danach unter **Allgemein › Einrichtungsassistent …**. **Allgemein**: Konfigurationsdatei, Hinweise, Ausgabeordner (`space-harvester-app.output-directory`, Vorgabe Downloads; „Ausgabeordner ändern …“ einmalig mit Ordnerdialog, „Ausgabeordner öffnen“, „Zurück auf Downloads“; gespeichert ohne Neustart der Laufzeit), Ablage, Diagnose, Über Space Harvester. **KI-Dienst** (ADR 0011): **Zugangsdaten beziehen über** KeePass (Vorgabe, wie im Enterprise AI Client) oder Manuell. KeePass: **Koppeln** über KeePassRPC (KeePass zeigt ein Einmal-Passwort; der Pairing-Schlüssel liegt im Tresor unter `secrets/keepass`), danach ein editierbares Auswahlfeld **Eintrag** (Vorgabe „Enterprise AI API“, wie im Enterprise AI Client): ein bestehender Eintrag liefert Basisadresse (URL-Feld) und API-Key (Passwortfeld), ein neu eingetippter Name legt beim Speichern einen Eintrag mit Basisadresse und eingegebenem Key in KeePass an; in der Datei stehen nur `space-harvester-app.ai-access: keepass` und `ai-keepass-entry`, nie der Key. Die Anbindung ist die unveränderte Kopie `:enterprise-ai-keepass` und gilt nur für die App (`AiCredentials` mit `KeePassAiCredentials` bzw. `ManualAiCredentials`). Manuell: Basisadresse (OpenAI-kompatibel), API-Key (nur im Tresor unter `secrets/ai`, nie in der Datei) und die Zeile **Freigegeben** mit der Zahl der Quellen, deren Inhalte an den Dienst gehen dürfen (Vorgabe keine; gesetzt je Quelle im Quellenformular). Eine Basisadresse muss mit http:// oder https:// beginnen; sieht sie wie ein API-Key aus, weist die App darauf hin. **Modelle**, zentral für alle Quellen: „Modelle laden“ fragt `GET {base-url}/models` über die Proxy-Route der Quellen ab und wählt Text- und Embedding-Modell wie im Assistenten vor, ▾ bietet die übrigen an; Text-Modell, Embedding-Modell, Dimension (leer misst Speichern sie beim Dienst; ohne Dimension bleibt die semantische Suche aus), Rerank-Modell (Cross-Encoder über `/rerank`, ordnet nur die Trefferliste aus dem Cache neu, leer: Reihenfolge bleibt) und eingeklappt unter **Erweitert** (keine Modelle, sondern die Zerlegung für Embeddings) Abschnittsgröße (Tokens je Abschnitt für Embeddings, leer 350) und Überlappung (Sätze, leer 1); in der Datei unter `space-harvester.ai` (`rerank-model`, `embedding-chunk-tokens`, `embedding-chunk-overlap-sentences`). **Netzwerk**: Proxy und Zertifikate mit „Proxy ändern …“ (derselbe Dialog wie an der Quelle) und **Serverzertifikate …** für alle Quellen ohne eigenes Bundle und den KI-Dienst: JVM-Truststore, Windows-Stammzertifikate, Windows-Speicher Root und CA samt Zwischenzertifikaten und eine PEM-/DER-Datei mit weiteren CAs, wie `network.tls` im Enterprise AI Client; in der Datei `space-harvester.network.tls`, ohne Angabe unter Windows JVM und Windows-Speicher, sonst nur JVM; geprüft und angewandt vom Core mit `TrustSettings`/`TrustPolicy` (#189), die App liest keinen Zertifikatspeicher selbst. **Cache**: „Cache und Aufbewahrung …“ mit **Aktualisierung gespeicherter Seiten** als Auswahl aus drei Strategien: „Immer Version prüfen“ (Vorgabe; jede Anzeige vergleicht die Version bei der Quelle), „Zeitbasiert ohne Versionsprüfung“ und „Zeitbasiert mit Versionsprüfung“ (innerhalb der **Frist** gilt die gespeicherte Seite ohne Rückfrage, danach neu laden bzw. erst die Version vergleichen); in der Datei `space-harvester.cache.max-age` (0 bei „Immer Version prüfen“) und `space-harvester.cache.version-check`, im Core `CacheFreshness(maxAge, versionCheck)`; die Rechte prüft der Core immer. Neue Seiten übernimmt die Suche immer und **Gesperrte Seiten entfernen nach** (`space-harvester.sync.withheld-retention`, Vorgabe 30 Tage, ADR 0014); dieselben Schlüssel wie im Server. **Sicherheit**: wo die Geheimnisse liegen (Tresor dieses Windows-Kontos mit DPAPI bzw. nur die Sitzung), je Eintrag nur „gespeichert“, „fehlt“ oder der Platzhalter `${NAME}`: API-Key des KI-Dienstes, Proxy-Passwort, Zugang jeder Quelle; ein Geheimnis im Klartext in der Datei steht rot da |

Aufbau des Hauptfensters: oben die Icon-Leiste in zwei Gruppen (nur Symbole, Name und Tastenkürzel Strg+1 bis Strg+7
im Tooltip): links die Arbeitsbereiche Suche, Inhalte, Herunterladen, Aufträge und Status, rechts abgetrennt
**Wissensquellen verwalten** (Datenbanksymbol; Quellen hinzufügen, bearbeiten, entfernen, Verbindung prüfen,
Synchronisierungsregeln, Katalogstatus; ohne den allgemeinen Einrichtungsassistenten) und **Einstellungen**, dann ✕;
eine globale Suchleiste gibt es nicht, gesucht wird im Bereich Suche. Darunter links dauerhaft der **Explorer** über
alle Wissensquellen (Breite ziehbar, einklappbar): Wissensquelle → Space → Seite → Unterseite, Spaces und Seiten erst
beim Aufklappen geladen, Kindseiten live über die Navigation des Core in der Reihenfolge der Quelle
(`PageNavigation.children`, #177; je Stapel „Weitere laden …“); Quellen ohne Seitenbaum zeigen den Seitenbaum des Space
aus dem Bestand bzw. aus den Metadaten der Quelle. Ein Klick oder Enter auf eine Seite öffnet sie unter Inhalte. Oben
im Explorer **Titel filtern …** nur zur Navigation: Seitentitel aus den geladenen Knoten und aus dem Bestand
(Titelsuche des Core), je Treffer mit Quelle und Space; Escape leert das Feld. Rechts die Arbeitsfläche des gewählten
Bereichs. Über der Statusleiste steht die feste Aktions- und Fortschrittszeile des Bereichs. Die App öffnet mit der
Suche.

Lange Arbeit (Netz, Lucene, Export) läuft immer im Hintergrund; die Oberfläche zeigt „läuft …“ und bleibt bedienbar.
Fehler erscheinen als verständliche Meldung mit Hinweis; Technische Details (Fehlerart, Code) lassen sich aufklappen,
Ursachenketten mit möglichen Geheimnissen nie.

**Raw-First:** Originaldaten zeigt die App byte-genau als Text (JSON eingerückt nur in der Anzeige) und speichert sie
mit „Original speichern …“ unverändert. „Aktualität prüfen“ ist `refresh=true`: echte Prüfung gegen die Quelle. Ein
Abgleich heißt nur „vollständig“, wenn der Core die Generation bestätigt hat; sonst zeigt „Aufträge“ den Grund.

**Zugriff:** Die App läuft im Vertrauensbereich des angemeldeten Windows-Benutzers. Sie setzt eine eigene
`AccessPolicy` (`LocalOperatorPolicy`): freigegeben ist nur der App-Aufrufer und nur für konfigurierte Quellen. Alle
Prüfungen der REST-Controller (metadata, content, export, sync; verdeckte Seiten unbekannter Spaces) laufen in
`Workspace` genauso; was die Quelle selbst verweigert, bleibt verweigert.

## Akronyme im Lesetext (#115)

Der Reiter „Text“ markiert die Akronyme, die die Core-Analyse `acronyms` (`AcronymProcessor`, seit Version 2 mit
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
- Erweitert › „Analyse“ zeigt das JSON unverändert; `GET …/pages/{pageId}?view=extended` liefert dasselbe Ergebnis.

**Sichtprüfung unter Windows:** Eine Seite mit „Die Application Programming Interface (API) nutzt REST. Jede API und
alle APIs melden sich per SSO (Single Sign-On) an.“ abgleichen und unter Inhalte öffnen.

1. Reiter „Text“: API (3×, auch „APIs“) und SSO rosa, REST grau; die Überschriften bleiben fett, Text lässt sich
   markieren und kopieren.
2. Maus über „REST“: Tooltip „Ausschreibung unbekannt“. In den Text klicken, Alt+→ drücken: die erste Fundstelle ist
   ausgewählt, unten steht „API: Application Programming Interface · 3× auf dieser Seite“.
3. Seite in Confluence ändern, Erweitert › „Aktualität prüfen“: Hinweis „Live-Fassung ist noch nicht analysiert“, keine Markierung.
   Nach erneutem Abgleich und Öffnen sind die Markierungen der neuen Fassung da.
4. Eine Seite einer zweiten Quelle mit derselben Seiten-ID öffnen: nur ihre eigenen Akronyme und Ausschreibungen.
5. Erweitert › „Analyse“ › „Neu berechnen“: Markierungen bleiben bzw. erscheinen neu, keine der vorigen Seite.

## Inhalte

Der Ablauf folgt der Aufgabe, nicht den Schnittstellen: Wissensquelle → Space → Seiten → Seiteninhalt. Eine
Synchronisierung ist keine Voraussetzung; fehlende Daten holt der Core selbst.

- **Space wählen** zeigt sofort die Seiten. Liegt der Space im lokalen Bestand, kommen sie von dort, sonst live aus
  der Quelle. Ein leerer Bestand ist bei einer neuen Quelle der Normalfall und erzeugt keine Fehlermeldung.
- **Liste / Seitenbaum** sind gleichwertige Darstellungen derselben Seiten und immer wählbar. Der Seitenbaum kommt aus
  dem Bestand, wenn der Space dort liegt, sonst aus den Live-Metadaten des Space (jede Seite nennt ihre Elternseite;
  höchstens 20 000 Seiten, die Statuszeile sagt es, wenn der Space größer ist).
- **Seite wählen** lädt sie sofort, ohne eigenen Knopf. Fehlt sie im Bestand, holt der Core sie und legt sie ab. Ohne gewählte Seite
  steht rechts nur „Wähle links eine Seite aus …“, nie die Seite eines vorigen Space.
- **Erweitert** enthält technische Angaben (Herkunft, Hashes, Revision, Cache-Stand), Analyse, Originaldaten
  (Payload, Storage-Format) sowie „Aktualität prüfen“ und „Original speichern …“.
- Wechsel von Quelle oder Space leert Liste und Seite und verwirft noch laufende Ladevorgänge.
- Fehler nennen den Schritt: „Spaces nicht abrufbar“, „Seitenliste nicht abrufbar“, „Seite nicht geladen“,
  „Lokaler Bestand nicht lesbar“. Scheitert ein Seitenabruf, obwohl die Spaces kamen, steht die Ursache des Core
  (z. B. Zeitüberschreitung, TLS-Fehler, HTTP-Status) direkt in der Meldung, denn die Verbindungsprüfung fragt nur
  die Spaces ab.
- Eine Seite, die nicht im Bestand liegt, holt der Core beim Öffnen bei der Quelle (nach Rechteprüfung an den
  Live-Kopfdaten), legt sie im Bestand ab und liefert sie aus.

## Rückverfolgbarkeit REST → Anwendungsfall → App → Test

| REST (Dienst) | Anwendungsfall (Core) | App-Aktion | Test |
|---|---|---|---|
| `GET /api/v1/sources` | `SourceQueries.sources` | Wissensquellen, Status | `WorkspaceTest.harvestsBrowses…` |
| `GET …/sources/{id}/spaces` | `SourceQueries.partitions` | Verbindung prüfen, Space-Auswahl | `WorkspaceTest.harvestsBrowses…`, `…onlyConfiguredSourcesAreReachable` |
| `GET …/spaces/{key}/pages/metadata` | `SourceQueries.pageMetadata` | Inhalte › Seiten ohne Bestand (live) | `WorkspaceTest.harvestsBrowses…`, `ContentViewTest` |
| `GET …/pages/{pageId}/metadata` | `SourceQueries.pageMetadata` | Seite › Eigenschaften (live) | `MainWindowTest` (Navigation) |
| `GET …/pages/{pageId}/attachments` | `SourceQueries.attachments` | Seite › Anhänge | `WorkspaceTest.attachmentsAre…` |
| `GET …/spaces/{key}/pages` | `PageQueries.pages` | Inhalte › Seiten aus dem Bestand | `WorkspaceTest.harvestsBrowses…`, `…readsLocalFiles…`, `ContentViewTest` |
| `GET …/spaces/{key}/tree` | `PageQueries.tree` | Inhalte › Seitenbaum | `WorkspaceTest.harvestsBrowses…`, `ContentViewTest` |
| `GET …/pages/{pageId}` (`refresh`, `view=extended`) | `PageQueries.page`, `Enrichments.view` | Seite wählen (ohne Bestand live), Erweitert › Aktualität prüfen, Analyse | `WorkspaceTest.harvestsBrowses…`, `…aFreshSourceDeliversPagesLive…`, `ContentViewTest` |
| `GET …/pages?url=` | `PageQueries.pageByUrl` | Inhalte › Seite öffnen (Adresse) | `WorkspaceTest.pagesOpenByAddress…` |
| `POST …/pages/{pageId}/enrichments` | `Enrichments.recompute` | Seite › Analyse › Neu berechnen | `WorkspaceTest.pagesOpenByAddress…` |
| `GET …/pages/{pageId}?view=extended` (`acronyms`) | `PageAcronyms.read` | Seite › Inhalt: Akronyme markiert, Tooltip, Alt+→ | `ReaderAcronymsTest`, `PageAcronymsTest` |
| `GET /api/v1/search?text=…[&sourceId=…\|sourceIds=…][&cursor=…]` | `KeywordSearch.search` (ohne Quelle global, #114) | Suche mit Quellen-Häkchen und „Weitere laden“ über den Core-Cursor | `WorkspaceTest.harvestsBrowses…`, `SearchViewTest`, `MainWindowTest` |
| `GET /api/v1/search?sort=relevance` | `RelevanceSearch.search` (ein Fenster über alle Quellen) | Suche › Semantische Suche (experimentell) | `WorkspaceTest.harvestsBrowses…` |
| `POST …/spaces/{key}/syncs` | `SpaceKeyCheck`, `SpaceSyncJobs.start` | Synchronisieren, Erneut abgleichen | `WorkspaceTest.*`, `MainWindowTest` |
| `GET /api/v1/syncs/{id}` | `SpaceSyncJobs.status` | Aufträge | `WorkspaceTest.harvestsBrowses…` |
| `GET /api/v1/syncs/{id}/events`, `GET /api/v1/exports/{id}/events` | `Harvester.events`, `Harvester.recentEvents` | Status (Diagnose), Aufträge › Diagnose ansehen | `StatusViewTest` |
| `POST /api/v1/syncs/{id}/cancel` | `SpaceSyncJobs.cancel` | Aufträge › Abbrechen | `WorkspaceTest.aRunningSyncCanBeCancelled` |
| `POST …/spaces/{key}/exports` | `Exports.start` | Herunterladen › Herunterladen | `WorkspaceTest.harvestsBrowses…` |
| `GET /api/v1/exports/{id}` | `Exports.artifact` | Aufträge › Ergebnis | `WorkspaceTest.harvestsBrowses…` |
| `GET /api/v1/exports/{id}/artifact/**` | `Exports.artifactFile` | – (nur REST) | `WorkspaceTest.harvestsBrowses…` |
| `POST /api/v1/exports/{id}/cancel` | `Jobs.cancel` | Aufträge › Abbrechen | – |
| `GET …/pages/{pageId}/assets` | `Assets.assets` | Seite › Anhänge | `WorkspaceTest.attachmentsAre…` |
| `POST …/pages/{pageId}/assets/harvest` | `Assets.harvest` | Seite › Anhänge › Alle ablegen | `WorkspaceTest.attachmentsAre…` |
| `GET …/assets/{id}/content` | `Assets.content` | Seite › Anhänge › Speichern … | `WorkspaceTest.attachmentsAre…` (byte-genau) |
| `GET/POST …/assets/{id}/description` | `Assets.describe` | noch nicht (braucht Vision-Modell, #19) | – |
| `GET /api/v1/info` | – (Dienst-Metadaten) | Einstellungen › Diagnose: App-, Java- und Systemversion, Analysen, Exportformate | `MainWindowTest` |
| `/api/compat/confluence/**` | Fassade | entfällt: REST-Kompatibilität für Fremdprogramme | – |
| – | `SourceManagement.test`, `.change`, `.remove` (#188) | Wissensquellen › Quelle hinzufügen …, Bearbeiten …, Entfernen … (Verbindung testen, Speichern) | `SourceFormTest`, `ConfigFileEditorTest`, `WorkspaceTest.theWizardTests…` |
| – | – | Konfiguration, Platzhalter, fehlende Zugangsdaten | `AppSettingsLoaderTest`, `PlaceholdersTest`, `WorkspaceTest.aSourceWithoutToken…` |
| – | Auftragsliste der App | Aufträge nach Neustart | `WorkspaceTest.theActivityListSurvivesARestart` |

„–“ in der Testspalte heißt: Die Aktion ist gebaut, aber noch nicht automatisch getestet.

## Bauen und prüfen

```bash
./gradlew :space-harvester-app:test :space-harvester-app:bootJar
java -jar space-harvester-app/build/libs/space-harvester-app-<version>.jar --version
xvfb-run -a java -jar space-harvester-app/build/libs/space-harvester-app-<version>.jar --config app.yml --screenshot shots
```

Die CI baut das JAR, startet `--version` und rendert die Bereiche unter Xvfb (`appJarCheck`).

## Releases

Der Workflow **Release** (`.github/workflows/release.yml`, bis #134 `desktop-app-release.yml`) baut bei jedem Push
alle Module mit `./gradlew build` (Tests, `appJarCheck`, `bootJarPacCheck`, `verifyPublishedPom`) und stellt
dieselbe Version aus demselben Commit als ein Release bereit, mit `SHA256SUMS` und Herkunftsnachweis
(Build-Provenance-Attestierung):

| Datei | Modul | Inhalt |
|---|---|---|
| `space-harvester-app-<v>.jar` | `:space-harvester-app` | Desktop-App, ausführbar, startet ohne Server |
| `space-harvester-<v>.jar` | `:space-harvester-server` | REST-Service, ausführbares Spring-Boot-JAR (Name wie vor #100) |
| `space-harvester-core-<v>.jar`, `-sources.jar`, `-javadoc.jar` | `:space-harvester-core` | Bibliothek, normales JAR ohne Abhängigkeiten |

Der Workflow prüft vor dem Hochladen, dass App (`--version`) und Server (`build-info.properties`) die Release-Version
nennen und das Core-JAR kein Fat-JAR ist. Für Maven-Nutzer mit transitiven Abhängigkeiten bleibt die POM-Veröffentlichung
(`publishMavenPublicationToLocalRepository`, siehe [MODULES.md](MODULES.md)) ein eigener Schritt.

| Auslöser | Ergebnis |
|---|---|
| Push auf `main` | neues Pre-Release `v<Basis>-<Kanal>.<N>`, z. B. `v0.1.0-beta.3`, `v0.1.0-beta.4`, … |
| Push auf einen anderen Branch | das rollierende Pre-Release `snapshot` wird durch diesen Stand ersetzt (`…-SNAPSHOT.jar`) |
| Tag `v<Major>.<Minor>.<Patch>[-<Vorabkennung>]` auf einem `main`-Stand | Release genau mit dieser Version |
| *Run workflow* auf `main` mit Feld `tag` | wie ein Tag; der Lauf legt den Tag selbst an |

- Die Version steuert zentral `build.gradle` für alle Module: ohne Angabe `0.1.0-SNAPSHOT`, im Release
  `-PreleaseVersion=<v>`. Lokal: `./gradlew build -PreleaseVersion=0.1.0-beta.3`.
- **Basis** ist die Version aus `build.gradle` ohne `-SNAPSHOT` (derzeit `0.1.0`), **Kanal** steht als
  `RELEASE_CHANNEL` im Workflow (derzeit `beta`; davor gab es `v0.1.0-alpha.1`). `N` zählt die vorhandenen Tags des
  Kanals je Push hoch. Für den nächsten Kanal `RELEASE_CHANNEL` auf `rc` setzen, für eine neue Version die Basis in
  `build.gradle` erhöhen; die Zählung beginnt dann wieder bei 1.
- Ein reguläres Release (ohne Vorabkennung, als „Latest“ markiert) entsteht nur über einen Tag wie `v0.1.0` oder
  *Run workflow* mit `tag`.
- Versionierte Releases werden nie überschrieben. Nur `snapshot` wird ersetzt; zum Testen ein versioniertes Release
  nehmen. Releases bis `v0.1.0-beta.2` enthalten nur die Desktop-App.
