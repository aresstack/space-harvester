package com.aresstack.spaceharvester.application.terms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Flüchtige Beziehungsablage, solange kein Verzeichnis eingerichtet ist; auch Test-Fake. */
public final class InMemoryTermRelationStore implements TermRelationStore {

    private final Map<String, TermRelation> relations = new LinkedHashMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();

    @Override
    public synchronized List<TermRelation> all() {
        return List.copyOf(relations.values());
    }

    @Override
    public synchronized Optional<TermRelation> find(String id) {
        return Optional.ofNullable(relations.get(id));
    }

    @Override
    public synchronized void write(Collection<TermRelation> put, Collection<String> removed,
                                   List<AuditEntry> entries) {
        removed.forEach(relations::remove);
        put.forEach(relation -> relations.put(relation.id(), relation));
        audit.addAll(entries);
    }

    @Override
    public synchronized List<AuditEntry> history(String id) {
        return audit.stream().filter(entry -> entry.relationId().equals(id)).toList();
    }
}
