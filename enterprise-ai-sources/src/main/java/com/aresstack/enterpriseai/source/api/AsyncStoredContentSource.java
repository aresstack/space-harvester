package com.aresstack.enterpriseai.source.api;

import java.util.concurrent.CompletionStage;

/**
 * Optional capability next to {@link StoredContentSource}: the same native response, but without holding the calling
 * thread while the source answers. For harvesters that load many resources in parallel over a non-blocking HTTP
 * client. Callers check with {@code instanceof}; a source offers it only when it was built with such a client, never
 * by running the blocking call on another thread.
 *
 * <p>Contract:
 * <ul>
 *     <li>{@link #storedContentAsync} returns without waiting for network I/O. Reading credentials from the local
 *     secret store may happen on the calling thread, before the request starts.</li>
 *     <li>The stage completes with the same {@link StoredContent} as {@link #storedContent} would, or exceptionally
 *     with the same {@link KnowledgeSourceException} (including {@link ResponseTooLargeException}); the method itself
 *     does not throw for these. {@code join()} wraps the failure in a {@code CompletionException}.</li>
 *     <li>Parsing the response does not run on the I/O threads of the HTTP client.</li>
 *     <li>Cancelling the stage ({@code toCompletableFuture().cancel(true)}) aborts the request and frees its
 *     connection.</li>
 * </ul>
 */
public interface AsyncStoredContentSource extends StoredContentSource {

    /**
     * @param localId the local ID in the source, see {@link #localId}
     * @return stage with the native response and its metadata
     */
    CompletionStage<StoredContent> storedContentAsync(String localId);
}
