package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.PageViewSource;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceConnectionVerifier;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.assets.DocumentTextException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Optional;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Expose a BSCW Classic WebDAV folder as native document resources.
 * Keep directory discovery separate from document downloads.
 */
final class BscwWebDavKnowledgeSource implements KnowledgeSourcePort, PartitionListingSource,
        ResourceMetadataSource, StoredContentSource, SourceConnectionVerifier, PageNavigationSource, PageViewSource,
        ResourceNodeKindSource, SourceMediaRange, KnownTitleLookup,
        VirtualNavigationRootSource, OriginalFileSource {

    static final String STORED_FORMAT = "file";
    private static final String FOLDER_FORMAT = "bscw-folder";
    private static final String DAV = "DAV:";
    private static final int MAX_BATCH = 500;
    private static final int NAVIGATION_BATCH = 200;
    private static final int MAX_ACTIVE_INVENTORIES = 32;
    private static final Pattern CURSOR = Pattern.compile("b2\\.([a-f0-9-]{36})\\.(\\d{1,9})");

    private final SourceAddress sourceId;
    private final URI root;
    private final BscwWebDavTransport transport;
    private final int maxDepth;
    private final DocumentText extractor;
    private final Map<URI, URI> parentFolders = new ConcurrentHashMap<>();
    private final Map<URI, DavEntry> knownEntries = new ConcurrentHashMap<>();
    private final Map<String, NavigationInventory> navigationInventories =
            Collections.synchronizedMap(new LinkedHashMap<String, NavigationInventory>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, NavigationInventory> eldest) {
                    return size() > 32;
                }
            });
    private final Map<String, Inventory> inventories = Collections.synchronizedMap(
            new LinkedHashMap<String, Inventory>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Inventory> eldest) {
                    return size() > MAX_ACTIVE_INVENTORIES;
                }
            });

    BscwWebDavKnowledgeSource(SourceAddress sourceId, URI root, BscwWebDavTransport transport, int maxDepth,
                              DocumentText extractor) {
        this(sourceId, root, transport, maxDepth, extractor, DEFAULT_CHUNK_BYTES);
    }

    /** @param chunkBytes so viele Bytes holt ein Download auf die Platte je Abruf ({@code options.chunkMegabytes}) */
    BscwWebDavKnowledgeSource(SourceAddress sourceId, URI root, BscwWebDavTransport transport, int maxDepth,
                              DocumentText extractor, int chunkBytes) {
        this.sourceId = sourceId;
        this.root = normalize(root);
        this.transport = transport;
        this.maxDepth = maxDepth;
        this.extractor = extractor;
        this.chunkBytes = chunkBytes;
    }

    /** Vorgabe für {@code options.chunkMegabytes}: 8 MiB je Abruf. */
    static final int DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024;
    private final int chunkBytes;

    /**
     * Das Original in Stücken per HTTP-Range auf die Platte (Angelo, 20.11.2026), damit auch eine Datei über der Grenze
     * {@code maxFileMegabytes} und weit über dem Speicher ankommt; ignoriert BSCW die Range, einmal ganz, sofern sie
     * dafür klein genug ist. Ordner, Videos und unerwünschte Typen lädt es nicht.
     */
    @Override
    public Optional<String> downloadOriginal(String localId, java.nio.file.Path target,
                                             java.util.function.Predicate<String> wanted, long maxBytes)
            throws KnowledgeSourceException {
        KnowledgeResource resource = resourceMetadata(localId);
        if (isFolder(resource) || streamableMedia(resource.title(), resource.contentType())
                || !wanted.test(resource.contentType())) {
            return Optional.empty();
        }
        long size;
        try {
            size = Long.parseLong(resource.metadata().asMap().getOrDefault(KnowledgeMetadata.FILE_SIZE, "-1"));
        } catch (NumberFormatException unknown) {
            size = -1;
        }
        if (maxBytes > 0 && size > maxBytes) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Datei größer als die Grenze für den Textauszug");
        }
        URI uri = uriOf(localId);
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(target)) {
            long offset = 0;
            while (size < 0 || offset < size) {
                int count = (int) (size < 0 ? chunkBytes : Math.min(chunkBytes, size - offset));
                BscwWebDavTransport.Chunk answer = transport.downloadChunk(uri, offset, count);
                byte[] chunk = answer.bytes();
                out.write(chunk);
                if (answer.whole()) {
                    // BSCW ignorierte die Range: die ganze Datei in einer Antwort.
                    break;
                }
                offset += chunk.length;
                if (maxBytes > 0 && offset > maxBytes) {
                    throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                            "Datei größer als die Grenze für den Textauszug");
                }
                if (chunk.length < count) {
                    break;
                }
            }
        } catch (java.io.IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Original nicht auf die Platte geschrieben ("
                    + e.getClass().getSimpleName() + ")", e);
        }
        return Optional.of(resource.contentType());
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<SourcePartition> partitions() {
        DavEntry folder = knownEntries.get(root);
        return List.of(new SourcePartition(sourceId.value(),
                folder == null ? "BSCW-Ordner" : folder.name));
    }

    @Override
    public void verifyConnection() throws KnowledgeSourceException {
        if (entries(root, 0).stream().noneMatch(entry -> entry.folder && sameResource(entry.uri, root))) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "BSCW WebDAV root is not a folder");
        }
    }

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        if (scope.selectionApplies() && scope.excludedPartitions().contains(sourceId.value())) {
            return List.of();
        }
        List<KnowledgeResource> resources = listResources(Math.min(maxDepth, scope.maxDepth()));
        return resources.subList(0, Math.min(resources.size(), scope.maxResources()));
    }

    @Override
    public PartitionResources resources(String partitionKey, String cursor, int limit)
            throws KnowledgeSourceException {
        if (!sourceId.value().equals(partitionKey)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Unknown BSCW partition");
        }
        Inventory inventory;
        int sequence;
        if (cursor == null || cursor.isEmpty()) {
            inventory = new Inventory(UUID.randomUUID().toString());
            synchronized (inventories) {
                inventories.put(inventory.id, inventory);
            }
            sequence = 0;
        } else {
            Matcher match = CURSOR.matcher(cursor);
            if (!match.matches()) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW cursor");
            }
            synchronized (inventories) {
                inventory = inventories.get(match.group(1));
            }
            if (inventory == null) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "BSCW inventory has expired");
            }
            sequence = Integer.parseInt(match.group(2));
        }
        return inventory.next(sequence, Math.max(1, Math.min(limit, MAX_BATCH)));
    }

    /** Search only metadata already learned from browsed folders; never start a full-tree crawl. */
    @Override
    public List<KnowledgeResource> knownTitles(String fragment, int limit) {
        if (fragment == null || fragment.isBlank() || limit < 1) {
            return List.of();
        }
        String needle = fragment.toLowerCase(java.util.Locale.ROOT);
        return knownEntries.values().stream()
                .filter(entry -> entry.name.toLowerCase(java.util.Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing((DavEntry entry) -> entry.name.toLowerCase(java.util.Locale.ROOT))
                        .thenComparing(entry -> entry.uri.toASCIIString()))
                .limit(limit)
                .map(this::resource)
                .toList();
    }

    @Override
    public KnowledgeResource resourceMetadata(String localId) throws KnowledgeSourceException {
        URI uri = uriOf(localId);
        DavEntry known = knownEntries.get(uri);
        if (known != null && known.folder) {
            // Keep folder navigation lazy: a prior depth-1 listing already supplied its metadata.
            return resource(known);
        }
        for (DavEntry entry : entries(uri, 0)) {
            if (sameResource(entry.uri, uri)) {
                return resource(entry);
            }
        }
        throw new KnowledgeSourceException(Kind.NOT_FOUND, "BSCW document not found");
    }

    @Override
    public String localId(KnowledgeResourceId resourceId) {
        if (resourceId == null) {
            return null;
        }
        String prefix = "bscw:" + root.getHost().toLowerCase() + "/";
        String value = resourceId.value();
        if (!value.startsWith(prefix)) {
            return null;
        }
        try {
            URI uri = normalize(transport.checked(URI.create(root.getScheme() + "://" + root.getRawAuthority()
                    + "/" + value.substring(prefix.length()))));
            return encode(uri.toASCIIString());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public StoredContent storedContent(String localId) throws KnowledgeSourceException {
        KnowledgeResource resource = resourceMetadata(localId);
        if (isFolder(resource)) {
            return new StoredContent(resource, new byte[0], "text/plain", FOLDER_FORMAT, Instant.now());
        }
        if (streamableMedia(resource.title(), resource.contentType())) {
            // A remote-media snapshot is metadata, never counterfeit original video bytes.
            return new StoredContent(resource, new byte[0], "application/x-space-harvester-remote-media",
                    "remote-media-metadata", Instant.now());
        }
        BscwWebDavTransport.Download downloaded = transport.downloadResource(uriOf(localId));
        if (downloaded.isLink()) {
            KnowledgeResource linked = resource.toBuilder().contentType("text/uri-list")
                    .metadata(resource.metadata().with(PageMetadata.RESOURCE_KIND, PageMetadata.LINK_KIND)).build();
            return new StoredContent(linked, downloaded.bytes(), "text/uri-list", "webdav-redirect",
                    Instant.now());
        }
        return new StoredContent(resource, downloaded.bytes(), resource.contentType(), STORED_FORMAT, Instant.now());
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String localId = localId(resourceId);
        if (localId == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Resource is not from this BSCW source");
        }
        StoredContent content = storedContent(localId);
        if (isFolder(content.resource())) {
            return KnowledgeDocument.of(content.resource(), "");
        }
        if ("text/uri-list".equals(content.mediaType())) {
            return KnowledgeDocument.of(content.resource(),
                    new String(content.payload(), java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            DocumentText.Extracted extracted = extractor.extract(content.resource().title(), content.mediaType(),
                    content.payload());
            return KnowledgeDocument.of(content.resource(), extracted.text());
        } catch (DocumentTextException unsupportedFormat) {
            // Preserve arbitrary binary files without inventing a readable document.
            return KnowledgeDocument.of(content.resource(), "");
        }
    }

    @Override
    public PageView pageView(KnowledgeResourceId page) throws KnowledgeSourceException {
        String local = requireLocalId(page);
        KnowledgeResource resource = resourceMetadata(local);
        URI uri = uriOf(local);
        String pageAddress = uri.toASCIIString();
        if (!isFolder(resource)) {
            if (resource.contentType().startsWith("text/") || "application/json".equals(resource.contentType())
                    || "application/xml".equals(resource.contentType())) {
                String text = load(page).text();
                return new PageView("<pre style=\"white-space:pre-wrap;overflow-wrap:anywhere\">"
                        + escapeHtml(text) + "</pre>", pageAddress, List.of());
            }
            return new PageView("<p>Originaldatei: " + escapeHtml(resource.title())
                    + "</p><p>Die Originaldatei ist der Inhalt dieser Seite.</p>",
                    pageAddress, List.of());
        }
        List<DavEntry> children = directChildren(uri);
        StringBuilder html = new StringBuilder("<p>Einträge in diesem Ordner: ")
                .append(children.size()).append("</p><ul>");
        for (DavEntry child : children) {
            html.append("<li>").append(escapeHtml(child.name)).append("</li>");
        }
        return new PageView(html.append("</ul>").toString(), pageAddress, List.of());
    }

    private static boolean isFolder(KnowledgeResource resource) {
        return "inode/directory".equals(resource.contentType());
    }

    private String requireLocalId(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String local = localId(resourceId);
        if (local == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Resource is not from this BSCW source");
        }
        return local;
    }

    @Override
    public byte[] loadViewImage(String src) throws KnowledgeSourceException {
        throw new KnowledgeSourceException(Kind.UNSUPPORTED, "BSCW WebDAV does not render inline images");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Return no synthetic attachments: the BSCW file page already owns the original bytes. */
    @Override
    public List<KnowledgeResource> attachmentsOf(KnowledgeResourceId page) throws KnowledgeSourceException {
        requireLocalId(page);
        return List.of();
    }

    @Override
    public NavigationChildren children(NavigationNode parent, String cursor) throws KnowledgeSourceException {
        URI folder = parent.isRoot() ? root : uriOf(localId(parent.resource()));
        NavigationInventory inventory;
        int offset;
        if (cursor == null || cursor.isEmpty()) {
            List<DavEntry> entries = directChildren(folder);
            List<NavigationNode> nodes = new ArrayList<>(entries.size());
            for (DavEntry entry : entries) {
                nodes.add(NavigationNode.page(idOf(entry.uri), entry.name,
                        entry.folder ? NavigationNode.Children.YES : NavigationNode.Children.NO));
            }
            inventory = new NavigationInventory(UUID.randomUUID().toString(), folder, List.copyOf(nodes));
            synchronized (navigationInventories) {
                navigationInventories.put(inventory.id, inventory);
            }
            offset = 0;
        } else {
            String[] fields = cursor.split(":", -1);
            if (fields.length != 2) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW navigation cursor");
            }
            synchronized (navigationInventories) {
                inventory = navigationInventories.get(fields[0]);
            }
            if (inventory == null || !sameResource(inventory.folder, folder)) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "BSCW navigation cursor expired");
            }
            try {
                offset = Integer.parseInt(fields[1]);
            } catch (NumberFormatException error) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW navigation offset");
            }
            if (offset < 1 || offset >= inventory.nodes.size()) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "BSCW navigation offset out of range");
            }
        }
        int end = Math.min(inventory.nodes.size(), offset + NAVIGATION_BATCH);
        String next = end < inventory.nodes.size() ? inventory.id + ":" + end : "";
        return new NavigationChildren(inventory.nodes.subList(offset, end), next);
    }

    @Override
    public PagePosition position(KnowledgeResourceId page) throws KnowledgeSourceException {
        String local = localId(page);
        if (local == null) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "BSCW resource not found");
        }
        URI uri = uriOf(local);
        DavEntry entry = entryOf(uri);
        List<NavigationNode> ancestors = new ArrayList<>();
        Set<URI> visited = new HashSet<>();
        URI parent = parentFolders.get(uri);
        while (parent != null && !sameResource(parent, root) && visited.add(parent)) {
            DavEntry ancestor = entryOf(parent);
            ancestors.add(NavigationNode.page(idOf(ancestor.uri), ancestor.name, NavigationNode.Children.YES));
            parent = parentFolders.get(parent);
        }
        Collections.reverse(ancestors);
        NavigationNode node = NavigationNode.page(page, entry.name,
                entry.folder ? NavigationNode.Children.YES : NavigationNode.Children.NO);
        return new PagePosition(NavigationNode.root("space:" + sourceId.value(), sourceId.value(),
                sourceId.value(), NavigationNode.Children.YES), ancestors, node);
    }

    /** Determine the navigation symbol from the native WebDAV collection property. */
    @Override
    public HarvestSource.PageNode.Kind kind(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        DavEntry resource = entryOf(uriOf(requireLocalId(resourceId)));
        return resource.folder ? HarvestSource.PageNode.Kind.FOLDER : HarvestSource.PageNode.Kind.FILE;
    }

    @Override
    public CrossReferences crossReferences(KnowledgeResourceId page) {
        return CrossReferences.outgoingOnly(List.of());
    }

    private DavEntry entryOf(URI uri) throws KnowledgeSourceException {
        DavEntry known = knownEntries.get(uri);
        if (known != null) {
            return known;
        }
        for (DavEntry entry : entries(uri, 0)) {
            if (sameResource(entry.uri, uri)) {
                return entry;
            }
        }
        throw new KnowledgeSourceException(Kind.NOT_FOUND, "BSCW resource metadata unavailable");
    }

    private List<DavEntry> directChildren(URI folder) throws KnowledgeSourceException {
        return childrenOf(folder, entries(folder, 1));
    }

    private List<DavEntry> childrenOf(URI folder, List<DavEntry> listed) {
        List<DavEntry> children = new ArrayList<>();
        for (DavEntry entry : listed) {
            if (!sameResource(entry.uri, folder) && !isSystemSidecar(entry.name)) {
                parentFolders.put(entry.uri, folder);
                children.add(entry);
            }
        }
        children.sort(Comparator.comparing((DavEntry entry) -> !entry.folder)
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        return children;
    }

    private static boolean isSystemSidecar(String name) {
        return ".DS_Store".equalsIgnoreCase(name) || name.startsWith("._");
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
        return List.of();
    }

    private static boolean sameResource(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && first.getPort() == second.getPort()
                && first.getRawPath().replaceAll("/+$", "").equals(second.getRawPath().replaceAll("/+$", ""));
    }

    private List<KnowledgeResource> listResources(int depthLimit) throws KnowledgeSourceException {
        ArrayDeque<Folder> pending = new ArrayDeque<>();
        Set<URI> visited = new HashSet<>();
        Map<String, KnowledgeResource> documents = new LinkedHashMap<>();
        pending.add(new Folder(root, 0));
        while (!pending.isEmpty()) {
            Folder folder = pending.removeFirst();
            if (!visited.add(folder.uri)) {
                continue;
            }
            DavEntry current = entryOf(folder.uri);
            documents.putIfAbsent(idOf(current.uri).value(), resource(current));
            for (DavEntry entry : directChildren(folder.uri)) {
                KnowledgeResource child = resource(entry);
                documents.putIfAbsent(child.id().value(), child);
                if (entry.folder && folder.depth < depthLimit) {
                    pending.addLast(new Folder(entry.uri, folder.depth + 1));
                }
            }
        }
        List<KnowledgeResource> result = new ArrayList<>(documents.values());
        result.sort(Comparator.comparing(resource -> resource.id().value()));
        return result;
    }

    private List<DavEntry> entries(URI folder, int depth) throws KnowledgeSourceException {
        return parseEntries(transport.properties(folder, depth));
    }

    private List<DavEntry> parseEntries(byte[] body) throws KnowledgeSourceException {
        Document document = parse(body);
        NodeList responses = document.getElementsByTagNameNS(DAV, "response");
        List<DavEntry> entries = new ArrayList<>();
        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);
            String href = firstText(response, "href");
            Element prop = successfulProperties(response);
            if (href == null || prop == null) {
                continue;
            }
            URI uri = normalize(transport.resolve(href));
            String name = firstText(prop, "displayname");
            String displayName = name == null || name.isBlank() ? filename(uri) : name;
            Element type = firstElement(prop, "resourcetype");
            boolean folderEntry = type != null && type.getElementsByTagNameNS(DAV, "collection").getLength() > 0;
            String etag = firstText(prop, "getetag");
            String date = firstText(prop, "getlastmodified");
            String length = firstText(prop, "getcontentlength");
            String mediaType = firstText(prop, "getcontenttype");
            String inferred = mimeType(displayName);
            if ("text/csv".equals(inferred) || "text/tab-separated-values".equals(inferred)
                    || mediaType == null || mediaType.isBlank()
                    || "application/octet-stream".equalsIgnoreCase(mediaType.trim())) {
                mediaType = inferred;
            }
            DavEntry entry = new DavEntry(uri, displayName, folderEntry, etag, parseDate(date), length,
                    folderEntry ? "inode/directory" : mediaType);
            knownEntries.put(uri, entry);
            entries.add(entry);
        }
        return entries;
    }

    @Override
    public byte[] readRange(String localId, long offset, int maximumBytes) throws KnowledgeSourceException {
        if (offset < 0 || maximumBytes < 1 || maximumBytes > 262144) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.INVALID_RESPONSE,
                    "Invalid BSCW media range");
        }
        URI uri = uriOf(localId);
        DavEntry known = knownEntries.get(uri);
        KnowledgeResource resource = known == null ? resourceMetadata(localId) : resource(known);
        if (!streamableMedia(resource.title(), resource.contentType())) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Kind.UNSUPPORTED,
                    "Not a streamable BSCW media resource");
        }
        return transport.downloadRange(uri, offset, maximumBytes);
    }

    private static boolean streamableMedia(String fileName, String mediaType) {
        return streamableMediaType(fileName, mediaType) != null;
    }

    /**
     * Video type of a file, from its name or, when the name has no known extension (BSCW titles often have none),
     * from the content type the server reports; {@code null} for anything else.
     */
    static String streamableMediaType(String fileName, String mediaType) {
        return VideoFileTypes.streamableType(fileName, mediaType);
    }

    private KnowledgeResource resource(DavEntry entry) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("bscw.path", entry.uri.getRawPath());
        attributes.put(PageMetadata.RESOURCE_KIND, entry.folder ? PageMetadata.FOLDER_KIND : PageMetadata.FILE_KIND);
        String remoteType = entry.folder ? null : streamableMediaType(entry.name, entry.mediaType);
        if (remoteType != null) {
            attributes.put(PageMetadata.REMOTE_MEDIA, "true");
            attributes.put(PageMetadata.REMOTE_MEDIA_TYPE, remoteType);
        }
        if (entry.etag != null) {
            attributes.put("dav.etag", entry.etag);
        }
        if (entry.length != null) {
            attributes.put("dav.contentLength", entry.length);
            if (!entry.folder) {
                attributes.put(KnowledgeMetadata.FILE_SIZE, entry.length);
            }
        }
        KnowledgeResource.Builder builder = KnowledgeResource.builder(idOf(entry.uri), sourceId)
                .title(entry.name).contentType(entry.mediaType)
                .revision(KnowledgeRevision.of(entry.modified, entry.etag))
                .scope(sourceId.value()).location(entry.uri).metadata(KnowledgeMetadata.of(attributes));
        URI parent = parentFolders.get(entry.uri);
        if (parent != null) {
            builder.parentId(idOf(parent));
        }
        return builder.build();
    }

    private KnowledgeResourceId idOf(URI uri) {
        return KnowledgeResourceId.of("bscw", root.getHost().toLowerCase() + normalize(uri).getRawPath());
    }

    private URI uriOf(String localId) throws KnowledgeSourceException {
        String value = decode(localId);
        if (value == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW resource identifier");
        }
        try {
            return normalize(transport.checked(URI.create(value)));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW resource identifier");
        }
    }

    static URI normalize(URI uri) {
        java.util.Objects.requireNonNull(uri, "uri");
        URI ascii = URI.create(uri.toASCIIString());
        String path = ascii.getRawPath();
        if (path == null || path.length() <= 1 || !path.endsWith("/")) {
            return ascii;
        }
        int trailingSlashes = 0;
        while (trailingSlashes < path.length() - 1
                && path.charAt(path.length() - trailingSlashes - 1) == '/') {
            trailingSlashes++;
        }
        String address = ascii.toASCIIString();
        int suffixLength = (ascii.getRawQuery() == null ? 0 : ascii.getRawQuery().length() + 1)
                + (ascii.getRawFragment() == null ? 0 : ascii.getRawFragment().length() + 1);
        int pathEnd = address.length() - suffixLength;
        return URI.create(address.substring(0, pathEnd - trailingSlashes) + address.substring(pathEnd));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return value.equals(encode(text)) ? text : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Document parse(byte[] bytes) throws KnowledgeSourceException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException | java.io.IOException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Malformed BSCW WebDAV response");
        }
    }

    private static Element successfulProperties(Element response) {
        NodeList stats = response.getElementsByTagNameNS(DAV, "propstat");
        for (int i = 0; i < stats.getLength(); i++) {
            Element stat = (Element) stats.item(i);
            String status = firstText(stat, "status");
            if (status != null && status.contains(" 200 ")) {
                return firstElement(stat, "prop");
            }
        }
        return null;
    }

    private static Element firstElement(Element element, String localName) {
        NodeList matches = element.getElementsByTagNameNS(DAV, localName);
        return matches.getLength() == 0 ? null : (Element) matches.item(0);
    }

    private static String firstText(Element element, String localName) {
        Element child = firstElement(element, localName);
        if (child == null) {
            return null;
        }
        String value = child.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant parseDate(String value) {
        try {
            return value == null ? null : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String filename(URI uri) {
        String path = uri.getPath().replaceAll("/+$", "");
        int separator = path.lastIndexOf('/');
        return path.substring(separator + 1);
    }

    private static String mimeType(String displayName) {
        String name = displayName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".tsv")) return "text/tab-separated-values";
        if (name.endsWith(".log")) return "text/plain";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".one")) return OneNoteContentDetector.MIME;
        if (name.endsWith(".txt") || name.endsWith(".md")) return "text/plain";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private static final class NavigationInventory {
        private final String id;
        private final URI folder;
        private final List<NavigationNode> nodes;

        private NavigationInventory(String id, URI folder, List<NavigationNode> nodes) {
            this.id = id;
            this.folder = folder;
            this.nodes = nodes;
        }
    }

    /**
     * One folder's PROPFIND per page. The queued state is private to this source instance.
     * Evicted or expired cursors fail closed, so incomplete inventories can never confirm deletions.
     */
    private final class Inventory {
        private final String id;
        private final ArrayDeque<Folder> pendingFolders = new ArrayDeque<>();
        private final ArrayDeque<KnowledgeResource> ready = new ArrayDeque<>();
        private final Set<URI> visitedFolders = new HashSet<>();
        private final Set<String> emittedResources = new HashSet<>();
        private int nextSequence;
        private int lastSequence = -1;
        private int lastLimit;
        private PartitionResources lastPage;

        private Inventory(String id) {
            this.id = id;
            pendingFolders.add(new Folder(root, 0));
        }

        private synchronized PartitionResources next(int sequence, int limit) throws KnowledgeSourceException {
            if (sequence == lastSequence && limit == lastLimit && lastPage != null) {
                return lastPage;
            }
            if (sequence != nextSequence) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW inventory cursor sequence");
            }
            // Do not traverse the complete tree before publishing the first page.
            // Skip empty folders, but never block one request on an unbounded number of PROPFINDs.
            int scanned = 0;
            while (ready.isEmpty() && !pendingFolders.isEmpty() && scanned < 4) {
                Folder folder = pendingFolders.peekFirst();
                if (visitedFolders.contains(folder.uri)) {
                    pendingFolders.removeFirst();
                    continue;
                }
                DavEntry current = entryOf(folder.uri);
                List<DavEntry> children = directChildren(folder.uri);
                pendingFolders.removeFirst();
                visitedFolders.add(folder.uri);
                offer(resource(current));
                for (DavEntry child : children) {
                    offer(resource(child));
                    if (child.folder && folder.depth < maxDepth && !visitedFolders.contains(child.uri)) {
                        pendingFolders.addLast(new Folder(child.uri, folder.depth + 1));
                    }
                }
                scanned++;
            }
            List<KnowledgeResource> batch = new ArrayList<>(Math.min(limit, ready.size()));
            while (batch.size() < limit && !ready.isEmpty()) {
                batch.add(ready.removeFirst());
            }
            nextSequence++;
            String cursor = ready.isEmpty() && pendingFolders.isEmpty()
                    ? "" : "b2." + id + "." + nextSequence;
            lastSequence = sequence;
            lastLimit = limit;
            lastPage = new PartitionResources(batch, cursor);
            return lastPage;
        }

        private void offer(KnowledgeResource resource) {
            if (emittedResources.add(resource.id().value())) {
                ready.addLast(resource);
            }
        }
    }

    private static final class Folder {
        private final URI uri;
        private final int depth;

        private Folder(URI uri, int depth) {
            this.uri = uri;
            this.depth = depth;
        }
    }

    private static final class DavEntry {
        private final URI uri;
        private final String name;
        private final boolean folder;
        private final String etag;
        private final Instant modified;
        private final String length;
        private final String mediaType;

        private DavEntry(URI uri, String name, boolean folder, String etag, Instant modified,
                         String length, String mediaType) {
            this.uri = uri;
            this.name = name;
            this.folder = folder;
            this.etag = etag;
            this.modified = modified;
            this.length = length;
            this.mediaType = mediaType;
        }
    }
}
