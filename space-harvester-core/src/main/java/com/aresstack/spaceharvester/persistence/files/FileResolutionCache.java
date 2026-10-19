package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.terms.ResolutionCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ergebnis-Cache der KI-Akronymauflösung (#122) als {@code ai-cache.json} neben der Beziehungsablage, atomar ersetzt.
 * Nur Fingerabdrücke (SHA-256) und Beziehungskennungen, keine Begriffe, kein Text und keine Modellantworten. Ein
 * Cache, keine Nutzerdaten: Ist die Datei unlesbar, beginnt er leer, und der nächste Lauf fragt das Modell neu.
 *
 * <p>Schema {@value #SCHEMA}.
 */
public final class FileResolutionCache implements ResolutionCache {

    public static final String SCHEMA = "term-relations-ai-cache/v1";
    static final String FILE = "ai-cache.json";

    private static final System.Logger LOG = System.getLogger(FileResolutionCache.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;
    private final Map<String, Map<String, List<String>>> bySource = new TreeMap<>();

    public FileResolutionCache(Path directory) {
        Objects.requireNonNull(directory, "directory");
        this.file = directory.resolve(FILE);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (Files.exists(file)) {
            load();
        }
    }

    /** Eine kaputte oder fremde Datei wird verworfen; der nächste {@link #replace} baut sie neu auf. */
    private void load() {
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(root.path("schema").asString(null))) {
                LOG.log(System.Logger.Level.WARNING,
                        "Ergebnis-Cache der Akronymauflösung mit fremdem Schema verworfen; beginnt leer");
                return;
            }
            for (var source : root.path("sources").properties()) {
                Map<String, List<String>> entries = new TreeMap<>();
                for (var entry : source.getValue().properties()) {
                    List<String> ids = new ArrayList<>();
                    for (JsonNode id : entry.getValue()) {
                        if (!id.isString()) {
                            throw new IllegalStateException("Beziehungskennung ist kein Text");
                        }
                        ids.add(id.asString());
                    }
                    entries.put(entry.getKey(), List.copyOf(ids));
                }
                bySource.put(source.getKey(), entries);
            }
        } catch (IOException | RuntimeException e) {
            bySource.clear();
            LOG.log(System.Logger.Level.WARNING, "Ergebnis-Cache der Akronymauflösung unlesbar ({0}); beginnt leer",
                    e.getClass().getName());
        }
    }

    @Override
    public synchronized Optional<List<String>> find(String fingerprint) {
        for (Map<String, List<String>> entries : bySource.values()) {
            List<String> ids = entries.get(fingerprint);
            if (ids != null) {
                return Optional.of(ids);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void replace(String sourceId, Map<String, List<String>> entries) {
        bySource.put(sourceId, new TreeMap<>(entries));
        ObjectNode root = JSON.createObjectNode().put("schema", SCHEMA);
        ObjectNode sources = root.putObject("sources");
        bySource.forEach((source, map) -> {
            ObjectNode node = sources.putObject(source);
            map.forEach((fingerprint, ids) -> ids.forEach(node.putArray(fingerprint)::add));
        });
        Path temp = file.resolveSibling(FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temp, JSON.writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nur ein Rest; der nächste Lauf schreibt neu.
            }
        }
    }
}
