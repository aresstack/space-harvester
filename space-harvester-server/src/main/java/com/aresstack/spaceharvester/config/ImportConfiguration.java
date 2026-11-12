package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.export.ExportPackageImport;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import com.aresstack.spaceharvester.application.sync.SyncCommitted;
import com.aresstack.spaceharvester.output.export.EnrichmentImportedAssets;
import com.aresstack.spaceharvester.output.export.ZipExportPackageReader;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Import eines JSON-Pakets ({@link ExportPackageImport}) in den Cache dieses Dienstes, wie im Harvester verdrahtet:
 * Generationen im Lucene-Cache, Dateien in der Asset-Ablage, Texte unter der eingestellten Extraktion, Lese-Ansichten
 * in der Anreicherungsablage, danach dieselben Folgeschritte wie nach einem Abgleich. Nur mit Cache-Verzeichnis.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "space-harvester.cache", name = "directory")
class ImportConfiguration {

    @Bean
    ExportPackageImport exportPackageImport(Jobs jobs, ConfiguredSourceCatalog catalog, SyncBaselineStore store,
                                            CacheProperties cache, ObjectProvider<AssetStore> assetStore,
                                            ObjectProvider<Assets> assets,
                                            ObjectProvider<LuceneEnrichmentStore> enrichments,
                                            ObjectProvider<SyncCommitted> afterCommit,
                                            @Value("${space-harvester.assets.max-size:0}") DataSize maxAsset) {
        Assets found = assets.getIfAvailable();
        LuceneEnrichmentStore views = enrichments.getIfAvailable();
        // Dieselbe Grenze wie AssetConfiguration: 0 heißt ohne Grenze, die Dateien gehen als Strom in die Ablage.
        return new ExportPackageImport(jobs, new ZipExportPackageReader(cache.maxPayloadSize().toBytes(),
                Assets.maxBytes(maxAsset.toBytes())), store, sourceId -> catalog.config(sourceId).isPresent(),
                assetStore.getIfAvailable(), found == null ? null : found::adoptText,
                views == null ? null : new ReadingViewStore(views),
                views == null ? null : new EnrichmentImportedAssets(views, Clock.systemUTC()),
                afterCommit.orderedStream().toList(), Clock.systemUTC());
    }
}
