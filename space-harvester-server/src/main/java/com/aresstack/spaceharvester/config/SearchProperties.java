package com.aresstack.spaceharvester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration unter {@code space-harvester.search}: Suchmodus der Laufzeitkonfiguration (ADR 0015, wie
 * {@link com.aresstack.spaceharvester.application.configuration.SearchSettings}). Die bestehenden Such-Beans lesen
 * diese Werte noch nicht; sie stehen im {@code ConfigurationManager} und unter {@code GET /api/v1/configuration}.
 *
 * @param semantic     semantische Suche erlaubt; Standard aus, und ohne Embedding-Modell bleibt sie ohnehin aus
 * @param rerank       Treffer über das Rerank-Modell umordnen, wenn eines eingestellt ist; Standard an
 * @param prefetchHits Treffer der nativen Quellensuche zuerst in den Cache laden; Standard an
 */
@ConfigurationProperties("space-harvester.search")
public record SearchProperties(@DefaultValue("false") boolean semantic, @DefaultValue("true") boolean rerank,
                               @DefaultValue("true") boolean prefetchHits) {
}
