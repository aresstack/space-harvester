package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.sync.NavigationMarks;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * {@link NavigationMarks} als Datei {@value #FILE} im Datenverzeichnis, gleiche Ablage wie {@link FileIndexMarks}
 * ({@link SpaceMarksFile}). Eine unlesbare Datei beginnt leer; dann ist der Grund unbekannt, entfernt wird nichts.
 */
public final class FileNavigationMarks implements NavigationMarks {

    static final String FILE = "navigation-marks.properties";
    /** Seiten mit unbekanntem Grund, festgehalten beim ersten „Bereinigen“ je Bereich. */
    static final String UNKNOWN_FILE = "unknown-reasons.properties";
    /** Markiert „festgehalten“; nie eine Seiten-ID. */
    private static final String RECORDED = "";

    private final SpaceMarksFile file;
    private final SpaceMarksFile unknown;

    /** @param directory Verzeichnis der Datei; wird bei Bedarf angelegt */
    public FileNavigationMarks(Path directory) {
        this.file = new SpaceMarksFile(Objects.requireNonNull(directory, "directory").resolve(FILE),
                com.aresstack.spaceharvester.application.sync.IndexMarks.WHOLE_SPACE, "Navigationsgründe");
        this.unknown = new SpaceMarksFile(directory.resolve(UNKNOWN_FILE), RECORDED, "Unbekannte Gründe");
    }

    @Override
    public Set<String> pages(String sourceId, String spaceKey) {
        return file.ids(sourceId, spaceKey);
    }

    @Override
    public void mark(String sourceId, String spaceKey, Collection<String> pageIds) {
        file.addAll(sourceId, spaceKey, pageIds);
    }

    @Override
    public synchronized Set<String> unknown(String sourceId, String spaceKey, Set<String> withoutReason) {
        Set<String> recorded = unknown.ids(sourceId, spaceKey);
        if (!recorded.contains(RECORDED)) {
            unknown.add(sourceId, spaceKey, null);
            unknown.addAll(sourceId, spaceKey, withoutReason);
            return Set.copyOf(withoutReason);
        }
        java.util.Set<String> result = new java.util.TreeSet<>(withoutReason);
        result.retainAll(recorded);
        return Set.copyOf(result);
    }

    @Override
    public synchronized void forgetUnknown(String sourceId, String spaceKey, Collection<String> pageIds) {
        unknown.removeAll(sourceId, spaceKey, pageIds);
    }
}
