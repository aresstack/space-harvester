package com.aresstack.enterpriseai.proasteion.emporion.deigma;

/**
 * Port for detecting the content type of a resource.
 *
 * <p>Implementations may use filename extensions, MIME magic bytes,
 * metadata hints or external libraries (e.g. Apache Tika) to determine
 * the content type.
 */
public interface ContentDetector {

    /**
     * Detects the content type of a resource.
     *
     * @param filenameHint optional filename (may be {@code null})
     * @param contentTypeHint optional known MIME type hint (may be {@code null})
     * @param contentPrefix optional first bytes of the content for magic-byte detection (may be {@code null})
     * @return the detected content type, never {@code null}
     */
    DetectedContentType detect(String filenameHint, String contentTypeHint, byte[] contentPrefix);
}
