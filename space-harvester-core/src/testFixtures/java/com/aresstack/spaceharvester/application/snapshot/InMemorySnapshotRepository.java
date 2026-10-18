package com.aresstack.spaceharvester.application.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link SnapshotRepository} im Speicher, als Fake für Tests anderer Stränge und als Referenz des Vertrags. */
public final class InMemorySnapshotRepository implements SnapshotRepository {

    private final Map<PageKey, PageSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<PageKey, PageHeader> headers = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(PageSnapshot snapshot) {
        snapshots.put(snapshot.key(), snapshot);
        headers.put(snapshot.key(), snapshot.header());
    }

    @Override
    public void saveHeader(PageHeader header) {
        headers.put(header.key(), header);
    }

    @Override
    public Optional<PageSnapshot> find(PageKey key) {
        return Optional.ofNullable(snapshots.get(key));
    }

    @Override
    public Optional<PageHeader> findHeader(PageKey key) {
        return Optional.ofNullable(headers.get(key));
    }

    /** Alle gespeicherten Snapshots mit ihren aktuellen Kopfdaten, als Kopie. */
    public synchronized Map<PageSnapshot, PageHeader> snapshotsWithHeaders() {
        Map<PageSnapshot, PageHeader> all = new LinkedHashMap<>();
        snapshots.forEach((key, snapshot) -> all.put(snapshot, headers.get(key)));
        return all;
    }

    @Override
    public synchronized boolean remove(PageKey key) {
        boolean hadSnapshot = snapshots.remove(key) != null;
        boolean hadHeader = headers.remove(key) != null;
        return hadSnapshot || hadHeader;
    }
}
