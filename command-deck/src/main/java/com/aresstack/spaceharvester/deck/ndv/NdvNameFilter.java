package com.aresstack.spaceharvester.deck.ndv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Namensfilter je Bibliothek aus MainframeMate ({@code RegexNameFilter}): ein freier regulärer Ausdruck (ohne
 * Rücksicht auf Groß- und Kleinschreibung) oder angekreuzte Präfixe aus den ersten drei Buchstaben, aus denen der
 * Ausdruck {@code ^(\QABC\E|\QXYZ\E)} wird. Ein ungültiger Ausdruck filtert nichts.
 */
public final class NdvNameFilter {

    private String regex = "";
    private Pattern compiled;
    private final LinkedHashSet<String> prefixes = new LinkedHashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Ob ein gültiger Ausdruck aktiv ist. */
    public boolean active() {
        return compiled != null;
    }

    public String regex() {
        return regex;
    }

    /** Ob der eingegebene Ausdruck ungültig ist (dann filtert er nichts). */
    public boolean invalid() {
        return !regex.isEmpty() && compiled == null;
    }

    public Set<String> prefixes() {
        return Set.copyOf(prefixes);
    }

    /** Freier Ausdruck; löscht die Präfixauswahl. */
    public void manual(String pattern) {
        regex = pattern == null ? "" : pattern.trim();
        prefixes.clear();
        compile();
        changed();
    }

    /** Kreuzt ein Präfix an oder ab und baut den Ausdruck daraus neu. */
    public void toggle(String prefix, boolean selected) {
        String clean = prefix == null ? "" : prefix.trim().toUpperCase(java.util.Locale.ROOT);
        if (clean.isEmpty()) {
            return;
        }
        if (selected) {
            prefixes.add(clean);
        } else {
            prefixes.remove(clean);
        }
        rebuild();
        changed();
    }

    /** Stellt gespeicherten Zustand her, ohne zu melden; angekreuzte Präfixe haben Vorrang. */
    public void restore(String savedRegex, Collection<String> savedPrefixes) {
        prefixes.clear();
        if (savedPrefixes != null) {
            savedPrefixes.stream().filter(p -> p != null && !p.isBlank())
                    .map(p -> p.trim().toUpperCase(java.util.Locale.ROOT)).forEach(prefixes::add);
        }
        if (prefixes.isEmpty()) {
            regex = savedRegex == null ? "" : savedRegex.trim();
            compile();
        } else {
            rebuild();
        }
    }

    public void clear() {
        regex = "";
        compiled = null;
        prefixes.clear();
        changed();
    }

    /** Leerer Filter oder passender Name; {@code null} fällt bei aktivem Filter durch. */
    public boolean matches(String name) {
        if (compiled == null) {
            return true;
        }
        return name != null && compiled.matcher(name).find();
    }

    public void observe(Runnable listener) {
        listeners.add(listener);
    }

    /** Die ankreuzbaren Präfixe: erste drei Buchstaben der Namen, sortiert, ohne Doppelte. */
    public static SortedSet<String> candidates(Collection<String> names) {
        SortedSet<String> result = new TreeSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String upper = name.trim().toUpperCase(java.util.Locale.ROOT);
            result.add(upper.length() >= 3 ? upper.substring(0, 3) : upper);
        }
        return result;
    }

    /** Für die Ablage: Präfixe mit Komma. */
    public static String join(Collection<String> values) {
        return String.join(",", values);
    }

    public static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    private void rebuild() {
        if (prefixes.isEmpty()) {
            regex = "";
        } else {
            StringBuilder sb = new StringBuilder("^(");
            boolean first = true;
            for (String p : prefixes) {
                if (!first) {
                    sb.append('|');
                }
                sb.append(Pattern.quote(p));
                first = false;
            }
            regex = sb.append(')').toString();
        }
        compile();
    }

    private void compile() {
        if (regex.isEmpty()) {
            compiled = null;
            return;
        }
        try {
            compiled = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            compiled = null;
        }
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }
}
