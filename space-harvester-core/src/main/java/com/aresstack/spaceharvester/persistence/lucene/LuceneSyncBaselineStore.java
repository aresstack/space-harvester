package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.StagedGeneration;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import com.aresstack.spaceharvester.application.sync.SyncBaselineStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Generationen der Synchronisierung (#16) im {@link LucenePageCache}: eine {@link StagedGeneration} ist ein
 * {@link LucenePageCache.Batch}, ihr Commit ein einziger Lucene-Commit aus Seitenänderungen und neuer Generation.
 *
 * <p>Ein Lauf hält die Schreibsperre des Caches vom {@link #begin} bis zu Commit oder {@link StagedGeneration#close}:
 * andere Schreibvorgänge (auch {@code save} über das {@code SnapshotRepository}) warten so lange. Gleichzeitige Läufe
 * laufen daher nacheinander; wer den zweiten mit derselben Generationsnummer bestätigen will, bekommt die
 * {@link com.aresstack.spaceharvester.application.sync.SyncConflictException}.
 */
public final class LuceneSyncBaselineStore implements SyncBaselineStore {

    private final LucenePageCache cache;

    public LuceneSyncBaselineStore(LucenePageCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Optional<SyncBaseline> confirmed(String sourceId, String spaceKey) {
        return cache.confirmed(sourceId, spaceKey);
    }

    @Override
    public java.util.List<SpaceRef> confirmedSpaces() {
        return cache.confirmedSpaces();
    }

    @Override
    public Map<String, BaselineEntry> knownPages(String sourceId, String spaceKey) {
        return cache.knownPages(sourceId, spaceKey);
    }

    @Override
    public int catalogedPages(String sourceId, String spaceKey) {
        return cache.countSpacePages(sourceId, spaceKey);
    }

    @Override
    public StagedGeneration begin(String sourceId, String spaceKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        LucenePageCache.Batch batch = cache.begin();
        return new StagedGeneration() {

            @Override
            public void save(PageSnapshot snapshot) {
                batch.save(snapshot);
            }

            @Override
            public void saveHeader(PageHeader header) {
                batch.saveHeader(header);
            }

            @Override
            public void remove(PageKey key) {
                batch.remove(key);
            }

            @Override
            public void withhold(PageKey key) {
                batch.withhold(key);
            }

            @Override
            public void commit(SyncBaseline next) {
                if (!next.sourceId().equals(sourceId) || !next.spaceKey().equals(spaceKey)) {
                    throw new IllegalArgumentException("Generation gehört zu " + next.sourceId() + "/"
                            + next.spaceKey() + ", der Lauf zu " + sourceId + "/" + spaceKey);
                }
                batch.confirm(next);
                batch.commit();
            }

            @Override
            public void close() {
                batch.close();
            }
        };
    }
}
