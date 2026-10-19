package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Prüfablage im Speicher, für Tests und Einbettungen ohne Datenverzeichnis. */
public final class InMemoryFactEditStore implements FactEditStore {

    private final Map<String, FactEdit> edits = new TreeMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();

    @Override
    public synchronized List<FactEdit> all() {
        return List.copyOf(edits.values());
    }

    @Override
    public synchronized List<FactEdit> forPage(PageKey page) {
        return edits.values().stream().filter(edit -> edit.fact().page().equals(page))
                .sorted(Comparator.comparing(FactEdit::id)).toList();
    }

    @Override
    public synchronized Optional<FactEdit> find(String factId) {
        return Optional.ofNullable(edits.get(factId));
    }

    @Override
    public synchronized void write(Collection<FactEdit> put, Collection<String> removed, List<AuditEntry> entries) {
        removed.forEach(edits::remove);
        put.forEach(edit -> edits.put(edit.id(), edit));
        audit.addAll(entries);
    }

    @Override
    public synchronized List<AuditEntry> history(String factId) {
        return audit.stream().filter(entry -> entry.factId().equals(factId)).toList();
    }
}
