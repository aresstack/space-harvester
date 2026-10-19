package com.aresstack.spaceharvester.application.terms;

/** Die Beziehung wurde inzwischen geändert; die API antwortet 409 mit der aktuellen Revision. */
public class RelationConflictException extends RuntimeException {

    private final String relationId;
    private final long revision;

    public RelationConflictException(String relationId, long revision) {
        super("Beziehung " + relationId + " hat inzwischen Revision " + revision);
        this.relationId = relationId;
        this.revision = revision;
    }

    public String relationId() {
        return relationId;
    }

    public long revision() {
        return revision;
    }
}
