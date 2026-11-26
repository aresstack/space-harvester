package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.sync.IndexMarks;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * {@link IndexMarks} als Datei {@value #FILE} im Datenverzeichnis: je Space ({@code <Quelle>/<Space>}) die markierten
 * Wurzeln zeilenweise, atomar ersetzt ({@link SpaceMarksFile}). Eine unlesbare Datei beginnt leer; dann läuft wieder
 * alles in der Betriebsart der Quelle, bis neu markiert wird.
 */
public final class FileIndexMarks implements IndexMarks {

    static final String FILE = "index-marks.properties";

    private final SpaceMarksFile file;

    /** @param directory Verzeichnis der Datei; wird bei Bedarf angelegt */
    public FileIndexMarks(Path directory) {
        this.file = new SpaceMarksFile(Objects.requireNonNull(directory, "directory").resolve(FILE), WHOLE_SPACE,
                "Indexier-Markierungen");
    }

    @Override
    public Set<String> roots(String sourceId, String spaceKey) {
        return file.ids(sourceId, spaceKey);
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
