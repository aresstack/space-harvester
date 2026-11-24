package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.holdings.HoldingsChanges;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.lang.ref.SoftReference;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Die Verwalter der Begriffe (Angelo 24.11.2026): Jeder Knoten kennt die Begriffe seines Bereichs, ein Elternknoten
 * vereinigt nur seine direkten Kinder. Ein Space hält die Menge seiner Begriffe, eine Quelle je Begriff die Zahl
 * ihrer Spaces, in denen er vorkommt, „alle Quellen“ je Begriff die Zahl der Quellen. So stimmt die Zahl der
 * verschiedenen Begriffe auf jeder Ebene, ohne dass eine Abfrage über die Einträge läuft.
 *
 * <p>Ändert sich eine Seite, wird nur vermerkt, welcher Space veraltet ist: Der Begriffsindex meldet neue und
 * entfernte Einträge ({@link TermIndex#subscribe}), der Seiten-Cache jede geschriebene, verschobene oder entfernte
 * Seite ({@link HoldingsChanges}, etwa nach einem Sync). Die nächste
 * Abfrage liest genau diese Spaces neu und reicht nur die Unterschiede nach oben. Gezählt wird, was im Cache liegt
 * und auf dem Stand der Seite ist, ohne Rechte eines Aufrufers: Wer fragt, muss die ganze Quelle lesen dürfen
 * ({@link TermQueries#totals}).
 *
 * <p>Die Verwalter hängen an einer {@link SoftReference}: Wird der Speicher knapp, gibt die Laufzeit sie frei, und
 * die nächste Abfrage baut sie neu auf. Nur Begriffe aus Seiten ({@link TermOrigin#PAGES}).
 */
public final class TermLedger implements HoldingsChanges {

    /** Ab so vielen geänderten Seiten einer Quelle wird die Quelle ganz neu gelesen statt Space für Space. */
    static final int MAX_DIRTY_PAGES = 5_000;

    private final TermIndex index;
    private final String processorVersion;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Supplier<Function<TermIndex.TermKey, Optional<PageHeader>>> currency;
    private final boolean notified;

    private final Object marks = new Object();
    /** Unter {@link #marks}: Quellen, die ganz neu zu lesen sind. */
    private final Set<String> staleSources = new HashSet<>();
    /** Unter {@link #marks}: geänderte Seiten je Quelle. */
    private final Map<String, Set<String>> stalePages = new HashMap<>();
    /** Unter {@link #marks}: Spaces je Quelle, die als Ganzes neu zu lesen sind. */
    private final Map<String, Set<String>> staleSpaces = new HashMap<>();
    /** Unter {@link #marks}: alles verwerfen. */
    private boolean staleAll;

    private SoftReference<State> state = new SoftReference<>(null);

    /**
     * @param headers  Kopfdaten einer Seite im Cache
     * @param currency je Neuaufbau eine Prüfung, ob ein Eintrag auf dem Stand seiner Seite ist; liefert dann deren
     *                 Kopfdaten
     */
    public TermLedger(TermIndex index, String processorVersion, Function<PageKey, Optional<PageHeader>> headers,
                      Supplier<Function<TermIndex.TermKey, Optional<PageHeader>>> currency) {
        this.index = Objects.requireNonNull(index, "index");
        this.processorVersion = Objects.requireNonNull(processorVersion, "processorVersion");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.notified = index.subscribe(this::changed) != null;
    }

    private void changed(TermIndex.Change change) {
        if (change.pageId() == null) {
            sourceChanged(change.sourceId());
        } else {
            pagesChanged(change.sourceId(), null, List.of(change.pageId()));
        }
    }

    @Override
    public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
        synchronized (marks) {
            if (!staleSources.contains(sourceId)) {
                Set<String> pages = stalePages.computeIfAbsent(sourceId, id -> new HashSet<>());
                pages.addAll(pageIds);
                if (pages.size() > MAX_DIRTY_PAGES) {
                    sourceChanged(sourceId);
                }
            }
        }
    }

    @Override
    public void spaceChanged(String sourceId, String spaceKey) {
        synchronized (marks) {
            if (!staleSources.contains(sourceId)) {
                staleSpaces.computeIfAbsent(sourceId, id -> new HashSet<>()).add(spaceKey == null ? "" : spaceKey);
            }
        }
    }

    @Override
    public void sourceChanged(String sourceId) {
        synchronized (marks) {
            staleSources.add(sourceId);
            stalePages.remove(sourceId);
            staleSpaces.remove(sourceId);
        }
    }

    @Override
    public void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
        // Laden ändert noch nichts; das Ende meldet pagesChanged.
    }

    /**
     * Von Hand neu aufbauen, für den Fehlerfall: Die Quelle samt allen ihren Spaces gilt als veraltet, die nächste
     * Abfrage liest sie neu. {@code null}: alle Quellen.
     */
    public void rebuild(String sourceId) {
        synchronized (marks) {
            if (sourceId == null) {
                staleAll = true;
                staleSources.clear();
                stalePages.clear();
                staleSpaces.clear();
            } else {
                sourceChanged(sourceId);
            }
        }
    }

    /**
     * Zahl der verschiedenen Begriffe je Art.
     *
     * @param sources  alle Quellen, die es gibt; die Wurzel vereinigt genau diese
     * @param sourceId die Quelle oder {@code null} für die Wurzel
     * @param spaceKey ein Space der Quelle oder {@code null}
     * @param progress Zwischenstände, solange ein Verwalter erst aufgebaut wird, oder {@code null}
     * @return leer, wenn die Ablage keine Änderungen meldet: Dann zählt der Aufrufer selbst
     */
    public synchronized Optional<Map<TermKind, Integer>> totals(Collection<String> sources, String sourceId,
                                                                String spaceKey,
                                                                Consumer<Map<TermKind, Integer>> progress) {
        if (!notified || sourceId == null && spaceKey != null) {
            return Optional.empty();
        }
        Set<String> staleNow;
        Map<String, Set<String>> pagesNow;
        Map<String, Set<String>> spacesNow;
        boolean all;
        synchronized (marks) {
            all = staleAll;
            staleAll = false;
            staleNow = new HashSet<>(staleSources);
            pagesNow = new HashMap<>(stalePages);
            spacesNow = new HashMap<>(staleSpaces);
            if (sourceId == null) {
                staleSources.clear();
                stalePages.clear();
                staleSpaces.clear();
            } else {
                // Nur diese Quelle wird gleich nachgeführt; die Vermerke der anderen bleiben stehen.
                staleSources.remove(sourceId);
                stalePages.remove(sourceId);
                staleSpaces.remove(sourceId);
            }
        }
        State held = all ? null : state.get();
        if (held == null) {
            // Neu oder von der Laufzeit freigegeben: Alles Gemerkte ist weg, jede Quelle wird beim Fragen gebaut.
            held = new State();
            state = new SoftReference<>(held);
        }
        if (sourceId != null) {
            SourceNode source = ensure(held, sourceId, staleNow.contains(sourceId), pagesNow.get(sourceId),
                    spacesNow.get(sourceId), spaceKey == null ? progress : null);
            if (spaceKey == null) {
                return Optional.of(counts(source.terms.refs.keySet()));
            }
            Set<String> terms = source.spaces.get(spaceKey);
            return Optional.of(terms == null ? Map.of() : counts(terms));
        }
        for (String gone : List.copyOf(held.sources.keySet())) {
            if (!sources.contains(gone)) {
                detach(held, held.sources.remove(gone));
            }
        }
        for (String id : sources) {
            ensure(held, id, staleNow.contains(id), pagesNow.get(id), spacesNow.get(id), null);
            if (progress != null) {
                progress.accept(counts(held.root.refs.keySet()));
            }
        }
        return Optional.of(counts(held.root.refs.keySet()));
    }

    private SourceNode ensure(State held, String sourceId, boolean stale, Set<String> pages, Set<String> spaces,
                              Consumer<Map<TermKind, Integer>> progress) {
        SourceNode source = held.sources.get(sourceId);
        if (source == null || stale) {
            if (source != null) {
                detach(held, source);
            }
            source = build(held, sourceId, progress);
            held.sources.put(sourceId, source);
        } else if (pages != null || spaces != null) {
            Map<String, Set<String>> bySpace = new HashMap<>();
            for (String space : spaces == null ? Set.<String>of() : spaces) {
                bySpace.computeIfAbsent(space, created -> new HashSet<>());
            }
            for (String pageId : pages == null ? Set.<String>of() : pages) {
                String before = source.pages.get(pageId);
                if (before != null) {
                    bySpace.computeIfAbsent(before, space -> new HashSet<>());
                }
                headers.apply(new PageKey(sourceId, pageId)).map(TermLedger::space)
                        .ifPresent(now -> bySpace.computeIfAbsent(now, space -> new HashSet<>()).add(pageId));
            }
            for (Map.Entry<String, Set<String>> space : bySpace.entrySet()) {
                reread(held, sourceId, source, space.getKey(), space.getValue());
            }
        }
        return source;
    }

    /** Die ganze Quelle lesen: je Space die Menge, daraus die Quelle, daraus der Beitrag zur Wurzel. */
    private SourceNode build(State held, String sourceId, Consumer<Map<TermKind, Integer>> progress) {
        SourceNode source = new SourceNode();
        Set<String> seen = progress == null ? null : new HashSet<>();
        scan(sourceId, null, (key, header) -> {
            String space = space(header);
            String group = key.groupKey();
            source.pages.put(key.page().pageId(), space);
            if (source.spaces.computeIfAbsent(space, created -> new HashSet<>()).add(group)
                    && source.terms.add(group)) {
                held.root.add(group);
            }
            if (seen != null) {
                seen.add(group);
            }
        }, progress == null ? null : () -> progress.accept(counts(seen)));
        return source;
    }

    /** Einen Space neu lesen und nur die Unterschiede an Quelle und Wurzel weiterreichen. */
    private void reread(State held, String sourceId, SourceNode source, String space, Set<String> changed) {
        Set<String> pages = new HashSet<>(changed);
        source.pages.forEach((pageId, of) -> {
            if (of.equals(space)) {
                pages.add(pageId);
            }
        });
        source.pages.values().removeIf(space::equals);
        Set<String> now = new HashSet<>();
        scan(sourceId, PageSet.of(Map.of(sourceId, pages)), (key, header) -> {
            if (space(header).equals(space)) {
                now.add(key.groupKey());
                source.pages.put(key.page().pageId(), space);
            }
        }, null);
        Set<String> before = source.spaces.getOrDefault(space, Set.of());
        for (String group : before) {
            if (!now.contains(group) && source.terms.remove(group)) {
                held.root.remove(group);
            }
        }
        for (String group : now) {
            if (!before.contains(group) && source.terms.add(group)) {
                held.root.add(group);
            }
        }
        if (now.isEmpty()) {
            source.spaces.remove(space);
        } else {
            source.spaces.put(space, now);
        }
    }

    private static void detach(State held, SourceNode source) {
        source.terms.refs.keySet().forEach(held.root::remove);
    }

    private interface Visitor {
        void visit(TermIndex.TermKey key, PageHeader header);
    }

    private void scan(String sourceId, PageSet pages, Visitor visitor, Runnable batchDone) {
        if (pages != null && pages.isEmpty()) {
            return;
        }
        TermIndex.Filter filter = new TermIndex.Filter(Set.of(sourceId), null, null, null, processorVersion, null,
                TermOrigin.PAGES, pages);
        Function<TermIndex.TermKey, Optional<PageHeader>> current = currency.get();
        String from = null;
        while (true) {
            List<TermIndex.TermKey> batch = index.keys(filter, from, TermIndex.MAX_KEY_BATCH);
            for (TermIndex.TermKey key : batch) {
                current.apply(key).ifPresent(header -> visitor.visit(key, header));
            }
            if (batch.size() < TermIndex.MAX_KEY_BATCH) {
                return;
            }
            if (batchDone != null) {
                batchDone.run();
            }
            from = batch.get(batch.size() - 1).sortKey();
        }
    }

    private static String space(PageHeader header) {
        String space = header.metadata().partitionKey();
        return space == null ? "" : space;
    }

    private static Map<TermKind, Integer> counts(Collection<String> groups) {
        Map<TermKind, Integer> counts = new EnumMap<>(TermKind.class);
        for (String group : groups) {
            counts.merge(TermKind.of(group.substring(group.lastIndexOf('\u0000') + 1)), 1, Integer::sum);
        }
        return counts;
    }

    private static final class State {
        final Children root = new Children();
        final Map<String, SourceNode> sources = new HashMap<>();
    }

    private static final class SourceNode {
        /** Je Begriff die Zahl der Spaces dieser Quelle, in denen er vorkommt. */
        final Children terms = new Children();
        final Map<String, Set<String>> spaces = new HashMap<>();
        /** Seiten mit Begriffen und ihr Space, damit ein Space gezielt neu gelesen werden kann. */
        final Map<String, String> pages = new HashMap<>();
    }

    /** Die Vereinigung der Kinder eines Knotens: je Begriff, in wie vielen Kindern er steht. */
    private static final class Children {
        final Map<String, Integer> refs = new HashMap<>();

        /** @return ob der Begriff damit neu im Knoten ist */
        boolean add(String group) {
            return refs.merge(group, 1, Integer::sum) == 1;
        }

        /** @return ob der Begriff damit aus dem Knoten verschwunden ist */
        boolean remove(String group) {
            Integer count = refs.get(group);
            if (count == null) {
                return false;
            }
            if (count == 1) {
                refs.remove(group);
                return true;
            }
            refs.put(group, count - 1);
            return false;
        }
    }
}
