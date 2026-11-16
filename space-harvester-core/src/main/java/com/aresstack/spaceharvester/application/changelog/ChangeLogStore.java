package com.aresstack.spaceharvester.application.changelog;

import java.util.List;
import java.util.function.Consumer;

/** Ablage des Änderungsprotokolls und der Update-Marken (ADR 0030), im Seiten-Cache. */
public interface ChangeLogStore extends UpdateMarks {

    /** Die Zeilen einer Quelle in der Reihenfolge ihres Entstehens. */
    List<ChangeEntry> changeEntries(String sourceId);

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
