package com.aresstack.spaceharvester.application.glossary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Glossarablage im Speicher, für Tests und ohne Datenverzeichnis. */
public final class InMemoryGlossaryStore implements GlossaryStore {

    private final Map<String, GlossaryEntry> entries = new LinkedHashMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();

    @Override
    public synchronized List<GlossaryEntry> all() {
        return List.copyOf(entries.values());
    }

    @Override
    public synchronized Optional<GlossaryEntry> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public synchronized void write(Collection<GlossaryEntry> put, Collection<String> removed,
                                   List<AuditEntry> lines) {
        removed.forEach(entries::remove);
        put.forEach(entry -> entries.put(entry.id(), entry));
        audit.addAll(lines);
    }

    @Override
    public synchronized List<AuditEntry> history(String id) {
        return audit.stream().filter(entry -> entry.entryId().equals(id)).toList();
    }
}
