package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.project.ProfileFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Favoriten des Benutzers (Angelo, 13.11.2026): Seiten (Quelle, Space, Seite) oder ganze Spaces, die er mit dem Stern
 * markiert hat. Gehören zum Bedienprofil der App, nicht zum Core und nicht in die Konfigurationsdatei: Datei
 * {@code favorites.json} im Datenordner der App. Ein Favorit merkt sich seinen Pfad (Space und Vorfahren) zur Anzeige.
 *
 * <p>Seit ADR 0034 (Angelo, 23.11.2026) ein Ordnerbaum nach {@code FavoritesStore} des Enterprise AI Client: Ordner mit
 * Unterordnern, jeder Favorit kommt im ganzen Baum höchstens einmal vor, Ordner dürfen gleich heißen. Alles läuft über
 * die IDs der Knoten, nie über Namen. Unbekannte oder ungültige Ziele landen in der obersten Ebene bzw. werden
 * abgelehnt, nie verworfen. Eine Datei im alten, flachen Format wird zur obersten Ebene; nichts geht verloren.
 */
public final class Favorites {

    /**
     * Ein Favorit: eine Seite ({@code pageId}), ein ganzer Space ({@code pageId} null) oder eine ganze Wissensquelle
     * ({@code spaceKey} und {@code pageId} null; Angelo, 16.11.2026, etwa um den Chat über die ganze Quelle zu
     * merken). {@code path} ist der Anzeigepfad (Quelle › Space › Vorfahren), {@code ancestorIds} die IDs der
     * Vorfahren im Seitenbaum.
     */
    public record Favorite(String sourceId, String spaceKey, String pageId, String title, List<String> path,
                           List<String> ancestorIds) {

        public Favorite {
            Objects.requireNonNull(sourceId, "sourceId");
            if (spaceKey == null && pageId != null) {
                throw new IllegalArgumentException("Ein Seiten-Favorit braucht seinen Space");
            }
            title = title == null || title.isBlank()
                    ? (spaceKey == null ? sourceId : pageId == null ? spaceKey : "Seite " + pageId) : title;
            path = path == null ? List.of() : List.copyOf(path);
            ancestorIds = ancestorIds == null ? List.of() : List.copyOf(ancestorIds);
        }

        public static Favorite space(String sourceId, String spaceKey, String name, String sourceTitle) {
            return new Favorite(sourceId, spaceKey, null, name, List.of(sourceTitle == null ? sourceId : sourceTitle),
                    List.of());
        }

        /**
         * Ein Seiten-Favorit aus dem Pfad der Seite (Quelle › Space › Vorfahren, dazu die IDs der Vorfahren); ohne
         * bekannte Position nur Quelle › Space. Der eine Weg für Stern im Baum und Stern an der Seite.
         */
        public static Favorite page(String sourceId, String spaceKey, String pageId, String title,
                                    com.aresstack.spaceharvester.application.navigation.PageNavigation.Position position,
                                    String sourceTitle) {
            List<String> path = new ArrayList<>();
            List<String> ancestors = new ArrayList<>();
            path.add(sourceTitle == null ? sourceId : sourceTitle);
            if (position == null || position.page() == null) {
                path.add(spaceKey);
                return new Favorite(sourceId, spaceKey, pageId, title, path, ancestors);
            }
            path.add(position.spaceName() == null || position.spaceName().isBlank() ? position.spaceKey()
                    : position.spaceName());
            for (com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode ancestor
                    : position.ancestors()) {
                path.add(ancestor.title());
                ancestors.add(ancestor.pageId());
            }
            return new Favorite(sourceId, position.spaceKey() == null ? spaceKey : position.spaceKey(), pageId,
                    position.page().title() == null ? title : position.page().title(), path, ancestors);
        }

        /** Eine ganze Wissensquelle. */
        public static Favorite source(String sourceId, String title) {
            return new Favorite(sourceId, null, null, title, List.of(), List.of());
        }

        /**
         * Die Wurzel dieses Favoriten für die Auswahl (ADR 0034 Abschnitt 1, die einzige Abbildung): dieselbe Menge wie
         * die Auswahl seines Knotens im Baum, eine Seite samt Unterseiten, ein Space oder eine Quelle ganz.
         */
        public com.aresstack.spaceharvester.application.holdings.Selection.Root root() {
            return new com.aresstack.spaceharvester.application.holdings.Selection.Root(sourceId, spaceKey, pageId);
        }

        /** Ob der Favorit eine ganze Wissensquelle ist. */
        public boolean isSource() {
            return spaceKey == null;
        }

        /** Derselbe Knoten, unabhängig von Titel und Pfad. */
        boolean same(String sourceId, String spaceKey, String pageId) {
            return this.sourceId.equals(sourceId) && Objects.equals(this.spaceKey, spaceKey)
                    && Objects.equals(this.pageId, pageId);
        }

        /** „Quelle › Space › Vorfahr“ für die Liste; leer für einen Space ohne gemerkten Pfad. */
        public String where() {
            return String.join(" › ", path);
        }
    }

