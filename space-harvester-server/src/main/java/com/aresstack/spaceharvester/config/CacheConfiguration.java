package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.enrichment.EnrichingCacheFill;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.jobs.JobStore;
import com.aresstack.spaceharvester.application.query.PageCacheFill;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobEventStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageSearchIndex;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSyncBaselineStore;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Öffnet die eingebetteten Lucene-Indizes, wenn {@code space-harvester.cache.directory} gesetzt ist: den Seiten-Cache
 * unter {@value #PAGES}/, die Auftragsablage unter {@value #JOBS}/, ihre Diagnoseereignisse unter {@value #JOB_EVENTS}/
 * und die Anreicherungen unter
 * {@value #ENRICHMENTS}/. Ein gesperrter, beschädigter oder in einem anderen Schema angelegter Index verhindert den
 * Start. Beim Herunterfahren werden die Indizes geschlossen und die
 * Sperren freigegeben.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "space-harvester.cache", name = "directory")
@EnableConfigurationProperties(CacheProperties.class)
class CacheConfiguration {

    /** Unterverzeichnis des Seiten-Index; weitere Indizes (z. B. Aufträge) liegen daneben. */
    static final String PAGES = "pages";
    /** Unterverzeichnis der Auftragsablage (#12). */
    static final String JOBS = "jobs";
    /** Unterverzeichnis der Diagnoseereignisse der Aufträge. */
    static final String JOB_EVENTS = "job-events";
    /** Unterverzeichnis der Anreicherungen (#29). */
    static final String ENRICHMENTS = "enrichments";
    /** Unterverzeichnis des Volltextindex (#180). */
    static final String TEXT = "text";

    @Bean(destroyMethod = "close")
    LucenePageCache pageCache(CacheProperties properties) {
        long bytes = properties.maxPayloadSize().toBytes();
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("space-harvester.cache.max-payload-size muss zwischen 1 Byte und "
                    + Integer.MAX_VALUE + " Bytes liegen");
        }
        return LucenePageCache.open(properties.directory().resolve(PAGES), (int) bytes);
    }

    /** Lesender Port der Query-API (#27); ohne Cache antwortet sie mit {@code cache-unavailable}. */
    @Bean
    LucenePageCacheReader pageCacheReader(LucenePageCache pageCache) {
        return new LucenePageCacheReader(pageCache);
    }

    /**
     * Nachgeladene Einzelseiten landen samt Anreicherungen im gemeinsamen Cache (ADR 0010); wartet nie auf einen
     * Abgleich.
     */
    @Bean
    PageCacheFill pageCacheFill(LucenePageCache pageCache, ObjectProvider<Enrichments> enrichments,
                                PageTextIndexing texts) {
        PageCacheFill store = PageCacheFill.of(pageCache::state, pageCache::trySave);
        Enrichments available = enrichments.getIfAvailable();
        return available == null ? store : new EnrichingCacheFill(store, available, texts);
    }

    /** Volltextindex über den Cache (#180), abgeleitet und neu aufbaubar. */
    @Bean(destroyMethod = "close")
    LucenePageTextIndex pageTextIndex(CacheProperties properties) {
        return LucenePageTextIndex.open(properties.directory().resolve(TEXT));
    }

    /** Hält den Volltextindex beim Ablegen und nach jedem Abgleich nach. */
    @Bean
    PageTextIndexing pageTextIndexing(LucenePageTextIndex index) {
        return new PageTextIndexing(List.of(new ConfluenceStorageTextProjector(), new LocalFilesTextProjector(),
                new KnowledgeDocumentTextProjector()), index);
    }

    /** Titel- und Metadatensuche (#28) auf demselben Index. */
    @Bean
    LucenePageSearchIndex pageSearchIndex(LucenePageCache pageCache, CacheProperties properties) {
        return new LucenePageSearchIndex(pageCache, properties.searchTimeout());
    }

    /** Bestätigte Generationen der Synchronisierung (#16) im selben Index wie die Seiten. */
    @Bean
    LuceneSyncBaselineStore syncBaselineStore(LucenePageCache pageCache) {
        return new LuceneSyncBaselineStore(pageCache);
    }

    /**
     * Dauerhafte Auftragsablage; ersetzt die flüchtige aus {@code JobsConfiguration}, die nur ohne andere
     * {@link JobStore}-Bean entsteht. Eigener Bean-Name, damit keine Bean-Definition überschrieben wird.
     */
    @Bean(destroyMethod = "close")
    LuceneJobStore luceneJobStore(CacheProperties properties) {
        return LuceneJobStore.open(properties.directory().resolve(JOBS));
    }

    /** Dauerhafte Diagnoseereignisse; ersetzt die flüchtigen aus {@code JobsConfiguration}. */
    @Bean(destroyMethod = "close")
    LuceneJobEventStore luceneJobEventStore(CacheProperties properties) {
        return LuceneJobEventStore.open(properties.directory().resolve(JOB_EVENTS));
    }

    /** Anreicherungen (#29) in einem eigenen Index, getrennt von Payload und Kopfdaten der Seiten. */
    @Bean(destroyMethod = "close")
    LuceneEnrichmentStore enrichmentStore(CacheProperties properties) {
        return LuceneEnrichmentStore.open(properties.directory().resolve(ENRICHMENTS));
    }
}
