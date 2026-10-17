package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Optional;

/** Reicht an einen Cache weiter; Tests überschreiben einzelne Methoden, um Änderungen während des Exports zu zeigen. */
class ForwardingPageCache implements PageCacheReader {

    private final PageCacheReader delegate;

    ForwardingPageCache(PageCacheReader delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<CacheGeneration> currentGeneration(String sourceId, String spaceKey) {
        return delegate.currentGeneration(sourceId, spaceKey);
    }

    @Override
    public List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId,
                                  int limit) {
        return delegate.pages(sourceId, spaceKey, generationId, afterPageId, limit);
    }

    @Override
    public Optional<CachedPage> page(PageKey key) {
        return delegate.page(key);
    }
}
