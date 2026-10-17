package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.AsyncStoredContentSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.source.confluence.InFlightLimit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** {@link HarvestSource} über einen {@link KnowledgeSourcePort} aus {@code enterprise-ai-sources}. */
final class KnowledgeSourceHarvestSource implements HarvestSource {

    private final String id;
    private final String type;
    private final String title;
    private final KnowledgeSourcePort port;
    private final InFlightLimit inFlight;

    KnowledgeSourceHarvestSource(String id, String type, String title, KnowledgeSourcePort port) {
        this(id, type, title, port, null);
    }

    /** @param inFlight Grenze gleichzeitiger asynchroner Abrufe; {@code null} = unbegrenzt */
    KnowledgeSourceHarvestSource(String id, String type, String title, KnowledgeSourcePort port,
                                 InFlightLimit inFlight) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.port = port;
        this.inFlight = inFlight;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Set<SourceCapability> capabilities() {
        Set<SourceCapability> capabilities = EnumSet.of(SourceCapability.PARTITIONS);
        if (port instanceof PartitionListingSource) {
            capabilities.add(SourceCapability.PAGE_LISTING);
        }
        if (port instanceof ResourceMetadataSource) {
            capabilities.add(SourceCapability.PAGE_METADATA);
        }
        if (port instanceof StoredContentSource) {
            capabilities.add(SourceCapability.STORED_PAGE);
        }
        if (port instanceof AsyncStoredContentSource) {
            capabilities.add(SourceCapability.ASYNC_STORED_PAGE);
        }
        return capabilities;
    }

    @Override
    public List<Partition> partitions() {
        try {
            return port.partitions().stream().map(KnowledgeSourceHarvestSource::partition).toList();
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    @Override
    public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
        if (!(port instanceof PartitionListingSource listing)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                    "Quelle kann Seiten einer Partition nicht auflisten", null);
        }
        PartitionResources batch;
        try {
            batch = listing.resources(partitionKey, cursor == null ? "" : cursor, limit);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return new PageMetadataBatch(batch.resources().stream()
                .map(resource -> pageMetadata(resource, partitionKey)).toList(), batch.nextCursor());
    }

    @Override
    public PageMetadata pageMetadata(String pageId) {
        if (!(port instanceof ResourceMetadataSource single)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                    "Quelle kann Metadaten einzelner Seiten nicht liefern", null);
        }
        KnowledgeResource resource;
        try {
            resource = single.resourceMetadata(pageId);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return withSpace(resource);
    }

    @Override
    public StoredPage storedPage(String pageId) {
        if (!(port instanceof StoredContentSource stored)) {
            return HarvestSource.super.storedPage(pageId);
        }
        StoredContent content;
        try {
            content = stored.storedContent(pageId);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return storedPage(content);
    }

    @Override
    public boolean asyncStoredPages() {
        return port instanceof AsyncStoredContentSource;
    }

    @Override
    public CompletionStage<StoredPage> storedPageAsync(String pageId) {
        if (!(port instanceof AsyncStoredContentSource stored)) {
            return HarvestSource.super.storedPageAsync(pageId);
        }
        CompletableFuture<StoredContent> request = inFlight == null
                ? stored.storedContentAsync(pageId).toCompletableFuture()
                : inFlight.submit(() -> stored.storedContentAsync(pageId).toCompletableFuture());
        CompletableFuture<StoredPage> result = new CompletableFuture<>();
        request.whenComplete((content, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                result.completeExceptionally(cause instanceof KnowledgeSourceException e ? accessFailure(e) : cause);
                return;
            }
            try {
                result.complete(storedPage(content));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        result.whenComplete((page, error) -> {
            if (result.isCancelled()) {
                request.cancel(true);
            }
        });
        return result;
    }

    private StoredPage storedPage(StoredContent content) {
        return new StoredPage(withSpace(content.resource()), content.payload(), content.mediaType(),
                content.nativeFormat(), content.retrievedAt());
    }

    /** Metadaten einer einzeln abgerufenen Seite; ihr Space muss in der Antwort stehen. */
    private PageMetadata withSpace(KnowledgeResource resource) {
        if (resource.scope() == null || resource.scope().isEmpty()) {
            throw new SourceAccessException(id, SourceAccessException.Reason.INVALID_RESPONSE,
                    "Seite ohne Space in der Antwort der Quelle", null);
        }
        return pageMetadata(resource, resource.scope());
    }

    private PageMetadata pageMetadata(KnowledgeResource resource, String partitionKey) {
        String version = resource.revision().version();
        return new PageMetadata(
                nativeId(resource.id()),
                resource.id().value(),
                resource.title(),
                resource.scope() == null || resource.scope().isEmpty() ? partitionKey : resource.scope(),
                resource.parentId().map(this::nativeId).orElse(null),
                version == null || version.isEmpty() ? null : version,
                resource.revision().modifiedAt().orElse(null),
                resource.location().orElse(null),
                attributes(resource.metadata().asMap()));
    }

    /**
     * Quellattribute mit stabil sortierten Listen: Labels liefert der Adapter kommagetrennt in der Reihenfolge der
     * Quelle; für {@code attributesHash} werden sie sortiert und entdoppelt (Confluence-Labels enthalten keine Kommas).
     */
    private static SortedMap<String, String> attributes(Map<String, String> source) {
        SortedMap<String, String> attributes = new TreeMap<>(source);
        attributes.replaceAll((key, value) -> key.endsWith(".labels") && value != null
                ? String.join(",", new TreeSet<>(Arrays.stream(value.split(",")).map(String::trim)
                        .filter(label -> !label.isEmpty()).toList()))
                : value);
        return attributes;
    }

    /**
     * Die ID der Seite in der Quelle: die lokale ID des Adapters ({@link ResourceMetadataSource#localId}), sonst das
     * letzte Pfadsegment der Ressourcen-ID ({@code confluence:host/page/123} → {@code 123}).
     */
    private String nativeId(KnowledgeResourceId id) {
        if (port instanceof ResourceMetadataSource single) {
            String local = single.localId(id);
            if (local != null && !local.isEmpty()) {
                return local;
            }
        }
        String part = id.schemeSpecificPart();
        return part.substring(part.lastIndexOf('/') + 1);
    }

    private static Partition partition(SourcePartition partition) {
        return new Partition(partition.key(), partition.title(),
                partition.homeResource() == null ? null : partition.homeResource().value());
    }

    private SourceAccessException accessFailure(KnowledgeSourceException e) {
        if (e instanceof RateLimitedException) {
            return new SourceAccessException(id, SourceAccessException.Reason.RATE_LIMITED, e.getMessage(), e);
        }
        SourceAccessException.Reason reason = switch (e.kind()) {
            case NOT_FOUND -> SourceAccessException.Reason.NOT_FOUND;
            case UNAVAILABLE -> SourceAccessException.Reason.UNAVAILABLE;
            case ACCESS_DENIED -> SourceAccessException.Reason.ACCESS_DENIED;
            case INVALID_RESPONSE -> SourceAccessException.Reason.INVALID_RESPONSE;
            case UNSUPPORTED -> SourceAccessException.Reason.UNSUPPORTED;
        };
        return new SourceAccessException(id, reason, e.getMessage(), e);
    }
}
