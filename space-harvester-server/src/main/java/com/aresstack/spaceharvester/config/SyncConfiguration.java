package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.ChangeEvents;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncBeforeSearch;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import java.time.Clock;
import java.util.List;
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
@EnableConfigurationProperties({SyncProperties.class, WebhookProperties.class})
class SyncConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SyncConfiguration.class);

    /** Die Frist für gesperrte Seiten gilt je Quelle und folgt der Konfiguration zur Laufzeit beim nächsten Lauf. */
    @Bean
    SpaceSyncJobs spaceSyncJobs(Jobs jobs, SourceQueries queries, ObjectProvider<SyncBaselineStore> store,
                                ObjectProvider<PageContentFetcher> fetcher, ObjectProvider<SyncCommitted> afterCommit,
                                LiveConfiguration live) {
        return new SpaceSyncJobs(jobs, queries, store.getIfAvailable(), fetcher.getIfAvailable(), Clock.systemUTC(),
                afterCommit.orderedStream().toList(), live::withheldRetentionFor);
    }

    /**
     * Änderungsquelle Webhooks (ADR 0014): nur für Quellen mit {@code sync.events} (global oder je Quelle, zur Laufzeit
     * änderbar) und einem Geheimnis unter {@code space-harvester.webhooks.secrets.<Quelle>}; markiert nur den Katalog.
     */
    @Bean
    ChangeEvents changeEvents(SpaceSyncJobs syncJobs, SourceQueries queries, LiveConfiguration live,
                              WebhookProperties webhooks) {
        return new ChangeEvents(syncJobs, queries, sourceId -> live.current().syncFor(sourceId).events(),
                webhooks::secret);
    }

    /** Für {@code ?refresh=true} (#27); {@link PageFreshness#available()} erst mit angebundenem Inhaltsabruf. */
    @Bean
    PageFreshness pageFreshness(SourceQueries queries, ObjectProvider<PageContentFetcher> fetcher) {
        return new PageFreshness(queries, fetcher.getIfAvailable(), Clock.systemUTC());
    }

    /**
     * Regelmäßiger Abgleich (ADR 0014) der Spaces aus {@code space-harvester.sync.spaces} im Abstand
     * {@code space-harvester.sync.interval} bzw. dem ihrer Quelle ({@code sync.sources.<Quelle>.interval}); ohne Abstand
     * oder Spaces, oder solange Cache oder Inhaltsabruf fehlen, ist nichts eingeplant. {@code space-harvester.sync.mode}
     * (Standard {@code catalog-only}, je Quelle überschreibbar) pflegt nur den Katalog; {@code sync-cache} lädt
     * zusätzlich gecachte Seiten mit neuer Version neu, {@code full} hält die Spaces vollständig. Ändert die
     * Konfiguration Spaces, Abstand oder Betriebsart, ersetzt der Server den Zeitplan; eine unbekannte Quelle oder ein
     * Abstand unter einer Minute lehnt die Änderung ab.
     */
    @Bean
    Reconciliation reconciliation(SpaceSyncJobs syncJobs, SourceCatalog catalog, LiveConfiguration live) {
        Reconciliation reconciliation = new Reconciliation(syncJobs, null, Clock.systemUTC());
        if (!syncJobs.available()) {
            if (!ConfiguredSchedules.plan(live.current(), catalog, false).isEmpty()) {
                LOG.warn("Hintergrundabgleich konfiguriert, aber Cache-Speicher oder Inhaltsabruf fehlen; "
                        + "er startet nicht");
            }
            return reconciliation;
        }
        ConfiguredSchedules schedules = new ConfiguredSchedules(reconciliation);
        schedules.replace(ConfiguredSchedules.plan(live.current(), catalog, false));
        live.listen(change -> {
            if (!change.syncChanged()) {
                return ConfigurationListener.Preparation.NOTHING;
            }
            List<ConfiguredSchedules.Planned> planned = ConfiguredSchedules.plan(change.next(), catalog, true);
            return () -> schedules.replace(planned);
        });
        return reconciliation;
    }

    /**
     * Regelmäßige Bestandsinventur (ADR 0014 Punkt 1) der Spaces aus {@code space-harvester.sync.spaces} im Abstand
     * {@code space-harvester.sync.inventory-interval} bzw. dem ihrer Quelle; ohne Abstand keine. Anders als das
     * Nachholen listet sie die Metadaten des ganzen Space und findet so auch Löschungen und Verschiebungen. Ein
     * Konfigurationswechsel ersetzt den Zeitplan wie beim Nachholen.
     */
    @Bean(destroyMethod = "close")
    InventorySchedule inventorySchedule(SpaceSyncJobs syncJobs, SourceCatalog catalog, LiveConfiguration live) {
        Reconciliation inventories = Reconciliation.inventories(syncJobs, null, Clock.systemUTC());
        if (!syncJobs.available()) {
            if (!ConfiguredSchedules.planInventories(live.current(), catalog, false).isEmpty()) {
                LOG.warn("Bestandsinventur konfiguriert, aber Cache-Speicher oder Inhaltsabruf fehlen; "
                        + "sie startet nicht");
            }
            return new InventorySchedule(inventories);
        }
        ConfiguredSchedules schedules = new ConfiguredSchedules(inventories);
        schedules.replace(ConfiguredSchedules.planInventories(live.current(), catalog, false));
        live.listen(change -> {
            if (!change.syncChanged()) {
                return ConfigurationListener.Preparation.NOTHING;
            }
            List<ConfiguredSchedules.Planned> planned = ConfiguredSchedules.planInventories(change.next(), catalog,
                    true);
            return () -> schedules.replace(planned);
        });
        return new InventorySchedule(inventories);
    }

    /** Die eingeplanten Bestandsinventuren; eigener Typ, damit {@link Reconciliation} eindeutig bleibt. */
    record InventorySchedule(Reconciliation inventories) implements AutoCloseable {

        @Override
        public void close() {
            inventories.close();
        }
    }

    /**
     * Holt vor jeder Suche die Änderungen ihrer Quellen nach, wo {@code space-harvester.sync.before-search} (global oder
     * je Quelle) es verlangt; die Regel folgt der wirksamen Konfiguration (ADR 0015).
     */
    @Bean
    SyncBeforeSearch syncBeforeSearch(SpaceSyncJobs syncJobs, LiveConfiguration live) {
        return new SyncBeforeSearch(syncJobs, live::current, null, java.time.Clock.systemUTC());
    }

    /**
     * Holt beim Start die verpassten Änderungen aller Spaces mit bestätigter Generation nach (ADR 0014,
     * {@code space-harvester.sync.catch-up-on-start}, Standard aus), je Quelle mit ihrer Regel und Betriebsart.
     */
    @Bean
    org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent>
            catchUpOnStart(SpaceSyncJobs syncJobs, LiveConfiguration live) {
        return event -> {
            if (!syncJobs.available()) {
                return;
            }
            HarvesterConfiguration configuration = live.current();
            try {
                int started = syncJobs.catchUpAll(null, sourceId -> {
                    SyncSettings rules = configuration.syncFor(sourceId);
                    return rules.catchUpOnStart() ? rules.mode() : null;
                }).size();
                if (started > 0) {
                    LOG.info("Nachholen beim Start: {} Spaces", started);
                }
            } catch (RuntimeException e) {
                // Das Nachholen ist ein Sicherheitsnetz; es darf den Start nicht verhindern.
                LOG.warn("Nachholen beim Start nicht möglich ({})", e.getClass().getName());
            }
        };
    }
}
