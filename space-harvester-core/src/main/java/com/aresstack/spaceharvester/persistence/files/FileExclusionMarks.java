package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.sync.ExclusionMarks;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ExclusionMarks} als Datei {@value #FILE} im Datenverzeichnis, gleiche Ablage wie {@link FileIndexMarks}
 * ({@link SpaceMarksFile}). Eine unlesbare Datei beginnt leer; dann ist wieder alles sichtbar, gelöscht wird nichts.
 */
public final class FileExclusionMarks implements ExclusionMarks {

    static final String FILE = "exclusion-marks.properties";

    private final SpaceMarksFile file;

    /** @param directory Verzeichnis der Datei; wird bei Bedarf angelegt */
    public FileExclusionMarks(Path directory) {
        this.file = new SpaceMarksFile(Objects.requireNonNull(directory, "directory").resolve(FILE), WHOLE_SPACE,
                "Ausschlüsse");
    }

    @Override
    public Set<String> roots(String sourceId, String spaceKey) {
        return file.ids(sourceId, spaceKey);
    }

    @Override
    public Set<String> spaces(String sourceId) {
        return file.spaces(sourceId);
    }

    @Override
    public void mark(String sourceId, String spaceKey, String pageId) {
        file.add(sourceId, spaceKey, pageId);
    }

    @Override
    public void unmark(String sourceId, String spaceKey, String pageId) {
        file.remove(sourceId, spaceKey, pageId);
    }
}
