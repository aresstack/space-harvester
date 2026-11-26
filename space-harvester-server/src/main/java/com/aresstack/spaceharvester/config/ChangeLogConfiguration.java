package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.changelog.ChangeLog;
import com.aresstack.spaceharvester.application.changelog.ChangeLogStore;
import com.aresstack.spaceharvester.application.changelog.ManualSyncRounds;
import com.aresstack.spaceharvester.application.changelog.SummaryModel;
import com.aresstack.spaceharvester.application.changelog.SnapshotSummaryMaterial;
import com.aresstack.spaceharvester.application.changelog.TextContentDiffer;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.configuration.ChangeLogSettings;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Änderungsprotokoll (ADR 0030) im Dienst wie in der Bibliothek: Update-Marke je Quelle nach manueller
 * Synchronisierung, Aufräumen nach {@code space-harvester.cache.change-log.retention} beim Start und nach jedem
 * Abgleich, Abfragen und Zusammenfassungen für die REST-API. Ohne Cache gibt es kein Protokoll.
 */
@Configuration(proxyBeanMethods = false)
class ChangeLogConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ChangeLogConfiguration.class);

    @Bean(destroyMethod = "close")
    ChangeLog changeLog(ObjectProvider<LucenePageCache> cache, AccessGuard access, SourceCatalog catalog, Jobs jobs,
                        SummaryModel summaries, ObjectProvider<AiConfiguration.UpdateSummaryPlanner> planner,
                        AiRelease release, PageTextProjector projector) {
        LucenePageCache available = cache.getIfAvailable();
        if (available != null) {
            // CL5: Unterschied der Lesetexte, wenn ein gespeicherter Inhalt ersetzt wird.
            available.contentDiffer(new TextContentDiffer(projector));
            // ADR 0031: Bearbeiter der gespeicherten Fassung in der Protokollzeile.
            available.editors(new com.aresstack.spaceharvester.source.confluence.ConfluenceEditors());
        }
        ChangeLogStore store = available == null ? ChangeLogStore.NONE : available;
        Function<PageKey, Optional<PageHeader>> headers = available == null ? key -> Optional.empty()
                : available::findHeader;
        // ADR 0030, Abschnitt 5: Zusammenfassen nur auf Wunsch, an das Modell nur Seiten mit KI-Freigabe.
        return new ChangeLog(store, access, headers, () -> catalog.all().stream().map(HarvestSource::id).toList(),
                new ChangeLog.Summaries(jobs, summaries, release::mayProcess, Clock.systemUTC(),
                        // Worum es geht (Angelo, 21.11.2026): Lesetext und Space-Namen aus dem Cache.
                        available == null ? null : new SnapshotSummaryMaterial(available::find, projector),
                        // Plan vorab (Angelo 22.11.2026), Zeile „Zusammenfassung – Plan“.
                        planner.getIfAvailable() == null ? null : planner.getIfAvailable().model()))
                // Höchstens ein Update je Tag (Angelo, 21.11.2026), Tage in der Zeitzone des Servers.
                .perDay(java.time.ZoneId.systemDefault()).globalSources(catalog::includeInGlobalSearch);
    }

    @Bean(destroyMethod = "close")
    ManualSyncRounds manualSyncRounds(Jobs jobs, ObjectProvider<LucenePageCache> cache) {
        LucenePageCache available = cache.getIfAvailable();
        return ManualSyncRounds.start(jobs, available == null ? ChangeLogStore.NONE : available, Clock.systemUTC());
    }

    /**
     * Räumt beim Start (im Hintergrund) und nach jedem bestätigten Abgleich auf, nie nach Zeitgeber (ADR 0030,
     * Abschnitt 2).
     */
    @Bean
    SyncCommitted pruneChangeLog(ObjectProvider<LucenePageCache> cache, LiveConfiguration live) {
        Runnable prune = () -> {
            LucenePageCache available = cache.getIfAvailable();
            if (available == null) {
                return;
            }
            try {
                ChangeLogSettings settings = live.current().changeLog();
                (settings != null ? settings : ChangeLogSettings.DEFAULT).cutoff(Clock.systemUTC().instant())
                        .ifPresent(available::pruneChangeLog);
            } catch (RuntimeException e) {
                LOG.warn("Änderungsprotokoll nicht aufgeräumt ({})", e.getClass().getName());
            }
        };
        // Beim Start im Hintergrund: Das Aufräumen wartet auf den Schreiblauf des Cache und hielte sonst den Start auf.
        Thread.ofVirtual().name("change-log-prune").start(prune);
        return run -> prune.run();
    }
}
