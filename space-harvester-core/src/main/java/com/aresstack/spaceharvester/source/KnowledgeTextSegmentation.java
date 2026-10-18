package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeChunkingPolicy;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeTokenCounter;
import com.aresstack.enterpriseai.astu.knowledge.SentenceSplitter;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import java.util.List;

/**
 * Zerlegung wie {@code KnowledgeChunker} im Enterprise AI Client (#181): {@link SentenceSplitter},
 * {@link KnowledgeChunkingPolicy#defaults()} (350 Tokens, ein Satz Überlappung) und
 * {@link KnowledgeTokenCounter#wordsAndSymbols()}.
 */
public final class KnowledgeTextSegmentation implements TextSegmentation {

    private static final KnowledgeTextSegmentation DEFAULTS = new KnowledgeTextSegmentation();

    private final KnowledgeChunkingPolicy policy = KnowledgeChunkingPolicy.defaults();
    private final KnowledgeTokenCounter counter = KnowledgeTokenCounter.wordsAndSymbols();
    private final SentenceSplitter splitter = new SentenceSplitter();

    private KnowledgeTextSegmentation() {
    }

    public static TextSegmentation defaults() {
        return DEFAULTS;
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
