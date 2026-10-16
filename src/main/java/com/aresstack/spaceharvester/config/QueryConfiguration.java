package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die cachegestützte Query-API (#27). Solange kein {@link PageCacheReader} als Bean existiert (der
 * Lucene-Cache aus #26 liefert ihn), antworten ihre Routen mit {@code cache-unavailable}.
 */
@Configuration(proxyBeanMethods = false)
class QueryConfiguration {

    @Bean
    PageQueries pageQueries(SourceCatalog catalog, ObjectProvider<PageCacheReader> cache, AccessGuard access,
                            @Value("${space-harvester.query.max-tree-nodes:50000}") int maxTreeNodes) {
        return new PageQueries(catalog, cache.getIfAvailable(PageCacheReader::unavailable), access, maxTreeNodes);
    }
}
