package com.aresstack.enterpriseai.astu.knowledge;

/**
 * Konfiguration des {@link KnowledgeChunker}: Token-Budget je Chunk und Overlap in ganzen Sätzen.
 *
 * <p>Defaults (350 Tokens, 1 Satz Overlap) und Overlap-Semantik übernommen aus aresstack/corenth
 * {@code LexicalChunkingConfig}. {@link #fingerprint()} beschreibt die Konfiguration als Text; ob bestehende
 * Chunks noch passen, entscheidet {@link KnowledgeChunker#fingerprint()}, das zusätzlich den Token-Zähler enthält.
 */
public final class KnowledgeChunkingPolicy {

    public static final int DEFAULT_MAX_TOKENS = 350;
    public static final int DEFAULT_OVERLAP_SENTENCES = 1;
    /** Kleinstes sinnvolles Budget; darunter zerfallen selbst kurze Sätze in Wortstücke. */
    public static final int MIN_MAX_TOKENS = 8;

    /**
     * Version des Chunking-Algorithmus; bei Verhaltensänderungen des Chunkers erhöhen, auch bei einer neuen
     * {@link UnicodeClasses#VERSION}. 2 seit der JDK-unabhängigen Zeichenklassifizierung.
     */
    static final int ALGORITHM_VERSION = 2;

    private final int maxTokens;
    private final int overlapSentences;

    private KnowledgeChunkingPolicy(int maxTokens, int overlapSentences) {
        if (maxTokens < MIN_MAX_TOKENS) {
            throw new IllegalArgumentException("maxTokens muss mindestens " + MIN_MAX_TOKENS + " sein: " + maxTokens);
        }
        if (overlapSentences < 0) {
            throw new IllegalArgumentException("overlapSentences darf nicht negativ sein: " + overlapSentences);
        }
        this.maxTokens = maxTokens;
        this.overlapSentences = overlapSentences;
    }

    public static KnowledgeChunkingPolicy defaults() {
        return new KnowledgeChunkingPolicy(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_SENTENCES);
    }

    /**
     * @param maxTokens        Obergrenze je Chunk (Überschriftenzeile eingerechnet), mindestens {@value #MIN_MAX_TOKENS}
     * @param overlapSentences wie viele Schlusssätze eines Chunks der nächste Chunk desselben Abschnitts
     *                         wiederholt; 0 schaltet Overlap ab
     */
    public static KnowledgeChunkingPolicy of(int maxTokens, int overlapSentences) {
        return new KnowledgeChunkingPolicy(maxTokens, overlapSentences);
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int overlapSentences() {
        return overlapSentences;
    }

    /** Z. B. {@code chunker-v2;maxTokens=350;overlapSentences=1}. */
    public String fingerprint() {
        return "chunker-v" + ALGORITHM_VERSION + ";maxTokens=" + maxTokens + ";overlapSentences=" + overlapSentences;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KnowledgeChunkingPolicy)) {
            return false;
        }
        KnowledgeChunkingPolicy that = (KnowledgeChunkingPolicy) other;
        return maxTokens == that.maxTokens && overlapSentences == that.overlapSentences;
    }

    @Override
    public int hashCode() {
        return 31 * maxTokens + overlapSentences;
    }

    @Override
    public String toString() {
        return fingerprint();
    }
}
