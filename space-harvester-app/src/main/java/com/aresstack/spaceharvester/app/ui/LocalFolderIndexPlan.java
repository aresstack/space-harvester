package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.source.LocalFilesSourceAdapter;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Resolve a folder to an existing source scope without expanding or duplicating its coverage. */
final class LocalFolderIndexPlan {

    record Target(Path folder, String sourceId, String pageId) {
        boolean newSource() {
            return sourceId == null;
        }
    }

    private LocalFolderIndexPlan() {
    }

    static Target resolve(Path requested, List<SourceSettingsConfig> configs, Set<String> active) throws IOException {
        Path folder = requested.toRealPath();
        if (!Files.isDirectory(folder) || folder.getParent() == null || folder.toString().startsWith("\\\\")) {
            throw new IllegalArgumentException("Bitte einen konkreten lokalen Ordner auswählen, kein Laufwerk oder Netzpfad.");
        }
        List<Target> matching = new ArrayList<>();
        for (SourceSettingsConfig config : configs) {
            if (!LocalFilesSourceAdapter.TYPE.equals(config.type())) {
                continue;
            }
            String directory = config.options().get("directory");
            if (directory == null || directory.isBlank()) {
                continue;
            }
            Path configured = Path.of(directory).toAbsolutePath().normalize();
            Path root;
            try {
                root = configured.toRealPath();
            } catch (IOException unavailable) {
                if (folder.startsWith(configured) || configured.startsWith(folder)) {
                    throw new IOException("Überlappende Quelle nicht erreichbar: " + config.id(), unavailable);
                }
                continue;
            }
            if (folder.startsWith(root)) {
                if (!active.contains(config.id())) {
                    throw new IllegalStateException("Passende Quelle ist deaktiviert: " + config.id());
                }
                String pageId = root.equals(folder) ? null : LocalFilesSourceAdapter.folderPageId(root.relativize(folder));
                matching.add(new Target(folder, config.id(), pageId));
            } else if (root.startsWith(folder)) {
                throw new IllegalStateException("Der Ordner enthält bereits die Quelle " + config.id()
                        + ". Bitte den Umfang in der Quellenverwaltung abstimmen.");
            }
        }
        if (matching.size() > 1) {
            throw new IllegalStateException("Mehrere Quellen decken den Ordner ab. Bitte eine Quelle in der Quellenverwaltung auswählen.");
        }
        return matching.isEmpty() ? new Target(folder, null, null) : matching.getFirst();
    }
}
