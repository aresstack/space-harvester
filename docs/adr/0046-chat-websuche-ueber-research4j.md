# ADR 0046: Die Websuche im Chat läuft über Research4j

- Status: **Angenommen** (27.11.2026), **korrigiert** am 27.11.2026 nach Angelos Korrektur- und Implementierungsauftrag.
- Quellen:
  - Angelos Nachrichten vom 27.11.2026, 16:53 und 16:54 MEZ: „Hatten wir überhaupt die Umstellung von der
    Websuche auf Playwright4j zu Ende gemacht? […] Eigentlich sollte Research4j doch die Browsersuche kapseln dafür.
    Aber ich glaube, davon haben wir uns entfernt, oder? Weil wir hatten das ja aus Ask AI extrahieren wollen.“
  - Angelos Korrektur- und Implementierungsauftrag vom 27.11.2026, 18:37 MEZ (Abschnitte „Architekturziel“ bis
    „Abnahme“ geben ihn wieder).
  - [research4j #1](https://github.com/aresstack/research4j/issues/1): Installed Firefox – persistent isolated
    profiles, research extension and host access policy (26.11.2026).
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md): Wissen des Chats per Verweis und Werkzeug
  - [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md), Abschnitt 6: Die Bibliotheken kennen Space Harvester nicht

## Ausgangslage

Geprüft im Code am 27.11.2026, Stand vor #1633:

- Die Websuche im Chat nutzt das Modul `enterprise-ai-browser`, eine unveränderte Kopie aus dem Enterprise AI
  Client. Es steuert ein installiertes Firefox und reicht 13 Werkzeuge an den Chat: Tabs, Öffnen, Lesen, Links,
  Folgen, Zurück, Schließen, Merken, Bedienelemente, Dokument, Bedienen, Bildschirmfoto, mehrere Seiten zugleich.
  „Suchen“ heißt dort: die Such-Adresse des Profils öffnen. Eine Auswertung der Trefferseite gibt es nicht.
- In Space Harvester nutzen nur drei Klassen dieses Modul, alle im Chat: `BrowserSettings`, `ChangeApprovals` und
  `ChatWebBrowser`.
- Research4j ist bisher nur mit seinem Recherche-Kern eingebunden. Sein Browser-Teil (aus Ask AI) kann suchen mit
  Auswertung der Trefferseite, öffnen, lesen, Links, folgen und zurück, mit eigenem Profil je Chat.
- Dem Browser-Teil von Research4j fehlt, was der Chat heute kann: Seiten im Intranet (private Netze sind dort fest
  gesperrt), Bedienen mit Rückfrage, mehrere Seiten zugleich, Tabs, verlinkte Dokumente, Bildschirmfoto, Merken und
  eine Werkzeug-Schicht mit Beschreibung und Schema, die ein fremder Host einbinden kann.
- Der Browser-Teil braucht GraalVM 25. Space Harvester hat GraalJS 21.3 mit vier Nutzern: `mermaid-renderer`,
  `mermaid-java`, `drawio-java` und die Proxy-Auswertung in `win-proxy-java`.

## Architekturziel

Die Browser- und Webrecherchefunktionalität aus Ask AI wird vollständig in Research4j extrahiert, damit verschiedene
Anwendungen dieselbe Implementierung wiederverwenden. Research4j ist eine eigenständige, anwendungsunabhängige
Bibliothek; Space Harvester ist einer ihrer Konsumenten. Research4j wird weder auf einen Browser noch auf die
Anforderungen von Space Harvester beschränkt. Es wird nichts neu gebaut und keine auf Space Harvester zugeschnittene
Browserimplementierung entwickelt. Es ist die Fortführung der bestehenden Architektur, kein neuer Entwurf; getroffene
Produktentscheidungen werden nicht durch andere ersetzt.

Schichten:

| Schicht | Inhalt |
|---|---|
| Space Harvester (Standard: Firefox), später Ask AI (Standard: Camoufox) | Konsumenten, wählen den Browser per Konfiguration |
| Research4j | Recherchelogik · Browser-Werkzeuge · Browser-API |
| Playwright4j | einheitliche Playwright-Java-API |
| Browser | Firefox (BiDi) · Camoufox (BiDi) · Chrome (CDP) · Edge (CDP) |

