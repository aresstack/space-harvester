package com.aresstack.spaceharvester.application.terms;

/** Eine Beziehung gibt es nicht oder der Aufrufer darf sie nicht sehen; die API antwortet in beiden Fällen 404. */
public class UnknownRelationException extends RuntimeException {

    private final String relationId;

    public UnknownRelationException(String relationId) {
        super("Unbekannte Beziehung " + relationId);
        this.relationId = relationId;
    }

    public String relationId() {
        return relationId;
    }
}
