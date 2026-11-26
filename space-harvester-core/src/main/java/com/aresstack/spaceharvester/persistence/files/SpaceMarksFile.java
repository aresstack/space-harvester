package com.aresstack.spaceharvester.persistence.files;

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
import java.util.TreeSet;
import java.util.UUID;

/**
 * Gemeinsame Ablage für Markierungen je Quelle und Space ({@code <Quelle>/<Space>}) in einer Properties-Datei: je
 * Schlüssel die markierten Seiten-IDs zeilenweise, die ganze Datei atomar ersetzt. Die Seiten-ID {@code null} steht
 * für den ganzen Space und wird als {@code wholeSpace} abgelegt; leere IDs und IDs mit Zeilenumbruch sind ungültig.
 * Eine unlesbare Datei beginnt leer. Benutzt von {@link FileIndexMarks} und {@link FileExclusionMarks}; threadsicher.
 */
public final class SpaceMarksFile {

    private static final System.Logger LOG = System.getLogger(SpaceMarksFile.class.getName());

    private final Path file;
    private final String wholeSpace;
    private final Properties marks = new Properties();

    /**
     * @param file       die Datei; ihr Verzeichnis wird beim ersten Schreiben angelegt
     * @param wholeSpace Ablage für „ganzer Space“
     * @param label      Name der Markierungen für die Warnung bei unlesbarer Datei
     */
    public SpaceMarksFile(Path file, String wholeSpace, String label) {
        this.file = Objects.requireNonNull(file, "file");
        this.wholeSpace = Objects.requireNonNull(wholeSpace, "wholeSpace");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            marks.load(reader);
        } catch (IOException | RuntimeException e) {
            marks.clear();
            LOG.log(System.Logger.Level.WARNING, "{0} unlesbar ({1}); beginnt leer", label, e.getClass().getName());
        }
    }

    /** Die markierten IDs des Space, {@code wholeSpace} für den ganzen Space. */
    public synchronized Set<String> ids(String sourceId, String spaceKey) {
        String value = marks.getProperty(key(sourceId, spaceKey));
        return value == null ? Set.of() : Set.copyOf(Arrays.asList(value.split("\n", -1)));
    }

    /** Die Spaces der Quelle mit mindestens einer Markierung, sortiert. */
    public synchronized Set<String> spaces(String sourceId) {
        String prefix = Objects.requireNonNull(sourceId, "sourceId") + "/";
        java.util.SortedSet<String> spaces = new TreeSet<>();
        for (String key : marks.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                spaces.add(key.substring(prefix.length()));
            }
        }
        return java.util.Collections.unmodifiableSortedSet(spaces);
    }

    /** Markiert die Seite, mit {@code pageId == null} den ganzen Space. */
    public synchronized void add(String sourceId, String spaceKey, String pageId) {
        Set<String> ids = new LinkedHashSet<>(ids(sourceId, spaceKey));
        if (ids.add(id(pageId))) {
            store(sourceId, spaceKey, ids);
        }
    }

    /** Markiert mehrere Seiten auf einmal; schreibt die Datei höchstens einmal. */
    public synchronized void addAll(String sourceId, String spaceKey, java.util.Collection<String> pageIds) {
        Set<String> ids = new LinkedHashSet<>(ids(sourceId, spaceKey));
        boolean changed = false;
        for (String pageId : pageIds) {
            changed |= ids.add(id(Objects.requireNonNull(pageId, "pageId")));
        }
        if (changed) {
            store(sourceId, spaceKey, ids);
        }
    }

    /** Hebt genau diese Markierung auf. */
    public synchronized void remove(String sourceId, String spaceKey, String pageId) {
        Set<String> ids = new LinkedHashSet<>(ids(sourceId, spaceKey));
        if (ids.remove(id(pageId))) {
            store(sourceId, spaceKey, ids);
        }
    }

    private static String key(String sourceId, String spaceKey) {
        return Objects.requireNonNull(sourceId, "sourceId") + "/" + Objects.requireNonNull(spaceKey, "spaceKey");
    }

    private String id(String pageId) {
        if (pageId != null && (pageId.isEmpty() || pageId.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Ungültige Seiten-ID");
        }
        return pageId == null ? wholeSpace : pageId;
    }

    private void store(String sourceId, String spaceKey, Set<String> ids) {
        if (ids.isEmpty()) {
            marks.remove(key(sourceId, spaceKey));
        } else {
            marks.setProperty(key(sourceId, spaceKey), String.join("\n", ids));
        }
        Path temp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
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
