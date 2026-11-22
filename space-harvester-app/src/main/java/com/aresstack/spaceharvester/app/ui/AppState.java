package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Der Bedienzustand der App (Application State) in einer eigenen JSON-Datei, getrennt von der YAML-Konfiguration:
 * Fenstergrenzen, maximiert, Breiten und offen/zu der Seitenleisten, gemerkte Dialoggrößen. Vorbild ist der
 * {@code applicationState} von MainframeMate, hier aber als eigene Datei {@code <Benutzerordner>/.space-harvester/
 * app-state.json} (Angelo, 22.11.2026). Die Systemeigenschaft {@value #FILE_PROPERTY} setzt einen anderen Ort (Tests).
 *
 * <p>Jede Änderung wird sofort geschrieben (über eine Zwischendatei, damit ein Absturz nie eine halbe Datei
 * hinterlässt). Fehlt die Datei, übernimmt der erste Start die bisher in den Java-Preferences gemerkten Werte. Ist die
 * Datei unlesbar oder nicht schreibbar, gelten die Standardwerte; die App startet trotzdem.
 */
public final class AppState {

    /** Systemeigenschaft für einen anderen Ort der Datei. */
    static final String FILE_PROPERTY = "space-harvester.app-state";
    static final String FILE_NAME = "app-state.json";
    /** Der frühere Speicher (Registry unter Windows), nur noch für die einmalige Übernahme. */
    static final String LEGACY_NODE = "com/aresstack/spaceharvester/app/windows";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT).build();
    private static AppState shared;

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();

    AppState(Path file) {
        this.file = file;
        load();
    }

    /** Der Zustand der laufenden App. */
    public static synchronized AppState shared() {
        if (shared == null) {
            String override = System.getProperty(FILE_PROPERTY);
            Path file = override == null || override.isBlank()
                    ? AppSettingsLoader.defaultHome().resolve(FILE_NAME) : Path.of(override);
            shared = new AppState(file);
        }
        return shared;
    }

    Path file() {
        return file;
    }

    public synchronized String get(String key) {
        return values.get(key);
    }

    public int getInt(String key, int fallback) {
        String value = get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    public void put(String key, int value) {
        put(key, String.valueOf(value));
    }

    public void put(String key, boolean value) {
        put(key, String.valueOf(value));
    }

    /** Setzt einen Wert ({@code null} entfernt ihn) und schreibt die Datei, wenn sich etwas geändert hat. */
    public synchronized void put(String key, String value) {
        String old = value == null ? values.remove(key) : values.put(key, value);
        if (value == null ? old != null : !value.equals(old)) {
            save();
        }
    }

    /** Setzt mehrere Werte auf einmal und schreibt die Datei nur einmal. */
    public synchronized void putAll(Map<String, String> changes) {
        boolean changed = false;
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String old = change.getValue() == null ? values.remove(change.getKey())
                    : values.put(change.getKey(), change.getValue());
            changed |= change.getValue() == null ? old != null : !change.getValue().equals(old);
        }
        if (changed) {
            save();
        }
    }

    private void load() {
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> read = JSON.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
                });
                read.forEach((key, value) -> {
                    if (key != null && value != null) {
                        values.put(key, String.valueOf(value));
                    }
                });
            } catch (RuntimeException e) {
                // Unlesbar: Standardwerte; beim nächsten Speichern entsteht die Datei neu.
            }
            return;
        }
        if (System.getProperty(FILE_PROPERTY) == null) {
            adoptLegacyPreferences();
        }
    }

    /** Übernimmt die früher in den Java-Preferences gemerkten Zahlen (Dialoggrößen, Chat-Leiste) einmalig. */
    private void adoptLegacyPreferences() {
        try {
            if (!Preferences.userRoot().nodeExists(LEGACY_NODE)) {
                return;
            }
            Preferences legacy = Preferences.userRoot().node(LEGACY_NODE);
            for (String key : legacy.keys()) {
                String value = legacy.get(key, null);
                if (value != null) {
                    values.put(key, value);
                }
            }
            if (!values.isEmpty()) {
                save();
            }
        } catch (BackingStoreException | RuntimeException e) {
            // Ohne alten Speicher beginnt der Zustand leer.
        }
    }

    private void save() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(temp.toFile(), new TreeMap<>(values));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            // Nicht schreibbar: der Zustand gilt bis zum Beenden, danach wieder der Standard.
        }
    }
}
