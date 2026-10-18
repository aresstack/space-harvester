package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die Synchronisierung (#16). Cache-Speicher ({@link SyncBaselineStore}, Lucene, #26) und Inhaltsabruf
 * ({@link PageContentFetcher}, #8) kommen als Beans aus ihren Strängen; solange einer fehlt, lehnt die Sync-API mit
 * {@code sync-unavailable} ab und der Hintergrundabgleich startet nicht.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SyncProperties.class)
class SyncConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SyncConfiguration.class);

    @Bean
    SpaceSyncJobs spaceSyncJobs(Jobs jobs, SourceQueries queries, ObjectProvider<SyncBaselineStore> store,
                                ObjectProvider<PageContentFetcher> fetcher, ObjectProvider<SyncCommitted> afterCommit,
                                SyncProperties properties) {
        return new SpaceSyncJobs(jobs, queries, store.getIfAvailable(), fetcher.getIfAvailable(), Clock.systemUTC(),
                afterCommit.orderedStream().toList(), properties.withheldRetention());
    }

    /** Für {@code ?refresh=true} (#27); {@link PageFreshness#available()} erst mit angebundenem Inhaltsabruf. */
    @Bean
    PageFreshness pageFreshness(SourceQueries queries, ObjectProvider<PageContentFetcher> fetcher) {
        return new PageFreshness(queries, fetcher.getIfAvailable(), Clock.systemUTC());
    }

    /**
     * Regelmäßiger Abgleich (ADR 0014) der Spaces aus {@code space-harvester.sync.spaces} im Abstand
     * {@code space-harvester.sync.interval}; ohne Abstand oder Spaces, oder solange Cache oder Inhaltsabruf fehlen, ist
     * nichts eingeplant. {@code space-harvester.sync.load-unknown} (Standard {@code true}) hält die Spaces vollständig;
     * {@code false} hält nur aktuell, was der Cache schon kennt.
     */
    @Bean
    Reconciliation reconciliation(SpaceSyncJobs syncJobs, SyncProperties properties) {
        Reconciliation reconciliation = new Reconciliation(syncJobs, null, Clock.systemUTC());
        if (properties.interval() == null || properties.spaces().isEmpty()) {
            return reconciliation;
        }
        if (!syncJobs.available()) {
            LOG.warn("Hintergrundabgleich konfiguriert, aber Cache-Speicher oder Inhaltsabruf fehlen; er startet nicht");
            return reconciliation;
        }
        for (String space : properties.spaces()) {
            int slash = space.indexOf('/');
            reconciliation.schedule(space.substring(0, slash), space.substring(slash + 1), properties.interval(),
                    properties.loadUnknown());
        }
        return reconciliation;
    }
}
