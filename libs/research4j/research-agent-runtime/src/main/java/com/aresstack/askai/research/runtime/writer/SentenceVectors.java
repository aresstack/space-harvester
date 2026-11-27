package com.aresstack.askai.research.runtime.writer;

import java.util.List;

/**
 * The sentence-embedding slot as the writer checks see it: one vector per sentence, in input order, all from the same
 * model (the host resolves "Sentence embedding model" and falls back to the main embedding model). Both sides of a
 * comparison are embedded through this port, so the vectors are always comparable.
 */
public interface SentenceVectors {

    /** @throws Exception when the model is unreachable; callers then judge lexically only */
    List<float[]> embed(List<String> sentences) throws Exception;
}
