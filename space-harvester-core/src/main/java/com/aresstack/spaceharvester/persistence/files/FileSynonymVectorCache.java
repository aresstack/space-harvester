package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.SynonymVectorCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SynonymVectorCache} als {@code vectors.json} neben dem Ergebnis-Cache der Synonymerkennung, atomar ersetzt.
 * Je Eintrag Modellkennung, Eingabe-Hash, Seitenschlüssel und Vektor (Base64, Little Endian); kein Begriff und kein
 * Text. Ein Cache, keine Nutzerdaten: Ist die Datei unlesbar, beginnt er leer, und der nächste Lauf bettet neu ein.
 *
 * <p>Schema {@value #SCHEMA}.
 */
public final class FileSynonymVectorCache implements SynonymVectorCache {

    public static final String SCHEMA = "term-relations-synonym-vectors/v1";
    static final String FILE = "vectors.json";

    private static final System.Logger LOG = System.getLogger(FileSynonymVectorCache.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;
    private final Map<String, Entry> vectors = new TreeMap<>();
    private final Map<String, String> states = new TreeMap<>();

    public FileSynonymVectorCache(Path directory) {
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

    private static String key(String modelId, String inputSha256) {
        return modelId + '\u0000' + inputSha256;
    }

    private void load() {
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(root.path("schema").asString(null))) {
                LOG.log(System.Logger.Level.WARNING, "Vektor-Cache der Synonyme mit fremdem Schema verworfen");
                return;
            }
            for (JsonNode node : root.path("vectors")) {
                Set<PageKey> pages = new HashSet<>();
                for (JsonNode page : node.path("pages")) {
                    pages.add(new PageKey(page.path("sourceId").asString(), page.path("pageId").asString()));
                }
                ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(node.path("vector").asString()))
                        .order(ByteOrder.LITTLE_ENDIAN);
                float[] vector = new float[bytes.remaining() / Float.BYTES];
                bytes.asFloatBuffer().get(vector);
                vectors.put(key(node.path("model").asString(), node.path("input").asString()),
                        new Entry(vector, pages));
            }
            for (var state : root.path("states").properties()) {
                states.put(state.getKey(), state.getValue().asString());
            }
        } catch (IOException | RuntimeException e) {
            vectors.clear();
            states.clear();
            LOG.log(System.Logger.Level.WARNING, "Vektor-Cache der Synonyme unlesbar ({0}); beginnt leer",
                    e.getClass().getName());
        }
    }

    @Override
    public synchronized Optional<float[]> find(String modelId, String inputSha256) {
        return Optional.ofNullable(vectors.get(key(modelId, inputSha256))).map(Entry::vector);
    }

    @Override
    public synchronized void putAll(String modelId, Map<String, Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        entries.forEach((sha, entry) -> vectors.put(key(modelId, sha), entry));
        store();
    }

    @Override
    public synchronized void withdraw(Collection<PageKey> pages) {
        Set<PageKey> gone = Set.copyOf(pages);
        if (vectors.values().removeIf(entry -> entry.pages().stream().anyMatch(gone::contains))) {
            store();
        }
    }

    @Override
    public synchronized void retainSource(String sourceId, String modelId, Set<String> inputSha256) {
        if (vectors.entrySet().removeIf(entry -> !entry.getValue().pages().isEmpty()
                && entry.getValue().pages().stream().allMatch(page -> page.sourceId().equals(sourceId))
                && !(entry.getKey().startsWith(modelId + '\u0000')
                && inputSha256.contains(entry.getKey().substring(modelId.length() + 1))))) {
            store();
        }
    }

    @Override
    public synchronized void withdrawSource(String sourceId) {
        boolean changed = vectors.values().removeIf(entry -> entry.pages().stream()
                .anyMatch(page -> page.sourceId().equals(sourceId)));
        if (states.remove(sourceId) != null || changed) {
            store();
        }
    }

    @Override
    public synchronized Optional<String> state(String sourceId) {
        return Optional.ofNullable(states.get(sourceId));
    }

    @Override
    public synchronized void state(String sourceId, String state) {
        boolean changed = state == null ? states.remove(sourceId) != null
                : !state.equals(states.put(sourceId, state));
        if (changed) {
            store();
        }
    }

    private void store() {
        ObjectNode root = JSON.createObjectNode().put("schema", SCHEMA);
        ArrayNode list = root.putArray("vectors");
        vectors.forEach((key, entry) -> {
            int split = key.indexOf('\u0000');
            float[] vector = entry.vector();
            ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asFloatBuffer().put(vector);
            ObjectNode node = list.addObject().put("model", key.substring(0, split))
                    .put("input", key.substring(split + 1))
                    .put("vector", Base64.getEncoder().encodeToString(bytes.array()));
            ArrayNode pages = node.putArray("pages");
            entry.pages().stream().sorted(java.util.Comparator.comparing(PageKey::sourceId)
                    .thenComparing(PageKey::pageId)).forEach(page -> pages.addObject()
                    .put("sourceId", page.sourceId()).put("pageId", page.pageId()));
        });
        ObjectNode stateNode = root.putObject("states");
        states.forEach(stateNode::put);
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
