package com.aresstack.spaceharvester.deck.ndv.analysis;

import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Zwischenspeicher der Natural-Quelltexte eines NDV-Servers wie {@code NdvSourceCacheService} aus MainframeMate:
 * im Speicher und dauerhaft ({@link Store}), mit Vorabladen einer ganzen Bibliothek im Hintergrund. Das Vorabladen
 * lädt nur, was sich seit dem letzten Mal geändert hat (Größe und Datum vom Server), überspringt Gesperrtes
 * ({@link NdvAccessRules}) und lässt zwischen zwei Objekten eine kurze Pause, damit die Verbindung für die Arbeit frei
 * bleibt. Aus dem Zwischenspeicher entstehen der Abhängigkeitsgraph ({@link NdvAnalysis}) und die Suche im Quelltext.
 */
public final class NdvSourceCache {

    /** Pause zwischen zwei Objekten beim Vorabladen, wie in MainframeMate. */
    static final long PAUSE_MILLIS = 50;

    /** Ein zwischengespeicherter Quelltext mit Größe und Datum, wie der Server sie beim Laden meldete. */
    public record Entry(String library, String name, String extension, String source, int size, String date,
                        int databaseId, int fileNumber) {

        public Entry(String library, String name, String extension, String source, int size, String date) {
            this(library, name, extension, source, size, date, 0, 0);
        }

        public Entry {
            library = upper(library);
            name = upper(name);
            extension = upper(extension);
            source = source == null ? "" : source;
            date = date == null ? "" : date;
        }

        public String key() {
            return library + "/" + objectKey();
        }

        public String objectKey() {
            return identity(name, extension, databaseId, fileNumber);
        }
    }

    private static String identity(String name, String extension, int databaseId, int fileNumber) {
        return upper(name) + "." + upper(extension) + "@" + databaseId + "-" + fileNumber;
    }

    private static String identity(NdvObject object) {
        return identity(object.displayName(), object.type().extension(),
                object.location().databaseId(), object.location().fileNumber());
    }

    /** Ein Treffer der Suche im Quelltext: Objekt, Zeile (ab 1) und die Zeile selbst. */
    public record Hit(String library, String name, String extension, int line, String text,
                      int databaseId, int fileNumber) {

        public boolean matches(NdvObject object) {
            return library.equalsIgnoreCase(object.library())
                    && name.equalsIgnoreCase(object.displayName())
                    && extension.equalsIgnoreCase(object.type().extension())
                    && databaseId == object.location().databaseId() && fileNumber == object.location().fileNumber();
        }
    }

    /** Dauerhafte Ablage (Port); {@code server} ist {@code host:port}. */
    public interface Store {

        Map<String, Entry> load(String server, String library);

        void put(String server, Entry entry);

        void removeLibrary(String server, String library);
    }

    /** Fortschritt des Vorabladens; kommt auf dem Hintergrund-Thread. */
    public interface PrefetchListener {

        void progress(int current, int total, String name);

        void complete(PrefetchResult result);
    }

    /** Ergebnis: neu geladen, unverändert übersprungen, gesperrt, fehlgeschlagen (mit Grund je Objekt). */
    public record PrefetchResult(String library, int total, int loaded, int unchanged, int blocked,
                                 Map<String, String> failed, boolean cancelled) {

        public PrefetchResult {
            failed = Map.copyOf(failed);
        }
    }

    private final String server;
    private final Store store;
    private final NdvAccessRules rules;
    private final ReentrantLock lock = new ReentrantLock();
    /** Bibliothek → Name → Eintrag; eine Bibliothek wird beim ersten Zugriff aus der Ablage gelesen. */
    private final Map<String, Map<String, Entry>> libraries = new LinkedHashMap<>();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    /** Je Bibliothek ein Zähler, den „Cache leeren“ erhöht; ein laufendes Vorabladen schreibt danach nichts mehr. */
    private final Map<String, Long> generations = new java.util.HashMap<>();
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public NdvSourceCache(String server, Store store, NdvAccessRules rules) {
        this.server = server;
        this.store = store;
        this.rules = rules;
    }

    public String server() {
        return server;
    }

    /** Ob ein Objekt Natural-Quelltext ist, der zwischengespeichert wird (wie {@code isNaturalSourceType}). */
    public static boolean naturalSource(NdvObject object) {
        String ext = object.type().extension();
        return object.source() && (ext == null || ext.isEmpty() || upper(ext).startsWith("NS") || upper(ext).equals("NAT"));
    }

