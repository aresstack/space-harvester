package com.aresstack.enterpriseai.source.api;

/**
 * Optional capability of a source: a single documented or vendor-specific REST query against the source with the
 * credentials, proxy route and TLS settings of the source, for data the other ports do not cover (Confluence:
 * version history, comments, content properties, restrictions, analytics). Callers check with {@code instanceof}.
 * All calls block and may hit the network.
 *
 * <p>Only {@code GET}, only paths below the REST root of the source ({@code /rest/…}); the body is returned byte for
 * byte, never rebuilt from parsed objects. The source never follows the path outside its base URL.
 */
public interface RestQuerySource {

    /**
     * @param pathAndQuery path and query relative to the base URL of the source, e.g.
     *                     {@code /rest/api/content/123/history?expand=lastUpdated}
     * @throws KnowledgeSourceException {@code UNSUPPORTED} for a path outside {@code /rest/}, {@code NOT_FOUND},
     *                                  {@code ACCESS_DENIED}, {@code UNAVAILABLE} and {@code INVALID_RESPONSE} as the
     *                                  source answers
     * @throws ResponseTooLargeException if the response exceeds the configured size limit; nothing is truncated
     */
    RestResponse restGet(String pathAndQuery) throws KnowledgeSourceException;
}
