package com.aresstack.spaceharvester.deck.files;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Anwendungsfall „Dateien durchsuchen“ des Command Deck, nachgebaut nach dem lokalen Dateibrowser von MainframeMate
 * ({@code LocalConnectionTabImpl}): Ordner öffnen, Zurück/Vorwärts/Nach oben, Neu laden, Regex-Filter auf den Namen
 * (ohne Unterscheidung von Groß- und Kleinschreibung), Sortierung mit Ordnern zuerst, Ordner und Dateien anlegen,
 * Einträge löschen.
 *
 * <p>Alles, was die Quelle fragt, läuft auf {@code background}. Jede Änderung meldet der Browser seinen Beobachtern
 * (kein Abfragen); die Meldung kommt auf dem Thread, der sie ausgelöst hat. Kommt ein älteres Ergebnis nach einem
 * neueren an, zählt nur das neuere.
 */
public final class FileBrowser {

    /** Woran die Oberfläche eine Meldung farblich erkennt. */
    public enum Tone { INFO, SUCCESS, ERROR }

    /** Die letzte Meldung des Browsers (Ergebnis einer Aktion oder Grund eines Fehlers). */
    public record Message(Tone tone, String text) {
    }

    private final FileSource source;
    private final Executor background;
    private final List<Consumer<FileBrowser>> observers = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<String> back = new ArrayDeque<>();
    private final Deque<String> forward = new ArrayDeque<>();
    private String location;
    private List<FileEntry> entries = List.of();
    private String filter = "";
    private Pattern pattern;
    private boolean filterValid = true;
    private SortMode sort = SortMode.NAME_ASC;
    private boolean loading;
    private String failure;
    private Message message;
    private long ticket;

    public FileBrowser(FileSource source, Executor background) {
        this.source = Objects.requireNonNull(source, "source");
        this.background = Objects.requireNonNull(background, "background");
    }

    public FileSource source() {
        return source;
    }

