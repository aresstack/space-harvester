package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.source.api.AsyncStoredContentSource;
import com.aresstack.enterpriseai.source.api.StoredContent;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * {@link ConfluenceKnowledgeSource} mit {@link AsyncStoredContentSource}: Seiten-Snapshots zusätzlich über einen
 * {@link ConfluenceAsyncHttpTransport}. Alles andere bleibt synchron über {@link ConfluenceHttpTransport}; beide
 * Transporte sollen dieselbe Route, dasselbe Vertrauen und dasselbe Client-Zertifikat haben, das stellt die
 * Composition Root sicher ({@link ConfluenceSourceProvider#withAsyncTransports}).
 */
final class AsyncConfluenceKnowledgeSource extends ConfluenceKnowledgeSource implements AsyncStoredContentSource {

    AsyncConfluenceKnowledgeSource(SourceAddress sourceId, ConfluenceConfig config, ConfluenceHttpTransport transport,
                                   ConfluenceAsyncHttpTransport async, Executor mapping, SecretProvider secrets,
                                   VaultEntryOptions options) {
        super(sourceId, config, transport, secrets, options, requireNonNull(async, "async"),
                requireNonNull(mapping, "mapping"));
    }

    @Override
    public CompletionStage<StoredContent> storedContentAsync(String localId) {
        return asyncStoredContent(localId);
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
