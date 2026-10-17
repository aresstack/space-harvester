package com.aresstack.spaceharvester.application.enrichment.embedding;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ähnlichkeitssuche über die gespeicherten Embeddings (#89, ADR 0004). Die Ablage kennt keine Rechte: Treffer sind
 * Kandidaten, die der Aufrufer erst nach der {@code content}-Prüfung und einem Abgleich mit dem Seitencache
 * weitergibt; Anzahl und Ähnlichkeit ungeprüfter Kandidaten verlassen die Anwendung nie.
 */
public interface EmbeddingIndex {

    /** Höchstes {@code k} je Anfrage. */
    int MAX_K = 1_000;

    /**
     * Die {@code k} ähnlichsten Stücke aus diesen Quellen zusammen (#114), nur aus Ergebnissen mit genau dieser
     * Prozessorversion (also demselben Modell, damit die Ähnlichkeiten über Quellen vergleichbar sind); absteigend nach
     * Ähnlichkeit, bei Gleichstand nach Seiten-ID, Quelle und Stück. Eine Abfrage für alle Quellen, nicht {@code k} je
     * Quelle.
     *
     * @param query Anfragevektor in der Dimension der Prozessorversion
     */
    List<ChunkHit> nearest(Set<String> sourceIds, String processorVersion, float[] query, int k);

    /** Die {@code k} ähnlichsten Stücke einer Quelle; siehe {@link #nearest(Set, String, float[], int)}. */
    default List<ChunkHit> nearest(String sourceId, String processorVersion, float[] query, int k) {
        return nearest(Set.of(sourceId), processorVersion, query, k);
    }

    /**
     * Ein ähnliches Stück.
     *
     * @param page        Seite
     * @param chunk       Nummer des Stücks in der Seite, ab 0
     * @param charStart   erstes Zeichen in der Textprojektion
     * @param charEnd     Ende (exklusiv)
     * @param inputSha256 Eingabe, aus der der Vektor stammt; ist sie nicht mehr aktuell, ist der Treffer veraltet
     * @param similarity  Kosinusähnlichkeit zwischen -1 und 1
     */
    record ChunkHit(PageKey page, int chunk, int charStart, int charEnd, String inputSha256, double similarity) {

        public ChunkHit {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(inputSha256, "inputSha256");
        }
    }

    /** Auf Länge 1 normiert; der Nullvektor bleibt null. */
    static float[] normalized(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        float[] out = new float[vector.length];
        if (norm == 0) {
            return out;
        }
        double length = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / length);
        }
        return out;
    }
}
