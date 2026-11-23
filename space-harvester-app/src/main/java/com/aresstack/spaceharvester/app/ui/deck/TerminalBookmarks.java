package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.terminal.TerminalMacro;
import com.aresstack.spaceharvester.deck.terminal.TerminalModel;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Terminal-Lesezeichen wie in MainframeMate: ein Name, das Ziel, der KeePass-Eintrag für die automatische Anmeldung
 * und das aufgezeichnete Makro, das nach dem Anmelden abgespielt wird. Gemerkt in {@code app-state.json}. Kein
 * Passwort: Die Anmeldung liest es beim Öffnen frisch aus KeePass, und verdeckte Felder zeichnet das Makro nie auf.
 */
final class TerminalBookmarks {

    static final String KEY = "deck.terminal.bookmarks";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Ein Lesezeichen. {@code entry} leer = ohne automatische Anmeldung. */
    record Bookmark(String name, TerminalProfile profile, String entry, String command,
                    List<TerminalMacro.Step> steps) {

        Bookmark {
            steps = steps == null ? List.of() : List.copyOf(steps);
            entry = entry == null ? "" : entry;
            command = command == null ? "" : command;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final AppState state;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    TerminalBookmarks(AppState state) {
        this.state = state;
    }

    List<Bookmark> all() {
        String text = state.get(KEY);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Bookmark> bookmarks = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = JSON.readValue(text, new TypeReference<List<Map<String, Object>>>() {
            });
            for (Map<String, Object> row : rows) {
                try {
                    bookmarks.add(read(row));
                } catch (RuntimeException unreadable) {
                    // kaputtes Lesezeichen überspringen
                }
            }
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        return List.copyOf(bookmarks);
    }

    void add(Bookmark bookmark) {
        List<Bookmark> bookmarks = new ArrayList<>(all());
        bookmarks.removeIf(existing -> existing.name().equals(bookmark.name()));
        bookmarks.add(bookmark);
        write(bookmarks);
    }

    void remove(String name) {
        List<Bookmark> bookmarks = new ArrayList<>(all());
        if (bookmarks.removeIf(existing -> existing.name().equals(name))) {
            write(bookmarks);
        }
    }

    void observe(Runnable listener) {
        listeners.add(listener);
    }

    private void write(List<Bookmark> bookmarks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Bookmark bookmark : bookmarks) {
            TerminalProfile profile = bookmark.profile();
            List<Map<String, String>> steps = new ArrayList<>();
            for (TerminalMacro.Step step : bookmark.steps()) {
                steps.add(Map.of("type", step.kind().name(), "value", step.value()));
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>(Map.of("name", bookmark.name(),
                    "host", profile.host(), "port", profile.port(),
                    "model", profile.model().name(), "tls", profile.tls(), "keepAlive", profile.keepAliveSeconds(),
                    "codePage", profile.codePage(), "entry", bookmark.entry(), "command", bookmark.command(),
                    "steps", steps));
            row.put("proxy", profile.viaProxy());
            rows.add(row);
        }
        state.put(KEY, JSON.writeValueAsString(rows));
        listeners.forEach(Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private static Bookmark read(Map<String, Object> row) {
        TerminalProfile profile = new TerminalProfile(String.valueOf(row.get("host")),
                ((Number) row.get("port")).intValue(), TerminalModel.valueOf(String.valueOf(row.get("model"))),
                Boolean.TRUE.equals(row.get("tls")), ((Number) row.getOrDefault("keepAlive", 0)).intValue(),
                (String) row.get("codePage"), Boolean.TRUE.equals(row.get("proxy")));
        List<TerminalMacro.Step> steps = new ArrayList<>();
        Object list = row.get("steps");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> step = (Map<String, Object>) item;
                steps.add(new TerminalMacro.Step(TerminalMacro.Step.Kind.valueOf(String.valueOf(step.get("type"))),
                        String.valueOf(step.get("value"))));
            }
        }
        return new Bookmark(String.valueOf(row.get("name")), profile, (String) row.get("entry"),
                (String) row.get("command"), steps);
    }
}
