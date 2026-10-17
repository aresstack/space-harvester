package com.aresstack.spaceharvester.application.ranking;

/** Eine Relevanzsuche ohne Titelwörter braucht ein Embedding-Modell, das nicht eingerichtet oder nicht erreichbar ist. */
public class SemanticSearchUnavailableException extends RuntimeException {

    public SemanticSearchUnavailableException(String message) {
        super(message);
    }
}
