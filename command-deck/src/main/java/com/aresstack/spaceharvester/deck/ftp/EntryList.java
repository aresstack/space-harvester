package com.aresstack.spaceharvester.deck.ftp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Sortieren, Filtern und Gruppieren einer Liste vom Host wie im Navigator von MainframeMate: Ordner bzw. Datasets
 * stehen immer vorn; der Filter sucht im Namen (auf MVS enthält, mit führendem {@code /} ein Regex; sonst ein Regex,
 * ungültig wie enthält), ohne Rücksicht auf Groß-/Kleinschreibung.
 */
public final class EntryList {

    /** Sortierung aus dem Menü des Navigators. */
    public enum Sort {
        NAME_ASC("Name A → Z"), NAME_DESC("Name Z → A"), SIZE_DESC("Größe (größte zuerst)"),
        DATE_DESC("Datum (neueste zuerst)"), EXTENSION("Endung");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Ergebnis des Filters: was zu sehen ist und ob die Eingabe ein gültiges Muster war. */
    public record Filtered(List<RemoteEntry> entries, boolean valid) {

        public Filtered {
            entries = List.copyOf(entries);
        }

        /** Rot hinterlegen: ungültiges Muster oder nichts gefunden, obwohl es Einträge gibt. */
        public boolean alarming(int total) {
            return !valid || (entries.isEmpty() && total > 0);
        }
    }

    private EntryList() {
    }

    public static List<RemoteEntry> sorted(List<RemoteEntry> entries, Sort sort) {
        List<RemoteEntry> result = new ArrayList<>(entries);
        Comparator<RemoteEntry> byName = Comparator.comparing(RemoteEntry::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<RemoteEntry> order = switch (sort == null ? Sort.NAME_ASC : sort) {
            case NAME_DESC -> byName.reversed();
            case SIZE_DESC -> Comparator.comparingLong(RemoteEntry::size).reversed().thenComparing(byName);
            case DATE_DESC -> Comparator.comparingLong((RemoteEntry entry) -> entry.modified()
                    .map(java.time.Instant::toEpochMilli).orElse(Long.MIN_VALUE)).reversed().thenComparing(byName);
            case EXTENSION -> Comparator.comparing(RemoteEntry::extension).thenComparing(byName);
            case NAME_ASC -> byName;
        };
        result.sort(Comparator.comparing((RemoteEntry entry) -> !entry.directory()).thenComparing(order));
        return result;
    }

    public static Filtered filtered(List<RemoteEntry> entries, String filter, boolean mvs) {
        String text = filter == null ? "" : filter.trim();
        if (text.isEmpty()) {
            return new Filtered(entries, true);
        }
        Pattern pattern = null;
        boolean valid = true;
        boolean regex = !mvs || (text.startsWith("/") && text.length() > 1);
        if (regex) {
            try {
                pattern = Pattern.compile(mvs ? text.substring(1) : text, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                valid = false;
            }
        }
        String needle = text.toLowerCase(Locale.ROOT);
        List<RemoteEntry> result = new ArrayList<>();
        for (RemoteEntry entry : entries) {
            boolean hit = pattern != null ? pattern.matcher(entry.name()).find()
                    : entry.name().toLowerCase(Locale.ROOT).contains(needle);
            if (hit) {
                result.add(entry);
            }
        }
        return new Filtered(result, valid);
    }

    /** „Nach Endung gruppieren“: Ordner zuerst unter „Ordner“, dann je Endung, ohne Endung unter „(ohne Endung)“. */
    public static Map<String, List<RemoteEntry>> grouped(List<RemoteEntry> entries) {
        Map<String, List<RemoteEntry>> groups = new LinkedHashMap<>();
        List<RemoteEntry> folders = entries.stream().filter(RemoteEntry::directory).toList();
        if (!folders.isEmpty()) {
            groups.put("Ordner", new ArrayList<>(folders));
        }
        entries.stream().filter(entry -> !entry.directory())
                .sorted(Comparator.comparing(RemoteEntry::extension))
                .forEach(entry -> groups.computeIfAbsent(entry.extension().isEmpty() ? "(ohne Endung)"
                        : "." + entry.extension(), key -> new ArrayList<>()).add(entry));
        return groups;
    }

    /** Ob die Liste nur Datasets und keine Members hat, also kein PDS ist („Kein PDS“ in MainframeMate). */
    public static boolean onlyDatasets(List<RemoteEntry> entries) {
        return !entries.isEmpty() && entries.stream().allMatch(RemoteEntry::directory);
    }
}
