package com.aresstack.spaceharvester.application.changelog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Höchstens ein Update je Tag (Angelo, 21.11.2026): Alle Abgleiche eines Tages bilden zusammen ein Update mit einer
 * Zusammenfassung. Maßgeblich ist der Tag des Abgleichs (das Ende der Runde), nicht der Tag der Änderungen; das Update
 * umfasst alles seit dem vorigen Tag mit Abgleich. Die Marken bleiben einzeln abgelegt, zusammengelegt wird beim
 * Lesen; so gilt das auch für die Marken von vorher.
 *
 * <p>Die Erstbefüllung bleibt für sich: Sie hat keine Zusammenfassung, und was danach am selben Tag geändert wurde,
 * wäre in ihr nicht mehr zu sehen.
 */
public final class DailyUpdateMarks {

    private DailyUpdateMarks() {
    }

    /**
     * Legt die Marken je Tag in {@code zone} zusammen.
     *
     * @param marks die Marken einer Quelle, älteste zuerst
     * @return je Tag eine Marke (die Erstbefüllung zusätzlich für sich), älteste zuerst
     */
    public static List<UpdateMark> perDay(List<UpdateMark> marks, ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        List<UpdateMark> days = new ArrayList<>(marks.size());
        for (UpdateMark mark : marks) {
            UpdateMark last = days.isEmpty() ? null : days.getLast();
            if (last != null && !last.initial() && !mark.initial() && day(last, zone).equals(day(mark, zone))) {
                days.set(days.size() - 1, merge(last, mark));
            } else {
                days.add(mark);
            }
        }
        return days;
    }

    private static LocalDate day(UpdateMark mark, ZoneId zone) {
        return mark.at().atZone(zone).toLocalDate();
    }

    /**
     * Zwei Runden eines Tages als eine: Zeitpunkt und Benutzer der späteren, Spaces beider. Was früher scheiterte und
     * später im selben Space wieder abgeglichen wurde, gilt als erledigt; so zählt nach „Abgleich wiederholen“ nur noch,
     * was auch dabei scheiterte.
     */
    static UpdateMark merge(UpdateMark earlier, UpdateMark later) {
        Set<String> spaces = new TreeSet<>(earlier.spaces());
        spaces.addAll(later.spaces());
        Set<String> open = new TreeSet<>(earlier.retrySpaces());
        open.removeAll(later.spaces());
        int remaining;
        if (earlier.failedSyncs() == 0 || (!earlier.retrySpaces().isEmpty() && open.isEmpty())) {
            remaining = 0;
        } else if (!earlier.failedSpaces().isEmpty()) {
            // Gescheiterte Aufträge ohne Space (selten) lassen sich keinem Abgleich zuordnen und bleiben stehen.
            remaining = open.size() + Math.max(0, earlier.failedSyncs() - earlier.failedSpaces().size());
        } else {
            remaining = earlier.failedSyncs();
        }
        int failed = later.failedSyncs() + remaining;
        Set<String> failedSpaces = new TreeSet<>(later.retrySpaces());
        if (remaining > 0) {
            failedSpaces.addAll(open);
        }
        return new UpdateMark(later.sourceId(), later.at(), later.user() != null ? later.user() : earlier.user(),
                List.copyOf(spaces), failed, false, failed == 0 ? List.of() : List.copyOf(failedSpaces));
    }
}
