package com.aresstack.askai.agent.model.embedding;

/**
 * The host port of the SENTENCE-embedding slot (sentence-against-sentence comparisons: near repetitions, claim check
 * stage 2, topic drift). Same contract as {@link EmbeddingConfigurationSnapshotProvider}; the host resolves its own
 * "Sentence embedding model" setting and falls back to the central embedding model while that model is not installed,
 * so {@code selectedModel} is ignored.
 */
public interface SentenceEmbeddingConfigurationSnapshotProvider extends EmbeddingConfigurationSnapshotProvider {
}
