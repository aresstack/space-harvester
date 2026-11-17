package com.aresstack.spaceharvester.application.release;

import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Die Liste der freigegebenen Seiten im Speicher (ADR 0028), auf Wunsch mit einem {@link Journal}, das jede Änderung
 * dauerhaft schreibt, bevor sie gilt: {@link #contains} liest eine nebenläufige Hash-Menge ohne Sperre, Ändern hält
 * die Sperre der Liste, und Beobachter hören nach dem Schreiben außerhalb der Sperre davon.
 */
public final class InMemoryPageReleases implements PageReleases {

    /** Schreibt eine Änderung dauerhaft; scheitert es, gilt sie nicht. */
    @FunctionalInterface
    public interface Journal {

        void write(List<Entry> added, List<PageKey> removed);

        /** Schreibt nichts: die Liste lebt nur im Speicher. */
        Journal NONE = (added, removed) -> { };
    }

    private final Journal journal;
    private final Map<PageKey, Entry> entries = new LinkedHashMap<>();
    private final Set<PageKey> keys = ConcurrentHashMap.newKeySet();
    private final Listeners<Consumer<Change>> listeners = new Listeners<>("Freigegebene Seiten");

    /**
     * @param loaded  der gespeicherte Stand, in der Reihenfolge des Eintragens
     * @param journal schreibt jede spätere Änderung
     */
    public InMemoryPageReleases(List<Entry> loaded, Journal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
        for (Entry entry : loaded) {
            entries.putIfAbsent(entry.key(), entry);
            keys.add(entry.key());
        }
    }

    @Override
    public boolean contains(PageKey key) {
        return key != null && keys.contains(key);
    }

    @Override
    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    @Override
    public List<Entry> add(Collection<Entry> wanted) {
        List<Entry> added = new ArrayList<>();
        synchronized (this) {
            Set<PageKey> seen = new LinkedHashSet<>();
            for (Entry entry : wanted) {
                if (!entries.containsKey(entry.key()) && seen.add(entry.key())) {
                    added.add(entry);
                }
            }
            if (added.isEmpty()) {
                return List.of();
            }
            journal.write(List.copyOf(added), List.of());
            for (Entry entry : added) {
                entries.put(entry.key(), entry);
                keys.add(entry.key());
            }
        }
        notify(new Change(added, List.of()));
        return List.copyOf(added);
    }

    @Override
    public List<Entry> remove(Collection<PageKey> unwanted) {
        List<Entry> removed = new ArrayList<>();
        synchronized (this) {
            for (PageKey key : new LinkedHashSet<>(unwanted)) {
                Entry entry = entries.get(key);
                if (entry != null) {
                    removed.add(entry);
                }
            }
            if (removed.isEmpty()) {
                return List.of();
            }
            journal.write(List.of(), removed.stream().map(Entry::key).toList());
            for (Entry entry : removed) {
                // Erst aus der Hash-Menge: ab hier fragt keine Berechnung mehr das Modell für diese Seite.
                keys.remove(entry.key());
                entries.remove(entry.key());
            }
        }
        notify(new Change(List.of(), removed));
        return List.copyOf(removed);
    }

    @Override
    public void removeSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        List<PageKey> ofSource;
        synchronized (this) {
            ofSource = entries.keySet().stream().filter(key -> key.sourceId().equals(sourceId)).toList();
        }
        remove(ofSource);
    }

    @Override
    public Subscription subscribe(Consumer<Change> listener) {
        return listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notify(Change change) {
        listeners.deliver(null, listener -> listener.accept(change));
    }
}
