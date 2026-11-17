package com.aresstack.spaceharvester.application.standing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Entscheidungen zum Stand nur im Speicher. */
final class InMemoryStandingDecisionStore implements StandingDecisionStore {

    private final Map<StandingDecision.Target, StandingDecision> decisions = new LinkedHashMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();

    @Override
    public synchronized List<StandingDecision> all() {
        return List.copyOf(decisions.values());
    }

    @Override
    public synchronized Optional<StandingDecision> find(StandingDecision.Target target) {
        return Optional.ofNullable(decisions.get(target));
    }

    @Override
    public synchronized void write(Collection<StandingDecision> put, Collection<StandingDecision.Target> removed,
                                   List<AuditEntry> entries) {
        removed.forEach(decisions::remove);
        put.forEach(decision -> decisions.put(decision.target(), decision));
        audit.addAll(entries);
    }

    @Override
    public synchronized List<AuditEntry> history(StandingDecision.Target target) {
        return audit.stream().filter(entry -> entry.target().equals(target)).toList();
    }
}
