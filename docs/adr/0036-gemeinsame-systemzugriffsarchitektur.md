# ADR 0036: Gemeinsame Systemzugriffsarchitektur von Space Harvester und Command Deck

- Status: **Angenommen als Zielbild** (Entscheidungen von Angelo, 25./26.11.2026, festgehalten in Epic #1461).
  Teilweise umgesetzt, siehe „Stand“. Die Punkte unter „Offene Fragen“ sind nicht entschieden.
- Quellen: Epic #1461 (Abschnitte A, B, D, E, F), #1459, #1466, #1475, PRs #1467, #1471, #1477, #1482; Angelos
  Zusammenfassung vom 26.11.2026 (Abschnitte 2 bis 4 und 15).
- Bezüge: [ADR 0012](0012-core-als-gemeinsame-bibliothek.md) (Core als gemeinsame Bibliothek),
  [ADR 0014](0014-abgleich-berechtigung-suche-getrennt.md) (Rechte), [ADR 0028](0028-ki-freigabe-je-seite.md)
  (KI-Freigabe), [ADR 0037](0037-quellenumfang-nutzungssteuerung-beschaffung.md) (Umfang und Nutzung der Quellen),
  [ADR 0038](0038-indexbasierte-progressive-und-agentengestuetzte-suche.md) (Suche),
  [ADR 0041](0041-dokumentbasierte-programmanalyse.md) (Programmanalyse).

## Kontext

Command Deck und Space Harvester sind getrennt gewachsen. Das Deck greift interaktiv auf FTP, NDV, JES, BetaView und
lokale Dateien zu, der Space Harvester erschließt konfigurierte Wissensquellen. Beide haben für dieselben Systeme
eigene Verbindungen und teils eigene Ablagen (im Deck etwa der NDV-Cache). Mit der Mainframe-Analyse (#1447) drohte
eine dritte Ablage für Programmquellen und Analysen.

## Entscheidung

1. **Zwei Perspektiven, eine Infrastruktur.** Command Deck (interaktiver Systemzugriff, Live-Recherche) und Space
   Harvester (erschlossene Wissensbestände, Analysen) sind zwei Perspektiven auf dieselben Quellsysteme. Sie
   unterscheiden sich im Verwendungszweck, nicht in der technischen Infrastruktur. Der Chat verbindet beide.
2. **Keine zweite Ablage.** Es gibt keine zweite dauerhafte Indexdatenbank, keinen zweiten Quellcache, keinen zweiten
   Indexierungsmechanismus und keine eigene Analyseinfrastruktur nur für das Deck. Wiederverwendet werden
   `SourceCatalog`/`HarvestSource`, `SourceSnapshots`/`SnapshotStorageStrategy`, `Holdings`, `PageTextIndexing`,
   `BackgroundJobs`, `Indexing`/`IndexMarks` und die Zugriffskontrollen. Keine neue Graphdatenbank.
3. **Vier Begriffe, getrennt.**

   | Begriff | Bedeutung |
   | --- | --- |
   | Quellsystem | Das erreichbare System mit Verbindung, Zugangskontext und Fähigkeiten |
   | Live-Dienst | Gezielter lesender Zugriff ohne vorausgesetzten dauerhaften Bestand; kein Eintrag im Wissensbaum |
   | Wissensquelle | Ein ausdrücklich abgegrenzter, erschließbarer Bereich eines Quellsystems |
   | Index | Eine abgeleitete Repräsentation eines ausgewählten Bestands |

   Ein Quellsystem kann zugleich Live-Dienst und eine oder mehrere Wissensquellen anbieten. Es gibt **keinen
   Quellentyp „Nur Live-Zugriff“**. „Indexierbar“ ist eine Fähigkeit des Bereichs und des Adapters, „indexiert“ ist ein
   Zustand, den der Benutzer auslöst. Nicht indexiert heißt nicht unzugänglich.
4. **Fähigkeiten als Verträge.** Navigation, gezielte Suche, Lesen, Inventur und selektive Übernahme sind Ports. In
   generischen Oberflächen und Anwendungsfällen gibt es keine Fallunterscheidung nach Quellentyp. Die Oberfläche
   zeigt nur Aktionen, die der Adapter tatsächlich anbietet.
5. **„Indexieren“ ist die zentrale Übernahmeaktion.** Der Benutzer navigiert im Deck unabhängig von vorhandenen
   Wissensquellen. Will er einen Bereich dauerhaft erschließen, löst er „Indexieren“ aus:
   - Umfang und Zugangskontext werden vor der Ausführung angezeigt und bestätigt. Der Umfang wird nie stillschweigend
     auf ein Laufwerk, einen Host oder unbeteiligte Bibliotheken erweitert.
   - Eine vorhandene Quelle wird wiederverwendet, wenn **Zugangskontext und Geltungsbereich** passen. Gleicher Host
     und Port oder gleicher Pfad genügen nicht. Sonst entsteht bewusst eine neue Quelle mit eindeutigem Umfang.
   - Es läuft der gemeinsame Indexierungsauftrag (`Indexing`, `IndexMarks`, `BackgroundJobs`). Indexiert wird nie
     schon durch Anmelden oder Browsen (die navigationsgetriebene Befüllung aus ADR 0037 ist ein eigener,
     ausdrücklich eingeschalteter Weg).
   - Wiederholung ist idempotent: keine doppelten Quellen, Aufträge oder Originale. Das Entfernen einer Markierung
     löscht keine geladenen Daten. Quelle entfernen und Bestand bereinigen bleiben eigene, bewusste Aktionen.
   - Es gibt keine Schreiboperationen auf dem Mainframe.
6. **Verbindung und Zugangskontext.** Verbindungen kommen aus der sicheren Konfiguration (`sourceId`,
   `CredentialRef`, Verbindungsprofil). Kennwörter werden nicht kopiert, Berechtigungen und Caches verschiedener
   Zugangskontexte nicht vermischt.
7. **Eine Ressource wird nicht allein über ihren Namen identifiziert.** Die logische Identität besteht aus System,
   Zugangskontext, Bereich, Objekt und Typ, bei NDV zusätzlich aus der Systemdatei (DBID/FNR). Davon getrennt sind
   die Gültigkeitsangaben Quellrevision, Inhalts-Hash und Parser-Version. Mehrdeutige Verweise werden nicht über den
   ersten passenden Namen aufgelöst. Wissensbaum, Live-Ansicht und Chat adressieren dieselbe Ressource und dieselbe
   Fundstelle.
8. **Rohinhalt, dekodierter Text, Darstellung und Analyseergebnis sind verschiedene Dinge.** Programmtexte bleiben
   zeilen- und spaltentreu (Fixed Format, Zeichensatz, Satzstruktur). Eine Markdown-Verpackung ist nie Eingabe eines
   Parsers.
9. **Live-Dienste sind unabhängig von der Oberfläche.** Mensch und KI nutzen dieselben lesenden Anwendungsfälle
   (`ChatToolset`, `DeckChatTools`, `JesChatTools`). Ein Werkzeug hängt nie davon ab, welcher Reiter sichtbar ist,
   und steuert nie die Oberfläche fern.
   - Freigaben gelten je Verbindung oder Arbeitsbereich, sind standardmäßig aus, widerrufbar und werden bei jedem
     Aufruf geprüft. Lesefreigabe und Freigabe zur Übermittlung an ein Modell sind getrennt.
   - Ein breiter lokaler KI-Zugriff hebt die Schutzregeln für Geheimnisorte nicht auf (KeePass-Datenbanken, `.ssh`,
     `.gnupg`, `.aws`, `.kube`, Browserprofile, Token-Speicher, Konfigurations- und Datenverzeichnisse des
     Harvesters). Netzlaufwerke, UNC-Pfade und Cloud-Platzhalter werden ohne eigene Freigabe nicht durchlaufen.
   - Eine KI-Abfrage indexiert nie dauerhaft. Fundstellen aus Live-Werkzeugen sind anklickbare, rechtegeprüfte
     Verweise in die passende Perspektive.
10. **JES und BetaView bleiben primär Live-Dienste.** Spool und Reportarchiv werden nie automatisch vollständig
    indexiert. Bestehende, eng konfigurierte Quellen bleiben möglich und werden nicht migriert. Eine spätere Aktion
    „In Wissensbestand übernehmen“ hält einen ausgewählten Joblauf oder Beleg fest. Die Identität einer solchen
    Evidenz umfasst Ausführung, Datum und Herkunft, weil Job-IDs wiederverwendet werden. Verschwindet sie aus dem
    Spool, gilt sie nicht als gelöscht. Das Terminal bleibt rein interaktiv und ist keine Wissensquelle.
11. **Schichtung.** Core und Server hängen nicht von Swing oder dem Modul `command-deck` ab. Die Upstream-Kopie
    `enterprise-ai-sources` wird nie direkt geändert, Änderungen gehen über den Enterprise AI Client.

## Stand (main `69835ca8`, 26.11.2026)

| Baustein | Stand |
| --- | --- |
| „Indexieren“ für lokale Ordner im Deck (`LocalFolderIndexAction`, `LocalFolderIndexPlan`) | auf main (#1467) |
| „Indexieren (Regel anlegen)“ und „Jetzt indexieren“ für NDV-Bibliotheken (`NdvSearchLink`, `NdvSourceRules`) | auf main (Regeln je Konto #1471, Bibliotheksauswahl #1482) |
| FTP: Quelle mit HLQs und Startpunkten, navigationsgetriebene Befüllung | auf main (#1477); **keine „Indexieren“-Aktion im FTP-Bereich des Decks** |
| NDV-Identität im Deck-Cache: Bibliothek, Name, Typ, DBID/FNR, je Konto getrennt (`NdvSourceCache`) | auf main (#1471) |
| Typisierte Identitätsauflösung in allen programmübergreifenden Analysen | offen (#1470) |
| UI-unabhängige Live-Werkzeuge mit Freigabe je Verbindung (Epic Abschnitt E, Schnitt 4) | offen |
| Übernahme flüchtiger Evidenz aus JES/BetaView (Abschnitt F) | offen |

## Widersprüche zwischen Zielbild und Stand

- **Deck-eigener NDV-Cache.** `NdvSourceCache` und `FileNdvSourceStore` im Modul `command-deck` sind weiterhin eine
  eigene dauerhafte Ablage neben dem Bestand der NDV-Wissensquelle. Punkt 2 schließt das im Zielbild aus. Epic #1461
  sagt dazu nur „prüfen und ggf. schrittweise integrieren, nicht ungeprüft entfernen“.
- **FTP ohne „Indexieren“ im Deck.** Die Epic-Abnahme verlangt die Aktion auch für FTP-Datasets. Auf main erreicht
  man denselben Bestand nur über die Quellenverwaltung oder die navigationsgetriebene Befüllung.

## Offene Fragen an Angelo

- **F36-1:** Soll der FTP-Bereich des Decks dieselbe Aktion „Indexieren“ bekommen wie lokale Ordner und NDV, oder
  gilt für FTP der Weg über die Quellenverwaltung (HLQs und Startpunkte) als endgültig?
- **F36-2:** Soll der Deck-eigene NDV-Cache im Bestand der NDV-Wissensquelle aufgehen (Ablöseziel), oder darf er als
  eigener Cache des Decks dauerhaft bleiben?

## Folgen

- Neue Quellsysteme brauchen einen Adapter mit Fähigkeiten, keine neue vertikale Anwendung.
- Das Deck verliert schrittweise eigene Ablagen. Bis dahin sind sie Übergang und werden nicht ausgebaut.
- Jede neue Funktion, die Inhalte dauerhaft ablegt, muss den gemeinsamen Bestand nutzen oder in einer ADR begründen,
  warum nicht.
