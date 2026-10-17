package com.aresstack.enterpriseai.proasteion.emporion.deigma;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of available resource extractors.
 *
 * <p>Selects the appropriate {@link ResourceExtractor} for a given
 * detected content type. Extractors are tried in registration order;
 * the first one that supports the content type is used.
 *
 * <p>This design allows optional extractors (e.g. Tika-based) to be
 * registered without coupling the core API to heavy dependencies.
 */
public final class ExtractionRegistry {

    private final List<ResourceExtractor> extractors = new ArrayList<ResourceExtractor>();

    /** Registers an extractor. Extractors are tried in registration order. */
    public void register(ResourceExtractor extractor) {
        if (extractor == null) {
            throw new IllegalArgumentException("Extractor must not be null");
        }
        extractors.add(extractor);
    }

    /**
     * Finds the first registered extractor that supports the given content type.
     *
     * @param contentType the detected content type
     * @return a supporting extractor, or {@code null} if none registered
     */
    public ResourceExtractor findExtractor(DetectedContentType contentType) {
        for (ResourceExtractor extractor : extractors) {
            if (extractor.supports(contentType)) {
                return extractor;
            }
        }
        return null;
    }

    /** Returns the number of registered extractors. */
    public int size() {
        return extractors.size();
    }
}
