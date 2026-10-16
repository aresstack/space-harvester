package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.sync.SyncBaselineStoreContract;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** Der Generationen-Vertrag der Synchronisierung (#16) gegen den Lucene-Cache, mit echtem Neustart. */
class LuceneSyncBaselineStoreContractTest extends SyncBaselineStoreContract {

    @TempDir
    Path directory;

    private final List<LucenePageCache> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(LucenePageCache::close);
    }

    @Override
    protected Fixture empty() {
        return fixture(LucenePageCache.open(directory));
    }

    @Override
    protected Fixture reopen(Fixture fixture) {
        ((LucenePageCache) fixture.snapshots()).close();
        return fixture(LucenePageCache.open(directory));
    }

    private Fixture fixture(LucenePageCache cache) {
        opened.add(cache);
        return new Fixture(new LuceneSyncBaselineStore(cache), cache);
    }
}
