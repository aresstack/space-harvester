package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.facts.FactEditStore;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.SourceDataPurge;
import com.aresstack.spaceharvester.application.sync.InventoryLog;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.core.SourceDataStores;
import com.aresstack.spaceharvester.persistence.files.FileInventoryLog;
import com.aresstack.spaceharvester.persistence.files.FileResolutionCache;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFillQueue;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * „Daten bereinigen“ ({@link SourceDataPurge}) mit den Ablagen dieses Dienstes, in derselben Reihenfolge wie im
 * Harvester ({@link SourceDataStores}). Ohne Cache-Verzeichnis fehlen Seiten-Cache, Volltext, Anreicherungen und
 * Füllschlange; dann bleibt, was es gibt.
 */
@Configuration(proxyBeanMethods = false)
class SourceDataConfiguration {

    @Bean
    SourceDataPurge sourceDataPurge(Jobs jobs, ConfiguredSourceCatalog catalog,
                                    ObjectProvider<LuceneFillQueue> fillQueue,
                                    ObjectProvider<LucenePageCache> cache,
                                    ObjectProvider<LucenePageTextIndex> texts,
                                    ObjectProvider<LuceneEnrichmentStore> enrichments,
                                    ObjectProvider<AssetStore> assets, ObjectProvider<ExportArtifactWriter> exports,
                                    ObjectProvider<TermRelations> relations,
                                    ObjectProvider<FileResolutionCache> resolutions,
                                    ObjectProvider<FactEditStore> facts, ObjectProvider<InventoryLog> inventory) {
        Clock clock = Clock.systemUTC();
        SourceDataStores stores = SourceDataStores.builder().fillQueue(fillQueue.getIfAvailable());
        cache.ifAvailable(stores::pageCache);
        texts.ifAvailable(stores::fullText);
        enrichments.ifAvailable(stores::enrichments);
        ExportArtifactWriter writer = exports.getIfAvailable();
        if (assets.getIfAvailable() instanceof FileSystemAssetStore files && writer != null) {
            stores.assets(files, writer, clock);
        }
        relations.ifAvailable(found -> stores.termRelations(found, resolutions.getIfAvailable()));
        facts.ifAvailable(found -> stores.factEdits(found, clock));
        if (inventory.getIfAvailable() instanceof FileInventoryLog log) {
            stores.inventoryLog(log);
        }
        return new SourceDataPurge(jobs, stores.build(), sourceId -> catalog.config(sourceId).isPresent());
    }
}
