package com.aresstack.spaceharvester.application.links;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link LinkIndex} im Speicher: für Tests und als Arbeitsstand der Lucene-Ablage, die ihn beim Öffnen füllt. Alle
 * Zugriffe sind synchronisiert; Listen kommen nach Seiten-ID sortiert.
 */
public final class InMemoryLinkIndex implements LinkIndex {

    private static final char SEPARATOR = '\u0000';
    private static final Comparator<Entry> ORDER = Comparator.comparing((Entry entry) -> entry.page().pageId());

    private final Map<PageKey, Entry> entries = new HashMap<>();
    private final Map<String, Set<PageKey>> byId = new HashMap<>();
    private final Map<String, Set<PageKey>> byTitleTarget = new HashMap<>();
    private final Map<String, Set<PageKey>> titles = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    public InMemoryLinkIndex() {
    }

    /** Mit vorhandenen Einträgen, etwa aus der Lucene-Ablage. */
    public InMemoryLinkIndex(Collection<Entry> initial) {
        initial.forEach(this::put);
    }

    @Override
    public synchronized Optional<Entry> entry(PageKey page) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(page, "page")));
    }

    @Override
    public synchronized void put(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        remove(entry.page());
        entries.put(entry.page(), entry);
        String sourceId = entry.page().sourceId();
        counts.merge(sourceId, 1, Integer::sum);
        add(titles, key(sourceId, entry.titleKey()), entry.page());
        for (LinkTarget target : entry.targets()) {
            if (target.byId()) {
                add(byId, key(sourceId, target.pageId()), entry.page());
            } else {
                add(byTitleTarget, key(sourceId, target.titleKey()), entry.page());
            }
        }
    }

    @Override
    public synchronized boolean remove(PageKey page) {
        Entry previous = entries.remove(Objects.requireNonNull(page, "page"));
        if (previous == null) {
            return false;
        }
        String sourceId = page.sourceId();
        counts.computeIfPresent(sourceId, (ignored, count) -> count == 1 ? null : count - 1);
        drop(titles, key(sourceId, previous.titleKey()), page);
        for (LinkTarget target : previous.targets()) {
            if (target.byId()) {
                drop(byId, key(sourceId, target.pageId()), page);
            } else {
                drop(byTitleTarget, key(sourceId, target.titleKey()), page);
            }
        }
        return true;
    }

    @Override
    public synchronized List<String> pageIds(String sourceId) {
        List<String> ids = new ArrayList<>();
        for (PageKey key : entries.keySet()) {
            if (key.sourceId().equals(sourceId)) {
                ids.add(key.pageId());
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    @Override
    public synchronized int count(String sourceId) {
        return counts.getOrDefault(sourceId, 0);
    }

    @Override
    public synchronized List<Entry> linkingToId(String sourceId, String pageId) {
        return entries(byId.get(key(sourceId, pageId)));
    }

    @Override
    public synchronized List<Entry> linkingToTitle(String sourceId, String titleKey) {
        return entries(byTitleTarget.get(key(sourceId, titleKey)));
    }

    @Override
    public synchronized List<Entry> titled(String sourceId, String titleKey) {
        return entries(titles.get(key(sourceId, titleKey)));
    }

    /** Alle Einträge, etwa für Tests. */
    public synchronized List<Entry> entries() {
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparing((Entry entry) -> entry.page().sourceId()).thenComparing(ORDER));
        return all;
    }

    private List<Entry> entries(Set<PageKey> keys) {
        if (keys == null) {
            return List.of();
        }
        List<Entry> found = new ArrayList<>(keys.size());
        for (PageKey key : keys) {
            found.add(entries.get(key));
        }
        found.sort(ORDER);
        return found;
    }

    private static String key(String sourceId, String value) {
        return sourceId + SEPARATOR + value;
    }

    private static void add(Map<String, Set<PageKey>> map, String key, PageKey page) {
        map.computeIfAbsent(key, ignored -> new HashSet<>()).add(page);
    }

    private static void drop(Map<String, Set<PageKey>> map, String key, PageKey page) {
        Set<PageKey> pages = map.get(key);
        if (pages != null && pages.remove(page) && pages.isEmpty()) {
            map.remove(key);
        }
    }
}
