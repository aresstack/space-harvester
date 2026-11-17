package com.aresstack.spaceharvester.application.changelog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Ablage des Änderungsprotokolls und der Update-Marken (ADR 0030), im Seiten-Cache. */
public interface ChangeLogStore extends UpdateMarks {

    /** Die Zeilen einer Quelle in der Reihenfolge ihres Entstehens. */
    List<ChangeEntry> changeEntries(String sourceId);

    /**
     * Die Zeilen zu {@code query} in ihrer Reihenfolge, seitenweise. Jeder Treffer geht durch {@code view} (Rechte des
     * Aufrufers, {@code null} heißt unsichtbar); {@link ChangeEntries#total()} zählt nur die sichtbaren. Diese Fassung
     * filtert im Speicher; eine Ablage mit eigener Abfrage überschreibt sie.
     */
    default ChangeEntries changeEntries(ChangeQuery query, UnaryOperator<ChangeEntry> view, int offset, int limit) {
        List<ChangeEntry> found = new ArrayList<>();
        for (String sourceId : query.sourceIds()) {
            for (ChangeEntry entry : changeEntries(sourceId)) {
                if (query.matches(entry)) {
                    found.add(entry);
                }
            }
        }
        found.sort(query.order());
        ChangeWindow window = new ChangeWindow(view, offset, limit);
        found.forEach(window::offer);
        return window.result();
    }

    /** Zahl der Seiten der Quelle im Bestand; negativ, wenn unbekannt. */
    default long pageCount(String sourceId) {
        return -1;
    }

    /** Die abgelegte Zusammenfassung eines Updates in einer Ebene ({@code spaceKey} {@code null}: ganze Quelle). */
    default java.util.Optional<StoredSummary> summary(String sourceId, String updateId, String spaceKey) {
        return java.util.Optional.empty();
    }

    /** Die Updates einer Quelle mit abgelegter Zusammenfassung in einer Ebene ({@code null}: ganze Quelle). */
    default java.util.Set<String> summarizedUpdates(String sourceId, String spaceKey) {
        return java.util.Set.of();
    }

    /** Alle abgelegten Zusammenfassungen einer Quelle, jeder Ebene. */
    default List<StoredSummary> summaries(String sourceId) {
        return List.of();
    }

    /** Legt die Zusammenfassung ab und ersetzt eine frühere desselben Updates und derselben Ebene; meldet die Quelle. */
    default void saveSummary(StoredSummary summary) {
        throw new IllegalStateException("Ohne Cache gibt es keine Ablage für Zusammenfassungen");
    }

    /** Entfernt die Zusammenfassung eines Updates in einer Ebene, ohne Meldung. */
    default void deleteSummary(String sourceId, String updateId, String spaceKey) {
    }

    /**
     * Die Unterschiede beim Ersetzen des Inhalts einer Seite nach {@code after} (ausschließlich, {@code null} ohne
     * Grenze), älteste zuerst, höchstens {@code limit}.
     */
    default List<ContentChange> contentChanges(com.aresstack.spaceharvester.application.snapshot.PageKey key,
                                               java.time.Instant after, int limit) {
        return List.of();
    }

    /**
     * Meldet nach jedem Commit mit neuen Zeilen und nach jeder neuen Marke die Quelle (ADR 0027); die Meldung darf nur
     * vormerken und kehrt sofort zurück.
     */
    void changeLogRecorded(Consumer<String> recorded);

    /** Ohne Cache: kein Protokoll, keine Marken. */
    ChangeLogStore NONE = new ChangeLogStore() {
        @Override
        public List<ChangeEntry> changeEntries(String sourceId) {
            return List.of();
        }

        @Override
        public void changeLogRecorded(Consumer<String> recorded) {
        }

        @Override
        public List<UpdateMark> marks(String sourceId) {
            return List.of();
        }

        @Override
        public void mark(UpdateMark mark) {
        }
    };
}
