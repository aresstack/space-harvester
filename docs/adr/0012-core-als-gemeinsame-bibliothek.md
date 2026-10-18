# ADR 0012: Space Harvester Core als gemeinsame Wissensquellen- und Dokumentverarbeitungsbibliothek

- Status: **Angenommen** (Strang S, 18.10.2026; Architekturvorgabe von Angelo, 18.10. 02:11).
- Bezüge: [ADR 0006](0006-core-bibliothek-und-server.md) (Core-Bibliothek und Server),
  [ADR 0008](0008-desktop-app.md) (Desktop-App), [ADR 0010](0010-export-ohne-vorbereiteten-bestand.md)
  (Beschaffungs-Use-Case), [ADR 0011](0011-embeddings-ueber-externen-ki-dienst.md) (Embeddings),
  Paritätsprüfung [docs/PARITY.md](../PARITY.md).

## Kontext

Der Enterprise AI Client (`aresstack/enterprise-ai-client`) enthält heute eine eigene, ausgereifte Wissensquellen- und
Dokumentverarbeitung: Konnektoren (Confluence und weitere Quellen), Live-Suche, Navigation, Leseansicht mit Bildern
und Diagrammen, Wissensindex mit Embeddings und einen Word-Export mit Inhaltsverzeichnis und eingebetteten Bildern.
Space Harvester ist bisher ein eigenständiger Dienst mit Desktop-App, der Teile davon über die unveränderte Kopie
`enterprise-ai-sources` nutzt und anderes neu gebaut hat.

Beim Word-Export hat sich gezeigt, was eine vermeintlich einfachere Neuimplementierung kostet: Das DOCX entstand,
aber Inhaltsverzeichnis und Bilder fehlten. Angelo hat deshalb festgelegt, dass Space Harvester langfristig die
gesamte Wissensquellen- und Dokumentverarbeitung des Enterprise AI Client übernimmt und der Client diese
Funktionalität danach nicht mehr selbst implementiert.

## Entscheidung

### 1. Zielarchitektur

```text
Enterprise AI Client          KI-Chat, Recherche, Agenten, Benutzeroberfläche
Space Harvester Desktop       Wissensquellen verwalten, suchen und exportieren
Space Harvester REST-Service  dieselben Core-Funktionen über HTTP
        │            │            │
        ▼            ▼            ▼
Space Harvester Core Library  Konnektoren · Suche · Seitenhierarchie · Berechtigungen · Versionen · Cache ·
                              Bilder · Anhänge · Diagramme · Dokumentexport · Embedding- und Suchinfrastruktur
```

Desktop, REST-Service und Enterprise AI Client besitzen keine eigenen fachlichen Implementierungen dieser
Fähigkeiten. Sie rufen dieselben Core-Use-Cases auf (Einstieg `Harvester.builder()`, ADR 0006). Sie unterscheiden
sich nur in Oberfläche und Integrationsadaptern (Swing, REST-Controller, Werkzeuge für Agenten).

### 2. Paritätsregel (verbindlich)

> Space Harvester Core muss die bestehenden Wissensquellen-, Beschaffungs-, Navigations-, Such-, Asset- und
> Exportfähigkeiten des Enterprise AI Client mindestens funktionsgleich bereitstellen. Der Enterprise AI Client soll
> diese Fähigkeiten langfristig ausschließlich über Space Harvester Core verwenden. Funktionsverluste bei der
> Migration sind nicht zulässig.

Daraus folgt:

1. Eine vorhandene Funktion des Enterprise AI Client darf im Space Harvester nicht durch eine funktional
   eingeschränkte Neuimplementierung ersetzt werden. Die interne Umsetzung darf anders, besser oder effizienter
   sein; der Funktionsumfang darf nicht zurückfallen.
2. Maßstab für jeden Strang ist der Enterprise AI Client, nicht „es entsteht eine Datei“. Ein DOCX ist erst fertig,
   wenn es Kapitel, Inhaltsverzeichnis, Tabellen, Bilder und Links mindestens so gut abbildet wie der Export des
   Clients.
3. Wo der Client eine ausgereifte Implementierung hat, ist die Übernahme (als unveränderte Kopie wie
   `enterprise-ai-sources` oder als Kopie in ein eigenes Modul wie `:document-export`) der Normalfall. Eine
   Neuimplementierung braucht eine Begründung in der PR-Beschreibung und einen Paritätsnachweis.
4. [docs/PARITY.md](../PARITY.md) ist die Liste der Fähigkeiten mit Fundstellen in beiden Repositories. Jede Lücke
   hat dort ein Issue. Eine Lücke gilt erst als geschlossen, wenn ihr Vergleichstest (PARITY.md, Abschnitt
   „Vergleichstests“) bestanden ist.
5. Neue Fähigkeiten des Clients im Bereich dieser Bibliothek landen künftig zuerst im Core oder werden gleichzeitig
   in PARITY.md als Lücke eingetragen.

### 3. Bilder, Anhänge und Diagramme gehören in den Core

Die Bildverarbeitung bleibt nicht im Word-Exporter oder in einer Oberfläche versteckt:

1. Seite abrufen und unverändertes Raw-JSON speichern (ADR 0002).
2. Zugehörige Bilder und Anhänge über den authentifizierten Quellkonnektor beschaffen, aus der Anhangsliste und
   aus der Leseansicht (`PageViewSource`), damit auch Bilder ohne Anhang im Storage-Format erfasst werden.
