package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
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
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Die Unterordner einer Ordner-Quelle als eigene Einträge, damit der Seitenbaum die Gliederung wie im Explorer zeigt
 * (Angelo, 24.11.2026): Die Ordner-Quelle aus {@code enterprise-ai-sources} listet nur Dateien und ohne Elternverweis,
 * der Baum ({@code PageTreeBuilder}) machte daraus lauter Wurzeln.
 *
 * <p>Hier bekommt jede Datei ihren Ordner als {@link KnowledgeResource#parentId()}, und jeder Ordner, der eine
 * gelistete Datei enthält, steht als eigener Eintrag ({@link PageMetadata#RESOURCE_KIND} = {@code folder}) direkt vor
 * seiner ersten Datei in der Partition, mit seinem übergeordneten Ordner als Eltern. Ordner an der Wurzel und Dateien
 * darin haben keinen Elternverweis. Das ist dasselbe Muster wie bei BSCW-Ordnern ({@link BscwWebDavKnowledgeSource}):
 * Ein Ordner hat keinen Inhalt (leeres Original im Format {@value #FOLDER_FORMAT}).
 *
 * <p>Welche Dateien gelesen werden (versteckte, Muster, Größe, Freigaben), entscheidet weiter allein die Quelle; ein
 * Ordner ohne gelistete Datei erscheint nicht.
 */
final class LocalFolderHierarchy implements KnowledgeSourcePort, PartitionListingSource, ResourceMetadataSource,
        StoredContentSource {

    /** {@link StoredContent#nativeFormat()} eines Ordners: kein Inhalt. */
    static final String FOLDER_FORMAT = "local-folder";
    static final String FOLDER_TYPE = "inode/directory";
    /** Feste Revision eines Ordners: Er ändert sich nur, wenn er umbenannt wird, und dann ist es ein anderer. */
    private static final String FOLDER_VERSION = "folder";
    private static final String FILE_PATH = "file.path";
    /** Eigener Cursor: {@code lh1.<letzter Dateipfad als Base64url>.<Cursor der Quelle>}. */
    private static final String CURSOR_PREFIX = "lh1.";

    private final KnowledgeSourcePort port;
    private final PartitionListingSource listing;
    private final ResourceMetadataSource metadata;
    private final StoredContentSource stored;
    private final Path root;
    /** Anfang jeder Ressourcen-ID der Quelle, wie dort gebildet: {@code file:<Verzeichnis mit />/}. */
    private final String prefix;

    LocalFolderHierarchy(KnowledgeSourcePort port, Path root) {
        Objects.requireNonNull(port, "port");
        if (!(port instanceof PartitionListingSource listing) || !(port instanceof ResourceMetadataSource metadata)
                || !(port instanceof StoredContentSource stored)) {
            throw new IllegalArgumentException("Ordner-Quelle ohne Partition, Metadaten oder Original: " + port);
        }
        this.port = port;
        this.listing = listing;
        this.metadata = metadata;
        this.stored = stored;
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.prefix = "file:" + SourceLocators.path(this.root.toString()) + "/";
    }

    @Override
    public SourceAddress sourceId() {
        return port.sourceId();
    }

    @Override
    public List<SourcePartition> partitions() throws KnowledgeSourceException {
        return port.partitions();
    }

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        return port.discover(scope).stream().map(this::withParent).toList();
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String folder = folderOf(metadata.localId(resourceId));
        if (folder != null) {
            return KnowledgeDocument.of(folder(folder), "");
        }
        KnowledgeDocument document = port.load(resourceId);
        return KnowledgeDocument.of(withParent(document.resource()), document.text(), document.blocks());
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        return folderOf(metadata.localId(resourceId)) != null ? List.of() : port.discoverLinks(resourceId);
    }

    /**
     * Die Dateien der Quelle in ihrer Reihenfolge (nach relativem Pfad), davor jeweils die Ordner, die die vorige Datei
     * nicht schon hatte. Nach Pfad sortiert stehen alle Dateien eines Ordners am Stück; so erscheint jeder Ordner genau
     * einmal, vor seinem Inhalt, auch über Stapelgrenzen (der Cursor merkt sich die letzte Datei).
     */
    @Override
    public PartitionResources resources(String partitionKey, String cursor, int limit)
            throws KnowledgeSourceException {
        String previous = null;
        String own = cursor;
        if (cursor != null && cursor.startsWith(CURSOR_PREFIX)) {
            int dot = cursor.indexOf('.', CURSOR_PREFIX.length());
            previous = dot < 0 ? null : decode(cursor.substring(CURSOR_PREFIX.length(), dot));
            if (previous == null) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Cursor gehört nicht zu dieser Partition");
            }
            own = cursor.substring(dot + 1);
        }
        PartitionResources batch = listing.resources(partitionKey, own, limit);
        List<KnowledgeResource> resources = new ArrayList<>();
        for (KnowledgeResource file : batch.resources()) {
            String path = pathOf(file);
            if (path == null) {
                resources.add(file);
                continue;
            }
            List<String> before = folders(previous);
            for (String folder : folders(path)) {
                if (!before.contains(folder)) {
                    resources.add(folder(folder));
                }
            }
            resources.add(withParent(file));
            previous = path;
        }
        String next = batch.nextCursor();
        if (next != null && previous != null) {
            next = CURSOR_PREFIX + encode(previous) + "." + next;
        }
        return new PartitionResources(resources, next);
    }

    @Override
    public String localId(KnowledgeResourceId resourceId) {
        return metadata.localId(resourceId);
    }

    @Override
    public KnowledgeResource resourceMetadata(String localId) throws KnowledgeSourceException {
        String folder = folderOf(localId);
        return folder != null ? folder(folder) : withParent(metadata.resourceMetadata(localId));
    }

    @Override
    public StoredContent storedContent(String localId) throws KnowledgeSourceException {
        String folder = folderOf(localId);
        if (folder != null) {
            return new StoredContent(folder(folder), new byte[0], "text/plain", FOLDER_FORMAT, Instant.now());
        }
        StoredContent content = stored.storedContent(localId);
        return new StoredContent(withParent(content.resource()), content.payload(), content.mediaType(),
                content.nativeFormat(), content.retrievedAt());
    }

    @Override
    public String toString() {
        return "LocalFolderHierarchy{" + port + "}";
    }

    /** Die Datei mit ihrem Ordner als Eltern; an der Wurzel unverändert. */
    private KnowledgeResource withParent(KnowledgeResource file) {
        String path = pathOf(file);
        String parent = path == null ? null : parentOf(path);
        return parent == null ? file : file.toBuilder().parentId(idOf(parent)).build();
    }

    /** Der Eintrag eines Ordners (relativer Pfad mit {@code /}). */
    private KnowledgeResource folder(String path) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(FILE_PATH, path);
        attributes.put(PageMetadata.RESOURCE_KIND, PageMetadata.FOLDER_KIND);
        KnowledgeResource.Builder builder = KnowledgeResource.builder(idOf(path), port.sourceId())
                .title(path.substring(path.lastIndexOf('/') + 1))
                .contentType(FOLDER_TYPE)
                .revision(KnowledgeRevision.version(FOLDER_VERSION))
                .scope(port.sourceId().value())
                .metadata(KnowledgeMetadata.of(attributes));
        String parent = parentOf(path);
        if (parent != null) {
            builder.parentId(idOf(parent));
        }
        return builder.build();
    }

    /**
     * Der relative Pfad, wenn die lokale ID einen Ordner unter der Wurzel nennt, sonst {@code null} (Datei, fremde ID,
     * versteckter Ordner).
     */
    private String folderOf(String localId) {
        String path = localId == null ? null : decode(localId);
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                return null;
            }
        }
        try {
            Path folder = root.resolve(path).normalize();
            return folder.startsWith(root) && !folder.equals(root) && Files.isDirectory(folder) ? path : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private KnowledgeResourceId idOf(String path) {
        return KnowledgeResourceId.of(prefix + path);
    }

    private static String pathOf(KnowledgeResource resource) {
        String path = resource.metadata().asMap().get(FILE_PATH);
        return path == null || path.isEmpty() ? null : path;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? null : path.substring(0, slash);
    }

    /** Die Ordner über einem Pfad von oben nach unten ({@code a/b/c.md} → {@code a}, {@code a/b}). */
    private static List<String> folders(String path) {
        List<String> folders = new ArrayList<>();
        if (path == null) {
            return folders;
        }
        for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
            folders.add(path.substring(0, slash));
        }
        return folders;
    }

    private static String encode(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    /** Wie die Quelle: Base64url ohne Auffüllung, sonst {@code null}. */
    private static String decode(String value) {
        if (value.isEmpty() || value.indexOf('=') >= 0) {
            return null;
        }
        try {
            String path = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            return encode(path).equals(value) ? path : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
