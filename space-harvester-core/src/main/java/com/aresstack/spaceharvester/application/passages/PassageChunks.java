package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.PassageChunking;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Verbindet die Embeddings mit der Absatzbildung ({@link PassageSegmentation}), wie {@code SemanticPassageSplitter} im
 * Enterprise AI Client (#181, ADR 0020).
 */
public final class PassageChunks implements PassageChunking {

    private final PassageSegmentation segmentation;

    public PassageChunks(PassageSegmentation segmentation) {
        this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
    }

    @Override
    public String fingerprint() {
        return segmentation.fingerprint();
    }

    @Override
    public boolean sentenceModel() {
        return segmentation.sentenceModel();
    }

    @Override
    public List<Piece> passages(PageText text, Function<List<String>, List<float[]>> sentences) {
        return segmentation.segment(text, sentences).passages().stream()
                .map(passage -> new Piece(passage.start(), passage.end(), passage.section()))
                .toList();
    }
}
