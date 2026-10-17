package com.aresstack.spaceharvester.app.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Merkt sich die IDs der Aufträge, die die App gestartet hat, damit „Aufträge“ sie auch nach einem Neustart
 * zeigt; Status und Ergebnis liegen weiter in der Auftragsablage des Cache. Nur IDs, keine Inhalte.
 */
public final class ActivityLog {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    /** Ältere IDs fallen heraus; die Auftragsablage hält Aufträge ohnehin nur für ihre Aufbewahrungsfrist. */
    static final int MAX_ENTRIES = 500;

    private final Path file;
    private final Set<String> ids = new LinkedHashSet<>();

    public ActivityLog(Path file) {
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String id = line.trim();
                    if (ID.matcher(id).matches()) {
                        ids.add(id);
                    }
                }
            } catch (IOException e) {
                // Ohne Liste zeigt die App nur neue Aufträge; die Ablage selbst bleibt unberührt.
            }
        }
    }

    public synchronized void remember(String id) {
        if (ID.matcher(id).matches() && ids.add(id)) {
            while (ids.size() > MAX_ENTRIES) {
                ids.remove(ids.iterator().next());
            }
            save();
        }
    }

    public synchronized void forget(String id) {
        if (ids.remove(id)) {
            save();
        }
    }

    public synchronized List<String> ids() {
        return new ArrayList<>(ids);
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, ids, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Nicht kritisch: die Aufträge laufen weiter, nur die Liste geht beim Neustart verloren.
        }
    }
}
