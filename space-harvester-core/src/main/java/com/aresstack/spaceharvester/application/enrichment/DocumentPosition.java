package com.aresstack.spaceharvester.application.enrichment;

/**
 * Wo ein abgelegter Abschnitt im Lesetext seines Dokuments steht, und was je Dokument an das Embedding-Modell gehen
 * darf (ADR 0026, AP9a). Nur der Prozessor {@code embedding} wertet es aus; Absätze und Navigation gelten ohne Grenze.
 *
 * @param offset            Zeichen der Abschnitte davor im Lesetext des Dokuments
 * @param embeddingMaxChars höchstens so viele Zeichen des Dokuments gehen an das Embedding-Modell, 0 ohne Grenze
 *                          ({@code space-harvester.enrichment.documents.embedding-max-chars})
 */
public record DocumentPosition(long offset, long embeddingMaxChars) {

    /** Erster Abschnitt, ohne Grenze. */
    public static final DocumentPosition UNLIMITED = new DocumentPosition(0, 0);

    public DocumentPosition {
        if (offset < 0 || embeddingMaxChars < 0) {
            throw new IllegalArgumentException("Stelle und Grenze dürfen nicht negativ sein");
        }
    }

    /**
     * Wie viele Zeichen dieses Abschnitts noch eingebettet werden dürfen, {@link Long#MAX_VALUE} ohne Grenze, 0 wenn
     * die Grenze schon vor ihm erreicht ist.
     */
    public long embeddingBudget() {
        return embeddingMaxChars == 0 ? Long.MAX_VALUE : Math.max(0, embeddingMaxChars - offset);
    }
}
