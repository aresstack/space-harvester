package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.AddressableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.AsyncStoredContentSource;
import com.aresstack.enterpriseai.source.api.AttachmentListSource;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.OneTimeCodeChallenge;
import com.aresstack.enterpriseai.source.api.OneTimeCodeRequiredException;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.ChangeListingSource;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.StoredAttachmentList;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.source.confluence.InFlightLimit;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** {@link HarvestSource} über einen {@link KnowledgeSourcePort} aus {@code enterprise-ai-sources}. */
final class KnowledgeSourceHarvestSource implements HarvestSource {

    /** Schema der öffentlichen Ressourcen-ID von Dateiquellen. */
    static final String FILES_SCHEME = "files";

    /** Schlüssel der Wurzel einer Partition im Enterprise AI Client ({@code space:DEV}). */
    private static final String SPACE_NODE = "space:";
    /** Präfix des Cursors der Kindseiten; versioniert das Format. */
    private static final String CURSOR_PREFIX = "c1.";

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

    /** Lokale Dateien: Eine fehlende Datei in einem lesbaren Verzeichnis ist gelöscht (ADR 0014). */
    @Override
    public boolean confirmsDeletion() {
        return LocalFilesSourceAdapter.TYPE.equals(type);
    }

    @Override
    public Set<SourceCapability> capabilities() {
        Set<SourceCapability> capabilities = EnumSet.of(SourceCapability.PARTITIONS);
        if (port instanceof PartitionListingSource) {
            capabilities.add(SourceCapability.PAGE_LISTING);
        }
        if (port instanceof ChangeListingSource) {
            capabilities.add(SourceCapability.CHANGES);
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
        if (port instanceof AttachmentListSource) {
            capabilities.add(SourceCapability.PAGE_ATTACHMENTS);
        }
        if (port instanceof PageNavigationSource && port instanceof ResourceMetadataSource) {
            capabilities.add(SourceCapability.NAVIGATION);
        }
        if (port instanceof SearchableKnowledgeSource) {
            capabilities.add(SourceCapability.LIVE_SEARCH);
        }
        if (port instanceof AddressableKnowledgeSource || port instanceof ResourceMetadataSource
                || LocalFilesSourceAdapter.TYPE.equals(type)) {
            capabilities.add(SourceCapability.ADDRESSES);
        }
        return capabilities;
    }

    @Override
    public List<SourceSearchMatch> searchPages(String text, int limit) {
        return searchPages(text, limit, SearchSpaces.all());
    }

    /**
     * Der Space-Filter geht in die Anfrage der Quelle: eine Auswahl als {@code space in (…)}
     * ({@link SourceQuery#withIncludedPartitions}), ein Ausschluss wie im Enterprise AI Client als
     * {@code space not in (…)} ({@link SourceQuery#withExcludedPartitions}); beides ohne Obergrenze der Spaces.
     */
    @Override
    public List<SourceSearchMatch> searchPages(String text, int limit, SearchSpaces spaces) {
        if (!(port instanceof SearchableKnowledgeSource searchable)) {
            return HarvestSource.super.searchPages(text, limit);
        }
        List<SourceSearchHit> hits;
        try {
            hits = searchable.search(new SourceQuery(text, limit).withIncludedPartitions(spaces.only())
                    .withExcludedPartitions(spaces.excluded()));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        Map<String, SourceSearchMatch> matches = new LinkedHashMap<>();
        for (SourceSearchHit hit : hits) {
            String version = hit.revision().version();
            matches.putIfAbsent(nativeId(hit.resourceId()), new SourceSearchMatch(nativeId(hit.resourceId()),
                    hit.title(), hit.snippet(), hit.url(), version == null || version.isEmpty() ? null : version,
                    hit.revision().modifiedAt().orElse(null)));
        }
        return List.copyOf(matches.values());
    }

    @Override
    public boolean addressable(String address) {
        String value = address == null ? "" : address.trim();
        return !value.isEmpty() && (byResourceId(value).isPresent() || pattern(value) != null);
    }

    /**
     * Zuerst Ressourcen-IDs und Bookmarks der Quelle ({@code confluence:<Ort>/page/123},
     * {@code confluence://<Ort>/page/123}, bei Dateiquellen {@code files:<Quelle>/<Seiten-ID>}), dann die
     * Adressauflösung der Quelle selbst ({@link AddressableKnowledgeSource}, im Enterprise AI Client dieselbe).
     * Unbekannte und für das Konto gesperrte Seiten sind leer, nicht ein Fehler.
     */
    @Override
    public Optional<String> resolveAddress(String address) {
        String value = address == null ? "" : address.trim();
        Optional<String> byId = byResourceId(value);
        if (byId.isPresent()) {
            return byId;
        }
        AddressableKnowledgeSource addressable = pattern(value);
        if (addressable == null) {
            return Optional.empty();
        }
        KnowledgeResourceId resolved;
        try {
            resolved = addressable.resolveAddress(value);
        } catch (KnowledgeSourceException e) {
            if (e.kind() == KnowledgeSourceException.Kind.NOT_FOUND
                    || e.kind() == KnowledgeSourceException.Kind.ACCESS_DENIED) {
                return Optional.empty();
            }
            throw accessFailure(e);
        }
        return resolved == null ? Optional.empty() : Optional.of(nativeId(resolved));
    }

    /** Die Quelle, wenn ihr Adressmuster auf die ganze Eingabe passt; sonst {@code null}. */
    private AddressableKnowledgeSource pattern(String value) {
        if (value.isEmpty() || !(port instanceof AddressableKnowledgeSource addressable)) {
            return null;
        }
        Pattern pattern = addressable.addressPattern();
        return pattern != null && pattern.matcher(value).matches() ? addressable : null;
    }

    private Optional<String> byResourceId(String value) {
        if (LocalFilesSourceAdapter.TYPE.equals(type)) {
            String prefix = FILES_SCHEME + ":" + id + "/";
            boolean own = value.startsWith(prefix) && value.length() > prefix.length()
                    && value.indexOf('/', prefix.length()) < 0;
            return own ? Optional.of(value.substring(prefix.length())) : Optional.empty();
        }
        if (!(port instanceof ResourceMetadataSource single)) {
            return Optional.empty();
        }
        KnowledgeResourceId resourceId;
        try {
            // Ein Bookmark hat nach dem Schema immer //, eine Ressourcen-ID nie (KnowledgeBookmarkIds).
            int separator = value.indexOf("://");
            resourceId = separator > 0
                    ? KnowledgeResourceId.of(value.substring(0, separator), value.substring(separator + 3))
                    : KnowledgeResourceId.of(value);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String local = single.localId(resourceId);
        return local == null || local.isEmpty() ? Optional.empty() : Optional.of(local);
    }

    @Override
    public List<Partition> partitions() {
        try {
            return port.partitions().stream().map(this::partition).toList();
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
    public boolean listsChanges() {
        return port instanceof ChangeListingSource;
    }

    @Override
    public PageMetadataBatch changedPageMetadata(String partitionKey, java.time.Duration within, String cursor,
                                                 int limit) {
        ChangeListingSource changes = changes();
        PartitionResources batch;
        try {
            batch = changes.changedResources(partitionKey, within, cursor == null ? "" : cursor, limit);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return new PageMetadataBatch(batch.resources().stream()
                .map(resource -> pageMetadata(resource, partitionKey)).toList(), batch.nextCursor());
    }

    @Override
    public PageMetadataBatch removedPageMetadata(String partitionKey, String cursor, int limit) {
        ChangeListingSource changes = changes();
        PartitionResources batch;
        try {
            batch = changes.removedResources(partitionKey, cursor == null ? "" : cursor, limit);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return new PageMetadataBatch(batch.resources().stream()
                .map(resource -> pageMetadata(resource, partitionKey)).toList(), batch.nextCursor());
    }

    private ChangeListingSource changes() {
        if (!(port instanceof ChangeListingSource changes)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED,
                    "Quelle meldet keine Änderungen", null);
        }
        return changes;
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

    @Override
    public PageAttachments attachments(String pageId) {
        if (!(port instanceof AttachmentListSource listing)) {
            return HarvestSource.super.attachments(pageId);
        }
        StoredAttachmentList list;
        try {
            list = listing.storedAttachments(pageId);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return new PageAttachments(pageId, list.attachments().stream().map(KnowledgeSourceHarvestSource::attachment)
                .toList(), list.payloads(), list.mediaType(), list.nativeFormat(), list.warnings(), list.retrievedAt());
    }

    @Override
    public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
        if (!(port instanceof PageViewSource files)) {
            return HarvestSource.super.attachmentFile(attachment, maxBytes);
        }
        KnowledgeResourceId resourceId;
        try {
            resourceId = KnowledgeResourceId.of(attachment.resourceId());
        } catch (IllegalArgumentException e) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED, "Keine Ressourcen-ID", null);
        }
        if (!files.isAttachment(resourceId)) {
            throw new SourceAccessException(id, SourceAccessException.Reason.UNSUPPORTED, "Kein Anhang der Quelle",
                    null);
        }
        SourceFile file;
        try {
            // Die Grenze gilt schon beim Lesen im Transport, nicht erst nach dem vollständigen Download.
            file = files.loadAttachmentFile(resourceId, (int) Math.min(maxBytes, Integer.MAX_VALUE));
        } catch (ResponseTooLargeException e) {
            throw new AttachmentTooLargeException(id, e.maxBytes());
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        PageAttachment described = attachment(file.resource());
        if (!described.attachmentId().equals(attachment.attachmentId())) {
            throw new SourceAccessException(id, SourceAccessException.Reason.INVALID_RESPONSE,
                    "Download gehört zu einem anderen Anhang", null);
        }
        return new AttachmentFile(described, file.content(), Instant.now());
    }

    @Override
    public List<ViewImage> viewImages(String pageId) {
        if (!(port instanceof PageViewSource views) || !(port instanceof ResourceMetadataSource single)) {
            return HarvestSource.super.viewImages(pageId);
        }
        PageView view;
        try {
            view = views.pageView(single.resourceMetadata(pageId).id());
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        return images(view);
    }

    private static List<ViewImage> images(PageView view) {
        return view.images().stream().map(image -> {
            KnowledgeResource resource = image.resource();
            String version = resource == null ? null : resource.revision().version();
            return new ViewImage(image.src(), image.title(), resource == null ? null : lastSegment(resource.id()),
                    resource == null ? null : resource.contentType(),
                    version == null || version.isEmpty() ? null : version);
        }).toList();
    }

    /**
     * Ansicht ({@link PageViewSource#pageView}, bei Confluence {@code body.export_view}) und Lesetext
     * ({@link KnowledgeSourcePort#load}, bei Confluence {@code body.view}) wie im Enterprise AI Client. Die Version
     * nennt die Ansicht selbst nicht; sie gilt nur, wenn Metadaten vorher und Lesetext danach dieselbe nennen.
     */
    @Override
    public ReadingPage readingPage(String pageId) {
        if (!(port instanceof PageViewSource views) || !(port instanceof ResourceMetadataSource single)) {
            return HarvestSource.super.readingPage(pageId);
        }
        try {
            KnowledgeResource before = single.resourceMetadata(pageId);
            PageView view = views.pageView(before.id());
            KnowledgeDocument document = port.load(before.id());
            String version = before.revision().version();
            boolean same = version != null && !version.isEmpty()
                    && version.equals(document.resource().revision().version());
            return new ReadingPage(same ? version : null, view.html(), view.baseUri(), document.text(), images(view));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    /**
     * Wie im Enterprise AI Client ({@link PageNavigationSource}): Kinder in Confluence-Reihenfolge mit
     * {@code hasChildren}; der Cursor ist der Folgelink der Quelle, undurchsichtig kodiert und an den Knoten gebunden.
     */
    @Override
    public ChildPages children(String partitionKey, String parentPageId, String cursor) {
        if (!(port instanceof PageNavigationSource navigation)) {
            return HarvestSource.super.children(partitionKey, parentPageId, cursor);
        }
        try {
            NavigationNode parent;
            String partition;
            if (parentPageId == null) {
                partition = Objects.requireNonNull(partitionKey, "partitionKey");
                parent = NavigationNode.root(SPACE_NODE + partition, partition, SPACE_NODE + partition,
                        NavigationNode.Children.UNKNOWN);
            } else {
                KnowledgeResource page = resource(parentPageId);
                partition = withSpace(page).partitionKey();
                parent = NavigationNode.page(page.id(), null, NavigationNode.Children.UNKNOWN);
            }
            NavigationChildren batch = navigation.children(parent, decodeCursor(cursor));
            return new ChildPages(partition, batch.nodes().stream().map(node -> node(node, partition)).toList(),
                    batch.complete() ? null : encodeCursor(batch.nextCursor()));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    @Override
    public PagePosition position(String pageId) {
        if (!(port instanceof PageNavigationSource navigation)) {
            return HarvestSource.super.position(pageId);
        }
        try {
            com.aresstack.enterpriseai.source.api.PagePosition position = navigation.position(resource(pageId).id());
            String partition = position.root().key().startsWith(SPACE_NODE)
                    ? position.root().key().substring(SPACE_NODE.length()) : position.root().key();
            return new PagePosition(partition, position.root().title(),
                    position.ancestors().stream().map(node -> node(node, partition)).toList(),
                    node(position.page(), partition));
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    @Override
    public PageReferences references(String pageId) {
        if (!(port instanceof PageNavigationSource navigation)) {
            return HarvestSource.super.references(pageId);
        }
        try {
            CrossReferences references = navigation.crossReferences(resource(pageId).id());
            return new PageReferences(links(references.outgoing()),
                    references.incomingSupported() ? links(references.incoming()) : null);
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
    }

    private KnowledgeResource resource(String pageId) throws KnowledgeSourceException {
        if (!(port instanceof ResourceMetadataSource single)) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED,
                    "Quelle kann Seiten nicht über ihre ID finden");
        }
        return single.resourceMetadata(pageId);
    }

    private PageNode node(NavigationNode node, String partitionKey) {
        Boolean children = switch (node.children()) {
            case YES -> Boolean.TRUE;
            case NO -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
        return new PageNode(nativeId(node.resource()), node.title(), partitionKey, children);
    }

    private List<PageNode> links(List<SourceLink> links) {
        return links.stream().map(link -> new PageNode(nativeId(link.target()), link.label(), null, null)).toList();
    }

    private static String encodeCursor(String next) {
        return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                next.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return "";
        }
        try {
            if (cursor.startsWith(CURSOR_PREFIX)) {
                return new String(Base64.getUrlDecoder().decode(cursor.substring(CURSOR_PREFIX.length())),
                        StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) {
            // unten: ungültig
        }
        throw new InvalidQueryException("Cursor gehört nicht zu diesem Knoten");
    }

    @Override
    public byte[] viewImage(ViewImage image, long maxBytes) {
        if (!(port instanceof PageViewSource views)) {
            return HarvestSource.super.viewImage(image, maxBytes);
        }
        byte[] bytes;
        try {
            bytes = views.loadViewImage(image.src());
        } catch (ResponseTooLargeException e) {
            throw new AttachmentTooLargeException(id, e.maxBytes());
        } catch (KnowledgeSourceException e) {
            throw accessFailure(e);
        }
        if (bytes.length > maxBytes) {
            throw new AttachmentTooLargeException(id, maxBytes);
        }
        return bytes;
    }

    private static PageAttachment attachment(KnowledgeResource resource) {
        String size = resource.metadata().asMap().get(KnowledgeMetadata.FILE_SIZE);
        String version = resource.revision().version();
        return new PageAttachment(lastSegment(resource.id()), resource.id().value(), resource.title(),
                resource.contentType(), size == null ? null : Long.valueOf(size),
                version == null || version.isEmpty() ? null : version, resource.revision().modifiedAt().orElse(null),
                resource.location().orElse(null));
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
                publicResourceId(resource.id()),
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
        return lastSegment(id);
    }

    /** Letzter Pfadteil der Ressourcen-ID, bei Confluence die Content-ID (Seiten und Anhänge). */
    private static String lastSegment(KnowledgeResourceId id) {
        String part = id.schemeSpecificPart();
        return part.substring(part.lastIndexOf('/') + 1);
    }

    private Partition partition(SourcePartition partition) {
        return new Partition(partition.key(), partition.title(),
                partition.homeResource() == null ? null : publicResourceId(partition.homeResource()));
    }

    /**
     * Die Ressourcen-ID, wie sie die API zeigt. Die Dateiquelle bildet ihre ID upstream aus dem Verzeichnis auf dem
     * Server ({@code file:/srv/handbuch/start.md}); nach außen gilt stattdessen {@code files:<Quelle>/<Seiten-ID>}
     * (#90), undurchsichtig und so stabil wie die Seiten-ID (der relative Pfad als Base64url). Zurückgelesen wird die
     * Ressourcen-ID einer Seite nie, aufgelöst wird immer über die Seiten-ID.
     */
    private String publicResourceId(KnowledgeResourceId resourceId) {
        return LocalFilesSourceAdapter.TYPE.equals(type) ? FILES_SCHEME + ":" + id + "/" + nativeId(resourceId)
                : resourceId.value();
    }

    private SourceAccessException accessFailure(KnowledgeSourceException e) {
        if (e instanceof OneTimeCodeRequiredException required) {
            // ADR 0018: die Einmalcode-Abfrage des Adapters geht an den Auftrag, nicht als bloße Verweigerung.
            return new SignInRequiredException(id, e.getMessage(), new Challenge(required.challenge()), e);
        }
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
        if (LocalFilesSourceAdapter.TYPE.equals(type)) {
            // Meldungen der Dateiquelle nennen Pfade auf dem Server; sie reichen bis in Sync-Ergebnisse und Logs.
            return new SourceAccessException(id, reason, "Dateiquelle: " + reason, null);
        }
        return new SourceAccessException(id, reason, e.getMessage(), e);
    }

    /** Die Einmalcode-Abfrage des Adapters als Abfrage des Core; Sitzung und Formular bleiben im Adapter. */
    private final class Challenge implements SignInChallenge {

        private final OneTimeCodeChallenge challenge;

        Challenge(OneTimeCodeChallenge challenge) {
            this.challenge = challenge;
        }

        @Override
        public String service() {
            return challenge.service();
        }

        @Override
        public String title() {
            return challenge.title();
        }

        @Override
        public String message() {
            return challenge.message();
        }

        @Override
        public String error() {
            return challenge.error();
        }

        @Override
        public boolean resendAvailable() {
            return challenge.resendAvailable();
        }

        @Override
        public SignInChallenge submit(char[] code) {
            try {
                return next(challenge.submit(code));
            } catch (KnowledgeSourceException e) {
                throw accessFailure(e);
            }
        }

        @Override
        public SignInChallenge resend() {
            try {
                return next(challenge.resend());
            } catch (KnowledgeSourceException e) {
                throw accessFailure(e);
            }
        }

        private SignInChallenge next(OneTimeCodeChallenge again) {
            return again == null ? null : new Challenge(again);
        }
    }
}
