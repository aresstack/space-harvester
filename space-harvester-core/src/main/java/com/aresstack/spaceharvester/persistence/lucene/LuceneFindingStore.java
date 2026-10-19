package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.acquisition.FindingCataloging;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Der Katalog im Seiten-Cache für das Katalogisieren von Funden (ADR 0016): liest Einträge ohne Payload und legt
 * Kopfdaten nur ab, wo sie fehlen oder eine andere Version nennen ({@link LucenePageCache#tryCatalog}).
 */
public final class LuceneFindingStore implements FindingCataloging.Store {

    private final LucenePageCache cache;

    public LuceneFindingStore(LucenePageCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Optional<FindingCataloging.Entry> entry(PageKey key) {
        return cache.catalogEntry(key);
    }

    @Override
    public FindingCataloging.Outcome catalog(PageHeader header) {
        return cache.tryCatalog(header);
    }
}
