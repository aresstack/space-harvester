package com.aresstack.enterpriseai.proasteion.emporion.deigma;

/**
 * Port for extracting structured content from a resource.
 *
 * <p>Implementations handle specific content types (e.g. plain text,
 * markdown, PDF, DOCX). The {@link ExtractionRegistry} selects the
 * appropriate extractor based on detected content type.
 */
public interface ResourceExtractor {

    /**
     * Returns {@code true} if this extractor supports the given content type.
     *
     * @param contentType the detected content type
     * @return whether this extractor can handle the content
     */
    boolean supports(DetectedContentType contentType);

    /**
     * Extracts structured content from the request.
     *
     * @param request the extraction request containing content and metadata
     * @return the extraction result
     */
    ExtractionResult extract(ExtractionRequest request);
}
