package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Abfrage an die Ablage des Protokolls (ADR 0030): Quellen, Arten, Auslöser, Space, Suchtext, Zeitraum und Richtung.
 * Die Ablage legt alles davon in ihre eigene Abfrage; nur die Rechte prüft {@link ChangeLog} je Zeile.
 *
 * @param sourceIds      nur diese Quellen, mindestens eine
 * @param kinds          nur diese Arten, leer für alle
 * @param triggers       nur diese Auslöser, leer für alle
 * @param spaceKey       nur Zeilen mit diesem neuen oder alten Space, {@code null} für alle
 * @param text           Wörter, die alle im alten oder neuen Titel vorkommen (als Wortanfang), oder genau die Seiten-ID;
 *                       {@code null} für alle
 * @param lower          untere Zeitgrenze, {@code null} ohne
 * @param lowerInclusive ob {@code lower} selbst dazugehört
 * @param upper          obere Zeitgrenze, {@code null} ohne
 * @param upperInclusive ob {@code upper} selbst dazugehört
 * @param newestFirst    neueste zuerst statt in der Reihenfolge des Entstehens
 */
public record ChangeQuery(Set<String> sourceIds, Set<ChangeKind> kinds, Set<ChangeTrigger> triggers, String spaceKey,
                          String text, Instant lower, boolean lowerInclusive, Instant upper, boolean upperInclusive,
                          boolean newestFirst) {

    public ChangeQuery {
        sourceIds = Set.copyOf(sourceIds);
        if (sourceIds.isEmpty()) {
            throw new IllegalArgumentException("keine Quelle");
        }
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        triggers = triggers == null ? Set.of() : Set.copyOf(triggers);
        text = text == null || text.isBlank() ? null : text.strip();
    }

    /** Alle Zeilen einer Quelle in der Reihenfolge ihres Entstehens. */
    public static ChangeQuery source(String sourceId) {
        return new ChangeQuery(Set.of(sourceId), Set.of(), Set.of(), null, null, null, false, null, false, false);
    }

    /** Die Zeilen eines Updates: nach {@code after} (ausschließlich) bis {@code until} (einschließlich). */
    public static ChangeQuery span(String sourceId, Instant after, Instant until) {
        return new ChangeQuery(Set.of(sourceId), Set.of(), Set.of(), null, null, after, false, until, true, false);
    }

    /** Die Wörter des Suchtexts; leer heißt, der Text schränkt nur über die Seiten-ID nicht ein. */
    public List<String> words() {
        return text == null ? List.of() : PageSearchQuery.words(text);
    }

    /** Die Reihenfolge der Treffer. */
    public Comparator<ChangeEntry> order() {
        Comparator<ChangeEntry> byTime = Comparator.comparing(ChangeEntry::at);
        return newestFirst ? byTime.reversed() : byTime;
    }

    /** Ob die Zeile passt; dieselbe Bedeutung wie die Abfrage der Ablage. */
    public boolean matches(ChangeEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return sourceIds.contains(entry.sourceId())
                && (kinds.isEmpty() || kinds.contains(entry.kind()))
                && (triggers.isEmpty() || triggers.contains(entry.trigger()))
                && (spaceKey == null || spaceKey.equals(entry.spaceKey()) || spaceKey.equals(entry.oldSpaceKey()))
                && (lower == null || (lowerInclusive ? !entry.at().isBefore(lower) : entry.at().isAfter(lower)))
                && (upper == null || (upperInclusive ? !entry.at().isAfter(upper) : entry.at().isBefore(upper)))
                && (text == null || text.equals(entry.pageId()) || titleMatches(entry));
    }

    private boolean titleMatches(ChangeEntry entry) {
        List<String> words = new ArrayList<>();
        if (entry.oldTitle() != null) {
            words.addAll(PageSearchQuery.words(entry.oldTitle()));
        }
        if (entry.newTitle() != null) {
            words.addAll(PageSearchQuery.words(entry.newTitle()));
        }
        for (String wanted : words()) {
            if (words.stream().noneMatch(word -> word.startsWith(wanted))) {
                return false;
            }
        }
        return true;
    }
}