    public Optional<String> source(String library, String name) {
        return entry(library, name).map(Entry::source);
    }

    /** Der Eintrag, falls zwischengespeichert und nach den aktuellen Regeln erlaubt. */
    public Optional<Entry> entry(String library, String name) {
        lock.lock();
        try {
            List<Entry> matches = library(library).values().stream()
                    .filter(entry -> entry.name().equals(upper(name))).filter(this::allowed).toList();
            return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public Optional<Entry> entry(NdvObject object) {
        return stored(object).filter(this::allowed);
    }

    /** Resolve an exact object rather than guessing from an untyped name. */
    private Optional<Entry> stored(NdvObject object) {
        lock.lock();
        try {
            return Optional.ofNullable(library(object.library()).get(identity(object)));
        } finally {
            lock.unlock();
        }
    }

    /** Ob ein zwischengespeicherter Eintrag nach den aktuellen Regeln sichtbar ist (später Gesperrtes nicht). */
    private boolean allowed(Entry entry) {
        return rules.isAllowed(NdvAccessRules.path(entry.library(), entry.name()));
    }

    /** Legt einen Quelltext ab (nach dem Öffnen, Speichern oder Vorabladen); Gesperrtes nicht. */
    public void put(NdvObject object, String source) {
        put(object, source, -1);
    }

    /**
     * Wie {@link #put(NdvObject, String)}, aber nur, wenn die Bibliothek seit {@code generation} nicht geleert wurde
     * ({@code -1} = immer). Prüfen und Schreiben geschehen unter derselben Sperre wie „Cache leeren“.
     *
     * @return ob abgelegt wurde
     */
    private boolean put(NdvObject object, String source, long generation) {
        if (!rules.isAllowed(NdvAccessRules.path(object.library(), object.displayName()))) {
            return false;
        }
        Entry entry = new Entry(object.library(), object.displayName(), object.type().extension(), source,
                object.sourceSize(), object.changed(), object.location().databaseId(), object.location().fileNumber());
        lock.lock();
        try {
            if (generation >= 0 && generation(entry.library()) != generation) {
                return false;
            }
            store.put(server, entry);
            library(entry.library()).put(entry.objectKey(), entry);
        } finally {
            lock.unlock();
        }
        changed();
        return true;
    }

    /** Unter der Sperre aufrufen. */
    private long generation(String library) {
        return generations.getOrDefault(upper(library), 0L);
    }

    /** Alle Quelltexte einer Bibliothek (Name → Text), für den Abhängigkeitsgraphen. */
    public Map<String, String> sources(String library) {
        lock.lock();
        try {
            Map<String, String> result = new LinkedHashMap<>();
            Set<String> ambiguous = new java.util.HashSet<>();
            library(library).values().stream().filter(this::allowed).forEach(entry -> {
                if (result.putIfAbsent(entry.name(), entry.source()) != null) {
                    ambiguous.add(entry.name());
                }
            });
            ambiguous.forEach(result::remove);
            return result;
        } finally {
            lock.unlock();
        }
    }

    public int size(String library) {
        lock.lock();
        try {
            return (int) library(library).values().stream().filter(this::allowed).count();
        } finally {
            lock.unlock();
        }
    }

    /** Bibliotheken, die schon gelesen wurden, mit ihrer Anzahl. */
    public int totalSize() {
        lock.lock();
        try {
            return (int) libraries.values().stream().flatMap(entries -> entries.values().stream())
                    .filter(this::allowed).count();
        } finally {
            lock.unlock();
        }
    }

    public boolean prefetching(String library) {
        AtomicBoolean flag = running.get(upper(library));
        return flag != null && !flag.get();
    }

    /** Bricht das Vorabladen einer Bibliothek ab; Geladenes bleibt. */
    public void cancel(String library) {
        AtomicBoolean flag = running.get(upper(library));
        if (flag != null) {
            flag.set(true);
        }
    }

    /**
     * Lädt die Quelltexte einer Bibliothek vorab (blockiert; aus dem Hintergrund aufrufen). Läuft für die Bibliothek
     * schon ein Vorabladen, kommt sofort ein leeres Ergebnis zurück.
     */
    public PrefetchResult prefetch(NdvSession session, String library, List<NdvObject> objects,
                                   PrefetchListener listener) {
        String lib = upper(library);
        AtomicBoolean cancelled = new AtomicBoolean();
        if (running.putIfAbsent(lib, cancelled) != null) {
            return new PrefetchResult(lib, 0, 0, 0, 0, Map.of(), false);
        }
        long generation;
        lock.lock();
        try {
            generation = generation(lib);
        } finally {
            lock.unlock();
        }
        changed();
        int loaded = 0;
        int unchanged = 0;
        int blocked = 0;
        Map<String, String> failed = new LinkedHashMap<>();
        List<NdvObject> wanted = objects.stream().filter(NdvSourceCache::naturalSource).toList();
        try {
            for (int i = 0; i < wanted.size(); i++) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                NdvObject object = wanted.get(i);
                if (!rules.isAllowed(NdvAccessRules.path(lib, object.displayName()))) {
                    blocked++;
                    continue;
                }
                Optional<Entry> known = stored(object);
                if (known.isPresent() && unchanged(known.get(), object)) {
                    unchanged++;
                    continue;
                }
                listener.progress(i + 1, wanted.size(), object.displayName());
                try {
                    String source = session.read(object);
                    if (!put(object, source, generation)) {
                        // Inzwischen geleert (oder gesperrt): nichts zurückschreiben, Vorabladen endet.
                        cancelled.set(true);
                        break;
                    }
                    loaded++;
                    Thread.sleep(PAUSE_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    failed.put(identity(object), e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage());
                }
            }
        } finally {
            running.remove(lib);
        }
        PrefetchResult result = new PrefetchResult(lib, wanted.size(), loaded, unchanged, blocked, failed,
                cancelled.get());
        listener.complete(result);
        changed();
        return result;
    }

    /** Require reliable matching metadata before skipping a download. */
    static boolean unchanged(Entry cached, NdvObject object) {
        return object.sourceSize() > 0 && object.sourceSize() == cached.size()
                && !object.changed().isBlank() && object.changed().equals(cached.date());
    }

    /** „Cache leeren“: Quelltexte der Bibliothek im Speicher und in der Ablage. */
    public void invalidate(String library) {
        String lib = upper(library);
        cancel(lib);
        lock.lock();
        try {
            generations.merge(lib, 1L, Long::sum);
            libraries.put(lib, new LinkedHashMap<>());
            store.removeLibrary(server, lib);
        } finally {
            lock.unlock();
        }
        changed();
    }

    /**
     * Sucht einen Text in allen zwischengespeicherten Quelltexten einer Bibliothek, ohne Rücksicht auf Groß- und
     * Kleinschreibung; höchstens {@code limit} Treffer.
     */
    public List<Hit> search(String library, String text, int limit) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        if (needle.isEmpty()) {
            return hits;
        }
        List<Entry> entries;
        lock.lock();
        try {
            entries = new ArrayList<>(library(library).values().stream().filter(this::allowed).toList());
        } finally {
            lock.unlock();
        }
        entries.sort(java.util.Comparator.comparing(Entry::name));
        for (Entry entry : entries) {
            String[] lines = entry.source().split("\r?\n", -1);
            for (int i = 0; i < lines.length && hits.size() < limit; i++) {
                if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                    hits.add(new Hit(entry.library(), entry.name(), entry.extension(), i + 1, lines[i].strip(),
                            entry.databaseId(), entry.fileNumber()));
                }
            }
            if (hits.size() >= limit) {
                break;
            }
        }
        return hits;
    }

    /** Namen der zwischengespeicherten Objekte einer Bibliothek. */
    public Set<String> names(String library) {
        lock.lock();
        try {
            return library(library).values().stream().filter(this::allowed).map(Entry::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } finally {
            lock.unlock();
        }
    }

    public void observe(Runnable listener) {
        listeners.add(listener);
    }

    public void unobserve(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }

    /** Unter der Sperre aufrufen. */
    private Map<String, Entry> library(String library) {
        String lib = upper(library);
        return libraries.computeIfAbsent(lib, k -> {
            Map<String, Entry> loaded = new LinkedHashMap<>();
            store.load(server, k).values().forEach(entry -> loaded.put(entry.objectKey(), entry));
            return loaded;
        });
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
