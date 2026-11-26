package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.InvalidNavigationCursorException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceConnectionVerifier;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.SearchableKnowledgeSource;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.aresstack.enterpriseai.source.api.SourceSearchHit;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.PartitionInventorySource;
import com.aresstack.enterpriseai.source.api.ChangeListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import java.util.function.Consumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dokumentbibliotheken einer SharePoint-Site als {@link KnowledgeSourcePort}.
 *
 * <ul>
 *   <li>Startpunkte: {@code .} = alle sichtbaren Dokumentbibliotheken der Site, sonst Ordner relativ zur Site
 *       ({@code Freigegebene Dokumente/Handbuch}) oder serverrelativ ({@code /sites/team/Dokumente}); Unterordner
 *       bis {@code maxDepth}, der Ordner {@code Forms} einer Bibliothek wird übersprungen.</li>
 *   <li>IDs: {@code sharepoint:<Site-Locator>/<Pfad relativ zur Site>}; Revision: Änderungszeit und Version
 *       ({@code UIVersionLabel}, sonst Größe).</li>
 *   <li>Inhalt: die Datei über {@code $value}, Erkennung und Text über {@code deigma}.</li>
 *   <li>Anmeldung: Anmeldedaten aus dem {@link SharePointCredentialsProvider} je Lauf als Basic-Anmeldung; ohne
 *       Anmeldedaten entscheidet das JDK (Windows-Anmeldung).</li>
 * </ul>
 */
final class SharePointKnowledgeSource implements SearchableKnowledgeSource, SourceConnectionVerifier, ResourceMetadataSource, PageNavigationSource, StoredContentSource, PartitionInventorySource, ChangeListingSource, com.aresstack.enterpriseai.source.api.FileDownloadSource {

    static final String SCHEME = "sharepoint";
    static final String ALL_LIBRARIES = ".";

    private static final String JSON = "application/json;odata=verbose";
    private static final int PREFIX_BYTES = 8192;
    private static final long MAX_JSON_BYTES = 32L * 1024L * 1024L;
    private static final Set<ContentCategory> INDEXABLE = Collections.unmodifiableSet(EnumSet.of(
            ContentCategory.PLAIN_TEXT, ContentCategory.MARKDOWN, ContentCategory.HTML, ContentCategory.PDF,
            ContentCategory.OFFICE_DOCUMENT, ContentCategory.SOURCE_CODE, ContentCategory.STRUCTURED_DATA));

    private final SourceAddress sourceId;
    private final URI siteUri;
    /** Serverrelativer Pfad der Site ohne {@code /} am Ende, z. B. {@code /sites/team}; leer für die Wurzel-Site. */
    private final String sitePath;
    private final String locator;
    private final SharePointCredentialsProvider credentials;
    private final SharePointTransport transport;
    private final ContentDetector detector;
    private final ExtractionRegistry extractors;
    private final long maxFileBytes;
    private final SourceScope configuredScope;
    private final SharePointChangePages changePages = new SharePointChangePages();
    private final SharePointNavigationPages navigationPages = new SharePointNavigationPages();

    SharePointKnowledgeSource(SourceAddress sourceId, URI siteUri, SharePointCredentialsProvider credentials,
                              SharePointTransport transport, ContentDetector detector, ExtractionRegistry extractors,
                              long maxFileBytes) {
        this(sourceId, siteUri, credentials, transport, detector, extractors, maxFileBytes,
                SourceScope.builder().startPoint(ALL_LIBRARIES).maxDepth(Integer.MAX_VALUE)
                        .maxResources(Integer.MAX_VALUE).build());
    }

