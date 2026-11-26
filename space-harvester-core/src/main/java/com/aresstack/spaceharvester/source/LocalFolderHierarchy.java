package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles.LocalFilesKnowledgeSource;
import java.util.Optional;
import java.util.function.Predicate;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.files.FilePatterns;
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
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.stream.Stream;
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
 * Ordner ohne gelistete Datei erscheint nur in der Live-Navigation, nicht im Dokumenteninventar.
 * The navigation reads only immediate children and never traverses unopened directories.
 */
final class LocalFolderHierarchy implements KnowledgeSourcePort, PartitionListingSource, ResourceMetadataSource,
        StoredContentSource, PageNavigationSource, ResourceNodeKindSource, OriginalFileSource, DirectOriginalFileSource, SourceMediaRange,
        com.aresstack.enterpriseai.source.api.PartitionInventorySource {

    /** {@link StoredContent#nativeFormat()} eines Ordners: kein Inhalt. */
    static final String FOLDER_FORMAT = "local-folder";
    static final String FOLDER_TYPE = "inode/directory";
    /** Feste Revision eines Ordners: Er ändert sich nur, wenn er umbenannt wird, und dann ist es ein anderer. */
    private static final String FOLDER_VERSION = "folder";
    private static final String FILE_PATH = "file.path";
    /** Eigener Cursor: {@code lh1.<letzter Dateipfad als Base64url>.<Cursor der Quelle>}. */
    private static final String CURSOR_PREFIX = "lh1.";

    private final LocalFilesKnowledgeSource port;
    private final PartitionListingSource listing;
    private final ResourceMetadataSource metadata;
    private final StoredContentSource stored;
    private final Path root;
    private final FilePatterns patterns;
    private static final int NAVIGATION_BATCH = 200;
    private static final String NAVIGATION_CURSOR = "lfn1.";
    /** Anfang jeder Ressourcen-ID der Quelle, wie dort gebildet: {@code file:<Verzeichnis mit />/}. */
    private final String prefix;

    LocalFolderHierarchy(KnowledgeSourcePort port, Path root, FilePatterns patterns) {
        Objects.requireNonNull(port, "port");
        if (!(port instanceof LocalFilesKnowledgeSource files)) {
            throw new IllegalArgumentException("Ordner-Quelle ohne Partition, Metadaten oder Original: " + port);
        }
        this.port = files;
        this.listing = files;
        this.metadata = files;
        this.stored = files;
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.patterns = Objects.requireNonNull(patterns, "patterns");
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
        return port.discover(scope).stream().map(this::describeFile).toList();
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String folder = folderOf(metadata.localId(resourceId));
        if (folder != null) {
            return KnowledgeDocument.of(folder(folder), "");
        }
        KnowledgeDocument document = port.load(resourceId);
        return KnowledgeDocument.of(describeFile(document.resource()), document.text(), document.blocks());
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
            resources.add(describeFile(file));
            previous = path;
        }
        String next = batch.nextCursor();
        if (!batch.complete() && previous != null) {
            next = CURSOR_PREFIX + encode(previous) + "." + next;
        }
        return new PartitionResources(resources, next);
    }

    @Override
    public void visitInventory(String partitionKey, Runnable checkpoint,
                               java.util.function.Consumer<KnowledgeResource> found) throws KnowledgeSourceException {
        java.util.Set<String> emittedFolders = new java.util.HashSet<>();
        port.visitInventory(partitionKey, checkpoint, file -> {
            for (String parent : folders(pathOf(file))) {
                if (emittedFolders.add(parent)) {
                    found.accept(folder(parent));
                }
            }
            found.accept(describeFile(file));
        });
    }

    @Override
    public String localId(KnowledgeResourceId resourceId) {
        return metadata.localId(resourceId);
    }

    @Override
    public KnowledgeResource resourceMetadata(String localId) throws KnowledgeSourceException {
        String folder = folderOf(localId);
        return folder != null ? folder(folder) : describeFile(metadata.resourceMetadata(localId));
    }

    @Override
    public StoredContent storedContent(String localId) throws KnowledgeSourceException {
        String folder = folderOf(localId);
        if (folder != null) {
            return new StoredContent(folder(folder), new byte[0], "text/plain", FOLDER_FORMAT, Instant.now());
        }
        KnowledgeResource resource = resourceMetadata(localId);
        if ("true".equals(resource.metadata().asMap().get(PageMetadata.REMOTE_MEDIA))) {
            return new StoredContent(resource, new byte[0], "application/x-space-harvester-remote-media",
                    "remote-media-metadata", Instant.now());
        }
        try {
            StoredContent content = stored.storedContent(localId);
            return new StoredContent(describeFile(content.resource()), content.payload(), content.mediaType(),
                    content.nativeFormat(), content.retrievedAt());
        } catch (com.aresstack.enterpriseai.source.api.ResponseTooLargeException oversized) {
            // Retain the file as a metadata-only resource when its source read limit is lower than the cache limit.
            return new StoredContent(resource, new byte[0],
                    com.aresstack.spaceharvester.application.snapshot.LargeFiles.MEDIA_TYPE,
                    com.aresstack.spaceharvester.application.snapshot.LargeFiles.FORMAT, Instant.now());
        }
    }

    @Override
    public Optional<String> downloadOriginal(String localId, Path target, Predicate<String> wanted, long maxBytes)
            throws KnowledgeSourceException {
        if (folderOf(localId) != null) {
            return Optional.empty();
        }
        return port.copyOriginal(localId, target, wanted, maxBytes);
    }

    @Override
    public <T> Optional<T> readOriginalInPlace(String localId, Predicate<String> wanted, long maxBytes,
                                               DirectOriginalFileSource.Reader<T> reader) throws KnowledgeSourceException {
        if (folderOf(localId) != null) {
            return Optional.empty();
        }
        return port.readOriginal(localId, wanted, maxBytes,
                (original, resource) -> reader.read(original, describeFile(resource)));
    }

    @Override
    public byte[] readRange(String localId, long offset, int maximumBytes) throws KnowledgeSourceException {
        return port.readMediaRange(localId, offset, maximumBytes);
    }

    @Override
    public NavigationChildren children(NavigationNode parent, String cursor) throws KnowledgeSourceException {
        String path = parentPath(parent);
        String after = navigationAfter(path, cursor);
        List<NavigationNode> nodes = new ArrayList<>();
        addDirectChildren(path, nodes);
        nodes.sort(Comparator.comparing(LocalFolderHierarchy::navigationOrder));
        List<NavigationNode> remaining = nodes.stream()
                .filter(node -> after == null || navigationOrder(node).compareTo(after) > 0).toList();
        int count = Math.min(NAVIGATION_BATCH, remaining.size());
        String next = count < remaining.size()
                ? NAVIGATION_CURSOR + encode(path) + "." + encode(navigationOrder(remaining.get(count - 1))) : null;
        return new NavigationChildren(remaining.subList(0, count), next);
    }

    private String parentPath(NavigationNode parent) throws KnowledgeSourceException {
        if (parent.isRoot()) {
            if (!parent.key().equals(rootKey())) {
                throw new KnowledgeSourceException(Kind.NOT_FOUND, "Unbekannte Ordner-Partition");
            }
            return ".";
        }
        String path = folderOf(metadata.localId(parent.resource()));
        if (path == null) {
            // Validate the resource without ever treating a file as a directory.
            resourceMetadata(metadata.localId(parent.resource()));
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Datei besitzt keine Ordnerkinder");
        }
        return path;
    }

    private void addDirectChildren(String path, List<NavigationNode> nodes) throws KnowledgeSourceException {
        Path directory = ".".equals(path) ? root : root.resolve(path);
        try (Stream<Path> children = Files.list(directory)) {
            children.forEach(child -> addChild(child, nodes));
        } catch (IOException | UncheckedIOException | SecurityException failure) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Ordner nicht lesbar: " + path, failure);
        }
    }

    private void addChild(Path child, List<NavigationNode> nodes) {
        String relative = relativePath(child);
        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            if (visibleFolder(relative)) {
                nodes.add(navigationNode(folder(relative)));
            }
        } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // Keep document eligibility and access checks in the existing file source.
                nodes.add(navigationNode(describeFile(metadata.resourceMetadata(encode(relative)))));
            } catch (KnowledgeSourceException ignored) {
                // Skip files rejected by the source, or files removed during enumeration.
            }
        }
    }

    private String relativePath(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String navigationOrder(NavigationNode node) {
        return (node.children() == NavigationNode.Children.NO ? "1:" : "0:") + node.key();
    }

    private static String navigationAfter(String path, String cursor) throws KnowledgeSourceException {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        String own = NAVIGATION_CURSOR + encode(path) + ".";
        String after = cursor.startsWith(own) ? decode(cursor.substring(own.length())) : null;
        if (after == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Cursor gehört nicht zu diesem Ordner");
        }
        return after;
    }

    @Override
    public PagePosition position(KnowledgeResourceId page) throws KnowledgeSourceException {
        KnowledgeResource resource = resourceMetadata(metadata.localId(page));
        List<NavigationNode> ancestors = folders(pathOf(resource)).stream()
                .map(this::folder).map(this::navigationNode).toList();
        Path name = root.getFileName();
        NavigationNode rootNode = NavigationNode.root(rootKey(), name == null ? root.toString() : name.toString(),
                rootKey(), NavigationNode.Children.UNKNOWN);
        return new PagePosition(rootNode, ancestors, navigationNode(resource));
    }

    private String rootKey() {
        return "space:" + sourceId().value();
    }

    private NavigationNode navigationNode(KnowledgeResource resource) {
        return NavigationNode.page(resource.id(), resource.title(), isFolder(resource)
                ? NavigationNode.Children.UNKNOWN : NavigationNode.Children.NO);
    }

    private static boolean isFolder(KnowledgeResource resource) {
        return PageMetadata.FOLDER_KIND.equals(resource.metadata().asMap().get(PageMetadata.RESOURCE_KIND));
    }

    @Override
    public HarvestSource.PageNode.Kind kind(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        return isFolder(resourceMetadata(metadata.localId(resourceId)))
                ? HarvestSource.PageNode.Kind.FOLDER : HarvestSource.PageNode.Kind.FILE;
    }

    @Override
    public CrossReferences crossReferences(KnowledgeResourceId page) throws KnowledgeSourceException {
        return CrossReferences.outgoingOnly(discoverLinks(page));
    }

    @Override
    public String toString() {
        return "LocalFolderHierarchy{" + port + "}";
    }

    /** Describe a file for the shared original-document reader and preserve its parent. */
    private KnowledgeResource describeFile(KnowledgeResource file) {
        KnowledgeMetadata attributes = file.metadata().with(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND);
        String videoType = VideoFileTypes.streamableType(file.title(), file.contentType());
        if (videoType != null) {
            attributes = attributes.with(PageMetadata.REMOTE_MEDIA, "true")
                    .with(PageMetadata.REMOTE_MEDIA_TYPE, videoType);
        }
        // The upstream version is the file size; include the modification time to detect same-size edits.
        KnowledgeRevision revision = file.revision();
        String version = "local-file/v1:" + revision.version() + ":"
                + revision.modifiedAt().map(Instant::toString).orElse("unknown");
        KnowledgeResource.Builder builder = file.toBuilder().metadata(attributes)
                .revision(KnowledgeRevision.of(revision.modifiedAt().orElse(null), version));
        String path = pathOf(file);
        String parent = path == null ? null : parentOf(path);
        if (parent != null) {
            builder.parentId(idOf(parent));
        }
        return builder.build();
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
        return path != null && visibleFolder(path) ? path : null;
    }

    // Validate every ancestor, including the real location, before exposing a directory.
    private boolean visibleFolder(String path) {
        if (path.isEmpty() || path.indexOf('\\') >= 0) {
            return false;
        }
        try {
            Path current = root;
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.startsWith(".")) {
                    return false;
                }
                current = current.resolve(segment);
                if (!current.startsWith(root) || !patterns.entersFolder(relativePath(current))
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isHidden(current)) {
                    return false;
                }
            }
            return !current.equals(root) && relativePath(current).equals(path)
                    && current.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | RuntimeException invalid) {
            return false;
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

    static String encode(String path) {
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
