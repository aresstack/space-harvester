package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeRevision;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedBlock;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedDocument;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PageNavigationSource;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.files.FilePatterns;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocketFactory;

/**
 * One ref of a Git repository as {@link KnowledgeSourcePort} with a folder tree ({@link PageNavigationSource}).
 *
 * <ul>
 *   <li>State: {@link #discover} fetches the ref into the bare cache (at most every {@code refreshMillis}) and pins
 *       the commit; {@link #load} and navigation read the commit fetched last, without network once there is one.
 *       Files always come from the tree of that commit, never from a working tree.</li>
 *   <li>Resource ids: {@code git:<repository>[@<ref>]/<path>}, independent of the commit; the revision is the blob
 *       id, so the index skips unchanged files and prunes deleted or renamed ones after a run. Folders have ids
 *       ending in {@code /}; they appear in navigation only, never in discovery.</li>
 *   <li>Start points are paths relative to the root ({@code .} = everything); a folder is read down to
 *       {@code maxDepth} levels, from a file discovery follows relative Markdown links up to {@code maxDepth}.</li>
 *   <li>Not listed: submodules (gitlinks), symbolic links, Git LFS pointers, empty and oversized files, excluded
 *       paths and unknown file types ({@link GitFiles}).</li>
 * </ul>
 */
public final class GitKnowledgeSource implements KnowledgeSourcePort, PageNavigationSource {

    static final String SCHEME = "git";
    private static final int CHILDREN_BATCH = 200;
    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");

    private final SourceAddress sourceId;
    private final GitRemote remote;
    private final String ref;
    private final String locator;
    private final String title;
    private final GitRepositoryCache cache;
    private final HttpRoutePort routes;
    private final SSLSocketFactory tls;
    private final GitCredentialsProvider credentials;
    private final int timeoutSeconds;
    private final FilePatterns patterns;
    private final long maxFileBytes;
    private final ContentDetector detector;
    private final ExtractionRegistry extractors;
    private final long refreshMillis;
    private final LongSupplier clock;

    private GitSnapshot current;
    private long fetchedAt;

    GitKnowledgeSource(SourceAddress sourceId, GitRemote remote, String ref, String locator, String title,
                       GitRepositoryCache cache, HttpRoutePort routes, SSLSocketFactory tls,
                       GitCredentialsProvider credentials, int timeoutSeconds,
                       FilePatterns patterns, long maxFileBytes, ContentDetector detector,
                       ExtractionRegistry extractors, long refreshMillis, LongSupplier clock) {
        if (sourceId == null || remote == null || cache == null || patterns == null || detector == null
                || extractors == null || clock == null) {
            throw new IllegalArgumentException("sourceId, remote, cache, patterns, detector, extractors and clock "
                    + "are required");
        }
        this.sourceId = sourceId;
        this.remote = remote;
        this.ref = ref == null ? "" : ref.trim();
        this.locator = locator;
        this.title = title == null || title.trim().isEmpty() ? remote.locator() : title.trim();
        this.cache = cache;
        this.routes = routes;
        this.tls = tls;
        this.credentials = credentials == null ? GitCredentialsProvider.none() : credentials;
        this.timeoutSeconds = timeoutSeconds;
        this.patterns = patterns;
        this.maxFileBytes = maxFileBytes;
        this.detector = detector;
        this.extractors = extractors;
        this.refreshMillis = refreshMillis;
        this.clock = clock;
    }

    @Override
    public SourceAddress sourceId() {
        return sourceId;
    }

    // ------------------------------------------------------------------ KnowledgeSourcePort

