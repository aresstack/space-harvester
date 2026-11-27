# AskAI Web Search Providers

Java-8-kompatibles, asynchrones Integrationsmodul für drei Websuchanbieter:

- Brave Search API
- Bright Data SERP API
- DataForSEO SERP API

Das Modul verwendet **AsyncHttpClient 2.x** für nichtblockierendes HTTP und **Gson** für die direkte Abbildung der JSON-Konfigurationen auf anbieterspezifische Config-DTOs.

## Architektur

```text
WebSearchProvider
├── BraveSearchProvider
├── BrightDataSearchProvider
└── DataForSeoSearchProvider

Provider-Adapter
    → Request Mapper
    → AsyncJsonHttpClient
    → Authentication Strategy
    → Response Mapper
    → neutrales WebSearchResult
```

Verwendete Patterns:

- **Adapter:** vereinheitlicht die drei unterschiedlichen REST-Verträge.
- **Strategy:** kapselt Header-, Bearer- und Basic-Authentifizierung.
- **Configuration Object:** bündelt sämtliche anbieterspezifischen Parameter.
- **Factory:** baut Provider aus validierten Konfigurationen.
- **Registry:** stellt aktivierte Provider anhand ihrer ID bereit.
- **Composite:** führt mehrere Provider parallel aus und dedupliziert URLs.

Die fachliche API kennt weder Gson noch AsyncHttpClient:

```java
CompletableFuture<WebSearchResult> result =
        provider.search(
                WebSearchRequest.builder("medizinische Wearables")
                        .countryCode("DE")
                        .languageCode("de")
                        .maximumResults(20)
                        .build());
```

## Gradle

```groovy
dependencies {
    api 'com.google.code.gson:gson:2.13.1'
    implementation 'org.asynchttpclient:async-http-client:2.16.0'
}
```

Das Modul selbst ist auf Java 8 (`--release 8`) begrenzt.

## Konfigurationsdateien

Beim ersten Aufruf von `WebSearchProvidersModule.openUserHome()` werden drei vollständige JSON-Dateien angelegt:

```text
${user.home}/agents/research/providers/
├── brave.json
├── brightdata.json
├── dataforseo.json
└── .provider-secrets.key
```

Die Beispieldateien unter `examples/providers/` zeigen alle verfügbaren Felder.

### Brave

`BraveSearchConfiguration` enthält unter anderem:

- Endpoint und optionale API-Version
- Land, Suchsprache und UI-Sprache
- Ergebnisanzahl und Offset
- Safe Search und Freshness
- Result-Filter, Goggles und zusätzliche Snippets
- Operatoren, Einheiten und Rich Callback
- optionale Standortheader
- HTTP-Transportparameter

`query`, `countryCode`, `languageCode`, `maximumResults` und `offset` aus `WebSearchRequest` überschreiben die entsprechenden Defaults für den jeweiligen Aufruf.

### Bright Data

`BrightDataSearchConfiguration` enthält:

- synchronen und asynchronen Endpoint
- Zone und optionale Customer-ID
- API-Ausführungsmodus
- Suchmaschine und optionalen Suchmaschinen-Endpoint
- Land, Sprache, Trefferzahl und Startoffset
- Safe Search
- Bright-Data-Ausgabeformat, Request-Methode und Datentransformation
- Polling-Intervall und maximale Polling-Versuche
- zusätzliche Suchmaschinenparameter
- HTTP-Transportparameter

Alle HTTP-Aufrufe sind durch AsyncHttpClient asynchron. `executionMode=ASYNCHRONOUS` aktiviert zusätzlich Bright Datas serverseitige Job-API mit Polling. Der mitgelieferte Job-Mapper unterstützt diese Variante zunächst für Google. Der synchrone `/request`-Endpoint unterstützt alle im Enum aufgeführten Suchmaschinen.

### DataForSEO

`DataForSeoSearchConfiguration` bildet die Parameter des Live-Organic-Endpunkts ab:

- Suchmaschine und Ausgabeformat (`advanced`, `regular`, `html`)
- Location Code, Location Name oder GPS-Koordinate
- Language Code oder Language Name
- Tiefe, Gerät und Betriebssystem
- asynchron nachgeladene AI Overview
- Tag
- Stop-Crawl-Ziele, Match-Modus und SERP-Elementfilter
- maximale Crawl-Seiten
- zusätzliche Search-Parameter
- zu entfernende URL-Parameter
- People-Also-Ask-Klicktiefe
- Gruppierung organischer Ergebnisse
- Rechteck-/Pixelberechnung samt Bildschirmparametern
- direkte Such-URL und Search-Engine-Domain
- Target-Filter
- HTTP-Transportparameter

Der Suchbegriff kommt immer aus `WebSearchRequest`. Die gewünschte maximale Trefferzahl begrenzt `depth`. Der DataForSEO-Standort bleibt absichtlich eine Providerkonfiguration, weil ein neutraler ISO-Ländercode nicht eindeutig auf einen DataForSEO-Location-Code abgebildet werden kann.

