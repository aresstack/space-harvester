package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepositoryContract;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** Der Vertrag aus Strang B, gegen den Lucene-Cache auf einem temporären Verzeichnis. */
class LucenePageCacheContractTest extends SnapshotRepositoryContract {

    @TempDir
    Path base;

    private final List<LucenePageCache> opened = new ArrayList<>();

    @Override
    protected SnapshotRepository emptyRepository() {
        return track(LucenePageCache.open(base.resolve("index-" + opened.size())));
    }

    @Override
    protected SnapshotRepository reopen(SnapshotRepository repository) {
        LucenePageCache cache = (LucenePageCache) repository;
        cache.close();
        return track(LucenePageCache.open(cache.path()));
    }

    private LucenePageCache track(LucenePageCache cache) {
        opened.add(cache);
        return cache;
    }

    @AfterEach
    void closeAll() {
        opened.forEach(LucenePageCache::close);
    }
}
