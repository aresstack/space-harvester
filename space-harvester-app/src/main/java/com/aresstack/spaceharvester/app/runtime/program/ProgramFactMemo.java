package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Search facts derived from stored program analyses, kept between program searches (#1486, P2).
 *
 * <p>Deriving facts means parsing the stored analysis JSON and converting it; that is the expensive part of a program
 * search. An entry is reused only for the same page and the same {@link ProgramAnalysisAccess#identity analysis
 * identity}, so a changed program, a recomputed analysis or another parser version always derives anew. There is no
 * second store and no time-based expiry: one entry per program page, replaced on change and dropped once a completed
 * search of its source no longer sees the page. Whether facts may be shown is decided on every search and before
 * delivery by {@link ProgramAnalysisAccess}, never by this memo.
 */
public final class ProgramFactMemo {

    private record Entry(String identity, Object facts) { }

    private final Map<PageKey, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong derivations = new AtomicLong();

    /** The facts for this page and analysis identity, derived only if not already known. */
    @SuppressWarnings("unchecked")
    public <T> T facts(PageKey page, String identity, Supplier<T> derive) {
        Objects.requireNonNull(identity, "identity");
        Entry known = entries.get(page);
        if (known != null && known.identity().equals(identity)) return (T) known.facts();
        T derived = derive.get();
        derivations.incrementAndGet();
        entries.put(page, new Entry(identity, derived));
        return derived;
    }

    /** After a completed search of a source: forget pages of that source the search no longer saw. */
    public void retain(String sourceId, Collection<PageKey> seen) {
        var keep = java.util.Set.copyOf(seen);
        entries.keySet().removeIf(key -> key.sourceId().equals(sourceId) && !keep.contains(key));
    }

    /** Forget everything of sources that are no longer program sources. */
    public void retainSources(Collection<String> sourceIds) {
        var keep = java.util.Set.copyOf(sourceIds);
        entries.keySet().removeIf(key -> !keep.contains(key.sourceId()));
    }

    /** How often facts were derived; measures reuse in tests. */
    public long derivations() {
        return derivations.get();
    }

    int size() {
        return entries.size();
    }
}
