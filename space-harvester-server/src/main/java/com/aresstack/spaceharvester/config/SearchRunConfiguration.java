package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.searchrun.InMemorySearchRunLog;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.searchrun.SearchRuns;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSearchRunLog;
import com.aresstack.spaceharvester.security.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Suchlauf-Protokoll (#182): {@code space-harvester.search.run-log.enabled} (Standard {@code true}) legt je Suche
 * (Schlagwortsuche, {@code sort=relevance}, progressive Suche) Anfrage, Quellen, Trefferzahlen je Stufe und Dauer ab,
 * mit Cache-Verzeichnis dauerhaft unter {@code search-runs/}, sonst flüchtig; {@code space-harvester.search.run-log.max-age}
 * (Standard 30 Tage) begrenzt, wie lange. Lesen dürfen ein Lauf nur sein Aufrufer und technische Clients mit
 * {@code manage-configuration}.
 */
@Configuration(proxyBeanMethods = false)
class SearchRunConfiguration {

    /** Unterverzeichnis des Protokolls neben den übrigen Indizes. */
    static final String SEARCH_RUNS = "search-runs";

    @Bean
    SearchRunLog searchRunLog(ObjectProvider<CacheProperties> cache,
                              @Value("${space-harvester.search.run-log.enabled:true}") boolean enabled,
                              @Value("${space-harvester.search.run-log.max-age:30d}") Duration maxAge) {
        if (!enabled) {
            return SearchRunLog.NONE;
        }
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("space-harvester.search.run-log.max-age muss positiv sein");
        }
        CacheProperties properties = cache.getIfAvailable();
        if (properties == null || properties.directory() == null) {
            return new InMemorySearchRunLog(maxAge, Clock.systemUTC());
        }
        return LuceneSearchRunLog.open(properties.directory().resolve(SEARCH_RUNS), maxAge, Clock.systemUTC());
    }

    /** Verwalter wie bei der Laufzeitkonfiguration: nur technische Clients mit {@code manage-configuration}. */
    @Bean
    SearchRuns searchRuns(SearchRunLog log, ObjectProvider<SecurityProperties> properties) {
        SecurityProperties security = properties.getIfAvailable();
        Set<String> administrators = new HashSet<>();
        if (security != null && security.mode() == SecurityProperties.Mode.CLIENTS && security.clients() != null) {
            for (SecurityProperties.Client client : security.clients()) {
                if (client.manageConfiguration() && client.id() != null) {
                    administrators.add(client.id());
                }
            }
        }
        return new SearchRuns(log, caller -> caller.kind() == AccessContext.PrincipalKind.TECHNICAL_CLIENT
                && administrators.contains(caller.principal()));
    }
}
