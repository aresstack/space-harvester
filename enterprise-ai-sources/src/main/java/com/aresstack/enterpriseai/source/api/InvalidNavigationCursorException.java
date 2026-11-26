package com.aresstack.enterpriseai.source.api;

/** Signal that navigation must restart from the parent rather than retry the cursor. */
public final class InvalidNavigationCursorException extends KnowledgeSourceException {
    private static final long serialVersionUID = 1L;

    public InvalidNavigationCursorException(String message) {
        super(Kind.INVALID_RESPONSE, message);
    }
}