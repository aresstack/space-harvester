package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.JobStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageSearchIndex;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSyncBaselineStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Öffnet die eingebetteten Lucene-Indizes, wenn {@code space-harvester.cache.directory} gesetzt ist: den Seiten-Cache
 * unter {@value #PAGES}/, die Auftragsablage unter {@value #JOBS}/ und die Anreicherungen unter
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
    /** Unterverzeichnis der Anreicherungen (#29). */
    static final String ENRICHMENTS = "enrichments";

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

    /** Anreicherungen (#29) in einem eigenen Index, getrennt von Payload und Kopfdaten der Seiten. */
    @Bean(destroyMethod = "close")
    LuceneEnrichmentStore enrichmentStore(CacheProperties properties) {
        return LuceneEnrichmentStore.open(properties.directory().resolve(ENRICHMENTS));
    }
}
