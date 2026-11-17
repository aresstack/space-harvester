package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.assets.ReadStates;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * {@link ReadStates} als Datei {@value #FILE} im Asset-Verzeichnis: je Quelle der zuletzt vollständig neu gelesene
 * Stand, atomar ersetzt. Eine unlesbare Datei beginnt leer; dann liest der nächste Start die veralteten Stände neu.
 */
public final class FileReadStates implements ReadStates {

    static final String FILE = "reread-state.properties";

    private static final System.Logger LOG = System.getLogger(FileReadStates.class.getName());

    private final Path file;
    private final Properties states = new Properties();

    /** @param directory Verzeichnis der Datei; wird bei Bedarf angelegt */
    public FileReadStates(Path directory) {
        this.file = Objects.requireNonNull(directory, "directory").resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            states.load(reader);
        } catch (IOException | RuntimeException e) {
            states.clear();
            LOG.log(System.Logger.Level.WARNING, "Stand des Neulesens unlesbar ({0}); beginnt leer",
                    e.getClass().getName());
        }
    }

    @Override
    public synchronized Optional<String> get(String sourceId) {
        return Optional.ofNullable(states.getProperty(Objects.requireNonNull(sourceId, "sourceId")));
    }

    @Override
    public synchronized void put(String sourceId, String state) {
        states.setProperty(Objects.requireNonNull(sourceId, "sourceId"), Objects.requireNonNull(state, "state"));
        Path temp = file.resolveSibling(FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                states.store(writer, null);
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
}
