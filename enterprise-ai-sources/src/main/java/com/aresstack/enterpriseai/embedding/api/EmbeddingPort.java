package com.aresstack.enterpriseai.embedding.api;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;

import java.util.List;

/**
 * Neutraler Batch-Port für Text-Embeddings. Eine Implementierung steht für genau eine Embedding-Welt
 * ({@link #modelIdentity()}); jeder gelieferte Vektor trägt diese Identität.
 *
 * <p>Vertrag für jede Implementierung:
 * <ul>
 *   <li>Das Ergebnis enthält genau einen Vektor je Eingabetext, in Eingabereihenfolge
 *       ({@code result.get(i)} gehört zu {@code texts.get(i)}). {@link EmbeddingBatch#of} prüft das.</li>
 *   <li>Eine leere Eingabeliste ergibt einen leeren Batch, ohne den Provider aufzurufen.</li>
 *   <li>Beliebig große Eingabelisten sind erlaubt; providerseitige Batch-Grenzen teilt der Adapter selbst auf.</li>
 *   <li>Bei jedem Fehler wird {@link EmbeddingException} geworfen. Es gibt keine Teilergebnisse, keine
 *       Null-Vektoren als Ersatz und keinen stillen Wechsel auf ein anderes Modell.</li>
 * </ul>
 *
 * <p>Konzept übernommen aus askai-java8 ({@code research-knowledge-pipeline/EmbeddingPort}), hier mit
 * Identität am Port und geprüftem Ergebnis-Typ.
 */
public interface EmbeddingPort {

    /** Die Embedding-Welt, zu der alle Vektoren dieses Ports gehören. */
    EmbeddingModelIdentity modelIdentity();

    /**
     * Vektoren für zu indexierende Texte (Dokumente, Abschnitte).
     *
     * @param texts Eingabetexte, nicht {@code null}, ohne {@code null}-Elemente
     * @return genau ein Vektor je Text, in Eingabereihenfolge
     * @throws IllegalArgumentException bei {@code null}-Liste oder {@code null}-Element
     * @throws EmbeddingException       bei jedem Provider-, Transport- oder Antwortfehler
     */
    EmbeddingBatch embed(List<String> texts);

    /**
     * Vektoren für Suchanfragen. Modelle, die Anfrage und Dokument unterschiedlich kodieren (E5: {@code query: } und
     * {@code passage: }), unterscheiden hier; alle anderen rechnen wie {@link #embed(List)}. Vertrag wie dort.
     */
    default EmbeddingBatch embedQueries(List<String> queries) {
        return embed(queries);
    }
}
