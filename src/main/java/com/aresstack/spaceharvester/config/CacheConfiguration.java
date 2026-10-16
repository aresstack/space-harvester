package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Öffnet den Seiten-Cache, wenn {@code space-harvester.cache.directory} gesetzt ist. Ein gesperrter, beschädigter
 * oder in einem anderen Schema angelegter Index verhindert den Start. Beim Herunterfahren wird der Index geschlossen
 * und die Sperre freigegeben.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "space-harvester.cache", name = "directory")
@EnableConfigurationProperties(CacheProperties.class)
class CacheConfiguration {

    @Bean(destroyMethod = "close")
    LucenePageCache pageCache(CacheProperties properties) {
        long bytes = properties.maxPayloadSize().toBytes();
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("space-harvester.cache.max-payload-size muss zwischen 1 Byte und "
                    + Integer.MAX_VALUE + " Bytes liegen");
        }
        return LucenePageCache.open(properties.directory(), (int) bytes);
    }
}
