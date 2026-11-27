package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Cosine similarity of sentences through the sentence-embedding slot ({@link SentenceVectors}), with a vector cache so
 * a remembered sentence is embedded once per writing run. Failure-tolerant: when the model fails once, the instance
 * logs it and reports "no similarity" from then on, so every check falls back to its lexical rule. Thread-safe.
 */
public final class SentenceSimilarity {

    private static final SentenceSimilarity NONE = new SentenceSimilarity(null, null);

    private final SentenceVectors vectors;
    private final Consumer<String> log;
    private final Map<String, float[]> cache = new HashMap<String, float[]>();
    private boolean failed;

    public SentenceSimilarity(SentenceVectors vectors, Consumer<String> log) {
        this.vectors = vectors;
        this.log = log;
    }

    /** No sentence model: every check stays lexical. */
    public static SentenceSimilarity none() {
        return NONE;
    }

    public synchronized boolean isAvailable() {
        return vectors != null && !failed;
    }

    /**
     * The highest cosine between {@code sentence} and any of {@code others}; {@code -1} when unavailable or
     * {@code others} is empty.
     */
    public double best(String sentence, List<String> others) {
        if (others == null || others.isEmpty() || !isAvailable()) {
            return -1;
        }
        List<String> all = new ArrayList<String>(others.size() + 1);
        all.add(sentence);
        all.addAll(others);
        List<float[]> v = vectorsOf(all);
        if (v == null) {
            return -1;
        }
        double best = -1;
        for (int i = 1; i < v.size(); i++) {
            best = Math.max(best, cosine(v.get(0), v.get(i)));
        }
        return best;
    }

    private synchronized List<float[]> vectorsOf(List<String> texts) {
        List<String> missing = new ArrayList<String>();
        for (String text : texts) {
            if (!cache.containsKey(text) && !missing.contains(text)) {
                missing.add(text);
            }
        }
        if (!missing.isEmpty()) {
            try {
                List<float[]> embedded = vectors.embed(Collections.unmodifiableList(missing));
                if (embedded == null || embedded.size() != missing.size()) {
                    throw new IllegalStateException("sentence model returned " + (embedded == null ? 0
                            : embedded.size()) + " vectors for " + missing.size() + " sentences");
                }
                for (int i = 0; i < missing.size(); i++) {
                    cache.put(missing.get(i), embedded.get(i));
                }
            } catch (Exception unavailable) {
                failed = true;
                if (log != null) {
                    log.accept("sentence embedding unavailable, checks stay lexical: " + unavailable.getMessage());
                }
                return null;
            }
        }
        List<float[]> out = new ArrayList<float[]>(texts.size());
        for (String text : texts) {
            out.add(cache.get(text));
        }
        return out;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return -1;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? -1 : dot / Math.sqrt(na * nb);
    }
}
