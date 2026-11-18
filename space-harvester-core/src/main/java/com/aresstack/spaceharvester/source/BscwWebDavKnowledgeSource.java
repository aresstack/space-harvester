package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.AttachmentListSource;
import com.aresstack.enterpriseai.source.api.StoredAttachmentList;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
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
        AttachmentListSource {

    static final String STORED_FORMAT = "file";
    private static final String FOLDER_FORMAT = "bscw-folder";
    private static final String ATTACHMENTS_FORMAT = "webdav-propfind-depth-1";
    private static final String DAV = "DAV:";
    private static final int MAX_BATCH = 500;
    private static final int NAVIGATION_BATCH = 200;
    private static final int MAX_ACTIVE_INVENTORIES = 4;
    private static final Pattern CURSOR = Pattern.compile("b1\\.([a-f0-9-]{36})\\.(\\d{1,9})");

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
        this.sourceId = sourceId;
        this.root = root;
        this.transport = transport;
        this.maxDepth = maxDepth;
        this.extractor = extractor;
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
        int start;
        if (cursor == null || cursor.isEmpty()) {
            inventory = new Inventory(UUID.randomUUID().toString(), listResources(maxDepth));
            synchronized (inventories) {
                inventories.put(inventory.id, inventory);
            }
            start = 0;
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
            start = Integer.parseInt(match.group(2));
            if (start < 1 || start >= inventory.resources.size()) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW cursor offset");
            }
        }
        int end = Math.min(inventory.resources.size(), start + Math.max(1, Math.min(limit, MAX_BATCH)));
        String next = end < inventory.resources.size() ? "b1." + inventory.id + "." + end : "";
        return new PartitionResources(inventory.resources.subList(start, end), next);
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
            URI uri = URI.create(root.getScheme() + "://" + root.getRawAuthority()
                    + "/" + value.substring(prefix.length()));
            transport.checked(uri);
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
        byte[] bytes = transport.download(uriOf(localId));
        return new StoredContent(resource, bytes, resource.contentType(), STORED_FORMAT, Instant.now());
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
        if (!isFolder(resource)) {
            if (resource.contentType().startsWith("text/") || "application/json".equals(resource.contentType())
                    || "application/xml".equals(resource.contentType())) {
                String text = load(page).text();
                return new PageView("<pre style=\"white-space:pre-wrap;overflow-wrap:anywhere\">"
                        + escapeHtml(text) + "</pre>", root.toString(), List.of());
            }
            return new PageView("<p>Originaldatei: " + escapeHtml(resource.title())
                    + "</p><p>Die Datei ist als Anhang ihres übergeordneten Ordners verfügbar.</p>",
                    root.toString(), List.of());
        }
        List<KnowledgeResource> files = attachmentsOf(page);
        StringBuilder html = new StringBuilder("<p>Dateien in diesem Ordner: ")
                .append(files.size()).append("</p><ul>");
        for (KnowledgeResource file : files) {
            html.append("<li>").append(escapeHtml(file.title())).append("</li>");
        }
        return new PageView(html.append("</ul>").toString(), root.toString(), List.of());
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

    /**
     * Map the files of a WebDAV directory to the existing page-attachment contract.
     * Return the unchanged PROPFIND response for the raw-first export pipeline.
     */
    @Override
    public StoredAttachmentList storedAttachments(String localId) throws KnowledgeSourceException {
        URI uri = uriOf(localId);
        KnowledgeResource owner = resourceMetadata(localId);
        if (!isFolder(owner)) {
            return new StoredAttachmentList(List.of(), List.of(), "application/xml",
                    ATTACHMENTS_FORMAT, Instant.now(), List.of());
        }
        byte[] raw = transport.properties(uri, 1);
        List<KnowledgeResource> files = childrenOf(uri, parseEntries(raw)).stream()
                .filter(entry -> !entry.folder)
                .map(this::resource).toList();
        if (files.size() > AttachmentListSource.MAX_ATTACHMENTS) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "BSCW attachment list exceeds the limit");
        }
        return new StoredAttachmentList(files, List.of(raw), "application/xml",
                ATTACHMENTS_FORMAT, Instant.now(), List.of());
    }

    @Override
    public List<KnowledgeResource> attachmentsOf(KnowledgeResourceId page) throws KnowledgeSourceException {
        return storedAttachments(requireLocalId(page)).attachments();
    }

    @Override
    public boolean isAttachment(KnowledgeResourceId resourceId) {
        String local = localId(resourceId);
        if (local == null) {
            return false;
        }
        try {
            return !isFolder(resourceMetadata(local));
        } catch (KnowledgeSourceException inaccessible) {
            return false;
        }
    }

    @Override
    public SourceFile loadAttachmentFile(KnowledgeResourceId attachment) throws KnowledgeSourceException {
        return loadAttachmentFile(attachment, Integer.MAX_VALUE);
    }

    @Override
    public SourceFile loadAttachmentFile(KnowledgeResourceId attachment, int maxBytes)
            throws KnowledgeSourceException {
        String local = requireLocalId(attachment);
        KnowledgeResource resource = resourceMetadata(local);
        if (isFolder(resource)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "A BSCW directory is not an attachment");
        }
        String declaredSize = resource.metadata().asMap().get("dav.contentLength");
        if (declaredSize != null) {
            try {
                if (Long.parseLong(declaredSize) > maxBytes) {
                    throw new ResponseTooLargeException("BSCW attachment exceeds the requested limit", maxBytes);
                }
            } catch (NumberFormatException invalidSize) {
                // Treat an invalid reported length as unknown, not as a truncated file.
            }
        }
        byte[] original = transport.download(uriOf(local));
        if (original.length > maxBytes) {
            throw new ResponseTooLargeException("BSCW attachment exceeds the requested limit", maxBytes);
        }
        return new SourceFile(resource, original);
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
            if (!sameResource(entry.uri, folder)) {
                parentFolders.put(entry.uri, folder);
                children.add(entry);
            }
        }
        children.sort(Comparator.comparing((DavEntry entry) -> !entry.folder)
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        return children;
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
            URI uri = transport.resolve(href);
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

    private KnowledgeResource resource(DavEntry entry) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("bscw.path", entry.uri.getRawPath());
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
        return KnowledgeResourceId.of("bscw", root.getHost().toLowerCase() + uri.getRawPath());
    }

    private URI uriOf(String localId) throws KnowledgeSourceException {
        String value = decode(localId);
        if (value == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW resource identifier");
        }
        try {
            return transport.checked(URI.create(value));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Invalid BSCW resource identifier");
        }
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
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (name.endsWith(".doc")) return "application/msword";
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

    private static final class Inventory {
        private final String id;
        private final List<KnowledgeResource> resources;

        private Inventory(String id, List<KnowledgeResource> resources) {
            this.id = id;
            this.resources = List.copyOf(resources);
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