    SharePointKnowledgeSource(SourceAddress sourceId, URI siteUri, SharePointCredentialsProvider credentials,
                              SharePointTransport transport, ContentDetector detector, ExtractionRegistry extractors,
                              long maxFileBytes, SourceScope configuredScope) {
        if (sourceId == null || siteUri == null || credentials == null || transport == null || detector == null
                || extractors == null) {
            throw new IllegalArgumentException("sourceId, siteUri, credentials, transport, detector and extractors are required");
        }
        this.sourceId = sourceId;
        this.siteUri = siteUri;
        this.sitePath = trimTrailingSlash(siteUri.getPath() == null ? "" : siteUri.getPath());
        this.locator = SharePointSourceProvider.siteLocator(siteUri.toString());
        this.credentials = credentials;
        this.transport = transport;
        this.detector = detector;
        this.extractors = extractors;
        this.maxFileBytes = maxFileBytes;
        this.configuredScope = configuredScope;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    @Override
    public void verifyConnection() throws KnowledgeSourceException {
        JsonObject web = get(api("?$select=Id"), authorization());
        if (string(web, "Id") == null) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: Site-ID fehlt");
        }
    }

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        List<KnowledgeResource> found = new ArrayList<KnowledgeResource>();
        walk(scope, () -> {}, found::add, false);
        return found;
    }

    @Override
    public void visitInventory(String partitionKey, Runnable checkpoint, Consumer<KnowledgeResource> found)
            throws KnowledgeSourceException {
        if (!sourceId.value().equals(partitionKey) || configuredScope.maxDepth() != Integer.MAX_VALUE
                || configuredScope.maxResources() != Integer.MAX_VALUE) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: keine vollständige Inventur für diesen Umfang");
        }
        walk(configuredScope, checkpoint, found, true);
    }

    private void walk(SourceScope scope, Runnable checkpoint, Consumer<KnowledgeResource> found, boolean strict)
            throws KnowledgeSourceException {
        String authorization = authorization();
        Set<String> seenFolders = new LinkedHashSet<String>();
        Set<String> seenFiles = new LinkedHashSet<String>();
        Deque<Object[]> queue = new ArrayDeque<Object[]>();
        for (String startPoint : scope.startPoints()) {
            if (ALL_LIBRARIES.equals(startPoint.trim())) {
                for (String library : libraries(authorization)) {
                    queue.add(new Object[] {library, 0, Boolean.TRUE});
                }
            } else {
                queue.add(new Object[] {serverRelative(startPoint), 0, Boolean.TRUE});
            }
        }
        while (!queue.isEmpty() && seenFiles.size() < scope.maxResources()) {
            checkpoint.run();
            Object[] next = queue.poll();
            String folder = (String) next[0];
            int depth = (Integer) next[1];
            boolean libraryRoot = (Boolean) next[2];
            if (!insideSite(folder)) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: Ordner außerhalb der Site");
            }
            if (!seenFolders.add(folder.toLowerCase(Locale.ROOT))) {
                continue;
            }
            URI files = resourceApi("Folder", folder, "/Files?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel");
            try {
                visitCollection(files, authorization, checkpoint, element -> {
                    if (seenFiles.size() >= scope.maxResources()) return;
                    KnowledgeResource resource = resourceOf(element.getAsJsonObject(), true);
                    if (resource != null && seenFiles.add(resource.id().value())) found.accept(resource);
                }, () -> seenFiles.size() >= scope.maxResources(), strict);
            } catch (KnowledgeSourceException e) {
                if (!strict && e.kind() == Kind.NOT_FOUND && libraryRoot) continue;
                throw e;
            }
            if (depth >= scope.maxDepth()) {
                continue;
            }
            if (seenFiles.size() >= scope.maxResources()) break;
            visitCollection(resourceApi("Folder", folder, "/Folders?$select=Name,ServerRelativeUrl"), authorization, checkpoint, element -> {
                JsonObject sub = element.getAsJsonObject();
                String name = string(sub, "Name");
                String path = string(sub, "ServerRelativeUrl");
                if (path != null && name != null && !(libraryRoot && "Forms".equalsIgnoreCase(name))
                        && path.startsWith(folder + "/") && path.lastIndexOf('/') == folder.length()) {
                    queue.add(new Object[] {path, depth + 1, Boolean.FALSE});
                }
            }, () -> false, strict);
        }
    }

    @Override
    public PartitionResources changedResources(String partitionKey, java.time.Duration within, String cursor, int limit)
            throws KnowledgeSourceException {
        requirePartition(partitionKey);
        return changePages.page(cursor, limit, () -> changedSnapshot(within));
    }

    @Override
    public PartitionResources removedResources(String partitionKey, String cursor, int limit)
            throws KnowledgeSourceException {
        requirePartition(partitionKey);
        if ((cursor != null && !cursor.isEmpty()) || limit < 1) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: ungültiger Löschcursor");
        }
        // Reconcile missing paths through inventory; deleted list item IDs cannot identify the old paths.
        return new PartitionResources(Collections.emptyList(), "");
    }

    private void requirePartition(String partitionKey) throws KnowledgeSourceException {
        if (!sourceId.value().equals(partitionKey)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "SharePoint: unbekannte Partition");
        }
    }

    private List<KnowledgeResource> changedSnapshot(java.time.Duration within) throws KnowledgeSourceException {
        if (within == null || within.isNegative() || within.isZero()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: ungültiger Änderungszeitraum");
        }
        try {
            if (configuredScope.maxDepth() == Integer.MAX_VALUE && configuredScope.maxResources() == Integer.MAX_VALUE) {
                return nativeChanges(within);
            }
        } catch (KnowledgeSourceException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw unavailable;
            // Fall back to inventory instead of accepting an incomplete delta.
        }
        List<KnowledgeResource> all = new ArrayList<KnowledgeResource>();
        walk(configuredScope, () -> {}, all::add, true);
        return all;
    }

    private List<KnowledgeResource> nativeChanges(java.time.Duration within) throws KnowledgeSourceException {
        String auth = authorization();
        List<String> roots = searchRoots(auth);
        if (roots.isEmpty()) return Collections.emptyList();
        Map<String, String> lists = changeLibraries(roots, auth);
        URI context = URI.create(trimTrailingSlash(siteUri.toString()) + "/_api/contextinfo");
        SharePointTransport.Response info = post(context, auth, null, new byte[0]);
        JsonObject data = json(info).getAsJsonObject("GetContextWebInformation");
        String digest = data == null ? null : string(data, "FormDigestValue");
        if (digest == null || digest.isEmpty() || info.serverDateMillis() <= 0) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: Änderungsabfrage ohne Serverzeit oder Digest");
        }
        Instant end = Instant.ofEpochMilli(info.serverDateMillis());
        Map<String, KnowledgeResource> found = new LinkedHashMap<String, KnowledgeResource>();
        for (Map.Entry<String, String> list : lists.entrySet()) {
            String id = list.getKey();
            String endpoint = "lists(guid'" + id + "')";
            URI changes = api(endpoint + "/GetChanges");
            SharePointChangeLog.visit(id, end.minus(within), end,
                    query -> json(post(changes, auth, digest, query)), event -> {
                JsonObject metadata = changedFile(event, endpoint, id, auth);
                String path = string(metadata, "ServerRelativeUrl");
                if (path == null || !path.startsWith(list.getValue() + "/")) {
                    throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: geänderte Datei außerhalb der Bibliothek");
                }
                if (!withinRoots(path, roots) || path.startsWith(list.getValue() + "/Forms/")) return;
                KnowledgeResource resource = resourceOf(metadata, true);
                if (resource != null) found.put(resource.id().value(), resource);
            });
        }
        return new ArrayList<KnowledgeResource>(found.values());
    }

    private Map<String, String> changeLibraries(List<String> roots, String auth) throws KnowledgeSourceException {
        JsonArray libraries = collection(api("lists?$select=Id,Hidden,RootFolder/ServerRelativeUrl"
                + "&$expand=RootFolder&$filter=BaseTemplate%20eq%20101"), auth);
        Map<String, String> lists = new LinkedHashMap<String, String>();
        Set<String> covered = new LinkedHashSet<String>();
        for (JsonElement element : libraries) {
            JsonObject list = element.getAsJsonObject();
            if (list.has("Hidden") && list.get("Hidden").getAsBoolean()) continue;
            JsonObject root = list.getAsJsonObject("RootFolder");
            String path = root == null ? null : string(root, "ServerRelativeUrl");
            if (!insideSite(path)) continue;
            for (String configured : roots) {
                if (configured.equals(path) || configured.startsWith(path + "/")) {
                    String id = SharePointChangeLog.guid(string(list, "Id"));
                    lists.put(id, path);
                    covered.add(configured);
                }
            }
        }
        if (!covered.containsAll(roots)) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: Änderungsumfang nicht vollständig");
        }
        return lists;
    }

    private JsonObject changedFile(JsonObject event, String listEndpoint, String listId, String auth)
            throws KnowledgeSourceException {
        String change = string(event, "ChangeType");
        if (!"1".equals(change) && !"2".equals(change)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: strukturelle Änderung erfordert Inventur");
        }
        String eventList = string(event, "ListId");
        if (eventList != null && !listId.equalsIgnoreCase(eventList)) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: fremder Änderungsdatensatz");
        }
        JsonObject meta = event.getAsJsonObject("__metadata");
        String kind = meta == null ? null : string(meta, "type");
        String fields = "Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel";
        if ("SP.ChangeFile".equals(kind)) {
            String id = SharePointChangeLog.guid(string(event, "UniqueId"));
            return get(api("GetFileById(guid'" + id + "')?$select=" + fields), auth);
        }
        if ("SP.ChangeItem".equals(kind)) {
            long item = number(event, "ItemId");
            if (item < 1 || item > Integer.MAX_VALUE) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: fehlende Element-ID");
            }
            JsonObject record = get(api(listEndpoint + "/items(" + item + ")?$select=FileSystemObjectType,File/"
                    + fields.replace(",", ",File/") + "&$expand=File"), auth);
            if ("0".equals(string(record, "FileSystemObjectType")) && record.has("File") && record.get("File").isJsonObject()) {
                return record.getAsJsonObject("File");
            }
        }
        throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: nicht eindeutig einer Datei zuordenbare Änderung");
    }

    @Override
    public List<SourceSearchHit> search(SourceQuery query) throws KnowledgeSourceException {
        String authorization = authorization();
        List<String> roots = searchRoots(authorization);
        if (roots.isEmpty()) return Collections.emptyList();
        String expression = SharePointSearch.query(query, roots, this::location);
        Map<String, SourceSearchHit> matches = new LinkedHashMap<String, SourceSearchHit>();
        for (int start = 0; start <= 50000 && matches.size() < query.limit();) {
            int pageSize = Math.min(100, query.limit() - matches.size());
            URI endpoint = URI.create(trimTrailingSlash(siteUri.toString()) + "/_api/search/query?querytext="
                    + odataString(expression) + "&rowlimit=" + pageSize + "&startrow=" + start
                    + "&trimduplicates=false&selectproperties='Path,Filename,Title,LastModifiedTime,IsDocument'");
            List<Map<String, String>> rows = SharePointSearch.rows(get(endpoint, authorization));
            if (rows.isEmpty()) break;
            for (Map<String, String> row : rows) {
                SourceSearchHit hit = searchHit(row, roots, query);
                if (hit != null && matches.size() < query.limit()) matches.putIfAbsent(hit.resourceId().value(), hit);
            }
            start += rows.size();
            if (rows.size() < pageSize) break;
        }
        return new ArrayList<SourceSearchHit>(matches.values());
    }

    private List<String> searchRoots(String authorization) throws KnowledgeSourceException {
        Set<String> roots = new LinkedHashSet<String>();
        for (String start : configuredScope.startPoints()) {
            if (ALL_LIBRARIES.equals(start)) {
                roots.addAll(libraries(authorization));
            } else {
                roots.add(serverRelative(start));
            }
        }
        for (String root : roots) {
            if (!insideSite(root)) throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: Suchumfang ungültig");
        }
        return new ArrayList<String>(roots);
    }

    private SourceSearchHit searchHit(Map<String, String> row, List<String> roots, SourceQuery query) {
        if (!"true".equalsIgnoreCase(row.get("IsDocument"))) return null;
        URI url;
        try {
            url = URI.create(row.getOrDefault("Path", "").replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (url.getHost() == null || !siteUri.getHost().equalsIgnoreCase(url.getHost())
                || !siteUri.getScheme().equalsIgnoreCase(url.getScheme())
                || effectivePort(siteUri) != effectivePort(url) || url.getRawUserInfo() != null
                || url.getRawFragment() != null || !insideSite(url.getPath())) return null;
        String path = url.getPath();
        boolean allowed = false;
        for (String root : roots) {
            if (path.startsWith(root + "/")) { allowed = true; break; }
        }
        if (!allowed) return null;
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith("~$") || !INDEXABLE.contains(detector.detect(name, null, null).category())) return null;
        if (query.titleOnly() && !name.toLowerCase(Locale.ROOT).contains(query.text().toLowerCase(Locale.ROOT))) return null;
        String relative = path.substring(sitePath.length() + 1);
        KnowledgeResourceId id = KnowledgeResourceId.of(SCHEME, locator + "/" + relative);
        return new SourceSearchHit(id, name, "", KnowledgeRevision.unknown(), location(path));
    }

    @Override
    public String localId(KnowledgeResourceId resourceId) {
        try {
            if (isFolderId(resourceId)) folderPath(resourceId);
            else serverRelativeOf(resourceId);
            // Preserve the existing encoded resource IDs in the Harvester cache.
            return Base64.getUrlEncoder().withoutPadding().encodeToString(resourceId.value().getBytes(StandardCharsets.UTF_8));
        } catch (KnowledgeSourceException invalid) {
            return null;
        }
    }

    private KnowledgeResourceId decodeLocalId(String localId) throws KnowledgeSourceException {
        try {
            if (localId == null) throw new IllegalArgumentException("missing id");
            KnowledgeResourceId id = KnowledgeResourceId.of(new String(Base64.getUrlDecoder().decode(localId), StandardCharsets.UTF_8));
            if (localId.equals(localId(id))) return id;
        } catch (IllegalArgumentException ignored) {
            // Reject invalid and foreign identifiers.
        }
        throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: ungültige Ressourcen-ID");
    }

    @Override
    public KnowledgeResource resourceMetadata(String localId) throws KnowledgeSourceException {
        KnowledgeResourceId id = decodeLocalId(localId);
        if (isFolderId(id)) return folderResource(folderPath(id));
        String path = serverRelativeOf(id);
        JsonObject metadata = get(resourceApi("File", path, "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel"), authorization());
        KnowledgeResource resource = resourceOf(metadata, false);
        if (resource == null || !path.equals(string(metadata, "ServerRelativeUrl"))) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: unvollständige Dateiangaben");
        }
        return resource;
    }

    private static final String FOLDER_SCHEME = "sharepoint-folder";

    private KnowledgeResourceId folderId(String path) {
        return KnowledgeResourceId.of(FOLDER_SCHEME, locator + "/" + path.substring(sitePath.length() + 1));
    }

    private boolean isFolderId(KnowledgeResourceId id) {
        return id.value().startsWith(FOLDER_SCHEME + ":");
    }

    private String folderPath(KnowledgeResourceId id) throws KnowledgeSourceException {
        String prefix = FOLDER_SCHEME + ":" + locator + "/";
        if (!id.value().startsWith(prefix) || id.value().length() == prefix.length()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: fremder Ordner");
        }
        String path = sitePath + "/" + id.value().substring(prefix.length());
        if (!insideSite(path)) throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: fremder Ordner");
        return path;
    }

    private KnowledgeResource folderResource(String path) {
        return KnowledgeResource.builder(folderId(path), sourceId)
                .title(path.substring(path.lastIndexOf('/') + 1))
                .scope(sourceId.value()).revision(KnowledgeRevision.unknown()).build();
    }

    private NavigationNode folderNode(String path) {
        return NavigationNode.folder(folderId(path), path.substring(path.lastIndexOf('/') + 1), NavigationNode.Children.UNKNOWN);
    }

    private NavigationNode fileNode(KnowledgeResource resource) {
        return NavigationNode.page(resource.id(), resource.title(), NavigationNode.Children.NO);
    }

    @Override
    public NavigationChildren children(NavigationNode parent, String cursor) throws KnowledgeSourceException {
        String authorization = authorization();
        List<String> roots = searchRoots(authorization);
        if (parent.isRoot()) {
            if (cursor != null && !cursor.isEmpty()) {
                throw new InvalidNavigationCursorException("SharePoint: ungültiger Navigationscursor");
            }
            List<NavigationNode> nodes = new ArrayList<NavigationNode>();
            for (String root : roots) nodes.add(folderNode(root));
            return new NavigationChildren(nodes, "");
        }
        if (!isFolderId(parent.resource())) {
            if (cursor != null && !cursor.isEmpty()) {
                throw new InvalidNavigationCursorException("SharePoint: ungültiger Navigationscursor");
            }
            return new NavigationChildren(Collections.<NavigationNode>emptyList(), "");
        }
        String folder = folderPath(parent.resource());
        if (!withinRoots(folder, roots)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: Ordner außerhalb des Umfangs");
        }
        URI folders = resourceApi("Folder", folder, "/Folders?$select=Name,ServerRelativeUrl&$top=" + SharePointNavigationPages.BATCH_SIZE);
        URI files = resourceApi("Folder", folder, "/Files?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel&$top=" + SharePointNavigationPages.BATCH_SIZE);
        return navigationPages.folder(parent.resource().value(), cursor, folders, files,
                (first, current, isFolderPage) -> navigationPage(folder, first, current, isFolderPage, authorization));
    }

    private SharePointNavigationPages.Page navigationPage(String folder, URI first, URI current, boolean folders,
                                                          String authorization) throws KnowledgeSourceException {
        JsonObject data = get(current, authorization);
        JsonElement rows = data.get("results");
        if (rows == null || !rows.isJsonArray()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: OData-Ergebnisliste fehlt");
        }
        List<NavigationNode> nodes = new ArrayList<NavigationNode>();
        for (JsonElement element : rows.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: ungültiger OData-Eintrag");
            }
            JsonObject entry = element.getAsJsonObject();
            String path = string(entry, "ServerRelativeUrl");
            String name = string(entry, "Name");
            if (!insideSite(path) || !path.startsWith(folder + "/") || path.lastIndexOf('/') != folder.length()
                    || name == null) continue;
            if (folders) {
                if (!"Forms".equalsIgnoreCase(name)) nodes.add(folderNode(path));
            } else {
                KnowledgeResource resource = resourceOf(entry, false);
                if (resource != null && !resource.title().startsWith("~$")) nodes.add(fileNode(resource));
            }
        }
        return new SharePointNavigationPages.Page(nodes, continuation(first, current, data));
    }


    private static boolean withinRoots(String path, List<String> roots) {
        for (String root : roots) {
            if (path.equals(root) || path.startsWith(root + "/")) return true;
        }
        return false;
    }

    @Override
    public PagePosition position(KnowledgeResourceId page) throws KnowledgeSourceException {
        List<String> roots = searchRoots(authorization());
        String path = isFolderId(page) ? folderPath(page) : serverRelativeOf(page);
        String root = null;
        for (String candidate : roots) {
            if ((path.equals(candidate) || path.startsWith(candidate + "/"))
                    && (root == null || candidate.length() < root.length())) root = candidate;
        }
        if (root == null) throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: Ressource außerhalb des Umfangs");
        List<NavigationNode> ancestors = new ArrayList<NavigationNode>();
        if (!path.equals(root)) {
            ancestors.add(folderNode(root));
            int slash = path.indexOf('/', root.length() + 1);
            while (slash >= 0) {
                ancestors.add(folderNode(path.substring(0, slash)));
                slash = path.indexOf('/', slash + 1);
            }
        }
        NavigationNode site = NavigationNode.root(sourceId.value(), locator, sourceId.value(), NavigationNode.Children.YES);
        NavigationNode node = isFolderId(page) ? folderNode(path) : fileNode(resourceMetadata(localId(page)));
        return new PagePosition(site, ancestors, node);
    }

    @Override
    public CrossReferences crossReferences(KnowledgeResourceId page) throws KnowledgeSourceException {
        if (isFolderId(page)) folderPath(page);
        else serverRelativeOf(page);
        return CrossReferences.outgoingOnly(Collections.emptyList());
    }

    @Override
    public java.util.Optional<String> downloadOriginal(String localId, java.nio.file.Path target,
            java.util.function.Predicate<String> wanted, long maxBytes) throws KnowledgeSourceException {
        KnowledgeResourceId id = decodeLocalId(localId);
        if (isFolderId(id)) return java.util.Optional.empty();
        KnowledgeResource before = resourceMetadata(localId);
        String type = before.contentType();
        if (!wanted.test(type)) return java.util.Optional.empty();
        long length = Long.parseLong(before.metadata().asMap().get("file.size"));
        if (maxBytes > 0 && length > maxBytes) {
            throw new ResponseTooLargeException("SharePoint: Datei überschreitet das Limit", maxBytes);
        }
        URI uri = resourceApi("File", serverRelativeOf(id), "/$value");
        requireApiUri(uri);
        java.nio.file.Path temporary = null;
        try {
            temporary = java.nio.file.Files.createTempFile(target.toAbsolutePath().getParent(), "sharepoint-", ".part");
            SharePointTransport.Response response = transport.download(uri, authorization(), temporary, length);
            requireSuccess(response);
            if (response.status() != 200) throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: kein vollständiger Download");
            KnowledgeResource after = resourceMetadata(localId);
            if (!before.revision().equals(after.revision()) || !before.metadata().asMap().get("file.size")
                    .equals(after.metadata().asMap().get("file.size")) || java.nio.file.Files.size(temporary) != length) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint: Datei änderte sich beim Abruf oder ist unvollständig");
            }
            java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return java.util.Optional.of(type);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint: Originalabruf fehlgeschlagen");
        } finally {
            if (temporary != null) {
                try { java.nio.file.Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    @Override
    public StoredContent storedContent(String localId) throws KnowledgeSourceException {
        KnowledgeResourceId id = decodeLocalId(localId);
        if (isFolderId(id)) throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: Ordner ist kein Dokument");
        KnowledgeResource before = resourceMetadata(localId);
        long length = Long.parseLong(before.metadata().asMap().get("file.size"));
        if (length > maxFileBytes) throw new ResponseTooLargeException("SharePoint: Datei überschreitet das Limit", maxFileBytes);
        byte[] bytes = request(resourceApi("File", serverRelativeOf(id), "/$value"),
                null, authorization(), maxFileBytes).body();
        KnowledgeResource after = resourceMetadata(localId);
        if (!before.revision().equals(after.revision()) || !before.metadata().asMap().get("file.size")
                .equals(after.metadata().asMap().get("file.size")) || bytes.length != length) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint: Datei änderte sich beim Abruf oder ist unvollständig");
        }
        DetectedContentType type = detector.detect(before.title(), null, Arrays.copyOf(bytes, Math.min(bytes.length, PREFIX_BYTES)));
        return new StoredContent(after.toBuilder().contentType(type.mimeType()).build(), bytes, type.mimeType(), "file", Instant.now());
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String path = serverRelativeOf(resourceId);
        String authorization = authorization();
        JsonObject metadata = get(resourceApi("File", path, "?$select=Name,ServerRelativeUrl,TimeLastModified,Length,UIVersionLabel"),
                authorization);
        KnowledgeResource resource = resourceOf(metadata, false);
        if (resource == null || !path.equals(string(metadata, "ServerRelativeUrl"))) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, resourceId + ": unvollständige Dateiangaben");
        }
        long length = number(metadata, "Length");
        if (length > maxFileBytes) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " ist größer als " + maxFileBytes + " Bytes");
        }
        SharePointTransport.Response content = request(resourceApi("File", path, "/$value"), null, authorization, maxFileBytes);
        byte[] bytes = content.body();
        String name = resource.title();
        DetectedContentType type = detector.detect(name, null, Arrays.copyOf(bytes, Math.min(bytes.length, PREFIX_BYTES)));
        ResourceExtractor extractor = extractors.findExtractor(type);
        if (extractor == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + ": kein Extraktor für " + type.mimeType());
        }
        ExtractionResult result = extractor.extract(new ExtractionRequest(resourceId.value(), bytes, name, null, type));
        if (!result.isSuccess()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, resourceId + ": " + result.errorMessage());
        }
        return KnowledgeDocument.of(resource.toBuilder().contentType(type.mimeType()).build(),
                DocumentText.of(name, result.document()));
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        serverRelativeOf(resourceId);
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "SharePointKnowledgeSource{" + sourceId + ", " + locator + "}";
    }

    // ── Bibliotheken, Ressourcen ─────────────────────────────────────────────────────────────────────────────

    /** Serverrelative Wurzelordner aller sichtbaren Dokumentbibliotheken (BaseTemplate 101). */
    private List<String> libraries(String authorization) throws KnowledgeSourceException {
        JsonArray lists = collection(api("lists?$select=Title,Hidden,BaseTemplate,RootFolder/ServerRelativeUrl"
                + "&$expand=RootFolder&$filter=BaseTemplate%20eq%20101"), authorization);
        List<String> roots = new ArrayList<String>();
        for (JsonElement element : lists) {
            JsonObject list = element.getAsJsonObject();
            if (list.has("Hidden") && list.get("Hidden").isJsonPrimitive() && list.get("Hidden").getAsBoolean()) {
                continue;
            }
            JsonElement root = list.get("RootFolder");
            String path = root != null && root.isJsonObject() ? string(root.getAsJsonObject(), "ServerRelativeUrl") : null;
            if (insideSite(path)) {
                roots.add(path);
            }
        }
        return roots;
    }

    /** Ressource einer Datei oder {@code null}, wenn sie nicht zur Site gehört oder (beim Lauf) nicht lesbar ist. */
    private KnowledgeResource resourceOf(JsonObject file, boolean filterByType) {
        String name = string(file, "Name");
        String path = string(file, "ServerRelativeUrl");
        if (name == null || path == null || !insideSite(path)) {
            return null;
        }
        long length = number(file, "Length");
        DetectedContentType type = detector.detect(name, null, null);
        if (filterByType && (name.startsWith("~$") || length == 0
                || !INDEXABLE.contains(type.category()))) {
            return null;
        }
        String relative = path.substring(sitePath.length() + 1);
        Instant modified = instant(string(file, "TimeLastModified"));
        String version = string(file, "UIVersionLabel");
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("sharepoint.path", path);
        metadata.put("resource.kind", "file");
        metadata.put("file.size", String.valueOf(length));
        KnowledgeResource.Builder builder = KnowledgeResource.builder(KnowledgeResourceId.of(SCHEME, locator + "/" + relative), sourceId)
                .title(name)
                .contentType(type.mimeType())
                .revision(modified == null ? KnowledgeRevision.version(version == null ? String.valueOf(length) : version)
                        : KnowledgeRevision.of(modified, version == null ? String.valueOf(length) : version))
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata));
        URI location = location(path);
        if (location != null) {
            builder.location(location);
        }
        return builder.build();
    }

    private boolean insideSite(String path) {
        if (path == null || !path.startsWith(sitePath + "/") || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment) || ".".equals(segment)) return false;
        }
        return true;
    }

    private URI location(String serverRelative) {
        try {
            return new URI(siteUri.getScheme(), null, siteUri.getHost(), siteUri.getPort(), serverRelative, null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String serverRelative(String startPoint) {
        String trimmed = trimTrailingSlash(startPoint.trim().replace('\\', '/'));
        if (trimmed.startsWith("/")) {
            return trimmed;
        }
        return sitePath + "/" + trimmed;
    }

    private String serverRelativeOf(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = resourceId.value();
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        String relative = value.substring(prefix.length());
        if (!insideSite(sitePath + "/" + relative)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " gehört nicht zur Quelle " + sourceId);
        }
        return sitePath + "/" + relative;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Basic-Anmeldung aus frischen Anmeldedaten; {@code null} ohne Anmeldedaten. */
    private String authorization() throws KnowledgeSourceException {
        SharePointCredentials c;
        try {
            c = credentials.credentials();
        } catch (Exception e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "SharePoint " + sourceId
                    + ": Anmeldedaten nicht verfügbar (KeePass)");
        }
        if (c == null) {
            return null;
        }
        try {
            java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(c.password()));
            byte[] user = (c.username() + ":").getBytes(StandardCharsets.UTF_8);
            byte[] joined = new byte[user.length + encoded.remaining()];
            System.arraycopy(user, 0, joined, 0, user.length);
            encoded.get(joined, user.length, encoded.limit());
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
            String header = "Basic " + Base64.getEncoder().encodeToString(joined);
            Arrays.fill(joined, (byte) 0);
            return header;
        } finally {
            c.clear();
        }
    }

    /** Keep ordinary URLs compatible with older servers; use the decoded ResourcePath API for ambiguous names. */
    private URI resourceApi(String kind, String path, String suffix) throws KnowledgeSourceException {
        if (path.indexOf('#') >= 0 || path.indexOf('%') >= 0 || path.indexOf('+') >= 0) {
            return api("Get" + kind + "ByServerRelativePath(decodedurl=@p)" + suffix
                    + (suffix.contains("?") ? "&" : "?") + "@p=" + odataString(path));
        }
        return api("Get" + kind + "ByServerRelativeUrl(" + odataString(path) + ")" + suffix);
    }

    private URI api(String relative) throws KnowledgeSourceException {
        try {
            return new URI(trimTrailingSlash(siteUri.toString()) + "/_api/web" + (relative.startsWith("?") ? "" : "/") + relative);
        } catch (URISyntaxException e) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint " + sourceId + ": ungültiger Pfad", e);
        }
    }

    private JsonObject get(URI uri, String authorization) throws KnowledgeSourceException {
        return json(request(uri, JSON, authorization, MAX_JSON_BYTES));
    }

    private SharePointTransport.Response post(URI uri, String authorization, String digest, byte[] body)
            throws KnowledgeSourceException {
        requireApiUri(uri);
        String path = uri.getPath();
        if (!(sitePath + "/_api/contextinfo").equals(path)
                && !path.matches(java.util.regex.Pattern.quote(sitePath + "/_api/web/lists(guid'")
                        + "[0-9a-f-]{36}" + java.util.regex.Pattern.quote("')/GetChanges"))) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "SharePoint: POST-Endpunkt nicht freigegeben");
        }
        SharePointTransport.Response response;
        try {
            response = transport.post(uri, authorization, digest, body, MAX_JSON_BYTES);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint: Änderungsabfrage nicht erreichbar");
        }
        requireSuccess(response);
        return response;
    }

    private JsonObject json(SharePointTransport.Response response) throws KnowledgeSourceException {
        if (response.contentType() == null || !response.contentType().toLowerCase(Locale.ROOT).contains("json")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: REST-Antwort ist kein JSON");
        }
        try {
            JsonElement root = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("d") || !root.getAsJsonObject().get("d").isJsonObject()) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint " + sourceId
                        + ": unerwartete Antwort (kein OData-JSON)");
            }
            return root.getAsJsonObject().getAsJsonObject("d");
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint " + sourceId
                    + ": unerwartete Antwort (kein JSON)", e);
        }
    }

    private SharePointTransport.Response request(URI uri, String accept, String authorization, long maxBytes)
            throws KnowledgeSourceException {
        requireApiUri(uri);
        SharePointTransport.Response response;
        try {
            response = transport.get(uri, accept, authorization, maxBytes);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint " + sourceId + " nicht erreichbar", e);
        }
        requireSuccess(response);
        return response;
    }

    private void requireSuccess(SharePointTransport.Response response) throws KnowledgeSourceException {
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "SharePoint " + sourceId
                    + ": Zugriff verweigert (HTTP " + status + ")");
        }
        if (status == 404) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "SharePoint " + sourceId + ": nicht gefunden");
        }
        if (status >= 500) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint " + sourceId + ": HTTP " + status);
        }
        if (status / 100 != 2) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint " + sourceId + ": HTTP " + status);
        }
    }

    // ── JSON ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Follow server-issued OData continuations only within the same API and origin. */
    private JsonArray collection(URI first, String authorization) throws KnowledgeSourceException {
        JsonArray all = new JsonArray();
        visitCollection(first, authorization, () -> {}, all::add, () -> false, true);
        return all;
    }

    private void visitCollection(URI first, String authorization, Runnable checkpoint, Consumer<JsonElement> found,
                                 java.util.function.BooleanSupplier done, boolean strict) throws KnowledgeSourceException {
        Set<URI> visited = new LinkedHashSet<URI>();
        URI next = first;
        while (next != null && !done.getAsBoolean()) {
            checkpoint.run();
            if (Thread.currentThread().isInterrupted()) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint: Abruf abgebrochen");
            }
            if (!visited.add(next)) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: wiederholte OData-Folgeseite");
            }
            JsonObject data;
            try {
                data = get(next, authorization);
            } catch (KnowledgeSourceException e) {
                if (!strict && visited.size() > 1 && e.kind() == Kind.NOT_FOUND) {
                    throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: OData-Folgeseite fehlt");
                }
                throw e;
            }
            JsonElement rows = data.get("results");
            if (rows == null || !rows.isJsonArray()) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: OData-Ergebnisliste fehlt");
            }
            for (JsonElement row : rows.getAsJsonArray()) {
                checkpoint.run();
                if (done.getAsBoolean()) break;
                found.accept(row);
            }
            next = continuation(first, next, data);
        }
    }

    private URI continuation(URI first, URI current, JsonObject data) throws KnowledgeSourceException {
        String continuation = string(data, "__next");
        if (continuation == null || continuation.isEmpty()) return null;
        try {
            URI next = continuation.startsWith("?")
                    ? URI.create(current.toString().split("\\?", 2)[0] + continuation)
                    : current.resolve(URI.create(continuation));
            requireApiUri(next);
            if (!first.getPath().equals(next.getPath()) || !java.util.Objects.equals(pathAlias(first), pathAlias(next))) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: fremde OData-Folgeseite");
            }
            return next;
        } catch (IllegalArgumentException invalid) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: ungültige OData-Folgeseite");
        }
    }


    private static String pathAlias(URI uri) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        String value = null;
        for (String part : query.split("&")) {
            if (part.startsWith("@p=")) {
                if (value != null) return "duplicate";
                value = part.substring(3);
            }
        }
        return value;
    }

    private void requireApiUri(URI uri) throws KnowledgeSourceException {
        if (uri.getHost() == null || !siteUri.getHost().equalsIgnoreCase(uri.getHost())
                || !siteUri.getScheme().equalsIgnoreCase(uri.getScheme())
                || effectivePort(siteUri) != effectivePort(uri) || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getPath() == null
                || !uri.getPath().startsWith(sitePath + "/_api/")
                || !uri.normalize().getPath().equals(uri.getPath())) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: fremder REST-Endpunkt abgelehnt");
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        String value = string(object, key);
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Serverrelativer Pfad als OData-Zeichenkette in der URL: {@code '} verdoppelt, alles außer unreservierten
     * Zeichen, {@code /} und {@code '} prozentkodiert (UTF-8).
     */
    static String odataString(String path) {
        String quoted = "'" + path.replace("'", "''") + "'";
        StringBuilder sb = new StringBuilder();
        for (byte b : quoted.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.'
                    || c == '_' || c == '~' || c == '/' || c == '\'') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