    @Override
    public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
        final GitSnapshot snapshot = snapshot(true);
        try (Repository repository = repository(); ObjectReader reader = repository.newObjectReader()) {
            RevTree tree = tree(repository, snapshot);
            Map<String, KnowledgeResource> found = new LinkedHashMap<String, KnowledgeResource>();
            for (String startPoint : scope.startPoints()) {
                if (found.size() >= scope.maxResources()) {
                    break;
                }
                String start = normalize(startPoint.trim().isEmpty() || ".".equals(startPoint.trim())
                        ? "" : startPoint.trim());
                if (start == null) {
                    continue;
                }
                if (start.isEmpty()) {
                    walk(repository, reader, tree, snapshot, "", scope, found);
                    continue;
                }
                try (TreeWalk entry = TreeWalk.forPath(repository, start, tree)) {
                    if (entry == null) {
                        continue;
                    }
                    if (entry.getFileMode(0) == FileMode.TREE) {
                        if (patterns.entersFolder(start)) {
                            walk(repository, reader, tree, snapshot, start, scope, found);
                        }
                    } else {
                        follow(repository, reader, tree, snapshot, start, scope, found);
                    }
                }
            }
            return new ArrayList<KnowledgeResource>(found.values());
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Git-Cache von " + remote + " nicht lesbar", e);
        }
    }

    @Override
    public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String path = pathOf(resourceId);
        GitSnapshot snapshot = snapshot(false);
        try (Repository repository = repository(); ObjectReader reader = repository.newObjectReader()) {
            RevTree tree = tree(repository, snapshot);
            if (path.isEmpty() || path.endsWith("/")) {
                return directory(repository, tree, snapshot, path);
            }
            try (TreeWalk entry = TreeWalk.forPath(repository, path, tree)) {
                if (entry == null || !patterns.acceptsFile(path)) {
                    throw notFound(resourceId);
                }
                FileMode mode = entry.getFileMode(0);
                if (mode == FileMode.TREE) {
                    throw notFound(resourceId);
                }
                if (!isFile(mode)) {
                    throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId
                            + ": Submodul oder symbolischer Link, wird nicht gelesen");
                }
                GitFiles.Kind kind = GitFiles.kindOf(path);
                if (kind == GitFiles.Kind.NONE) {
                    throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + ": Dateityp wird nicht gelesen");
                }
                ObjectId blob = entry.getObjectId(0);
                long size = reader.getObjectSize(blob, Constants.OBJ_BLOB);
                if (size > maxFileBytes) {
                    throw new KnowledgeSourceException(Kind.UNSUPPORTED, resourceId + " ist größer als "
                            + maxFileBytes + " Bytes");
                }
                byte[] bytes = kind == GitFiles.Kind.ASSET && size > 1024 ? new byte[0]
                        : reader.open(blob, Constants.OBJ_BLOB).getBytes((int) maxFileBytes);
                KnowledgeResource resource = resource(path, blob, size, snapshot);
                return KnowledgeDocument.of(resource, render(resourceId, path, kind, bytes, size, snapshot));
            }
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, resourceId + " nicht lesbar", e);
        }
    }

    @Override
    public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
        String path = pathOf(resourceId);
        GitSnapshot snapshot = snapshot(false);
        try (Repository repository = repository(); ObjectReader reader = repository.newObjectReader()) {
            RevTree tree = tree(repository, snapshot);
            if (path.isEmpty() || path.endsWith("/")) {
                if (!isDirectory(repository, tree, path)) {
                    throw notFound(resourceId);
                }
                return Collections.emptyList();
            }
            List<SourceLink> links = new ArrayList<SourceLink>();
            for (String target : links(repository, reader, tree, path, resourceId)) {
                links.add(new SourceLink(idOf(target), GitFiles.nameOf(target)));
            }
            return links;
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, resourceId + " nicht lesbar", e);
        }
    }

    // ------------------------------------------------------------------ PageNavigationSource

    @Override
    public PagePosition position(KnowledgeResourceId page) throws KnowledgeSourceException {
        String path = pathOf(page);
        GitSnapshot snapshot = snapshot(false);
        try (Repository repository = repository()) {
            RevTree tree = tree(repository, snapshot);
            boolean directory = path.isEmpty() || path.endsWith("/");
            String bare = directory && !path.isEmpty() ? path.substring(0, path.length() - 1) : path;
            if (directory ? !isDirectory(repository, tree, bare) : !navigable(repository, tree, path)) {
                throw notFound(page);
            }
            List<NavigationNode> ancestors = new ArrayList<NavigationNode>();
            int slash = bare.indexOf('/');
            while (slash > 0) {
                String folder = bare.substring(0, slash);
                ancestors.add(NavigationNode.page(idOf(folder + "/"), GitFiles.nameOf(folder),
                        NavigationNode.Children.YES));
                slash = bare.indexOf('/', slash + 1);
            }
            NavigationNode node = directory
                    ? NavigationNode.page(idOf(path), bare.isEmpty() ? title : GitFiles.nameOf(bare),
                    NavigationNode.Children.YES)
                    : NavigationNode.page(page, GitFiles.nameOf(path), NavigationNode.Children.NO);
            return new PagePosition(root(), ancestors, node);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, page + " nicht lesbar", e);
        }
    }

    @Override
    public NavigationChildren children(NavigationNode parent, String cursor) throws KnowledgeSourceException {
        String folder;
        if (parent.isRoot()) {
            folder = "";
        } else {
            String path = pathOf(parent.resource());
            if (!path.isEmpty() && !path.endsWith("/")) {
                return new NavigationChildren(Collections.<NavigationNode>emptyList(), "");
            }
            folder = path.isEmpty() ? "" : path.substring(0, path.length() - 1);
        }
        int offset = 0;
        try {
            offset = cursor == null || cursor.isEmpty() ? 0 : Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            offset = 0;
        }
        GitSnapshot snapshot = snapshot(false);
        try (Repository repository = repository()) {
            RevTree tree = tree(repository, snapshot);
            if (!isDirectory(repository, tree, folder)) {
                throw new KnowledgeSourceException(Kind.NOT_FOUND, "Verzeichnis " + folder + " nicht gefunden");
            }
            List<NavigationNode> all = entries(repository, tree, folder);
            int end = Math.min(all.size(), offset + CHILDREN_BATCH);
            List<NavigationNode> batch = offset >= all.size() ? Collections.<NavigationNode>emptyList()
                    : all.subList(offset, end);
            return new NavigationChildren(batch, end < all.size() ? String.valueOf(end) : "");
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Verzeichnis " + folder + " nicht lesbar", e);
        }
    }

    @Override
    public CrossReferences crossReferences(KnowledgeResourceId page) throws KnowledgeSourceException {
        return CrossReferences.outgoingOnly(discoverLinks(page));
    }

    @Override
    public String toString() {
        return "GitKnowledgeSource{" + sourceId + ", " + remote + (ref.isEmpty() ? "" : " " + ref) + "}";
    }

    // ------------------------------------------------------------------ Snapshot

    /** The pinned commit; with {@code refresh} fetched again once {@code refreshMillis} have passed. */
    private synchronized GitSnapshot snapshot(boolean refresh) throws KnowledgeSourceException {
        long now = clock.getAsLong();
        if (current != null && (!refresh || now - fetchedAt < refreshMillis)) {
            return current;
        }
        if (current == null && !refresh) {
            GitSnapshot cached = cache.cached(remote, ref);
            if (cached != null) {
                current = cached;
                return cached;
            }
        }
        current = fetch();
        fetchedAt = now;
        return current;
    }

    /** One fetch; for HTTP(S) over the application's route and trust, with credentials only for its duration. */
    private GitSnapshot fetch() throws KnowledgeSourceException {
        if (!remote.isHttp()) {
            return cache.fetch(remote, ref, null, timeoutSeconds);
        }
        GitCredentials access;
        try {
            access = credentials.credentials();
        } catch (Exception e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "Zugangsdaten für " + remote
                    + " nicht verfügbar (KeePass-Eintrag prüfen)");
        }
        try {
            return cache.fetch(remote, ref, new GitHttpTransport(routes, tls, access), timeoutSeconds);
        } finally {
            if (access != null) {
                access.clear();
            }
        }
    }

    private Repository repository() throws IOException {
        return GitRepositoryCache.open(cache.directoryOf(remote, ref));
    }

    private static RevTree tree(Repository repository, GitSnapshot snapshot) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseTree(ObjectId.fromString(snapshot.tree));
        }
    }

    // ------------------------------------------------------------------ Discovery

    /** All listable files below {@code folder} up to the depth of the scope, in path order. */
    private void walk(Repository repository, ObjectReader reader, RevTree tree, GitSnapshot snapshot, String folder,
                      SourceScope scope, Map<String, KnowledgeResource> found) throws IOException {
        int baseDepth = folder.isEmpty() ? 0 : depth(folder) + 1;
        try (TreeWalk walk = new TreeWalk(repository, reader)) {
            walk.addTree(tree);
            walk.setRecursive(false);
            if (!folder.isEmpty()) {
                walk.setFilter(PathFilter.create(folder));
            }
            while (walk.next() && found.size() < scope.maxResources()) {
                String path = walk.getPathString();
                if (walk.isSubtree()) {
                    boolean onTheWay = !folder.isEmpty() && (folder.equals(path) || folder.startsWith(path + "/"));
                    if (onTheWay || (depth(path) - baseDepth < scope.maxDepth() && patterns.entersFolder(path))) {
                        walk.enterSubtree();
                    }
                    continue;
                }
                KnowledgeResource resource = listable(reader, walk, path, snapshot);
                if (resource != null && !found.containsKey(path)) {
                    found.put(path, resource);
                }
            }
        }
    }

    /** A file start point and, up to the depth, the files its Markdown links lead to (breadth first). */
    private void follow(Repository repository, ObjectReader reader, RevTree tree, GitSnapshot snapshot, String start,
                        SourceScope scope, Map<String, KnowledgeResource> found) throws IOException {
        Deque<String[]> queue = new ArrayDeque<String[]>();
        Set<String> seen = new LinkedHashSet<String>();
        queue.add(new String[] {start, "0"});
        seen.add(start);
        while (!queue.isEmpty() && found.size() < scope.maxResources()) {
            String[] next = queue.poll();
            String path = next[0];
            int depth = Integer.parseInt(next[1]);
            try (TreeWalk entry = TreeWalk.forPath(repository, reader, path, tree)) {
                KnowledgeResource resource = entry == null ? null : listable(reader, entry, path, snapshot);
                if (resource == null) {
                    continue;
                }
                if (!found.containsKey(path)) {
                    found.put(path, resource);
                }
            }
            if (depth < scope.maxDepth()) {
                try {
                    for (String target : links(repository, reader, tree, path, idOf(path))) {
                        if (seen.add(target)) {
                            queue.add(new String[] {target, String.valueOf(depth + 1)});
                        }
                    }
                } catch (KnowledgeSourceException e) {
                    // Links of a single file that cannot be read are skipped.
                }
            }
        }
    }

    /** The resource of a file the source lists, or {@code null}. */
    private KnowledgeResource listable(ObjectReader reader, TreeWalk walk, String path, GitSnapshot snapshot)
            throws IOException {
        if (!isFile(walk.getFileMode(0)) || !patterns.acceptsFile(path)) {
            return null;
        }
        GitFiles.Kind kind = GitFiles.kindOf(path);
        if (kind == GitFiles.Kind.NONE) {
            return null;
        }
        ObjectId blob = walk.getObjectId(0);
        long size = reader.getObjectSize(blob, Constants.OBJ_BLOB);
        if (size == 0 || size > maxFileBytes) {
            return null;
        }
        if (size <= 1024 && GitFiles.isLfsPointer(reader.open(blob, Constants.OBJ_BLOB).getBytes())) {
            return null;
        }
        return resource(path, blob, size, snapshot);
    }

    private KnowledgeResource resource(String path, ObjectId blob, long size, GitSnapshot snapshot) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("git.remote", remote.uri());
        metadata.put("git.ref", snapshot.branch);
        metadata.put("git.commit", snapshot.commit);
        metadata.put("git.path", path);
        metadata.put("git.blob", blob.name());
        if (GitFiles.kindOf(path) == GitFiles.Kind.TEXT) {
            metadata.put("git.language", GitFiles.languageOf(path));
        }
        metadata.put("file.size", String.valueOf(size));
        int slash = path.lastIndexOf('/');
        KnowledgeResource.Builder builder = KnowledgeResource.builder(idOf(path), sourceId)
                .title(GitFiles.nameOf(path))
                .contentType(GitFiles.contentTypeOf(path))
                .revision(KnowledgeRevision.version(blob.name()))
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata));
        if (slash > 0) {
            builder.parentId(idOf(path.substring(0, slash + 1)));
        }
        URI location = remote.browserUrl(path, snapshot.branch, snapshot.commit);
        if (location != null) {
            builder.location(location);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ Navigation

    private NavigationNode root() {
        return NavigationNode.root(SCHEME + ":" + locator, title, ".", NavigationNode.Children.YES);
    }

    /** Folders first, then files, each by name ignoring case. */
    private List<NavigationNode> entries(Repository repository, RevTree tree, String folder) throws IOException {
        List<NavigationNode> folders = new ArrayList<NavigationNode>();
        List<NavigationNode> files = new ArrayList<NavigationNode>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(tree);
            walk.setRecursive(false);
            if (!folder.isEmpty()) {
                walk.setFilter(PathFilter.create(folder));
            }
            while (walk.next()) {
                String path = walk.getPathString();
                if (!folder.isEmpty() && (folder.equals(path) || folder.startsWith(path + "/"))) {
                    walk.enterSubtree();
                    continue;
                }
                if (walk.isSubtree()) {
                    if (patterns.entersFolder(path)) {
                        folders.add(NavigationNode.page(idOf(path + "/"), walk.getNameString(),
                                NavigationNode.Children.YES));
                    }
                } else if (isFile(walk.getFileMode(0)) && patterns.acceptsFile(path)
                        && GitFiles.kindOf(path) != GitFiles.Kind.NONE) {
                    files.add(NavigationNode.page(idOf(path), walk.getNameString(), NavigationNode.Children.NO));
                }
            }
        }
        Comparator<NavigationNode> byName = new Comparator<NavigationNode>() {
            @Override
            public int compare(NavigationNode a, NavigationNode b) {
                return a.title().compareToIgnoreCase(b.title());
            }
        };
        Collections.sort(folders, byName);
        Collections.sort(files, byName);
        List<NavigationNode> all = new ArrayList<NavigationNode>(folders);
        all.addAll(files);
        return all;
    }

    private KnowledgeDocument directory(Repository repository, RevTree tree, GitSnapshot snapshot, String path)
            throws IOException, KnowledgeSourceException {
        String folder = path.isEmpty() ? "" : path.substring(0, path.length() - 1);
        if (!isDirectory(repository, tree, folder)) {
            throw notFound(idOf(path));
        }
        ObjectId id = folder.isEmpty() ? tree.getId() : treeIdOf(repository, tree, folder);
        StringBuilder text = new StringBuilder("# ").append(folder.isEmpty() ? title : folder + "/");
        text.append("\n\nVerzeichnis in ").append(remote.locator()).append(" (").append(snapshot.branch)
                .append(", Commit ").append(snapshot.commit, 0, 12).append(")\n");
        for (NavigationNode entry : entries(repository, tree, folder)) {
            String relative = entry.title() + (entry.children() == NavigationNode.Children.YES ? "/" : "");
            text.append("\n- [").append(relative).append("](").append(relative.replace(" ", "%20")).append(')');
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("git.remote", remote.uri());
        metadata.put("git.ref", snapshot.branch);
        metadata.put("git.commit", snapshot.commit);
        metadata.put("git.path", path);
        KnowledgeResource.Builder resource = KnowledgeResource.builder(idOf(path), sourceId)
                .title(folder.isEmpty() ? title : GitFiles.nameOf(folder) + "/")
                .contentType("text/markdown")
                .revision(KnowledgeRevision.version(id.name()))
                .scope(sourceId.value())
                .metadata(KnowledgeMetadata.of(metadata));
        int slash = folder.lastIndexOf('/');
        if (slash > 0) {
            resource.parentId(idOf(folder.substring(0, slash + 1)));
        }
        return KnowledgeDocument.of(resource.build(), text.toString());
    }

    private boolean isDirectory(Repository repository, RevTree tree, String folder) throws IOException {
        if (folder.isEmpty()) {
            return true;
        }
        if (!patterns.entersFolder(folder)) {
            return false;
        }
        try (TreeWalk entry = TreeWalk.forPath(repository, folder, tree)) {
            return entry != null && entry.getFileMode(0) == FileMode.TREE;
        }
    }

    private ObjectId treeIdOf(Repository repository, RevTree tree, String folder) throws IOException {
        try (TreeWalk entry = TreeWalk.forPath(repository, folder, tree)) {
            return entry.getObjectId(0);
        }
    }

    private boolean navigable(Repository repository, RevTree tree, String path) throws IOException {
        try (TreeWalk entry = TreeWalk.forPath(repository, path, tree)) {
            return entry != null && isFile(entry.getFileMode(0)) && patterns.acceptsFile(path)
                    && GitFiles.kindOf(path) != GitFiles.Kind.NONE;
        }
    }

    // ------------------------------------------------------------------ Links

    /** Relative Markdown links of a file to other listable files of the snapshot, each once. */
    private List<String> links(Repository repository, ObjectReader reader, RevTree tree, String path,
                               KnowledgeResourceId id) throws IOException, KnowledgeSourceException {
        ObjectId blob;
        try (TreeWalk entry = TreeWalk.forPath(repository, reader, path, tree)) {
            if (entry == null || !isFile(entry.getFileMode(0)) || !patterns.acceptsFile(path)) {
                throw notFound(id);
            }
            blob = entry.getObjectId(0);
        }
        if (GitFiles.kindOf(path) != GitFiles.Kind.MARKDOWN
                || reader.getObjectSize(blob, Constants.OBJ_BLOB) > maxFileBytes) {
            return Collections.emptyList();
        }
        String text = GitFiles.decode(reader.open(blob, Constants.OBJ_BLOB).getBytes());
        String base = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        Set<String> targets = new LinkedHashSet<String>();
        Matcher matcher = MARKDOWN_LINK.matcher(text);
        while (matcher.find()) {
            String target = matcher.group(1);
            if (target.startsWith("#") || target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
                continue;
            }
            int cut = indexOfAny(target, '#', '?');
            if (cut >= 0) {
                target = target.substring(0, cut);
            }
            try {
                target = URLDecoder.decode(target.replace("+", "%2B"), "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                continue;
            }
            String resolved = normalize(target.startsWith("/") ? target.substring(1) : base + target);
            if (resolved == null || resolved.isEmpty() || resolved.equals(path)) {
                continue;
            }
            if (navigable(repository, tree, resolved)) {
                targets.add(resolved);
            }
        }
        return new ArrayList<String>(targets);
    }

    // ------------------------------------------------------------------ Text

    private String render(KnowledgeResourceId id, String path, GitFiles.Kind kind, byte[] bytes, long size,
                          GitSnapshot snapshot) throws KnowledgeSourceException {
        String name = GitFiles.nameOf(path);
        String origin = "`" + path + "` · " + snapshot.branch + " @ " + snapshot.commit.substring(0, 12);
        if (GitFiles.isLfsPointer(bytes)) {
            return "# " + name + "\n\n" + origin + "\n\nGit-LFS-Verweis: Der Inhalt liegt im LFS-Speicher des "
                    + "Servers und wird nicht nachgeladen.";
        }
        switch (kind) {
            case ASSET:
                boolean figma = name.toLowerCase(Locale.ROOT).endsWith(".fig");
                return "# " + name + "\n\n" + origin + "\n\n" + (figma
                        ? "Figma-Originaldatei (proprietäres Binärformat, " + size + " Bytes). Inhalt, Frames und "
                        + "Kommentare sind über Git nicht lesbar; die Datei ist nur über Pfad und Name auffindbar."
                        : "Bilddatei (" + GitFiles.contentTypeOf(path) + ", " + size + " Bytes), als Asset des "
                        + "Repositorys auffindbar; der Bildinhalt wird nicht als Text gelesen.");
            case DOCUMENT:
                return "# " + name + "\n\n" + origin + extracted(id, name, bytes);
            case MARKDOWN:
                if (GitFiles.isBinary(bytes)) {
                    throw binary(id);
                }
                String markdown = GitFiles.decode(bytes);
                return markdown.trim().startsWith("# ") ? markdown : "# " + name + "\n\n" + markdown;
            default:
                if (GitFiles.isBinary(bytes)) {
                    throw binary(id);
                }
                String code = GitFiles.decode(bytes);
                String fence = GitFiles.fence(code);
                return "# " + name + "\n\n" + origin + " · " + GitFiles.languageOf(path) + "\n\n" + fence
                        + GitFiles.languageOf(path) + "\n" + (code.endsWith("\n") ? code : code + "\n") + fence;
        }
    }

    private String extracted(KnowledgeResourceId id, String name, byte[] bytes) throws KnowledgeSourceException {
        DetectedContentType type = detector.detect(name, null, Arrays.copyOf(bytes, Math.min(bytes.length, 8192)));
        ResourceExtractor extractor = extractors.findExtractor(type);
        if (extractor == null) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, id + ": kein Extraktor für " + type.mimeType());
        }
        ExtractionResult result = extractor.extract(new ExtractionRequest(id.value(), bytes, name, null, type));
        if (!result.isSuccess()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, id + ": " + result.errorMessage());
        }
        StringBuilder sb = new StringBuilder();
        ExtractedDocument document = result.document();
        for (ExtractedBlock block : document.blocks()) {
            String text = block.text();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            sb.append("\n\n");
            switch (block.kind()) {
                case HEADING:
                    sb.append("## ").append(text.trim());
                    break;
                case CODE:
                    String fence = GitFiles.fence(text);
                    String language = block.attributes().get("language");
                    sb.append(fence).append(language == null ? "" : language).append('\n').append(text)
                            .append('\n').append(fence);
                    break;
                default:
                    sb.append(text.trim());
                    break;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ IDs and paths

    KnowledgeResourceId idOf(String path) {
        return KnowledgeResourceId.of(SCHEME, locator + "/" + path);
    }

    /** The path of an id of this source ({@code dir/} for folders, empty for the root folder). */
    private String pathOf(KnowledgeResourceId id) throws KnowledgeSourceException {
        String prefix = SCHEME + ":" + locator + "/";
        String value = id == null ? "" : id.value();
        if (!value.startsWith(prefix)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, id + " gehört nicht zur Quelle " + sourceId);
        }
        String path = value.substring(prefix.length());
        boolean folder = path.endsWith("/");
        String normalized = normalize(folder ? path.substring(0, path.length() - 1) : path);
        if (normalized == null || (!normalized.equals(folder ? path.substring(0, path.length() - 1) : path))) {
            throw notFound(id);
        }
        return folder && !normalized.isEmpty() ? normalized + "/" : normalized;
    }

    /** A path inside the repository with {@code /}; {@code null} if it leaves the root. */
    static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<String>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
                continue;
            }
            if (".git".equalsIgnoreCase(part)) {
                return null;
            }
            parts.addLast(part);
        }
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(part);
        }
        return result.toString();
    }

    private static boolean isFile(FileMode mode) {
        return mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE;
    }

    private static int depth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static int indexOfAny(String value, char a, char b) {
        int first = value.indexOf(a);
        int second = value.indexOf(b);
        return first < 0 ? second : second < 0 ? first : Math.min(first, second);
    }

    private static KnowledgeSourceException notFound(KnowledgeResourceId id) {
        return new KnowledgeSourceException(Kind.NOT_FOUND, id + " nicht gefunden");
    }

    private static KnowledgeSourceException binary(KnowledgeResourceId id) {
        return new KnowledgeSourceException(Kind.UNSUPPORTED, id + ": Binärdatei, wird nicht als Text gelesen");
    }
}
