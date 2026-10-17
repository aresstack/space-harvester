package com.aresstack.spaceharvester.application.enrichment.embedding;

/** Das Embedding-Modell ist nicht erreichbar oder überlastet; die Meldung nennt nie eingebettete Texte. */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }
}