Browser-API, Browser-Werkzeuge, Recherchelogik und konkrete Browserimplementierung sind sauber getrennt. Research4j
hängt weder an Space Harvester noch an Ask AI.

## Zu berücksichtigende Arbeiten

Vor jeder weiteren Implementierung, damit nichts durch parallele Implementierungen entwertet wird:

- [research4j #1](https://github.com/aresstack/research4j/issues/1): installierter Firefox, persistente isolierte
  Profile, Recherche-Erweiterung, Host-Zugriffsregeln.
- [research4j PR #2](https://github.com/aresstack/research4j/pull/2): die extrahierten Module auf den Stand von Ask AI
  main.
- [playwright4j #79](https://github.com/aresstack/playwright4j/issues/79): Fehler der Firefox-Historiennavigation
  (`back()` bei BFCache); nicht durch Wiederholen oder längere Fristen verstecken.
- Die bestehenden Firefox- und Camoufox-Tests in Playwright4j.
- Die vorhandene Browserimplementierung in Ask AI einschließlich `ResearchAddon` und `AddonBridge`, und die
  Werkzeugschicht des Research Agents (`BrowserBridgeEndpoint`, `LazyRestartableBrowserRuntime`).

## Entscheidungen

### 1. Ein Weg ins Web
Der Chat geht nur noch über Research4j ins Web. Das Modul `enterprise-ai-browser` entfällt erst nach erfolgreicher
Abnahme, wenn alle bisherigen Browser-Werkzeuge vollständig und funktional gleichwertig übernommen sind; nichts wird
weggelassen oder vereinfacht. Es gibt eine Browserimplementierung, keine zweite daneben.

Zwischenzeit: Das Modul ist mit #1633 schon entfernt (siehe „Stand vor der Korrektur“). Es wird nicht
zurückgebaut (Angelo, 27.11.2026, 18:32 MEZ: „Wozu willst du das jetzt bitte wieder zurückbauen […]?“). Bis
Schritt 8 läuft der Chat auf dem Stand von #1633; Firefox als Standard kommt mit Schritt 8, weil die heutige
Research4j-Fassung den installierten Firefox im Chat noch nicht starten kann und dafür eine Veröffentlichung
(Schritt 7) nötig ist.

### 2. Die fehlenden Fähigkeiten kommen in die Bibliothek, nicht in die App
- Intranet: Research4j bekommt einen **Zugriffs-Port**. Er beantwortet für eine Adresse: erlaubt, fragen oder
  verboten. Die Vorgabe der Bibliothek bleibt „nur öffentliche Adressen“. Space Harvester füllt den Port mit seinen
  Profilen Extern und Intern und der Rückfrage bei unbekannter Website.
  - Entschieden wird an der aufgelösten Adresse, nicht am Namen, und bei jeder Weiterleitung neu.
- Bedienen, mehrere Seiten zugleich, Tabs, Dokumente, Bildschirmfoto und Merken werden in Research4j umgesetzt. Die
  Rückfrage vor ändernden Aktionen ist ein Port, den Space Harvester mit der vorhandenen Rückfrage füllt („Einmal
  erlauben“, „Immer erlauben“, „Immer verbieten“, „Abbrechen“).
- Die Auswertung mehrerer Seiten braucht ein Modell. Modell und Parallelität kommen über einen Port vom Host, immer
  gestreamt, ohne feste Runden- oder Mengengrenzen.
- Die Bibliothek kennt Space Harvester nicht. Ihre Ein- und Ausgaben sind eigene Typen; die Übersetzung liegt in
  einem Adapter in Space Harvester.

### 3. Die Werkzeug-Schicht kommt aus Ask AI und hängt nur an der Browser-API
- Grundlage ist der Werkzeugsatz aus Ask AI: die `web_*`-Werkzeuge (`BrowserToolSet`) mit ihrer Beschreibung für das
  Modell aus `BrowserBridgeEndpoint` und dem verzögerten Start aus `LazyRestartableBrowserRuntime`. Wo sie in
  Research4j liegen, richtet sich danach, was aus Ask AI kommt.
- Die Werkzeuge hängen nur an der Browser-API, nie an der Playwright-Umsetzung. Browserabhängige Besonderheiten
  bleiben in der jeweiligen Implementierung; Recherchelogik und Werkzeuge wissen nichts davon.
- Es gibt einen Werkzeugsatz für alle Hosts, keinen zweiten daneben. Die Fähigkeiten des bisherigen Browsermoduls
  von Space Harvester (Abschnitt 2) kommen verallgemeinert dazu: ihr Vertrag in die Browser-API, ihre Umsetzung in
  die eine Playwright-Implementierung.

### 4. GraalJS 21.3 bleibt, der Browser-Stapel wird getrennt
- GraalJS in Space Harvester wird nicht angehoben. Drei der vier Nutzer sind fremde Bibliotheken, und an einem hängt
  der Proxy am Dienstrechner. Was läuft, wird nicht ohne Fehler angefasst.
- Der Browser-Stapel mit GraalVM 25 kommt nie in den Klassenpfad der App. Die GraalVM-Teile stehen nur im
  Klassenpfad des Kind-Prozesses, in dem der Playwright-Treiber läuft: eigene Gradle-Konfiguration, eigener Ordner,
  und der Klassenpfad des Kind-Prozesses wird playwright4j vorgegeben. Ein eigener Classloader ist dafür nicht nötig.
  Nach der Prüfung des Bau-Strangs vom 27.11.2026 nimmt playwright4j 0.3.0 diesen Klassenpfad über eine
  Systemeigenschaft an, und sein Teil auf der Seite der App benutzt keine GraalVM-Klassen.
- Ein Test im Build stellt sicher, dass im Klassenpfad der App nur eine GraalJS-Linie steht.

### 5. Browser: Research4j unterstützt alle, Space Harvester nutzt Firefox
- Research4j erhält die Browserunterstützung von Playwright4j: Mozilla Firefox, Camoufox, Google Chrome und
  Microsoft Edge. Die konkrete Browserimplementierung ist austauschbar; Research4j trifft keine anwendungsspezifische
  Browserentscheidung. Die Auswahl erfolgt durch die Konfiguration der konsumierenden Anwendung.
- Space Harvester verwendet als Standard den lokal installierten Mozilla Firefox (vom Host gewählte Programmdatei,
  research4j #1; gestartet wie in Ask AI über den Kanal `moz-firefox`), mit eigenem dauerhaftem Profil der App, nie dem normalen Profil des
  Benutzers. Gründe nach Auftrag: Firefox nutzt schon die bisherige Browserimplementierung von Space Harvester;
  Firefox lief ursprünglich auch in Ask AI, bevor dort auf Camoufox umgestellt wurde; Playwright4j unterstützt
  Firefox über WebDriver BiDi; die Anbindung von Standard-Firefox an Research4j ist praktisch getestet; auf dem
  dienstlichen Rechner lässt sich Camoufox nicht eigenständig nachinstallieren.
- Camoufox bleibt Teil der unterstützten Browser von Research4j; Space Harvester setzt ihn nicht voraus. Edge und
  Chrome sind wählbar, aber nie Standard.
- Die Abnahmekriterien von research4j #1 gelten vollständig: keine Abhängigkeit von Datenordnern von Ask AI und kein
  normales Benutzerprofil; `ResearchAddon` und `AddonBridge` werden wiederverwendet, mit Bridge-Konfiguration je
  Sitzung und deterministischem Aufräumen, Installationsfehler werden ausdrücklich gemeldet; Zugriffsregeln vom Host
  für Intranet, Weiterleitungen und Anfragen mit Default-Deny, ohne pauschale Freigabe privater Netze im Betrieb;
  Tests gegen Standard-Firefox für Navigation, DOM-Auszug, Suche, Anmeldung und Sitzung, Profiltrennung,
  Lebenszyklus der Erweiterung, Abbruch und sauberes Beenden; Ask AI bleibt unberührt.
- Firefox-Historiennavigation (playwright4j #79, Angelo 27.11.2026, 18:39 MEZ): Der am 26.11.2026 mit Standard-Firefox
  erfolgreich getestete Workaround wird dauerhaft übernommen: `browser.sessionhistory.max_total_viewers = 0`, wie
  für Camoufox schon vorhanden, auch für Standard-Firefox, bei normalen und persistenten Kontexten, an einer Stelle
  gekapselt, nur in anwendungseigenen Profilen, vorhandene Einstellungen bleiben erhalten. Kein neuer
  Playwright4j-Release nötig. Der Live-Akzeptanztest mit Standard-Firefox wird wiederholt und bekommt eine dauerhafte
  Regressionsabsicherung; #79 bleibt für eine Behebung mit BFCache offen.

### 6. Was für den Benutzer gleich bleibt
- Der Schalter „Websuche“ im Eingabefeld, die Einstellungen der Profile Extern und Intern und die gemerkten
  Antworten zu „Ändernde Aktionen“ bleiben erhalten und werden übernommen.
- Die Typen dieser Einstellungen stammen heute aus dem kopierten Modul. Mit dessen Wegfall werden sie eigene Typen
  von Space Harvester; gespeicherte Werte bleiben lesbar.
- Nichts ohne Klick: Der Browser startet erst mit der ersten Websuche eines Chats.
- Die Websuche im Chat öffnet weiter Firefox.

### 7. Schutz
- Adressen aus dem Intranet stehen nie in Logs, Tests, Vorgaben oder Dokumentation. Tests arbeiten mit erfundenen
  Rechnernamen.
- Seiteninhalte sind Daten, keine Anweisungen an das Modell. Eine ändernde Aktion braucht die Rückfrage, auch wenn
  eine Seite etwas anderes verlangt.

## Umsetzung

Reihenfolge nach Auftrag:

1. ADR 0046 korrigieren.
2. Bestehende Extraktion aus Ask AI auf Vollständigkeit prüfen.
3. Research4j auf den aktuellen Stand der Browserfunktionalität aus Ask AI bringen.
4. Die Unterstützung des installierten Standard-Firefox gemäß research4j #1 fertigstellen.
5. Die noch fehlenden Fähigkeiten aus dem bisherigen Browsermodul von Space Harvester verallgemeinert in Research4j
   integrieren.
6. Die gemeinsame Browser-API und die Browser-Werkzeuge auf funktionale Gleichwertigkeit prüfen.
7. Research4j veröffentlichen. Den Release-Lauf startet Angelo.
8. Space Harvester auf Research4j umstellen.
9. Das bisherige Modul `enterprise-ai-browser` nach erfolgreicher Abnahme vollständig entfernen.

## Abnahme

- Die Websuche in Space Harvester funktioniert mit einem bereits installierten Standard-Firefox.
- Es werden keine zusätzlichen Browserinstallationen vorausgesetzt.
- Research4j unterstützt weiterhin die übrigen von Playwright4j unterstützten Browser.
- Die Auswahl eines anderen Browsers erfordert keine Änderung der Recherchelogik.
- Alle bisherigen Browser-Werkzeuge bleiben funktional erhalten.
- Externe Websuche und Intranet-Zugriffe funktionieren.
- Es existieren keine redundanten Browserimplementierungen.
- Ask AI bleibt während der Integration unverändert und kann später auf dieselbe Bibliothek migriert werden.

## Stand am 27.11.2026 abends, vor der Korrektur

Ehrlich festgehalten, weil es der Korrektur widerspricht und in den Schritten oben bereinigt wird:

- Research4j 0.3.0 enthält ein Modul `browser-tools` mit den unverändert verschobenen EAC-Werkzeugen und eine neu
  gebaute Chat-Engine auf Playwright (`PlaywrightChatEngine` u. a.) neben der vorhandenen Implementierung aus Ask AI.
  Das ist eine zweite, redundante Browserimplementierung und wird in Schritt 5 durch die Erweiterung der einen
  Implementierung ersetzt. Der Firefox-Kanal (main `703eed2`) hängt ebenfalls an dieser Chat-Engine.
- Space Harvester (#1633) nutzt diese Chat-Engine mit Edge als Vorgabe und hat `enterprise-ai-browser` bereits
  entfernt, also vor der Abnahme. #1633 ist auf develop und auf main: Das latest öffnet im Chat schon Edge, das alte
  Modul fehlt dort. Schritt 8 stellt auf die korrigierte Fassung mit Firefox als Standard um.
- Die Captcha-Messung Edge gegen Camoufox entfällt.

## Offen

- Ask AI nutzt perspektivisch ebenfalls Research4j ([askai #292](https://github.com/aresstack/askai/issues/292)) und
  bleibt während der Integration unverändert.