## Zugangsdaten speichern

Die Config-DTOs besitzen ausschließlich `EncryptedSecret`-Felder. Es existiert kein Klartextfeld für API Keys oder Passwörter.

```java
ProviderConfigurationService configurations =
        module.getConfigurationService();

BraveSearchConfiguration brave =
        configurations.loadOrCreateBrave();

brave.setEnabled(true);
configurations.saveBrave(
        brave,
        "brave-api-key".toCharArray());
```

Entsprechend:

```java
configurations.saveBrightData(
        brightDataConfiguration,
        "bright-data-api-key".toCharArray());

configurations.saveDataForSeo(
        dataForSeoConfiguration,
        "dataforseo-password".toCharArray());
```

Die übergebenen `char[]` werden nach der Verschlüsselung gelöscht. Nach dem Speichern kann `module.reloadProviders()` aufgerufen werden, um die aktiven Provider kontrolliert neu aufzubauen.

### Verschlüsselungsmodell

- AES/GCM/NoPadding
- zufälliger 96-Bit-IV pro Secret
- 128-Bit-AES-Key für universelle Java-8-Kompatibilität
- atomar geschriebene Key- und JSON-Dateien
- Owner-only-Dateirechte, soweit das Dateisystem dies unterstützt

Die lokale Schlüsseldatei liegt zunächst neben den Konfigurationen. Dadurch stehen Secrets nicht im Klartext, das Verfahren schützt aber **nicht** vor einem Angreifer, der unter demselben Betriebssystemkonto sowohl JSON-Dateien als auch Schlüsseldatei lesen kann. Der spätere KeePass-Adapter ersetzt lediglich `SecretKeyProvider` beziehungsweise die Secret-Auflösung; die Provider und Config-DTOs bleiben unverändert.

## Modul öffnen

```java
WebSearchProvidersModule module =
        WebSearchProvidersModule.openUserHome();

try {
    WebSearchProvider brave =
            module.getProviderRegistry()
                    .require(SearchProviderId.BRAVE);

    CompletableFuture<WebSearchResult> future =
            brave.search(
                    WebSearchRequest.builder("Wearables Forschung Deutschland")
                            .countryCode("DE")
                            .languageCode("de")
                            .maximumResults(20)
                            .build());

    future.thenAccept(result -> {
        // Process the normalized search hits.
    });
} finally {
    module.close();
}
```

In Swing darf der Completion-Thread keine Komponenten direkt ändern. UI-Aktualisierungen müssen über `SwingUtilities.invokeLater(...)` auf den EDT übergeben werden.

## Mehrere Provider parallel durchsuchen

```java
CompositeWebSearchProvider composite =
        new CompositeWebSearchProvider(
                module.getProviderRegistry().getAll(),
                true);

CompletableFuture<WebSearchResult> combined =
        composite.search(request);
```

`toleratePartialFailure=true` liefert Ergebnisse der erfolgreichen Provider, falls ein anderer Provider ausfällt. URLs werden nach Scheme, Host, Port und Pfad dedupliziert. Provider-spezifische Ränge bleiben an den einzelnen Treffern erhalten; ein semantischer Reranker gehört bewusst hinter das Composite. Der normale Konstruktor übernimmt nicht den Lifecycle der Provider, weil sie typischerweise der Registry gehören. Für eigenständig erzeugte Provider steht `CompositeWebSearchProvider.owning(...)` bereit.

## Integration in AskAI

Empfohlene Modulgrenze:

```text
:web-search-providers
    → enthält API, Adapter, Config, HTTP und Secret-Verschlüsselung

:research-agent-runtime
    → hängt nur vom neutralen WebSearchProvider-Port ab
```

Bei Übernahme als Unterprojekt:

```groovy
include ':web-search-providers'
```

```groovy
dependencies {
    implementation project(':web-search-providers')
}
```

Die Provider-Konfigurationen sollten in AskAI über eine eigene Einstellungs-UI bearbeitet und anschließend über `ProviderConfigurationService` gespeichert werden. Die UI darf Secrets nie durch einen normalen Getter zurücklesen oder anzeigen.

## Tests

Die Tests decken ab:

- AES-GCM-Roundtrip
- kein Klartext-Secret in der JSON-Datei
- Brave-URL-Mapping
- Bright-Data-Ziel-URL-Mapping
- DataForSEO-Request- und Response-Mapping

Es werden keine kostenpflichtigen Live-API-Aufrufe ausgeführt.

## Offizielle API-Dokumentation

- Brave Web Search: `https://api-dashboard.search.brave.com/app/documentation/web-search/get-started`
- Bright Data SERP API: `https://docs.brightdata.com/api-reference/rest-api/serp/serp-api`
- DataForSEO Live Advanced: `https://docs.dataforseo.com/v3/serp-se-type-live-advanced/`
