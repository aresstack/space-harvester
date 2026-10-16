package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.JobStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Öffnet die eingebetteten Lucene-Indizes, wenn {@code space-harvester.cache.directory} gesetzt ist: den Seiten-Cache
 * unter {@value #PAGES}/ und die Auftragsablage unter {@value #JOBS}/. Ein gesperrter, beschädigter oder in einem
 * anderen Schema angelegter Index verhindert den Start. Beim Herunterfahren werden die Indizes geschlossen und die
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
     * Dauerhafte Auftragsablage; ersetzt die flüchtige aus {@code JobsConfiguration}, die nur ohne andere
     * {@link JobStore}-Bean entsteht. Eigener Bean-Name, damit keine Bean-Definition überschrieben wird.
     */
    @Bean(destroyMethod = "close")
    LuceneJobStore luceneJobStore(CacheProperties properties) {
        return LuceneJobStore.open(properties.directory().resolve(JOBS));
    }
}
