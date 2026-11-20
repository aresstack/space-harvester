package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.sync.IndexMarks;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * {@link IndexMarks} als Datei {@value #FILE} im Datenverzeichnis: je Space ({@code <Quelle>/<Space>}) die markierten
 * Wurzeln zeilenweise, atomar ersetzt. Eine unlesbare Datei beginnt leer; dann läuft wieder alles in der Betriebsart
 * der Quelle, bis neu markiert wird.
 */
public final class FileIndexMarks implements IndexMarks {

    static final String FILE = "index-marks.properties";

    private static final System.Logger LOG = System.getLogger(FileIndexMarks.class.getName());

    private final Path file;
    private final Properties marks = new Properties();

    /** @param directory Verzeichnis der Datei; wird bei Bedarf angelegt */
    public FileIndexMarks(Path directory) {
        this.file = Objects.requireNonNull(directory, "directory").resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            marks.load(reader);
        } catch (IOException | RuntimeException e) {
            marks.clear();
            LOG.log(System.Logger.Level.WARNING, "Indexier-Markierungen unlesbar ({0}); beginnt leer",
                    e.getClass().getName());
        }
    }

    @Override
    public synchronized Set<String> roots(String sourceId, String spaceKey) {
        String value = marks.getProperty(key(sourceId, spaceKey));
        return value == null ? Set.of() : Set.copyOf(Arrays.asList(value.split("\n", -1)));
    }

    @Override
    public synchronized void mark(String sourceId, String spaceKey, String pageId) {
        Set<String> roots = new LinkedHashSet<>(roots(sourceId, spaceKey));
        if (roots.add(root(pageId))) {
            store(sourceId, spaceKey, roots);
        }
    }

    @Override
    public synchronized void unmark(String sourceId, String spaceKey, String pageId) {
        Set<String> roots = new LinkedHashSet<>(roots(sourceId, spaceKey));
        if (roots.remove(root(pageId))) {
            store(sourceId, spaceKey, roots);
        }
    }

    private static String key(String sourceId, String spaceKey) {
        return Objects.requireNonNull(sourceId, "sourceId") + "/" + Objects.requireNonNull(spaceKey, "spaceKey");
    }

    private static String root(String pageId) {
        if (pageId != null && (pageId.isEmpty() || pageId.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Ungültige Seiten-ID");
        }
        return pageId == null ? WHOLE_SPACE : pageId;
    }

    private void store(String sourceId, String spaceKey, Set<String> roots) {
        if (roots.isEmpty()) {
            marks.remove(key(sourceId, spaceKey));
        } else {
            marks.setProperty(key(sourceId, spaceKey), String.join("\n", roots));
        }
        Path temp = file.resolveSibling(FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                marks.store(writer, null);
            }
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
                // Nur ein Rest; die nächste Markierung schreibt neu.
            }
        }
    }
}
