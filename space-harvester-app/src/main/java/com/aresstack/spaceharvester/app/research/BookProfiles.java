package com.aresstack.spaceharvester.app.research;

import com.aresstack.writing4j.sourcerating.ProfileMemory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Die Quellensteckbriefe eines Korb-Ordners zwischen zwei Läufen (Angelo 27.11.2026: ein abgebrochenes Buch nicht neu
 * rechnen): neben den Notizen des Ordners ({@code app/books/<Ordner>.profiles.json}), je Steckbrief ein Eintrag unter
 * dem Schlüssel von Writing4j. Jeder fertige Steckbrief wird sofort geschrieben, damit auch ein Neustart der App ihn
 * nicht verliert. Die Datei trägt Titel und Einordnung der Quellen, nie Seitentext; sie geht nie in ein Protokoll.
 */
public final class BookProfiles implements ProfileMemory {

    private static final System.Logger LOG = System.getLogger(BookProfiles.class.getName());
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final Path file;
    private final Map<String, String> kept;

    public BookProfiles(Path dataDirectory, String folderId) {
        Path notes = BookNotes.file(dataDirectory, folderId);
        String name = notes.getFileName().toString();
        this.file = notes.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".profiles.json");
        this.kept = read(file);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> read(Path file) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return map;
        }
        try {
            Map<String, Object> raw = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
            raw.forEach((key, value) -> {
                if (value instanceof String text) {
                    map.put(key, text);
                }
            });
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(System.Logger.Level.WARNING, "Gemerkte Quellensteckbriefe nicht lesbar ({0})",
                    unreadable.getClass().getSimpleName());
        }
        return map;
    }

    @Override
    public synchronized String recall(String key) {
        return kept.get(Objects.requireNonNull(key, "key"));
    }

    @Override
    public synchronized void remember(String key, String profile) {
        kept.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(profile, "profile"));
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, JSON.writeValueAsString(kept), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException notWritten) {
            LOG.log(System.Logger.Level.WARNING, "Quellensteckbrief nicht gemerkt ({0})",
                    notWritten.getClass().getSimpleName());
        }
    }

    /** Wie viele Steckbriefe gemerkt sind. */
    synchronized int size() {
        return kept.size();
    }
}
