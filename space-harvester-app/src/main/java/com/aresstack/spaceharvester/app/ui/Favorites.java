package com.aresstack.spaceharvester.app.ui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Favoriten des Benutzers (Angelo, 13.11.2026): Seiten (Quelle, Space, Seite) oder ganze Spaces, die er mit dem Stern
 * markiert hat. Gehören zum Bedienprofil der App, nicht zum Core und nicht in die Konfigurationsdatei: Datei
 * {@code favorites.json} im Datenordner der App. Keine Ordner wie im Favoritenbaum des EAC; die Struktur ist der
 * Seitenbaum selbst, deshalb merkt sich ein Favorit seinen Pfad (Space und Vorfahren) nur zur Anzeige.
 */
public final class Favorites {

    /**
     * Ein Favorit: eine Seite ({@code pageId}) oder ein ganzer Space ({@code pageId} null). {@code path} ist der
     * Anzeigepfad (Quelle › Space › Vorfahren), {@code ancestorIds} die IDs der Vorfahren im Seitenbaum.
     */
    public record Favorite(String sourceId, String spaceKey, String pageId, String title, List<String> path,
                           List<String> ancestorIds) {

        public Favorite {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(spaceKey, "spaceKey");
            title = title == null || title.isBlank() ? (pageId == null ? spaceKey : "Seite " + pageId) : title;
            path = path == null ? List.of() : List.copyOf(path);
            ancestorIds = ancestorIds == null ? List.of() : List.copyOf(ancestorIds);
        }

        public static Favorite space(String sourceId, String spaceKey, String name, String sourceTitle) {
            return new Favorite(sourceId, spaceKey, null, name, List.of(sourceTitle == null ? sourceId : sourceTitle),
                    List.of());
        }

        /** Derselbe Knoten, unabhängig von Titel und Pfad. */
        boolean same(String sourceId, String spaceKey, String pageId) {
            return this.sourceId.equals(sourceId) && this.spaceKey.equals(spaceKey)
                    && Objects.equals(this.pageId, pageId);
        }

        /** „Quelle › Space › Vorfahr“ für die Liste; leer für einen Space ohne gemerkten Pfad. */
        public String where() {
            return String.join(" › ", path);
        }
    }

    private static final Logger LOG = Logger.getLogger(Favorites.class.getName());
    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    static final String FILE = "favorites.json";

    private final Supplier<Path> file;
    private List<Favorite> entries;
    private Path loadedFrom;
    private final List<Runnable> listeners = new ArrayList<>();

    /** @param file die Datei; {@code null}, solange kein Datenordner bekannt ist (dann nur im Speicher) */
    public Favorites(Supplier<Path> file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Alle Favoriten in der Reihenfolge, in der sie gesetzt wurden. */
    public synchronized List<Favorite> all() {
        return List.copyOf(load());
    }

    public synchronized boolean contains(String sourceId, String spaceKey, String pageId) {
        return find(sourceId, spaceKey, pageId) != null;
    }

    /** Setzt den Favoriten oder nimmt ihn zurück; liefert, ob er danach gesetzt ist. */
    public boolean toggle(Favorite favorite) {
        boolean set;
        synchronized (this) {
            List<Favorite> current = load();
            Favorite existing = find(favorite.sourceId(), favorite.spaceKey(), favorite.pageId());
            if (existing != null) {
                current.remove(existing);
                set = false;
            } else {
                current.add(favorite);
                set = true;
            }
            save(current);
        }
        notifyListeners();
        return set;
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

    private Favorite find(String sourceId, String spaceKey, String pageId) {
        if (sourceId == null || spaceKey == null) {
            return null;
        }
        for (Favorite favorite : load()) {
            if (favorite.same(sourceId, spaceKey, pageId)) {
                return favorite;
            }
        }
        return null;
    }

    /** Liest die Datei beim ersten Zugriff und erneut, sobald ein anderer Datenordner gilt. */
    private List<Favorite> load() {
        Path path = file.get();
        if (entries != null && Objects.equals(path, loadedFrom)) {
            return entries;
        }
        loadedFrom = path;
        entries = new ArrayList<>();
        if (path == null || !Files.isRegularFile(path)) {
            return entries;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            for (JsonNode node : root.path("favorites")) {
                String sourceId = text(node, "sourceId");
                String spaceKey = text(node, "spaceKey");
                if (sourceId == null || spaceKey == null) {
                    continue;
                }
                entries.add(new Favorite(sourceId, spaceKey, text(node, "pageId"), text(node, "title"),
                        strings(node.path("path")), strings(node.path("ancestorIds"))));
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Favoriten nicht lesbar: " + path + " (" + e.getClass().getSimpleName() + ")");
        }
        return entries;
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

    private void save(List<Favorite> current) {
        Path path = file.get();
        if (path == null) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Favorite favorite : current) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", favorite.sourceId());
            row.put("spaceKey", favorite.spaceKey());
            row.put("pageId", favorite.pageId());
            row.put("title", favorite.title());
            row.put("path", favorite.path());
            row.put("ancestorIds", favorite.ancestorIds());
            rows.add(row);
        }
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".part");
            Files.writeString(temp, JSON.writeValueAsString(Map.of("favorites", rows)), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Favoriten nicht gespeichert: " + path + " (" + e.getClass().getSimpleName() + ")");
        }
    }
}
