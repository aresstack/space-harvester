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

    private final SpaceMarksFile file;

    /** @param directory Verzeichnis der Datei; wird bei Bedarf angelegt */
    public FileNavigationMarks(Path directory) {
        this.file = new SpaceMarksFile(Objects.requireNonNull(directory, "directory").resolve(FILE),
                com.aresstack.spaceharvester.application.sync.IndexMarks.WHOLE_SPACE, "Navigationsgründe");
    }

    @Override
    public Set<String> pages(String sourceId, String spaceKey) {
        return file.ids(sourceId, spaceKey);
    }

    @Override
    public void mark(String sourceId, String spaceKey, Collection<String> pageIds) {
        file.addAll(sourceId, spaceKey, pageIds);
    }
}
