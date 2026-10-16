package com.aresstack.spaceharvester.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Konfiguration unter {@code space-harvester.cache}: der eingebettete Lucene-Seiten-Cache (#26).
 *
 * @param directory      Indexverzeichnis; ohne Angabe läuft der Dienst ohne Cache
 * @param maxPayloadSize größte zulässige Payload einer Seite; größere Seiten werden abgewiesen, nie gekürzt
 * @param searchTimeout  Zeitgrenze einer Suche (#28); länger laufende Suchen werden abgebrochen
 */
@ConfigurationProperties("space-harvester.cache")
public record CacheProperties(Path directory, @DefaultValue("20MB") DataSize maxPayloadSize,
                              @DefaultValue("5s") Duration searchTimeout) {
}
