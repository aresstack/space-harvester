package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.lucene.LuceneLinkIndex;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageLinkPages;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageLinks;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verzeichnis der Verweise (Paket V1) im Dienst wie im {@code Harvester}: Verweise in beide Richtungen aus den
 * gespeicherten Seiten, nur mit Cache. Braucht Rechte und Quellen und steht darum neben {@link CacheConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "space-harvester.cache", name = "directory")
class LinksConfiguration {

    /**
     * Hört auf jeden Commit des Cache (ADR 0027) und gleicht beim Start im Hintergrund ab. Spring schließt es vor Index
     * und Cache, die es braucht; {@link PageLinks#close()} wartet auf eine laufende Erfassung.
     */
    @Bean(destroyMethod = "close")
    PageLinks pageLinks(LucenePageCache pageCache, LuceneLinkIndex linkIndex, AccessGuard access,
                        SourceCatalog catalog) {
        PageLinks links = new PageLinks(new LucenePageLinkPages(pageCache), linkIndex,
                List.of(new ConfluenceStorageLinks()), access);
        // Bisher hört niemand sonst im Dienst auf den Cache; wer dazukommt, bündelt mit HoldingsChanges.all.
        pageCache.changes(links);
        links.reconcileOnStart(catalog.all().stream().map(HarvestSource::id).toList());
        return links;
    }
}
