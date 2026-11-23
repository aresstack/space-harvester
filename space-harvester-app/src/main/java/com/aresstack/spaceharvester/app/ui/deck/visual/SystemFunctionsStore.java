package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunction;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunctions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Die einstellbaren Systemprogramme (Einstellungen › Diagramme › Systemfunktionen) in einer eigenen JSON-Datei wie
 * {@code system_functions.json} in MainframeMate: {@code <Benutzerordner>/.space-harvester/system-functions.json}.
 * Fehlt die Datei, gilt die Standardliste aus MainframeMate (und wird beim ersten Speichern geschrieben). Die
 * Systemeigenschaft {@value #FILE_PROPERTY} setzt einen anderen Ort (Tests).
 */
public final class SystemFunctionsStore {

    static final String FILE_PROPERTY = "space-harvester.system-functions";
    static final String FILE_NAME = "system-functions.json";

    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static SystemFunctionsStore shared;

    private final Path file;
    private final List<Runnable> observers = new CopyOnWriteArrayList<>();
    private volatile List<SystemFunction> functions;

    SystemFunctionsStore(Path file) {
        this.file = file;
        this.functions = load(file);
    }

    public static synchronized SystemFunctionsStore shared() {
        if (shared == null) {
            String override = System.getProperty(FILE_PROPERTY);
            shared = new SystemFunctionsStore(override == null || override.isBlank()
                    ? AppSettingsLoader.defaultHome().resolve(FILE_NAME) : Path.of(override));
        }
        return shared;
    }

    public List<SystemFunction> all() {
        return functions;
    }

    /** Die Namen für die Hervorhebung im Diagramm (Großbuchstaben). */
    public Set<String> names() {
        return SystemFunctions.names(functions);
    }

    public Map<String, SystemFunction> lookup() {
        return SystemFunctions.lookup(functions);
    }

    /** Ersetzt die Liste und schreibt sie sofort (über eine Zwischendatei). */
    public synchronized void save(List<SystemFunction> next) throws IOException {
        List<SystemFunction> copy = List.copyOf(next);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        List<Map<String, String>> rows = new ArrayList<>();
        for (SystemFunction function : copy) {
            rows.add(Map.of("name", function.name(), "wikiTitleDe", function.wikiTitleDe(),
                    "wikiTitleEn", function.wikiTitleEn(), "description", function.description()));
        }
        try {
            JSON.writeValue(temp.toFile(), rows);
        } catch (RuntimeException e) {
            throw new IOException("Systemfunktionen nicht schreibbar: " + e.getMessage(), e);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        functions = copy;
        observers.forEach(Runnable::run);
    }

    public void onChange(Runnable observer) {
        observers.add(observer);
    }

    private static List<SystemFunction> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return SystemFunctions.DEFAULTS;
        }
        try {
            List<Map<String, String>> rows = JSON.readValue(file.toFile(),
                    new TypeReference<List<Map<String, String>>>() {
                    });
            List<SystemFunction> read = new ArrayList<>();
            for (Map<String, String> row : rows) {
                SystemFunction function = new SystemFunction(row.get("name"), row.get("wikiTitleDe"),
                        row.get("wikiTitleEn"), row.get("description"));
                if (!function.name().isEmpty()) {
                    read.add(function);
                }
            }
            return List.copyOf(read);
        } catch (RuntimeException e) {
            // Unlesbar: Standardliste; beim nächsten Speichern entsteht die Datei neu.
            return SystemFunctions.DEFAULTS;
        }
    }
}
