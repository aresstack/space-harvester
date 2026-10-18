package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncAlreadyRunningException;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService syncScheduler(SpaceSyncJobs syncJobs, SyncProperties properties) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("sync-scheduler").factory());
        if (properties.interval() == null || properties.spaces().isEmpty()) {
            return scheduler;
        }
        if (!syncJobs.available()) {
            LOG.warn("Hintergrundabgleich konfiguriert, aber Cache-Speicher oder Inhaltsabruf fehlen; er startet nicht");
            return scheduler;
        }
        long millis = properties.interval().toMillis();
        scheduler.scheduleWithFixedDelay(() -> startAll(syncJobs, properties), millis, millis, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    private static void startAll(SpaceSyncJobs syncJobs, SyncProperties properties) {
        for (String space : properties.spaces()) {
            int slash = space.indexOf('/');
            try {
                syncJobs.start(space.substring(0, slash), space.substring(slash + 1), null);
            } catch (SyncAlreadyRunningException e) {
                LOG.debug("Abgleich {} läuft noch; übersprungen", space);
            } catch (JobCapacityExceededException e) {
                LOG.warn("Abgleich {} nicht gestartet: {}", space, e.getMessage());
            } catch (RuntimeException e) {
                LOG.warn("Abgleich {} nicht gestartet ({})", space, e.getClass().getName());
            }
        }
    }
}
