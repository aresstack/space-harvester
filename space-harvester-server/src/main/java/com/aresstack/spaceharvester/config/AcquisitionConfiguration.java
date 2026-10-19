package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.lucene.LuceneCatalogGaps;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFillQueue;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Die Hintergrundfüllung (ADR 0016, Punkt 5), wenn es einen Cache gibt ({@code space-harvester.cache.directory}): Sie
 * lädt in Leerlaufzeit Seiten, die der Katalog kennt, deren Inhalt aber fehlt oder veraltet ist, zuletzt geänderte
 * zuerst, unabhängig von der Betriebsart ({@code sync.mode}). Die Warteschlange liegt unter {@value #FILL_QUEUE}/
 * neben den übrigen Indizes und überdauert einen Neustart. Geladen wird als Aktion des Servers mit den Zugangsdaten der
 * Quelle ({@link QueryConfiguration#BACKGROUND}), nie mit der Anmeldung eines Benutzers.
 *
 * <p>Folgt der wirksamen Konfiguration ohne Neustart ({@code POST /api/v1/configuration}):
 * {@code sync.background-fill} (Standard an), {@code sync.sources.<Quelle>.background-fill} und
 * {@code sync.background-parallelism}. Abschalten zieht übergebene Anfragen zurück, die Warteschlange bleibt.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "space-harvester.cache", name = "directory")
class AcquisitionConfiguration {

    /** Unterverzeichnis der Warteschlange der Hintergrundfüllung. */
    static final String FILL_QUEUE = "fill-queue";

    @Bean(destroyMethod = "close")
    LuceneFillQueue fillQueue(CacheProperties properties) {
        return LuceneFillQueue.open(properties.directory().resolve(FILL_QUEUE));
    }

    @Bean(destroyMethod = "close")
    BackgroundFill backgroundFill(AcquisitionScheduler scheduler, LuceneFillQueue queue, LucenePageCache cache,
                                  SourceCatalog catalog, LiveConfiguration live) {
        BackgroundFill fill = new BackgroundFill(scheduler, queue, new LuceneCatalogGaps(cache),
                QueryConfiguration.BACKGROUND,
                sourceId -> catalog.find(sourceId).isPresent() && live.current().syncFor(sourceId).backgroundFill(),
                BackgroundFill.Timing.DEFAULT, live.current().sync().backgroundFillAnywhere());
        live.listen(change -> {
            if (!change.syncChanged()) {
                return ConfigurationListener.Preparation.NOTHING;
            }
            SyncSettings next = change.next().sync();
            return () -> {
                scheduler.backgroundParallelism(next.backgroundParallelism());
                fill.enabled(next.backgroundFillAnywhere());
            };
        });
        return fill.start();
    }
}