    /** Meldet künftige Änderungen. */
    public void observe(Consumer<FileBrowser> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void unobserve(Consumer<FileBrowser> observer) {
        observers.remove(observer);
    }

    // ---- Zustand ---------------------------------------------------------------------------------------------------

    /** Der geöffnete Ordner, leer vor dem ersten Öffnen. */
    public Optional<String> location() {
        return read(() -> Optional.ofNullable(location));
    }

    /** Alle Einträge des Ordners, ungefiltert. */
    public List<FileEntry> entries() {
        return read(() -> entries);
    }

    /** Die Einträge nach Filter und Sortierung, so wie die Liste sie zeigt. */
    public List<FileEntry> visible() {
        return read(() -> {
            List<FileEntry> shown = new ArrayList<>();
            for (FileEntry entry : entries) {
                if (pattern == null || pattern.matcher(entry.name()).find()) {
                    shown.add(entry);
                }
            }
            shown.sort(sort.comparator());
            return List.copyOf(shown);
        });
    }

    public String filter() {
        return read(() -> filter);
    }

    /** {@code false}, solange der Filter kein gültiger regulärer Ausdruck ist (dann filtert er nicht). */
    public boolean filterValid() {
        return read(() -> filterValid);
    }

    public SortMode sort() {
        return read(() -> sort);
    }

    public boolean loading() {
        return read(() -> loading);
    }

    /** Warum der Ordner nicht gelesen werden konnte. */
    public Optional<String> failure() {
        return read(() -> Optional.ofNullable(failure));
    }

    public Optional<Message> message() {
        return read(() -> Optional.ofNullable(message));
    }

    public boolean canGoBack() {
        return read(() -> !back.isEmpty());
    }

    public boolean canGoForward() {
        return read(() -> !forward.isEmpty());
    }

    public boolean canGoUp() {
        return read(() -> location != null && source.parent(location).isPresent());
    }

    // ---- Navigation ------------------------------------------------------------------------------------------------

    /** Öffnet einen Ordner; der bisherige kommt in den Verlauf, die letzte Meldung gilt nicht mehr. */
    public void open(String folder) {
        Objects.requireNonNull(folder, "folder");
        String target;
        try {
            target = source.normalize(folder);
        } catch (FileAccessException e) {
            say(new Message(Tone.ERROR, e.getMessage()));
            return;
        }
        lock.lock();
        try {
            message = null;
            if (location != null && !location.equals(target)) {
                back.push(location);
                forward.clear();
            }
        } finally {
            lock.unlock();
        }
        load(target);
    }

    public void back() {
        String target = read(() -> {
            if (back.isEmpty()) {
                return null;
            }
            message = null;
            if (location != null) {
                forward.push(location);
            }
            return back.pop();
        });
        if (target != null) {
            load(target);
        }
    }

    public void forward() {
        String target = read(() -> {
            if (forward.isEmpty()) {
                return null;
            }
            message = null;
            if (location != null) {
                back.push(location);
            }
            return forward.pop();
        });
        if (target != null) {
            load(target);
        }
    }

    public void up() {
        String current = read(() -> location);
        if (current != null) {
            source.parent(current).ifPresent(this::open);
        }
    }

    /** Liest den geöffneten Ordner neu. */
    public void refresh() {
        String current = read(() -> location);
        if (current != null) {
            load(current);
        }
    }

    // ---- Filter und Sortierung -------------------------------------------------------------------------------------

    /** Setzt den Regex-Filter auf den Namen; ein ungültiger Ausdruck filtert nicht und wird als ungültig gemeldet. */
    public void filter(String expression) {
        String text = expression == null ? "" : expression.strip();
        lock.lock();
        try {
            filter = text;
            if (text.isEmpty()) {
                pattern = null;
                filterValid = true;
            } else {
                try {
                    pattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                    filterValid = true;
                } catch (PatternSyntaxException e) {
                    pattern = null;
                    filterValid = false;
                }
            }
        } finally {
            lock.unlock();
        }
        changed();
    }

    public void sort(SortMode mode) {
        lock.lock();
        try {
            sort = Objects.requireNonNull(mode, "mode");
        } finally {
            lock.unlock();
        }
        changed();
    }

    // ---- Aktionen --------------------------------------------------------------------------------------------------

    /** Legt im geöffneten Ordner einen Ordner an und liest den Ordner danach neu. */
    public void createFolder(String name) {
        create(name, true);
    }

    /** Legt im geöffneten Ordner eine leere Datei an und liest den Ordner danach neu. */
    public void createFile(String name) {
        create(name, false);
    }

    private void create(String name, boolean folder) {
        Optional<String> problem = FileNames.problem(name);
        if (problem.isPresent()) {
            say(new Message(Tone.ERROR, problem.get()));
            return;
        }
        String current = read(() -> location);
        if (current == null) {
            return;
        }
        String clean = name.strip();
        background.execute(() -> {
            String target = source.child(current, clean);
            try {
                if (folder) {
                    source.createFolder(target);
                } else {
                    source.createFile(target);
                }
                say(new Message(Tone.SUCCESS, (folder ? "Ordner „" : "Datei „") + clean + "“ angelegt."));
            } catch (FileAccessException e) {
                say(new Message(Tone.ERROR, e.getMessage()));
            }
            reloadIfStill(current);
        });
    }

    /**
     * Löscht die Einträge nacheinander; was scheitert, steht mit Grund in der Meldung, der Rest wird trotzdem
     * gelöscht. Danach wird der Ordner neu gelesen. Rückfragen stellt die Oberfläche vorher.
     */
    public void delete(Collection<FileEntry> doomed) {
        List<FileEntry> all = List.copyOf(doomed);
        if (all.isEmpty()) {
            return;
        }
        String current = read(() -> location);
        background.execute(() -> {
            Map<String, String> failed = new LinkedHashMap<>();
            int deleted = 0;
            for (FileEntry entry : all) {
                try {
                    source.delete(entry.location());
                    deleted++;
                } catch (FileAccessException e) {
                    failed.put(entry.name(), e.getMessage());
                }
            }
            say(deletionMessage(deleted, failed));
            reloadIfStill(current);
        });
    }

    static Message deletionMessage(int deleted, Map<String, String> failed) {
        if (failed.isEmpty()) {
            return new Message(Tone.SUCCESS, deleted == 1 ? "1 Eintrag gelöscht." : deleted + " Einträge gelöscht.");
        }
        StringBuilder text = new StringBuilder();
        text.append(deleted).append(" gelöscht, ").append(failed.size()).append(" nicht gelöscht: ");
        boolean first = true;
        for (Map.Entry<String, String> entry : failed.entrySet()) {
            if (!first) {
                text.append("; ");
            }
            text.append(entry.getKey()).append(" – ").append(entry.getValue());
            first = false;
        }
        return new Message(Tone.ERROR, text.toString());
    }

    // ---- intern ----------------------------------------------------------------------------------------------------

    private void reloadIfStill(String folder) {
        if (folder != null && folder.equals(read(() -> location))) {
            load(folder);
        }
    }

    /**
     * Setzt {@code folder} sofort als geöffneten Ordner und liest ihn im Hintergrund. Jede Anfrage bekommt eine
     * Nummer; ein Ergebnis zählt nur, wenn seither keine neuere Anfrage kam.
     */
    private void load(String folder) {
        long mine;
        lock.lock();
        try {
            mine = ++ticket;
            boolean sameFolder = folder.equals(location);
            location = folder;
            loading = true;
            failure = null;
            if (!sameFolder) {
                entries = List.of();
            }
        } finally {
            lock.unlock();
        }
        changed();
        background.execute(() -> fetch(folder, mine));
    }

    private void fetch(String folder, long mine) {
        List<FileEntry> read = null;
        String problem = null;
        try {
            read = List.copyOf(source.list(folder));
        } catch (FileAccessException e) {
            problem = e.getMessage();
        } catch (RuntimeException e) {
            problem = "Ordner konnte nicht gelesen werden (" + e.getClass().getSimpleName() + ")";
        }
        lock.lock();
        try {
            if (mine != ticket) {
                return;
            }
            loading = false;
            entries = read == null ? List.of() : read;
            failure = problem;
        } finally {
            lock.unlock();
        }
        changed();
    }

    private void say(Message next) {
        lock.lock();
        try {
            message = next;
        } finally {
            lock.unlock();
        }
        changed();
    }

    private void changed() {
        observers.forEach(observer -> observer.accept(this));
    }

    private <T> T read(java.util.function.Supplier<T> reader) {
        lock.lock();
        try {
            return reader.get();
        } finally {
            lock.unlock();
        }
    }
}
