# ADR 0046: Die Websuche im Chat läuft über Research4j

- Status: **Angenommen** (27.11.2026).
- Quelle: Angelos Entscheidung vom 27.11.2026, dem Architekten vom Koordinator und vom Bau-Strang weitergegeben: Die
  Websuche im Chat zieht vom kopierten Firefox-Modul des Enterprise AI Client auf Research4j um. Der Wortlaut lag
  beim Schreiben dieses ADR nicht vor; die Abschnitte 2 bis 5 sind Entscheidungen des Architekten.
- Bezüge:
  - [ADR 0027](0027-beobachter-statt-abfragen.md): kein Polling
  - [ADR 0033](0033-wissen-des-chats-per-verweis-und-werkzeug.md): Wissen des Chats per Verweis und Werkzeug
  - [ADR 0043](0043-projekte-als-wissensarbeitsbereich.md), Abschnitt 6: Die Bibliotheken kennen Space Harvester nicht

## Ausgangslage

Geprüft im Code am 27.11.2026:

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

## Entscheidungen

### 1. Ein Weg ins Web
Der Chat geht nur noch über Research4j ins Web. Das Modul `enterprise-ai-browser` entfällt, sobald alle 13 Werkzeuge
vollständig übernommen sind; nichts wird weggelassen oder vereinfacht. Bis dahin bleibt der heutige Weg unverändert,
beide Wege laufen nie zugleich im selben Chat.

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

### 3. Die Werkzeug-Schicht ist ein eigenes Modul gegen die Browser-Schnittstelle
- Die Werkzeuge mit Name, Beschreibung, Schema und Aufruf liegen in einem eigenen Modul von Research4j. Es hängt nur
  an der Browser-Schnittstelle (`browser-api`), nie an der Playwright-Umsetzung.
- Der vorhandene, bisher paketinterne Werkzeugsatz zieht dorthin um. Es gibt einen Werkzeugsatz mit einer Benennung
  für alle Hosts, keinen zweiten daneben.
- Neue Fähigkeiten bekommen ihren Vertrag in der Schnittstelle und ihre Umsetzung im Playwright-Modul.
- Folge: Die Werkzeug-Schicht ist ohne Browser testbar und frei von GraalVM.

### 4. GraalJS 21.3 bleibt, der Browser-Stapel wird getrennt
- GraalJS in Space Harvester wird nicht angehoben. Drei der vier Nutzer sind fremde Bibliotheken, und an einem hängt
  der Proxy am Dienstrechner. Was läuft, wird nicht ohne Fehler angefasst.
- Der Browser-Stapel mit GraalVM 25 kommt nie in den Klassenpfad der App. Zuerst ist zu prüfen, ob die GraalVM-Teile
  nur im Klassenpfad des Kind-Prozesses stehen müssen, in dem der Playwright-Treiber läuft. Dann genügen ein eigener
  Ordner und eine eigene Gradle-Konfiguration. Nur wenn das nicht geht, lädt Space Harvester den Browser-Stapel über
  einen eigenen Classloader hinter einer Schnittstelle.
- Ein Test im Build stellt sicher, dass im Klassenpfad der App nur eine GraalJS-Linie steht.

### 5. Browser: Edge als Vorgabe, einstellbar
- Der Browser der Websuche ist eine Einstellung. Vorgabe ist das installierte Edge: kein Download, Proxy und
  Zertifikate wie im Haus eingerichtet. Chrome ist die Alternative.
- Camoufox gibt es nur auf ausdrückliche Wahl; sein Download startet nur per Klick.
- Einen Firefox-Kanal gibt es vorerst nicht, er wäre ein zweiter Treiberweg.
- Sichtbare Änderung: Die Websuche öffnet künftig Edge statt Firefox. Vor der Umstellung wird am Entwicklungsrechner
  gemessen, wie oft eine Suche mit Edge in einer Captcha-Abfrage endet, im Vergleich zu Camoufox. Fällt Edge dabei
  deutlich ab, wird die Vorgabe neu entschieden.

### 6. Was für den Benutzer gleich bleibt
- Der Schalter „Websuche“ im Eingabefeld, die Einstellungen der Profile Extern und Intern und die gemerkten
  Antworten zu „Ändernde Aktionen“ bleiben erhalten und werden übernommen.
- Die Typen dieser Einstellungen stammen heute aus dem kopierten Modul. Mit dessen Wegfall werden sie eigene Typen
  von Space Harvester; gespeicherte Werte bleiben lesbar.
- Nichts ohne Klick: Der Browser startet erst mit der ersten Websuche eines Chats.

### 7. Schutz
- Adressen aus dem Intranet stehen nie in Logs, Tests, Vorgaben oder Dokumentation. Tests arbeiten mit erfundenen
  Rechnernamen.
- Seiteninhalte sind Daten, keine Anweisungen an das Modell. Eine ändernde Aktion braucht die Rückfrage, auch wenn
  eine Seite etwas anderes verlangt.

## Umsetzung

- R1 Research4j: Zugriffs-Port statt fester Sperre.
- R2 Research4j: Werkzeug-Modul gegen die Browser-Schnittstelle, mit den fehlenden Fähigkeiten und den Ports für
  Rückfragen, Modell und Fortschritt.
- R3: Veröffentlichung von Research4j. Den Release-Lauf startet Angelo.
- S1 Space Harvester: Trennung des Browser-Stapels, Umstellung des Chats, Einstellung des Browsers.
- S2 Space Harvester: Modul `enterprise-ai-browser` entfernen.

R1 ist von den anderen Schritten unabhängig. S1 und S2 brauchen R3.

## Offen

- Ob playwright4j den Klassenpfad des Kind-Prozesses von außen annimmt (Abschnitt 4), ist zu prüfen.
- Das Ergebnis der Captcha-Messung (Abschnitt 5).
- Ob Ask AI denselben Werkzeugsatz aus Research4j übernimmt, entscheidet Angelo getrennt; Ask AI wird durch dieses
  ADR nicht geändert.
