package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
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
        ResourceMetadataSource, StoredContentSource {

    static final String STORED_FORMAT = "file";
    private static final String DAV = "DAV:";
    private static final int MAX_BATCH = 500;
    private static final int MAX_ACTIVE_INVENTORIES = 4;
    private static final Pattern CURSOR = Pattern.compile("b1\\.([a-f0-9-]{36})\\.(\\d{1,9})");

    private final SourceAddress sourceId;
    private final URI root;
    private final BscwWebDavTransport transport;
    private final int maxDepth;
    private final DocumentText extractor;
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
        return List.of(new SourcePartition(sourceId.value(), sourceId.value()));
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
        for (DavEntry entry : entries(uri, 0)) {
            if (entry.uri.equals(uri) && !entry.folder) {
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
        String text;
        try {
            DocumentText.Extracted extracted = extractor.extract(content.resource().title(), content.mediaType(),
                    content.payload());
            text = extracted.text();
        } catch (DocumentTextException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "BSCW document extraction failed");
        }
        return KnowledgeDocument.of(content.resource(), text);
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) {
        return List.of();
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
            for (DavEntry entry : entries(folder.uri, 1)) {
                if (entry.uri.equals(folder.uri)) {
                    continue;
                }
                if (entry.folder) {
                    if (folder.depth < depthLimit) {
                        pending.addLast(new Folder(entry.uri, folder.depth + 1));
                    }
                } else {
                    KnowledgeResource resource = resource(entry);
                    documents.putIfAbsent(resource.id().value(), resource);
                }
            }
        }
        List<KnowledgeResource> result = new ArrayList<>(documents.values());
        result.sort(Comparator.comparing(resource -> resource.id().value()));
        return result;
    }

    private List<DavEntry> entries(URI folder, int depth) throws KnowledgeSourceException {
        byte[] body = transport.properties(folder, depth);
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
            Element type = firstElement(prop, "resourcetype");
            boolean folderEntry = type != null && type.getElementsByTagNameNS(DAV, "collection").getLength() > 0;
            String etag = firstText(prop, "getetag");
            String date = firstText(prop, "getlastmodified");
            String length = firstText(prop, "getcontentlength");
            String mediaType = firstText(prop, "getcontenttype");
            entries.add(new DavEntry(uri, name == null || name.isBlank() ? filename(uri) : name,
                    folderEntry, etag, parseDate(date), length,
                    mediaType == null || mediaType.isBlank() ? mimeType(uri) : mediaType));
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
        }
        return KnowledgeResource.builder(idOf(entry.uri), sourceId)
                .title(entry.name).contentType(entry.mediaType)
                .revision(KnowledgeRevision.of(entry.modified, entry.etag))
                .scope(sourceId.value()).location(entry.uri).metadata(KnowledgeMetadata.of(attributes))
                .build();
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

    private static String mimeType(URI uri) {
        String name = filename(uri).toLowerCase(java.util.Locale.ROOT);
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
