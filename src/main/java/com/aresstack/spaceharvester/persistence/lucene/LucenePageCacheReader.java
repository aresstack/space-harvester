package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Der lesende Port der Query-API (#27) über dem {@link LucenePageCache}.
 *
 * <p>Generation eines Space ist der zuletzt aufgezeichnete Lauf ({@link SpaceSyncState#generation()}, ID
 * {@code g<n>}): {@link CacheState#CONFIRMED}, wenn er vollständig war, sonst {@link CacheState#INCOMPLETE}. Liegen
 * Seiten eines Space im Cache, ohne dass je ein Lauf aufgezeichnet wurde, ist das die Generation {@code g0} im Zustand
 * {@link CacheState#INCOMPLETE}. {@link CacheState#STALE} legt die Synchronisierung (#16) fest und wird hier nicht
 * abgeleitet.
 *
 * <p>Ein Lauf wird mit einem Lucene-Commit sichtbar; Seitenliste und Generation stammen daher aus demselben Stand,
 * solange zwischen Prüfung und Lesen kein Commit liegt. Wechselt die Generation dabei, gilt die Liste nicht
 * ({@link CacheGenerationChangedException}).
 */
public final class LucenePageCacheReader implements PageCacheReader {

    private final LucenePageCache cache;

    public LucenePageCacheReader(LucenePageCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Optional<CacheGeneration> currentGeneration(String sourceId, String spaceKey) {
        return guarded(() -> generation(sourceId, spaceKey));
    }

    @Override
    public List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId,
                                  int limit) {
        Objects.requireNonNull(generationId, "generationId");
        return guarded(() -> {
            requireGeneration(sourceId, spaceKey, generationId);
            List<ListedPage> pages = cache.spacePages(sourceId, spaceKey, afterPageId, limit).stream()
                    .map(listed -> new ListedPage(listed.header(), listed.rawSha256()))
                    .toList();
            requireGeneration(sourceId, spaceKey, generationId);
            return pages;
        });
    }

    @Override
    public Optional<CachedPage> page(PageKey key) {
        Objects.requireNonNull(key, "key");
        return guarded(() -> {
            Optional<LucenePageCache.StoredPage> stored = cache.stored(key);
            if (stored.isEmpty() || stored.get().snapshot() == null) {
                return Optional.<CachedPage>empty();
            }
            PageHeader header = stored.get().header();
            PageSnapshot snapshot = stored.get().snapshot();
            CacheGeneration generation = generation(key.sourceId(), header.metadata().partitionKey())
                    .orElseGet(() -> new CacheGeneration(id(0), CacheState.INCOMPLETE, null, null));
            return Optional.of(new CachedPage(header, snapshot, generation));
        });
    }

    private Optional<CacheGeneration> generation(String sourceId, String spaceKey) {
        Optional<SpaceSyncState> state = cache.spaceState(sourceId, spaceKey);
        if (state.isPresent()) {
            SpaceSyncState s = state.get();
            return Optional.of(new CacheGeneration(id(s.generation()),
                    s.lastRunComplete() ? CacheState.CONFIRMED : CacheState.INCOMPLETE, s.confirmedAt(),
                    s.lastRunAt()));
        }
        if (cache.countSpacePages(sourceId, spaceKey) > 0) {
            return Optional.of(new CacheGeneration(id(0), CacheState.INCOMPLETE, null, null));
        }
        return Optional.empty();
    }

    private void requireGeneration(String sourceId, String spaceKey, String generationId) {
        String current = generation(sourceId, spaceKey).map(CacheGeneration::id).orElse(null);
        if (!generationId.equals(current)) {
            throw new CacheGenerationChangedException("Die Cache-Generation von " + sourceId + "/" + spaceKey
                    + " hat gewechselt");
        }
    }

    private static String id(long generation) {
        return "g" + generation;
    }

    /** Ein nicht lesbarer oder geschlossener Cache ist für die Query-API ein nicht verfügbarer. */
    private static <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (PageCacheException | IllegalStateException e) {
            throw new CacheUnavailableException("Seitencache nicht lesbar", e);
        }
    }
}
