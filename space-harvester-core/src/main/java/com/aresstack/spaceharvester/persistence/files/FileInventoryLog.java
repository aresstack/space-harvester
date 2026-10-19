package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.sync.InventoryLog;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * {@link InventoryLog} als Datei {@value #FILE} im Cache-Verzeichnis: je Zeile {@code <Quelle>/<Space>} und Zeitpunkt,
 * atomar ersetzt. Eine unlesbare Datei beginnt leer; dann zählt der nächste Abstand ab jetzt.
 */
public final class FileInventoryLog implements InventoryLog {

    static final String FILE = "inventories.properties";

    private static final System.Logger LOG = System.getLogger(FileInventoryLog.class.getName());

    private final Path file;
    private final Properties marks = new Properties();

    /** @param directory Verzeichnis der Datei; wird bei Bedarf angelegt */
    public FileInventoryLog(Path directory) {
        this.file = Objects.requireNonNull(directory, "directory").resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            marks.load(reader);
            marks.values().forEach(value -> Instant.parse((String) value));
        } catch (IOException | RuntimeException e) {
            marks.clear();
            LOG.log(System.Logger.Level.WARNING, "Zeitpunkte der Bestandsinventur unlesbar ({0}); beginnt leer",
                    e.getClass().getName());
        }
    }

    @Override
    public synchronized Optional<Instant> since(String sourceId, String spaceKey) {
        String value = marks.getProperty(key(sourceId, spaceKey));
        return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
    }

    @Override
    public synchronized void mark(String sourceId, String spaceKey, Instant at) {
        marks.setProperty(key(sourceId, spaceKey), Objects.requireNonNull(at, "at").toString());
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
                // Nur ein Rest; der nächste Vermerk schreibt neu.
            }
        }
    }

    private static String key(String sourceId, String spaceKey) {
        return sourceId + "/" + spaceKey;
    }
}
