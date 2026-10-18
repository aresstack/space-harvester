package com.aresstack.enterpriseai.proasteion.application.knowledge;

import com.aresstack.enterpriseai.astu.acropolis.image.ImageAnalysis;
import com.aresstack.enterpriseai.astu.acropolis.image.SourceImageAnalysisUseCase;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.SourceFileTextExtractor;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Text aus Dateien einer Quelle (Confluence-Anhänge): Dokumente über dieselbe Erkennung und dieselben Extraktoren wie
 * Chat-Anhänge und „Lokale Dateien“ (Markdown und Klartext direkt, sonst Apache Tika), Bilder über die
 * Bildbeschreibung des Lesebereichs (Modell der Kategorie VISION, je Bild und Version zwischengespeichert).
 */
public final class DocumentSourceFileTextExtractor implements SourceFileTextExtractor {

    private static final int PREFIX_BYTES = 8192;
    private static final String IMAGE_TYPE_PREFIX = "image/";

    private final ContentDetector detector;
    private final ExtractionRegistry extractors;
    private final Supplier<SourceImageAnalysisUseCase> images;

    /** @param images Bildbeschreibung (spät gebunden; {@code null} oder ein Lieferant von {@code null}: keine) */
    public DocumentSourceFileTextExtractor(ContentDetector detector, ExtractionRegistry extractors,
                                           Supplier<SourceImageAnalysisUseCase> images) {
        if (detector == null || extractors == null) {
            throw new IllegalArgumentException("detector and extractors must not be null");
        }
        this.detector = detector;
        this.extractors = extractors;
        this.images = images == null ? () -> null : images;
    }

    @Override
    public String extract(KnowledgeResource file, byte[] content) throws KnowledgeSourceException {
        String name = file.title();
        DetectedContentType type = detector.detect(name, file.contentType(),
                Arrays.copyOf(content, Math.min(content.length, PREFIX_BYTES)));
        if (isImage(file.contentType()) || isImage(type.mimeType())) {
            return describe(file, imageType(file.contentType(), type.mimeType()), content);
        }
        ResourceExtractor extractor = extractors.findExtractor(type);
        if (extractor == null) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED,
                    name + ": Format " + type.mimeType() + " wird nicht unterstützt");
        }
        ExtractionResult result;
        try {
            result = extractor.extract(new ExtractionRequest(name, content, name, null, type));
        } catch (RuntimeException e) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.INVALID_RESPONSE,
                    name + ": Text konnte nicht gelesen werden (" + e.getClass().getSimpleName() + ")", e);
        }
        if (!result.isSuccess()) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.INVALID_RESPONSE,
                    name + ": " + result.errorMessage());
        }
        return result.document().combinedText();
    }

    private String describe(KnowledgeResource file, String mediaType, byte[] content)
            throws KnowledgeSourceException {
        SourceImageAnalysisUseCase descriptions = images.get();
        if (descriptions == null) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED,
                    file.title() + ": keine Bildbeschreibung verfügbar");
        }
        ImageAnalysis analysis;
        try {
            analysis = descriptions.describe(file.id().value(), file.title(), file.revision().version(), mediaType,
                    content);
        } catch (RuntimeException e) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNAVAILABLE,
                    file.title() + ": Bildbeschreibung fehlgeschlagen (" + e.getClass().getSimpleName() + ")", e);
        }
        if (analysis == null) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED,
                    file.title() + ": kein Bildmodell (Kategorie VISION) gewählt");
        }
        return analysis.description();
    }

    private static boolean isImage(String mediaType) {
        return mediaType != null && mediaType.toLowerCase(Locale.ROOT).startsWith(IMAGE_TYPE_PREFIX);
    }

    private static String imageType(String declared, String detected) {
        String type = isImage(declared) ? declared : detected;
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }
}
