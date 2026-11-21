package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Der Seiten-Cache, wie ihn das Verzeichnis der Verweise liest ({@link PageLinks.StoredPages}). */
public final class LucenePageLinkPages implements PageLinks.StoredPages {

    private final LucenePageCache cache;

    public LucenePageLinkPages(LucenePageCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Optional<PageHeader> header(PageKey key) {
        return cache.findHeader(key);
    }

    @Override
    public Optional<PageSnapshot> snapshot(PageKey key) {
        return cache.find(key);
    }

    @Override
    public List<PageLinks.StoredPage> pages(String sourceId, String afterPageId, int limit) {
        return cache.sourcePages(sourceId, afterPageId, Math.min(limit, LucenePageCache.MAX_PAGE_SIZE)).stream()
                .map(page -> new PageLinks.StoredPage(page.header(), page.rawSha256())).toList();
    }

    @Override
    public long known(String sourceId) {
        return cache.pageCount(sourceId);
    }
}
