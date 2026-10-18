package com.aresstack.enterpriseai.astu.modelcatalog;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ordnet ein Modell allein über seine gemeldeten Metadaten Kategorien zu: {@code capabilities} sowie Ein- und
 * Ausgabemodalitäten (bei der Enterprise-API {@code architecture.input_modalities}/{@code output_modalities}). Modellnamen spielen keine Rolle. Meldet ein Katalog
 * gar keine Metadaten, gilt das Modell wie bisher für Chat und Embeddings als möglich.
 */
public final class ModelClassification {

    private ModelClassification() {
    }

    public static Set<ModelCategory> categoriesOf(List<String> capabilities, List<String> inputModalities,
                                                  List<String> outputModalities) {
        Set<ModelCategory> categories = EnumSet.noneOf(ModelCategory.class);
        boolean noMetadata = capabilities.isEmpty() && inputModalities.isEmpty() && outputModalities.isEmpty();
        if (noMetadata) {
            categories.add(ModelCategory.CHAT);
            categories.add(ModelCategory.EMBEDDING);
            return categories;
        }
        boolean textIn = inputModalities.isEmpty() || inputModalities.contains("text");
        boolean imageIn = inputModalities.contains("image");
        boolean audioIn = inputModalities.contains("audio");
        boolean textOut = outputModalities.isEmpty() || outputModalities.contains("text");
        boolean audioOut = outputModalities.contains("audio");
        boolean generates = any(capabilities, "completion", "chat", "generation", "text_generation");
        boolean embeds = any(capabilities, "embeddings", "embedding")
                || any(outputModalities, "embeddings", "embedding");

        if (generates && textIn && textOut) {
            categories.add(ModelCategory.CHAT);
        }
        if (textOut && (any(capabilities, "vision") || (generates && imageIn))) {
            categories.add(ModelCategory.VISION);
        }
        if (any(capabilities, "ocr", "document", "document_ocr") || (imageIn && textOut && !embeds)) {
            categories.add(ModelCategory.DOCUMENT_OCR);
        }
        if (embeds && textIn) {
            categories.add(ModelCategory.EMBEDDING);
        }
        if (embeds && imageIn) {
            categories.add(ModelCategory.IMAGE_EMBEDDING);
        }
        if (any(capabilities, "reranking", "rerank", "reranker")) {
            categories.add(ModelCategory.RERANK);
        }
        if (any(capabilities, "text_to_speech", "tts") || (audioOut && textIn)) {
            categories.add(ModelCategory.TTS);
        }
        if (any(capabilities, "speech_to_text", "stt", "transcription", "audio_transcription", "asr")
                || (audioIn && textOut && !audioOut)) {
            categories.add(ModelCategory.STT);
        }
        return categories;
    }

    private static boolean any(Collection<String> values, String... wanted) {
        for (String candidate : Arrays.asList(wanted)) {
            if (values.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
