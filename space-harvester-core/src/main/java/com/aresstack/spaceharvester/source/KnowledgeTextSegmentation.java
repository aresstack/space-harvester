package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeChunkingPolicy;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeTokenCounter;
import com.aresstack.enterpriseai.astu.knowledge.SentenceSplitter;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import java.util.List;

/**
 * Zerlegung wie {@code KnowledgeChunker} im Enterprise AI Client (#181): {@link SentenceSplitter},
 * {@link KnowledgeChunkingPolicy} (Standard 350 Tokens, ein Satz Überlappung; einstellbar wie dort
 * {@code knowledge.chunk.maxTokens} und {@code knowledge.chunk.overlapSentences}) und
 * {@link KnowledgeTokenCounter#wordsAndSymbols()}.
 */
public final class KnowledgeTextSegmentation implements TextSegmentation {

    private static final KnowledgeTextSegmentation DEFAULTS = new KnowledgeTextSegmentation(
            KnowledgeChunkingPolicy.defaults());

    /** Standardbudget je Stück in Tokens. */
    public static final int DEFAULT_MAX_TOKENS = KnowledgeChunkingPolicy.DEFAULT_MAX_TOKENS;
    /** Standardüberlappung in Sätzen. */
    public static final int DEFAULT_OVERLAP_SENTENCES = KnowledgeChunkingPolicy.DEFAULT_OVERLAP_SENTENCES;

    private final KnowledgeChunkingPolicy policy;
    private final KnowledgeTokenCounter counter = KnowledgeTokenCounter.wordsAndSymbols();
    private final SentenceSplitter splitter = new SentenceSplitter();

    private KnowledgeTextSegmentation(KnowledgeChunkingPolicy policy) {
        this.policy = policy;
    }

    public static TextSegmentation defaults() {
        return DEFAULTS;
    }

    /**
     * @param maxTokens        Budget je Stück, mindestens {@value KnowledgeChunkingPolicy#MIN_MAX_TOKENS}
     * @param overlapSentences so viele Schlusssätze wiederholt das nächste Stück; 0 schaltet die Überlappung ab
     * @throws IllegalArgumentException bei Werten außerhalb dieser Grenzen
     */
    public static TextSegmentation of(int maxTokens, int overlapSentences) {
        return new KnowledgeTextSegmentation(KnowledgeChunkingPolicy.of(maxTokens, overlapSentences));
    }

    @Override
    public String id() {
        return policy.maxTokens() + "-" + policy.overlapSentences() + "-" + counter.id();
    }

    @Override
    public int maxTokens() {
        return policy.maxTokens();
    }

    @Override
    public int overlapSentences() {
        return policy.overlapSentences();
    }

    @Override
    public List<String> sentences(String block) {
        return splitter.split(block);
    }

    @Override
    public int tokens(String text) {
        return counter.count(text);
    }
}
