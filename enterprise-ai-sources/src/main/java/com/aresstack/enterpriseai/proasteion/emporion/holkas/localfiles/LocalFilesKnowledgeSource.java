package com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles;

import com.aresstack.enterpriseai.source.api.files.FilePatterns;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedBlock;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedDocument;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
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
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.files.LocalFileAccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Ein lokales Verzeichnis als {@link KnowledgeSourcePort}.
 *
 * <ul>
 *   <li>Startpunkte im {@link SourceScope} sind Pfade relativ zum Wurzelverzeichnis ({@code .} = die Wurzel) oder
 *       einzelne Dateien; Verzeichnisse werden bis {@code maxDepth} Ebenen tief gelesen ({@code 0} = nur die Dateien
 *       direkt darin). Versteckte Dateien, Office-Sperrdateien ({@code ~$…}) und Dateien über der Größengrenze
 *       werden übersprungen, ebenso Dateien, deren Endung keinen Dokumenttyp ergibt.</li>
 *   <li>Zugriff (#120): jede Datei muss nach Auflösen von Links in der Wurzel oder einem anderen freigegebenen Ordner
 *       der {@link LocalFileAccessPolicy} liegen; ein Link aus der Wurzel hinaus erweitert die Freigabe nicht.
 *       Include-/Exclude-Muster begrenzen, was gelesen wird.</li>
 *   <li>Resource-IDs: {@code file:<Quell-ID>/<relativer Pfad mit />}; Revision: Änderungszeit und Größe.</li>
 *   <li>Inhalt: Erkennung per {@link ContentDetector}, Extraktion über das erste passende
 *       {@link ResourceExtractor} der {@link ExtractionRegistry}; Überschriften und Code werden als Markdown
 *       ausgegeben.</li>
 *   <li>Für Harvester, die Originale ablegen: genau eine Partition (Schlüssel = Quell-ID, wie
 *       {@link KnowledgeResource#scope()}), darin alle lesbaren Dateien nach relativem Pfad sortiert
 *       ({@link PartitionListingSource}); Metadaten einer Datei ({@link ResourceMetadataSource}) und ihre Bytes
 *       unverändert ({@link StoredContentSource}, Format {@value #STORED_FORMAT}). Die lokale ID ist der relative
 *       Pfad als Base64url ohne Auffüllung, damit sie als ein URL-Pfadsegment taugt.</li>
 * </ul>
 */
public final class LocalFilesKnowledgeSource implements KnowledgeSourcePort, PartitionListingSource,
        ResourceMetadataSource, StoredContentSource {

    static final String SCHEME = "file";
    /** Standardobergrenze je Datei. */
    public static final long DEFAULT_MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int PREFIX_BYTES = 8192;
    /** {@link StoredContent#nativeFormat()}: die Datei Byte für Byte. */
    static final String STORED_FORMAT = "file";
    /** Höchstens so viele Dateien liefert ein Stapel von {@link #resources}. */
    static final int MAX_BATCH = 500;
    /** Verzeichnistiefe der Partition, wie der Standard des Providers. */
    static final int PARTITION_DEPTH = 20;
    private static final String CURSOR_PREFIX = "f1.";
    private static final Set<ContentCategory> INDEXABLE = Collections.unmodifiableSet(EnumSet.of(
            ContentCategory.PLAIN_TEXT, ContentCategory.MARKDOWN, ContentCategory.HTML, ContentCategory.PDF,
            ContentCategory.OFFICE_DOCUMENT, ContentCategory.SOURCE_CODE, ContentCategory.STRUCTURED_DATA));

    private final SourceAddress sourceId;
    private final Path root;
    /** Das Verzeichnis absolut mit {@code /}, Teil jeder Ressourcen-ID ({@code file:/C:/Daten/Doku/a.txt}). */
    private final String locator;
    private final ContentDetector detector;
    private final ExtractionRegistry extractors;
    private final long maxFileBytes;
    private final Supplier<LocalFileAccessPolicy> access;
    private final FilePatterns patterns;

    public LocalFilesKnowledgeSource(SourceAddress sourceId, Path root, ContentDetector detector,
                                     ExtractionRegistry extractors, long maxFileBytes) {
        this(sourceId, root, detector, extractors, maxFileBytes, LocalFileAccessPolicy::none,
                Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    /**
     * @param access   the user's grants, read on every access; the source folder itself is always granted
     * @param includes glob patterns of files to read (empty: all indexable files)
     * @param excludes glob patterns of files and folders to skip
     */
    public LocalFilesKnowledgeSource(SourceAddress sourceId, Path root, ContentDetector detector,
                                     ExtractionRegistry extractors, long maxFileBytes,
                                     Supplier<LocalFileAccessPolicy> access, List<String> includes,
                                     List<String> excludes) {
        if (sourceId == null || root == null || detector == null || extractors == null) {
            throw new IllegalArgumentException("sourceId, root, detector and extractors are required");
        }
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes must be >= 1");
        }
        this.sourceId = sourceId;
        this.root = root.toAbsolutePath().normalize();
        this.locator = locatorOf(this.root);
        this.detector = detector;
        this.extractors = extractors;
        this.maxFileBytes = maxFileBytes;
        this.access = access == null ? LocalFileAccessPolicy::none : access;
        this.patterns = new FilePatterns(includes == null ? Collections.<String>emptyList() : includes,
                excludes == null ? Collections.<String>emptyList() : excludes);
    }

    /** Kanonischer Locator eines Verzeichnisses: absolut, normalisiert, mit {@code /} getrennt. */
    static String locatorOf(Path directory) {
        return SourceLocators.path(directory.toAbsolutePath().normalize().toString());
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        if (!Files.isDirectory(root)) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Verzeichnis nicht gefunden: " + root);
        }
        if (scope.selectionApplies() && scope.excludedPartitions().contains(sourceId.value())) {
            return Collections.emptyList();
        }
        final LocalFileAccessPolicy policy = policy();
        Set<Path> files = new LinkedHashSet<Path>();
        for (String startPoint : scope.startPoints()) {
            final Path start = inside(startPoint.trim().isEmpty() ? "." : startPoint.trim());
            if (start == null || !Files.exists(start)) {
                continue;
            }
            if (Files.isRegularFile(start)) {
                files.add(start);
                continue;
            }
            final List<Path> found = new ArrayList<Path>();
            try {
                Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), scope.maxDepth() + 1,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                return dir.equals(start) || patterns.entersFolder(relative(dir))
                                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (attrs.isRegularFile()) {
                                    found.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                                // Startverzeichnis nicht lesbar: Fehler statt leerer Liste (sonst räumt der Index
                                // alle Dokumente der Quelle ab). Einzelne Unterverzeichnisse und Dateien überspringen.
                                if (file.equals(start)) {
                                    throw exc;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Verzeichnis nicht lesbar: " + start, e);
            }
            Collections.sort(found);
            files.addAll(found);
        }
        List<KnowledgeResource> resources = new ArrayList<KnowledgeResource>();
        for (Path file : files) {
            if (resources.size() >= scope.maxResources()) {
                break;
            }
            if (!candidate(file) || !patterns.acceptsFile(relative(file)) || !policy.isAllowed(file)) {
                continue;
            }
            try {
                resources.add(resource(file, typeByName(file)));
            } catch (IOException | RuntimeException e) {
                // Datei verschwunden oder nicht lesbar: überspringen.
            }
        }
        return resources;
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        Path file = fileOf(resourceId);
        byte[] bytes;
        try {
            if (Files.size(file) > maxFileBytes) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " ist größer als " + maxFileBytes + " Bytes");
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, resourceId + " nicht lesbar", e);
        }
        String name = file.getFileName().toString();
        DetectedContentType type = detector.detect(name, null, Arrays.copyOf(bytes, Math.min(bytes.length, PREFIX_BYTES)));
        ResourceExtractor extractor = extractors.findExtractor(type);
        if (extractor == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + ": kein Extraktor für " + type.mimeType());
        }
        ExtractionResult result = extractor.extract(new ExtractionRequest(resourceId.value(), bytes, name, null, type));
        if (!result.isSuccess()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, resourceId + ": " + result.errorMessage());
        }
        KnowledgeResource resource;
        try {
            resource = resource(file, type);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, resourceId + " nicht lesbar", e);
        }
        return KnowledgeDocument.of(resource, text(resource.title(), result.document()));
    }

    /** Genau eine Partition: das Verzeichnis, Schlüssel = Quell-ID. */
    @Override
    public List<SourcePartition> partitions() throws KnowledgeSourceException {
        if (!Files.isDirectory(root)) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Verzeichnis nicht gefunden: " + root);
        }
        Path name = root.getFileName();
        return Collections.singletonList(new SourcePartition(sourceId.value(), name == null ? root.toString()
                : name.toString()));
    }

    /**
     * Die Dateien der Partition nach relativem Pfad sortiert, so wie {@link #discover} sie mit Startpunkt {@code .}
     * und Tiefe {@value #PARTITION_DEPTH} findet, aber ohne Gesamtgrenze. Der Cursor nennt die letzte gelieferte
     * Datei: Neue oder gelöschte Dateien verschieben keinen Stapel.
     */
    @Override
    public PartitionResources resources(String partitionKey, String cursor, int limit)
            throws KnowledgeSourceException {
        if (!sourceId.value().equals(partitionKey)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Keine Partition dieser Quelle: " + partitionKey);
        }
        String after = null;
        if (cursor != null && !cursor.isEmpty()) {
            after = cursor.startsWith(CURSOR_PREFIX) ? decode(cursor.substring(CURSOR_PREFIX.length())) : null;
            if (after == null) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Cursor gehört nicht zu dieser Partition");
            }
        }
        List<KnowledgeResource> all = discover(SourceScope.builder().startPoints(".").maxDepth(PARTITION_DEPTH)
                .maxResources(Integer.MAX_VALUE).ignoringSelection().build());
        List<String> paths = new ArrayList<String>();
        Map<String, KnowledgeResource> byPath = new LinkedHashMap<String, KnowledgeResource>();
        for (KnowledgeResource resource : all) {
            String path = resource.metadata().get("file.path").orElse(null);
            if (path != null && (after == null || path.compareTo(after) > 0)) {
                paths.add(path);
                byPath.put(path, resource);
            }
        }
        Collections.sort(paths);
        int size = Math.min(paths.size(), Math.max(1, Math.min(limit, MAX_BATCH)));
        List<KnowledgeResource> batch = new ArrayList<KnowledgeResource>();
        for (int i = 0; i < size; i++) {
            batch.add(byPath.get(paths.get(i)));
        }
        String next = size < paths.size() ? CURSOR_PREFIX + encode(paths.get(size - 1)) : null;
        return new PartitionResources(batch, next);
    }

    /** Der relative Pfad als Base64url ohne Auffüllung, oder {@code null} für fremde IDs. */
    @Override
    public String localId(KnowledgeResourceId resourceId) {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId == null ? null : resourceId.value();
        return value != null && value.startsWith(prefix) && value.length() > prefix.length()
                ? encode(value.substring(prefix.length())) : null;
    }

    @Override
    public KnowledgeResource resourceMetadata(String localId) throws KnowledgeSourceException {
        Path file = readable(localId);
        try {
            return resource(file, typeByName(file));
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, localId + " nicht lesbar", e);
        }
    }

    /**
     * Die Datei Byte für Byte mit den Metadaten von vor dem Lesen; hat sie sich beim Lesen in der Größe geändert,
     * scheitert der Aufruf ({@code UNAVAILABLE}) statt Revision und Inhalt zu mischen.
     */
    @Override
    public StoredContent storedContent(String localId) throws KnowledgeSourceException {
        Path file = readable(localId);
        KnowledgeResource resource;
        byte[] bytes;
        try {
            long size = Files.size(file);
            if (size > maxFileBytes) {
                throw new ResponseTooLargeException(localId + ": Datei größer als " + maxFileBytes + " Bytes",
                        maxFileBytes);
            }
            resource = resource(file, typeByName(file));
            bytes = Files.readAllBytes(file);
            if (bytes.length != size) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, localId + " hat sich beim Lesen geändert");
            }
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, localId + " nicht lesbar", e);
        }
        String name = file.getFileName().toString();
        String mediaType = detector.detect(name, null, Arrays.copyOf(bytes, Math.min(bytes.length, PREFIX_BYTES)))
                .mimeType();
        return new StoredContent(resource, bytes, mediaType == null || mediaType.isEmpty()
                ? "application/octet-stream" : mediaType, STORED_FORMAT, Instant.now());
    }

    /** Eine Datei, die {@link #discover} liefern würde; sonst NOT_FOUND oder UNSUPPORTED. */
    private Path readable(String localId) throws KnowledgeSourceException {
        String relative = localId == null ? null : decode(localId);
        if (relative == null || relative.isEmpty()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Keine Datei-ID dieser Quelle: " + localId);
        }
        Path file = fileOf(idOf(relative));
        if (!candidate(file) || !relative(file).equals(relative)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, localId + " nicht gefunden");
        }
        return file;
    }

    private static String encode(String relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
    }

    /** Dekodiert eine lokale ID; {@code null}, wenn sie kein Base64url ohne Auffüllung ist. */
    private static String decode(String localId) {
        if (localId.isEmpty() || localId.indexOf('=') >= 0) {
            return null;
        }
        try {
            String relative = new String(Base64.getUrlDecoder().decode(localId), StandardCharsets.UTF_8);
            return encode(relative).equals(localId) ? relative : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        fileOf(resourceId);
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "LocalFilesKnowledgeSource{" + sourceId + ", " + root + "}";
    }

    private boolean candidate(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("~$")) {
            return false;
        }
        try {
            if (Files.isHidden(file) || Files.size(file) > maxFileBytes || Files.size(file) == 0) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return INDEXABLE.contains(typeByName(file).category());
    }

    private DetectedContentType typeByName(Path file) {
        return detector.detect(file.getFileName().toString(), null, null);
    }

    private KnowledgeResource resource(Path file, DetectedContentType type) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        String relative = relative(file);
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("file.path", relative);
        metadata.put("file.size", String.valueOf(attributes.size()));
        return KnowledgeResource.builder(idOf(relative), sourceId)
                .title(file.getFileName().toString())
                .contentType(type.mimeType())
                .revision(KnowledgeRevision.of(attributes.lastModifiedTime().toInstant(),
                        String.valueOf(attributes.size())))
                .scope(sourceId.value())
                .location(file.toUri())
                .metadata(KnowledgeMetadata.of(metadata))
                .build();
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private KnowledgeResourceId idOf(String relative) {
        return KnowledgeResourceId.of(SCHEME, locator + "/" + relative);
    }

    private Path fileOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId.value();
        Path file = value.startsWith(prefix) && value.length() > prefix.length()
                ? inside(value.substring(prefix.length())) : null;
        if (file == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        if (!Files.isRegularFile(file) || !patterns.acceptsFile(relative(file))) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, resourceId + " nicht gefunden");
        }
        if (!policy().isAllowed(file)) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED,
                    resourceId + " führt aus den freigegebenen Ordnern hinaus");
        }
        return file;
    }

    /** The user's grants plus this source's own folder. */
    private LocalFileAccessPolicy policy() {
        LocalFileAccessPolicy granted = access.get();
        return (granted == null ? LocalFileAccessPolicy.none() : granted).including(root);
    }

    /** Pfad unterhalb der Wurzel oder {@code null}, wenn er hinausführt. */
    private Path inside(String relative) {
        try {
            Path path = root.resolve(relative).normalize();
            return path.startsWith(root) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String text(String fileName, ExtractedDocument document) {
        StringBuilder sb = new StringBuilder();
        String title = document.title() != null && !document.title().trim().isEmpty() ? document.title().trim() : fileName;
        sb.append("# ").append(title);
        for (ExtractedBlock block : document.blocks()) {
            String text = block.text();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            sb.append("\n\n");
            switch (block.kind()) {
                case HEADING:
                    sb.append(hashes(block.attributes().get("level"))).append(' ').append(text.trim());
                    break;
                case CODE:
                    String language = block.attributes().get("language");
                    sb.append("```").append(language == null ? "" : language).append('\n').append(text)
                            .append("\n```");
                    break;
                default:
                    sb.append(text.trim());
                    break;
            }
        }
        return sb.toString();
    }

    private static String hashes(String level) {
        int n = 2;
        try {
            n = level == null ? 2 : Math.max(1, Math.min(6, Integer.parseInt(level.trim())));
        } catch (NumberFormatException e) {
            n = 2;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('#');
        }
        return sb.toString();
    }
}