    /** Ein Knoten des Baums: ein Ordner oder ein Favorit. */
    public sealed interface Entry permits Folder, Bookmark {

        /** Die ID des Knotens, fest über Neustarts. */
        String id();
    }

    /** Ein Ordner mit Namen und Kindern (Unterordner und Favoriten) in ihrer Reihenfolge. */
    public record Folder(String id, String name, List<Entry> children) implements Entry {

        public Folder {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            children = List.copyOf(children);
        }

        /** Alle Favoriten dieses Ordners, mit {@code recursive} auch die aller Unterordner, in Baumreihenfolge. */
        public List<Favorite> favorites(boolean recursive) {
            List<Favorite> out = new ArrayList<>();
            collect(children, recursive, out);
            return List.copyOf(out);
        }

        /** Der Unterordner {@code id} auf irgendeiner Ebene unter diesem Ordner (nie er selbst), falls vorhanden. */
        public java.util.Optional<Folder> subfolder(String id) {
            for (Entry entry : children) {
                if (entry instanceof Folder folder) {
                    if (folder.id().equals(id)) {
                        return java.util.Optional.of(folder);
                    }
                    java.util.Optional<Folder> below = folder.subfolder(id);
                    if (below.isPresent()) {
                        return below;
                    }
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** Ein Favorit an seiner Stelle im Baum. */
    public record Bookmark(String id, Favorite favorite) implements Entry {

        public Bookmark {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(favorite, "favorite");
        }
    }

    private static final Logger LOG = Logger.getLogger(Favorites.class.getName());
    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    static final String FILE = "favorites.json";
    static final int FORMAT_VERSION = 2;

    private final Supplier<Path> file;
    private List<Node> root;
    private Path loadedFrom;
    private final List<Runnable> listeners = new ArrayList<>();
    /** Wohin ein neuer Stern kommt: der in der Favoritenansicht gewählte Ordner ({@code null} = oberste Ebene). */
    private volatile Supplier<String> target = () -> null;

    /** @param file die Datei; {@code null}, solange kein Datenordner bekannt ist (dann nur im Speicher) */
    public Favorites(Supplier<Path> file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    // ------------------------------------------------------------------ Lesen

    /** Der Baum unterhalb der (unsichtbaren) obersten Ebene. */
    public synchronized List<Entry> tree() {
        return snapshot(load());
    }

    /** Alle Favoriten (ohne Ordner) in Baumreihenfolge. */
    public synchronized List<Favorite> all() {
        List<Favorite> out = new ArrayList<>();
        collect(tree(), true, out);
        return List.copyOf(out);
    }

    public synchronized boolean contains(String sourceId, String spaceKey, String pageId) {
        return findFavorite(load(), sourceId, spaceKey, pageId) != null;
    }

    /**
     * Die Favoriten eines Ordners ({@code null} = oberste Ebene), mit {@code recursive} auch die aller Unterordner, in
     * Baumreihenfolge; leer für einen unbekannten Ordner.
     */
    public synchronized List<Favorite> favoritesIn(String folderId, boolean recursive) {
        if (folderId == null) {
            List<Favorite> out = new ArrayList<>();
            collect(tree(), recursive, out);
            return List.copyOf(out);
        }
        return folder(folderId).map(found -> found.favorites(recursive)).orElse(List.of());
    }

    /** Der Ordner mit dieser ID, falls vorhanden. */
    public synchronized java.util.Optional<Folder> folder(String id) {
        Node node = find(load(), id);
        return node != null && node.isFolder() ? java.util.Optional.of((Folder) snapshot(node))
                : java.util.Optional.empty();
    }

    /** Ob {@code id} ein vorhandener Ordner ist. */
    public synchronized boolean isFolder(String id) {
        Node node = find(load(), id);
        return node != null && node.isFolder();
    }

    // ------------------------------------------------------------------ Ändern

    /**
     * Der Stern (ADR 0034): setzt den Favoriten in den Zielordner ({@link #target}) oder nimmt ihn zurück, egal in
     * welchem Ordner er liegt; liefert, ob er danach gesetzt ist. Stern im Baum und Stern an der Seite laufen hierüber.
     */
    public boolean toggle(Favorite favorite) {
        return toggle(favorite, target.get());
    }

    /** Woher {@link #toggle(Favorite)} den Zielordner nimmt (die Favoritenansicht meldet ihren gewählten Ordner). */
    public void target(Supplier<String> folder) {
        this.target = Objects.requireNonNull(folder, "folder");
    }

    /**
     * Stern geklickt: Ist der Knoten schon ein Favorit (egal in welchem Ordner), wird er entfernt; sonst kommt er ans
     * Ende des Ordners {@code folderId} ({@code null}, unbekannt oder kein Ordner = oberste Ebene).
     *
     * @return ob er danach gesetzt ist
     */
    public boolean toggle(Favorite favorite, String folderId) {
        Objects.requireNonNull(favorite, "favorite");
        boolean set;
        synchronized (this) {
            List<Node> nodes = load();
            Node existing = findFavorite(nodes, favorite.sourceId(), favorite.spaceKey(), favorite.pageId());
            if (existing != null) {
                removeNode(nodes, existing.id);
                set = false;
            } else {
                childrenOf(folderId).add(Node.favorite(newId(), favorite));
                set = true;
            }
            save();
        }
        notifyListeners();
        return set;
    }

    /** Legt einen Ordner am Ende von {@code parentId} an ({@code null}/unbekannt = oberste Ebene); {@code null} bei leerem Namen. */
    public String createFolder(String name, String parentId) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Node folder = Node.folder(newId(), name.trim());
        synchronized (this) {
            childrenOf(parentId).add(folder);
            save();
        }
        notifyListeners();
        return folder.id;
    }

    /** Benennt einen Ordner um. */
    public boolean renameFolder(String id, String name) {
        synchronized (this) {
            Node node = find(load(), id);
            if (node == null || !node.isFolder() || name == null || name.isBlank()) {
                return false;
            }
            node.name = name.trim();
            save();
        }
        notifyListeners();
        return true;
    }

    /** Entfernt einen Favoriten bzw. einen Ordner samt Inhalt (die Rückfrage stellt die Oberfläche). */
    public boolean remove(String id) {
        synchronized (this) {
            if (removeNode(load(), id) == null) {
                return false;
            }
            save();
        }
        notifyListeners();
        return true;
    }

    /** Verschiebt einen Knoten; siehe {@link #moveAll}. */
    public boolean move(String id, String targetFolderId, int index) {
        return moveAll(List.of(id), targetFolderId, index);
    }

    /**
     * Verschiebt mehrere Knoten als einen Block in Baumreihenfolge an Position {@code index} des Ordners
     * {@code targetFolderId} ({@code null} = oberste Ebene), gezählt in der Liste VOR dem Herausnehmen wie die
     * Einfügemarke im Baum (außerhalb = ans Ende). Ein Knoten, dessen Ordner mitwandert, wandert nur mit ihm. Abgelehnt
     * wird alles bei einem unbekannten Knoten, einem Ziel, das kein Ordner ist, und einem Ordner in sich selbst oder
     * seinen Unterbaum; gespeichert wird nur nach Erfolg.
     */
    public boolean moveAll(List<String> ids, String targetFolderId, int index) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        synchronized (this) {
            List<Node> nodes = load();
            Set<Node> requested = new LinkedHashSet<>();
            for (String id : ids) {
                Node node = find(nodes, id);
                if (node == null) {
                    return false;
                }
                requested.add(node);
            }
            List<Node> moved = new ArrayList<>();
            collectTopmost(nodes, requested, false, moved);
            List<Node> target;
            if (targetFolderId == null) {
                target = nodes;
            } else {
                Node folder = find(nodes, targetFolderId);
                if (folder == null || !folder.isFolder() || find(moved, targetFolderId) != null) {
                    return false;
                }
                target = folder.children;
            }
            if (index < 0 || index > target.size()) {
                index = target.size();
            }
            int insertAt = index;
            for (int i = 0; i < index; i++) {
                if (moved.contains(target.get(i))) {
                    insertAt--; // über der Einfügemarke wird die Liste kürzer
                }
            }
            for (Node node : moved) {
                removeNode(nodes, node.id);
            }
            target.addAll(insertAt, moved);
            save();
        }
        notifyListeners();
        return true;
    }

    /** Meldet jede Änderung (auf dem Thread, der sie auslöst). */
    public synchronized void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notifyListeners() {
        List<Runnable> copy;
        synchronized (this) {
            copy = List.copyOf(listeners);
        }
        for (Runnable listener : copy) {
            listener.run();
        }
    }

    // ------------------------------------------------------------------ Baum

    private List<Node> childrenOf(String folderId) {
        List<Node> nodes = load();
        Node folder = folderId == null ? null : find(nodes, folderId);
        return folder != null && folder.isFolder() ? folder.children : nodes;
    }

    private static void collect(List<Entry> entries, boolean recursive, List<Favorite> out) {
        for (Entry entry : entries) {
            switch (entry) {
                case Bookmark bookmark -> out.add(bookmark.favorite());
                case Folder folder -> {
                    if (recursive) {
                        collect(folder.children(), true, out);
                    }
                }
            }
        }
    }

    /** Die gewünschten Knoten in Baumreihenfolge, ohne die unter einem gewünschten Ordner (sie wandern mit ihm). */
    private static void collectTopmost(List<Node> nodes, Set<Node> requested, boolean underRequested,
                                       List<Node> out) {
        for (Node node : nodes) {
            boolean selected = requested.contains(node);
            if (selected && !underRequested) {
                out.add(node);
            }
            if (node.isFolder()) {
                collectTopmost(node.children, requested, underRequested || selected, out);
            }
        }
    }

    private static Node find(List<Node> nodes, String id) {
        if (id == null) {
            return null;
        }
        for (Node node : nodes) {
            if (node.id.equals(id)) {
                return node;
            }
            if (node.isFolder()) {
                Node found = find(node.children, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Node findFavorite(List<Node> nodes, String sourceId, String spaceKey, String pageId) {
        if (sourceId == null) {
            return null;
        }
        for (Node node : nodes) {
            if (node.isFolder()) {
                Node found = findFavorite(node.children, sourceId, spaceKey, pageId);
                if (found != null) {
                    return found;
                }
            } else if (node.favorite.same(sourceId, spaceKey, pageId)) {
                return node;
            }
        }
        return null;
    }

    private static Node removeNode(List<Node> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node.id.equals(id)) {
                return nodes.remove(i);
            }
            if (node.isFolder()) {
                Node removed = removeNode(node.children, id);
                if (removed != null) {
                    return removed;
                }
            }
        }
        return null;
    }

    private static List<Entry> snapshot(List<Node> nodes) {
        List<Entry> entries = new ArrayList<>();
        for (Node node : nodes) {
            entries.add(snapshot(node));
        }
        return List.copyOf(entries);
    }

    private static Entry snapshot(Node node) {
        return node.isFolder() ? new Folder(node.id, node.name, snapshot(node.children))
                : new Bookmark(node.id, node.favorite);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ Datei

    /** Liest die Datei beim ersten Zugriff und erneut, sobald ein anderer Datenordner gilt. */
    private List<Node> load() {
        Path path = file.get();
        if (root != null && Objects.equals(path, loadedFrom)) {
            return root;
        }
        loadedFrom = path;
        root = new ArrayList<>();
        if (path == null || !Files.isRegularFile(path)) {
            return root;
        }
        boolean converted = false;
        try {
            JsonNode json = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            Set<String> ids = new HashSet<>();
            List<Favorite> seen = new ArrayList<>();
            if (json.has("entries")) {
                root.addAll(fromEntries(json.path("entries"), ids, seen));
            } else {
                // Altes, flaches Format (bis ADR 0034): jeder Favorit in die oberste Ebene, vorher einmal gesichert.
                for (JsonNode node : json.path("favorites")) {
                    Node favorite = favorite(node, newId(), seen);
                    if (favorite != null) {
                        root.add(favorite);
                    }
                }
                converted = true;
            }
        } catch (IOException | RuntimeException e) {
            // Nie überschreiben: Die unlesbare Datei kommt in Quarantäne, die Favoriten beginnen leer.
            quarantine(path, e);
            return root;
        }
        if (converted) {
            backup(path);
            save();
        }
        return root;
    }

    /** Benennt eine unlesbare Datei um ({@code favorites.json.defekt-<Zeit>}), damit kein Stern sie überschreibt. */
    private static void quarantine(Path path, Exception cause) {
        Path target = ProfileFiles.quarantine(path);
        if (target != null) {
            LOG.log(Level.WARNING, "Favoriten nicht lesbar (" + cause.getClass().getSimpleName()
                    + "), beiseitegelegt: " + target);
        } else {
            LOG.log(Level.WARNING, "Favoriten nicht lesbar und nicht beiseitezulegen: " + path);
        }
    }

    /** Sichert die flache Datei vor der Umwandlung einmalig als {@code favorites.json.v1}. */
    private static void backup(Path path) {
        Path target = path.resolveSibling(path.getFileName() + ".v1");
        try {
            if (!Files.exists(target)) {
                Files.copy(path, target);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Favoriten nicht gesichert: " + target + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Liest den Baum; doppelte IDs bekommen eine neue, doppelte Favoriten und unvollständige Einträge entfallen. */
    private static List<Node> fromEntries(JsonNode entries, Set<String> ids, List<Favorite> seen) {
        List<Node> nodes = new ArrayList<>();
        for (JsonNode entry : entries) {
            String stored = text(entry, "id");
            String id = stored == null || stored.isBlank() || ids.contains(stored) ? newId() : stored;
            if (entry.path("folder").asBoolean(false)) {
                String name = text(entry, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                ids.add(id);
                Node folder = Node.folder(id, name.trim());
                folder.children.addAll(fromEntries(entry.path("children"), ids, seen));
                nodes.add(folder);
            } else {
                Node favorite = favorite(entry, id, seen);
                if (favorite != null) {
                    ids.add(id);
                    nodes.add(favorite);
                }
            }
        }
        return nodes;
    }

    private static Node favorite(JsonNode node, String id, List<Favorite> seen) {
        String sourceId = text(node, "sourceId");
        String spaceKey = text(node, "spaceKey");
        String pageId = text(node, "pageId");
        if (sourceId == null || spaceKey == null && pageId != null) {
            return null;
        }
        for (Favorite known : seen) {
            if (known.same(sourceId, spaceKey, pageId)) {
                return null;
            }
        }
        Favorite favorite = new Favorite(sourceId, spaceKey, pageId, text(node, "title"), strings(node.path("path")),
                strings(node.path("ancestorIds")));
        seen.add(favorite);
        return Node.favorite(id, favorite);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asString());
        }
        return values;
    }

    private void save() {
        Path path = file.get();
        if (path == null) {
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("version", FORMAT_VERSION);
        content.put("entries", toEntries(load()));
        try {
            ProfileFiles.writeAtomic(path, JSON.writeValueAsString(content));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Favoriten nicht gespeichert: " + path + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static List<Map<String, Object>> toEntries(List<Node> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Node node : nodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", node.id);
            if (node.isFolder()) {
                row.put("folder", true);
                row.put("name", node.name);
                row.put("children", toEntries(node.children));
            } else {
                Favorite favorite = node.favorite;
                row.put("sourceId", favorite.sourceId());
                row.put("spaceKey", favorite.spaceKey());
                row.put("pageId", favorite.pageId());
                row.put("title", favorite.title());
                row.put("path", favorite.path());
                row.put("ancestorIds", favorite.ancestorIds());
            }
            rows.add(row);
        }
        return rows;
    }

    /** Ein Knoten im Speicher. */
    private static final class Node {
        final String id;
        String name;
        final Favorite favorite;
        final List<Node> children;

        private Node(String id, String name, Favorite favorite) {
            this.id = id;
            this.name = name;
            this.favorite = favorite;
            this.children = favorite == null ? new ArrayList<>() : List.of();
        }

        static Node folder(String id, String name) {
            return new Node(id, name, null);
        }

        static Node favorite(String id, Favorite favorite) {
            return new Node(id, favorite.title(), favorite);
        }

        boolean isFolder() {
            return favorite == null;
        }
    }
}