3. Binärdateien versioniert und SHA-256-adressiert im gemeinsamen `AssetStore` ablegen.
4. Referenzen zwischen Seite und Dateien dauerhaft halten (Asset-Manifest des Datenbestands).
5. Word, PDF und Markdown verwenden die gespeicherten Dateien; kein Konverter fragt die Quelle erneut an.
6. Diagramme: vorhandene textuelle Darstellungen (draw.io-XML, SVG, Mermaid-Quelltext) haben Vorrang vor
   Vorschaubildern; Bilder nur, wenn es keine textuelle Form gibt.

Das Raw-JSON behält seine ursprünglichen URLs; kein Base64 im Raw-JSON.

### 4. Abgrenzung

Im Enterprise AI Client bleiben:

- KI-Chat-Oberfläche, Gesprächsführung und Chat-Verlauf,
- Agentenverwaltung (ACP, MCP-Laufzeit, Research-Agent) und die anwendungsspezifische Rechercheorchestrierung
  (Rechercheplanung, Recherchebereiche, Konzeptspeicher),
- Gedächtnis- und Faktenspeicher des Assistenten, Sprachausgabe, Browser je Chat, Modellverwaltung für den Chat.

In die Bibliothek gehören die allgemeingültigen Fähigkeiten, die solche Anwendungen brauchen: Wissensquellen
durchsuchen, Dokumente beschaffen, Inhalte zwischenspeichern, Bilder und Anhänge verwalten, Ergebnisse exportieren.

Bei der semantischen Suche gilt dieselbe Trennung: Allgemeine Embedding- und Suchinfrastruktur (Einbettung über den
externen Dienst nach ADR 0011, Vektorablage, Zerlegung in Abschnitte, Reranking, Live-Prüfung der
Treffer) liegt in der Bibliothek. Die darüberliegende Rechercheplanung (welche Fragen, in welcher Reihenfolge,
welche Quellen, wie Ergebnisse zusammengeführt werden) bleibt im Client.

Werkzeuge, mit denen Agenten des Clients Wissensquellen durchsuchen und lesen (MCP-Werkzeuge), sind Adapter im
Client; der fachliche Use-Case dahinter kommt aus dem Core.

### 5. Laufzeit: Java 21

Der Core bleibt Java 21 (ADR 0006, Punkt 5; ein Java-8-Core wäre ein Neubau von Cache, Suche und Transport). Damit
der Enterprise AI Client den Core als Bibliothek im eigenen Prozess verwenden kann, wird der Client vor
Migrationsschritt 2 auf Java 21 gehoben; dort fällt dann `Java8CompatibilityTest` für die betroffenen Module weg.
Java-8-Anwendungen ohne diesen Schritt (MainframeMate, askai-java8) binden weiter über die REST-API an.

Diese Festlegung ist die Empfehlung von Strang S und wartet auf Angelos Bestätigung; eine abweichende Antwort
(Client bleibt Java 8 und nutzt den Core über REST) ersetzt diesen Punkt und Schritt 2 unten.

### 6. Migrationsreihenfolge

1. **Parität herstellen.** Die Lücken aus PARITY.md im Core schließen, jede mit Vergleichstest. Im Enterprise AI
   Client wird in dieser Phase nichts entfernt.
2. **Client auf Core-Schnittstellen umstellen.** Der Client wird auf Java 21 gehoben und ruft die Core-Use-Cases
   über seine eigenen Ports auf: Ein Adapter im Client implementiert die bisherigen Ports (etwa
   `KnowledgeSourcePort`, `PageViewSource`, den Index-Port und den Export-Use-Case) über den Core. Beide
   Implementierungen bestehen nebeneinander, umschaltbar je Fähigkeit; die Vergleichstests laufen gegen beide.
3. **Erst dann im Client entfernen.** Eine eigene Implementierung im Client wird erst gelöscht, wenn der Client sie
   im Alltag erfolgreich über den Core nutzt und ihr Vergleichstest grün ist. Jede Entfernung ist ein eigener PR im
   Client mit Verweis auf die Paritätszeile.

## Alternativen

- **Client behält seine Implementierungen, Space Harvester bleibt eigenständiges Exportprogramm:** zwei
  Implementierungen derselben Fähigkeiten, die auseinanderlaufen; abgelehnt.
- **Sofort im Client entfernen und auf den Core umstellen:** verliert heute Fähigkeiten (Word-Export, Bilder in der
  Leseansicht, weitere Quellen); abgelehnt.
- **Client bleibt Java 8 und nutzt den Core über REST:** möglich, aber dann ist der Core keine Bibliothek des Clients,
  sondern ein zweiter Prozess mit eigenem Betrieb; nur als Rückfall.

## Konsequenzen

- PARITY.md wird mit jedem Strang gepflegt; ein PR, der eine Fähigkeit im Bereich dieser Bibliothek ändert, prüft
  die betroffene Zeile.
- Weitere Quellen des Clients (MediaWiki, lokale Dateien, Git, FTP, NDV, Outlook, SharePoint, BetaView) und die
  Text-Extraktion aus Dateien gehören zum Zielumfang des Core, auch wenn die erste Ausbaustufe Confluence ist.
- Die Kopie `enterprise-ai-sources` bleibt bis Schritt 2 unverändert. Hängt der Client selbst vom Core ab, wandern
  die übernommenen Klassen (Confluence-Client, Quellen-Ports) in den Core, und der Client entfernt seine eigenen;
  sonst entstünde eine zyklische Abhängigkeit.
- Lokale Embeddings bleiben nach ADR 0011 außerhalb des Core; der Client hängt seinen lokalen Adapter als eigenen
  `EmbeddingProvider` über `Harvester.builder()` ein (#188). Das ist keine Paritätslücke, sondern ein Einhängepunkt.
