package com.aresstack.spaceharvester.deck.ndv.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sperr- und Freigabeliste für NDV wie die Gruppe „NDV“ im {@code SecurityFilterService} aus MainframeMate: Pfade
 * {@code ndv://BIBLIOTHEK} oder {@code ndv://BIBLIOTHEK/OBJEKT}; ein Eintrag gilt auch für alles darunter. Die
 * Sperrliste gewinnt immer; mit „Alles sperren“ kommen nur freigegebene Pfade durch. Was hier gesperrt ist, wird nicht
 * zwischengespeichert, nicht analysiert und mit „Gesperrte ausblenden“ nicht gezeigt.
 */
public final class NdvAccessRules {

    /** Gespeicherter Stand (für die Einstellungen der App). */
    public record Snapshot(boolean blacklistAll, List<String> whitelist, List<String> blacklist) {

        public Snapshot {
            whitelist = List.copyOf(whitelist == null ? List.of() : whitelist);
            blacklist = List.copyOf(blacklist == null ? List.of() : blacklist);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> whitelist = new LinkedHashSet<>();
    private final Set<String> blacklist = new LinkedHashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean blacklistAll;

    /** {@code ndv://BIBLIOTHEK} bzw. {@code ndv://BIBLIOTHEK/OBJEKT} (Objekt ohne Endung, wie in MainframeMate). */
    public static String path(String library, String object) {
        String lib = library == null ? "" : library.trim().toUpperCase(Locale.ROOT);
        String obj = object == null ? "" : object.trim().toUpperCase(Locale.ROOT);
        return "ndv://" + lib + (obj.isEmpty() ? "" : "/" + obj);
    }

    /** Darf der Pfad zwischengespeichert und analysiert werden? */
    public boolean isAllowed(String path) {
        if (path == null) {
            return true;
        }
        lock.lock();
        try {
            if (matchesAny(blacklist, path)) {
                return false;
            }
            return !blacklistAll || matchesAny(whitelist, path);
        } finally {
            lock.unlock();
        }
    }

    public boolean isBlacklisted(String path) {
        lock.lock();
        try {
            return path != null && matchesAny(blacklist, path);
        } finally {
            lock.unlock();
        }
    }

    public boolean isWhitelisted(String path) {
        lock.lock();
        try {
            return path != null && matchesAny(whitelist, path);
        } finally {
            lock.unlock();
        }
    }

    public boolean blacklistAll() {
        lock.lock();
        try {
            return blacklistAll;
        } finally {
            lock.unlock();
        }
    }

    public void blacklistAll(boolean enabled) {
        change(() -> blacklistAll = enabled);
    }

    /** „Auf Freigabeliste setzen“: nimmt die Pfade zugleich von der Sperrliste. */
    public void whitelist(Collection<String> paths) {
        change(() -> paths.stream().map(NdvAccessRules::normalize).forEach(p -> {
            blacklist.remove(p);
            whitelist.add(p);
        }));
    }

    /** „Auf Sperrliste setzen“: nimmt die Pfade zugleich von der Freigabeliste. */
    public void blacklist(Collection<String> paths) {
        change(() -> paths.stream().map(NdvAccessRules::normalize).forEach(p -> {
            whitelist.remove(p);
            blacklist.add(p);
        }));
    }

    /** „Filter entfernen“: aus beiden Listen. */
    public void remove(Collection<String> paths) {
        change(() -> paths.stream().map(NdvAccessRules::normalize).forEach(p -> {
            whitelist.remove(p);
            blacklist.remove(p);
        }));
    }

    public List<String> whitelistEntries() {
        lock.lock();
        try {
            return List.copyOf(whitelist);
        } finally {
            lock.unlock();
        }
    }

    public List<String> blacklistEntries() {
        lock.lock();
        try {
            return List.copyOf(blacklist);
        } finally {
            lock.unlock();
        }
    }

    /** Wie viele Sperr-Einträge unter einem Pfad liegen (für die Anzeige an der Bibliothek). */
    public int countBlacklistedDescendants(String prefix) {
        lock.lock();
        try {
            String norm = normalize(prefix);
            return (int) blacklist.stream().filter(rule -> rule.equals(norm) || rule.startsWith(norm + "/")).count();
        } finally {
            lock.unlock();
        }
    }

    public boolean hasWhitelistedDescendants(String prefix) {
        lock.lock();
        try {
            String norm = normalize(prefix);
            return whitelist.stream().anyMatch(rule -> rule.equals(norm) || rule.startsWith(norm + "/"));
        } finally {
            lock.unlock();
        }
    }

    /** Ein Satz für die Anzeige des Filterzustands eines Pfads (wie {@code refreshSecurityStatus}). */
    public String describe(String path) {
        if (isBlacklisted(path)) {
            return "gesperrt";
        }
        if (isWhitelisted(path)) {
            return "freigegeben";
        }
        return blacklistAll() ? "gesperrt (alles gesperrt)" : "erlaubt";
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(blacklistAll, new ArrayList<>(whitelist), new ArrayList<>(blacklist));
        } finally {
            lock.unlock();
        }
    }

    /** Stellt einen gespeicherten Stand her, ohne zu melden. */
    public void restore(Snapshot snapshot) {
        lock.lock();
        try {
            blacklistAll = snapshot.blacklistAll();
            whitelist.clear();
            blacklist.clear();
            snapshot.whitelist().stream().map(NdvAccessRules::normalize).forEach(whitelist::add);
            snapshot.blacklist().stream().map(NdvAccessRules::normalize).forEach(blacklist::add);
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

    private void change(Runnable change) {
        lock.lock();
        try {
            change.run();
        } finally {
            lock.unlock();
        }
        listeners.forEach(Runnable::run);
    }

    private static String normalize(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toUpperCase(Locale.ROOT).replaceFirst("^NDV://", "ndv://");
    }

    /** Gleich oder darunter (Ordner-Präfix), wie {@code matchesAny} in MainframeMate. */
    private static boolean matchesAny(Set<String> rules, String path) {
        String norm = normalize(path);
        for (String rule : rules) {
            if (rule.equals(norm) || norm.startsWith(rule + "/")) {
                return true;
            }
        }
        return false;
    }
}
