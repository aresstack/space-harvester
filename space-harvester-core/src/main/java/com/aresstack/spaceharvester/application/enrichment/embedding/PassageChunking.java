package com.aresstack.spaceharvester.application.enrichment.embedding;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.List;
import java.util.function.Function;

/**
 * Zerlegung in semantische Absätze für die Embeddings (#181, ADR 0020), wie {@code KnowledgePassageSplitter} im
 * Enterprise AI Client: Statt fester Stücke nach Token-Budget bekommt jeder Absatz sein eigenes Embedding.
 */
public interface PassageChunking {

    /** Fingerabdruck der Zerlegung; Teil der Version des Prozessors {@code embedding}. */
    String fingerprint();

    /** Ob die Satzgrenzen aus einem Satzmodell statt aus dem Regel-Splitter kommen. */
    default boolean sentenceModel() {
        return true;
    }

    /**
     * Die Absätze der Seite in Lesereihenfolge.
     *
     * @param sentences bettet Sätze für die Themengrenzen ein, mit dem Modell des laufenden Vorgangs
     */
    List<Piece> passages(PageText text, Function<List<String>, List<float[]>> sentences);

    /** Ein Absatz: Stelle in der Textprojektion und seine Überschriftenkette, {@code null} ohne. */
    record Piece(int start, int end, String section) {
    }
}
