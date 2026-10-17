package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.InMemorySnapshotRepository;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fake für {@link SyncBaselineStore}: merkt Schreibvorgänge vor und wendet sie erst beim Commit zusammen mit der
 * Generation auf das {@link SnapshotRepository} an. Die Lucene-Umsetzung (#26) muss dieselben Regeln erfüllen.
 */
public final class InMemorySyncStore implements SyncBaselineStore {

    final SnapshotRepository repository;
    private final Map<String, SyncBaseline> baselines = new HashMap<>();
    /** Lässt den nächsten Commit mit einem Konflikt scheitern, als hätte ein paralleler Lauf gewonnen. */
    boolean failNextCommit;

    public InMemorySyncStore() {
        this(new InMemorySnapshotRepository());
    }

    public InMemorySyncStore(SnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    public synchronized Optional<SyncBaseline> confirmed(String sourceId, String spaceKey) {
        return Optional.ofNullable(baselines.get(sourceId + "/" + spaceKey));
    }

    @Override
    public StagedGeneration begin(String sourceId, String spaceKey) {
        return new StagedGeneration() {

            private final List<Consumer<SnapshotRepository>> pending = new ArrayList<>();
            private boolean open = true;

            @Override
            public void save(PageSnapshot snapshot) {
                stage(repository -> repository.save(snapshot));
            }

            @Override
            public void saveHeader(PageHeader header) {
                stage(repository -> repository.saveHeader(header));
            }

            @Override
            public void remove(PageKey key) {
                stage(repository -> repository.remove(key));
            }

            @Override
            public void commit(SyncBaseline next) {
                if (!open) {
                    throw new IllegalStateException("bereits abgeschlossen");
                }
                synchronized (InMemorySyncStore.this) {
                    long current = confirmed(next.sourceId(), next.spaceKey()).map(SyncBaseline::generation)
                            .orElse(0L);
                    if (failNextCommit || next.generation() != current + 1) {
                        failNextCommit = false;
                        throw new SyncConflictException("Generation " + next.generation() + " folgt nicht auf "
                                + current);
                    }
                    pending.forEach(write -> write.accept(repository));
                    baselines.put(next.sourceId() + "/" + next.spaceKey(), next);
                }
                open = false;
            }

            @Override
            public void close() {
                open = false;
                pending.clear();
            }

            private void stage(Consumer<SnapshotRepository> write) {
                if (!open) {
                    throw new IllegalStateException("bereits abgeschlossen");
                }
                pending.add(write);
            }
        };
    }
}
