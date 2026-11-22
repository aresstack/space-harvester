package com.aresstack.spaceharvester.deck.files;

import java.time.Instant;
import java.util.Comparator;

/** Sortierungen der Dateiliste wie in MainframeMate; Ordner stehen immer vorn. */
public enum SortMode {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    SIZE_DESC("Größte zuerst"),
    DATE_DESC("Neueste zuerst"),
    EXTENSION("Dateityp");

    private static final Comparator<FileEntry> BY_NAME =
            Comparator.comparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER);

    private final String label;

    SortMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Vergleich mit Ordnern zuerst, bei Gleichstand nach Name. */
    public Comparator<FileEntry> comparator() {
        Comparator<FileEntry> foldersFirst = Comparator.comparing(entry -> !entry.directory());
        Comparator<FileEntry> order = switch (this) {
            case NAME_ASC -> BY_NAME;
            case NAME_DESC -> BY_NAME.reversed();
            case SIZE_DESC -> Comparator.comparingLong(FileEntry::size).reversed().thenComparing(BY_NAME);
            case DATE_DESC -> Comparator.comparing((FileEntry entry) -> entry.modified() == null ? Instant.MIN
                    : entry.modified()).reversed().thenComparing(BY_NAME);
            case EXTENSION -> Comparator.comparing(FileEntry::extension).thenComparing(BY_NAME);
        };
        return foldersFirst.thenComparing(order);
    }
}
