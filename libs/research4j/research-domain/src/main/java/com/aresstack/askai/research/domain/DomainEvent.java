package com.aresstack.askai.research.domain;

/**
 * One fachliche operation that happened, published AFTER the aggregate applied it (synchronous, ordered).
 * No heavy event sourcing — events describe operations for listeners/projections, the aggregate state is
 * the truth.
 */
public final class DomainEvent {

    private final String name;
    private final String subjectId;

    public DomainEvent(String name, String subjectId) {
        this.name = name == null ? "" : name;
        this.subjectId = subjectId == null ? "" : subjectId;
    }

    public String getName() {
        return name;
    }

    public String getSubjectId() {
        return subjectId;
    }

    @Override
    public String toString() {
        return name + "(" + subjectId + ")";
    }
}
