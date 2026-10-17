package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.jobs.JobEventStore;
import com.aresstack.spaceharvester.application.jobs.JobEventStoreContract;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** Der Ereignisvertrag gegen die Lucene-Ablage auf einem temporären Verzeichnis. */
class LuceneJobEventStoreContractTest extends JobEventStoreContract {

    @TempDir
    Path base;

    private final List<LuceneJobEventStore> opened = new ArrayList<>();

    @Override
    protected JobEventStore newStore() {
        return track(LuceneJobEventStore.open(base.resolve("events-" + opened.size())));
    }

    @Override
    protected JobEventStore reopen(JobEventStore store) {
        LuceneJobEventStore lucene = (LuceneJobEventStore) store;
        lucene.close();
        return track(LuceneJobEventStore.open(lucene.path()));
    }

    private LuceneJobEventStore track(LuceneJobEventStore store) {
        opened.add(store);
        return store;
    }

    @AfterEach
    void closeAll() {
        opened.forEach(LuceneJobEventStore::close);
    }
}
