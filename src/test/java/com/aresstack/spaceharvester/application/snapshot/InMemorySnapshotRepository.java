package com.aresstack.spaceharvester.application.snapshot;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link SnapshotRepository} im Speicher, als Fake für Tests anderer Stränge und als Referenz des Vertrags. */
public final class InMemorySnapshotRepository implements SnapshotRepository {

    private final Map<PageKey, PageSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(PageSnapshot snapshot) {
        snapshots.put(snapshot.key(), snapshot);
    }

    @Override
    public Optional<PageSnapshot> find(PageKey key) {
        return Optional.ofNullable(snapshots.get(key));
    }

    @Override
    public boolean remove(PageKey key) {
        return snapshots.remove(key) != null;
    }
}
