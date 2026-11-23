package com.aresstack.spaceharvester.deck.ftp;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Lokaler Namensfilter, übernommen aus MainframeMate ({@code RegexNameFilter}): entweder ein frei eingegebener Regex
 * (ohne Rücksicht auf Groß-/Kleinschreibung) oder angekreuzte Werte, aus denen ein verankerter Regex
 * {@code ^(\QA\E|\QB\E)} entsteht. „Aktiviert“ schaltet den Filter ab, ohne ihn zu vergessen. Beobachter statt
 * Abfragen.
 */
public final class NameFilter {

    private final Set<String> selected = new LinkedHashSet<>();
    private final List<Runnable> observers = new CopyOnWriteArrayList<>();
    private String regex = "";
    private Pattern compiled;
    private boolean enabled = true;

    /** Ob gerade gefiltert wird (eingeschaltet und ein gültiger Regex). */
    public boolean active() {
        return enabled && compiled != null;
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean value) {
        if (enabled != value) {
            enabled = value;
            changed();
        }
    }

    public String regex() {
        return regex;
    }

    /** Die angekreuzten Werte (groß geschrieben). */
    public Set<String> selected() {
        return Set.copyOf(selected);
    }

    /** Ein von Hand eingegebener Regex; die Häkchen werden dabei gelöscht. */
    public void manual(String pattern) {
        regex = pattern == null ? "" : pattern.trim();
        selected.clear();
        compile();
        changed();
    }

    /** Kreuzt einen Wert an oder ab; der Regex entsteht daraus neu. */
    public void toggle(String value, boolean on) {
        String upper = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) {
            return;
        }
        if (on) {
            selected.add(upper);
        } else {
            selected.remove(upper);
        }
        rebuild();
        changed();
    }

    public void select(Collection<String> values) {
        selected.clear();
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank()).map(v -> v.trim().toUpperCase(Locale.ROOT))
                    .forEach(selected::add);
        }
        rebuild();
        changed();
    }

    /** „Filter zurücksetzen“. */
    public void clear() {
        regex = "";
        compiled = null;
        selected.clear();
        changed();
    }

    /** Leerer oder ausgeschalteter Filter lässt alles durch; {@code null} fällt bei aktivem Filter durch. */
    public boolean matches(String name) {
        if (!active()) {
            return true;
        }
        return name != null && compiled.matcher(name).find();
    }

    public void observe(Runnable observer) {
        observers.add(observer);
    }

    private void rebuild() {
        if (selected.isEmpty()) {
            regex = "";
        } else {
            StringBuilder text = new StringBuilder("^(");
            boolean first = true;
            for (String value : selected) {
                if (!first) {
                    text.append('|');
                }
                text.append(Pattern.quote(value));
                first = false;
            }
            regex = text.append(')').toString();
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
        observers.forEach(Runnable::run);
    }
}
