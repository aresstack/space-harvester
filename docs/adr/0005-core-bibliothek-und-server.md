# ADR 0005: Core-Bibliothek und Spring-Boot-Server als getrennte Gradle-Module

- Status: **Angenommen** (Strang K, 17.10.2026; Auftrag und Zielbild von Angelo in #100, Java 21 von Angelo am
  17.10. bestätigt).
- Details der Zuordnung: [docs/MODULES.md](../MODULES.md).

## Kontext

Space Harvester war ein einziges Spring-Boot-Projekt. Angelo will ihn zugleich als REST-Dienst und als Bibliothek
nutzen, etwa in einer Desktop-App (#103), ohne Controller, Main und Spring-Kontext mitzuschleppen, und die
Bibliothek später auf Maven Central veröffentlichen. Die Paketstruktur trennte Fachlogik (`application`) und Adapter
(`source`, `persistence`, `output`, `embedding`) bereits von Spring (`api`, `config`, `security`); ArchUnit hielt das
fest.

## Entscheidung

1. **Zwei Module plus Kopie.** `:space-harvester-core` (`java-library`) enthält `application`, `source`,
   `persistence`, `output`, `embedding` und den Einstieg `core.Harvester`. `:space-harvester-server` (Spring Boot)
   enthält `SpaceHarvesterApplication`, `api`, `config`, `security`, Ressourcen und UI. Abhängigkeit nur
   Server → Core → `:enterprise-ai-sources`. Die Wurzel ist Aggregator.
2. **Core ohne Spring Boot.** Keine Controller, keine Boot-Main, kein ApplicationContext, kein Servlet, kein Spring
   Security (`CoreModuleTest`). Einzige Spring-Abhängigkeit ist Spring WebClient als HTTP-Client im asynchronen
   Confluence-Transport (ADR 0001); der synchrone Rückfall braucht sie nicht.
3. **Quellenadapter und Aufträge bleiben im Core.** Ohne Adapter könnte die Bibliothek nichts ernten; `Jobs` mit
   `JobContext` ist die frameworkneutrale Fortschritts- und Abbruchschnittstelle. Der Server übernimmt REST-Annahme,
   Eigentümerprüfung, Konfiguration und Hintergrundplanung.
4. **Ein Einstieg ohne zweite Implementierung.** `Harvester` baut dieselben Anwendungsfälle wie `config` (Quellen,
   Lucene-Cache, Abgleich, Seiten, Suche, Export) und gibt sie heraus; der Server verdrahtet sie weiter einzeln über
   Spring.
5. **Java 21** für alle Module. Ein Java-8-Core hätte Cache, Suche, Transport und Domänenmodell neu gebraucht
   (Befund in docs/MODULES.md, Abschnitt 5); Java-8-Anwendungen binden über REST an.
6. **Veröffentlichung vorbereitet.** `maven-publish` für `com.aresstack:space-harvester-core` und, weil der Core sie
   braucht, `com.aresstack:space-harvester-enterprise-ai-sources`; POM mit Lizenz, SCM, Entwicklern, Sources und
   Javadoc; `verifyPublishedPom` lehnt Spring Boot, Servlet, Spring Security und unveröffentlichte Projekte in der
   Core-POM ab. Versionen der früher von der Boot-BOM verwalteten Bibliotheken stehen im Versionskatalog. Keine
   Signatur, kein Upload.

## Alternativen

- **Ein Modul behalten und das Jar als Bibliothek einbinden:** zieht Spring Boot, Tomcat und Security in jede
  einbettende Anwendung; abgelehnt.
- **Drittes Modul `space-harvester-connector-confluence`:** sauberer für weitere Quellen, aber heute ohne Nutzen, weil
  alle Adapter auf derselben Upstream-Kopie aufsetzen; spätere Ausbaustufe.
- **Spring-freier Transport:** WebClient durch Reactor Netty direkt ersetzen; würde den abgenommenen Transport neu
  schreiben, deshalb vertagt.
- **Kopie in das Core-JAR einbetten statt eigener Publikation:** versteckt Abhängigkeiten (gson, jsoup, jgraphx) und
  Klassen fremder Pakete im Core-JAR; abgelehnt.

## Konsequenzen

- REST-Routen, Konfigurationsschlüssel, Profile, Hashes, Konverter-Versionen und Indexformat bleiben gleich. Das
  ausführbare JAR heißt weiter `space-harvester-<version>.jar`, liegt aber unter `space-harvester-server/build/libs/`.
- Tests leben bei ihrem Modul; gemeinsame Fakes (`InMemoryPageCache`, `InMemorySearchIndex`, `InMemorySyncStore`,
  `InMemorySnapshotRepository`, `TestCertificates`) sind Testfixtures des Core. `LayeringTest` im Server prüft die
  Schichtregeln über beide Module, `CoreModuleTest` die Modulgrenze.
- Server-Tests, `bootRun` und `demoConfluence` laufen in der Repository-Wurzel, weil `config/` und `docs/E2E.md`
  dort relativ gelesen werden.
