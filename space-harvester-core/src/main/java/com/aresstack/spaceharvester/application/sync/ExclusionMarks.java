package com.aresstack.spaceharvester.application.sync;

import java.util.Set;

/**
 * Was der Benutzer ausgeschlossen hat (Angelo 26.11.2026): Ordner oder Seiten samt allem darunter oder ein ganzer
 * Bereich (Space, Bibliothek), je Quelle. Ausgeschlossenes erscheint nicht im Wissensbaum, nicht in der Suche und wird
 * nicht indexiert. Der Ausschluss ändert nur den Umfang, er löscht nichts (ADR 0037, Punkt 3 und 12): Bereits
 * Erworbenes bleibt im Bestand, bis der Benutzer ausdrücklich „Bereinigen“ wählt. Gesetzt über „Ausschließen“ im
 * Kontextmenü des Baums oder in den Einstellungen der Quelle, aufgehoben nur dort.
 *
 * <p>Gleiche Form wie {@link IndexMarks}: markierte Wurzeln je Quelle und Bereich, die Unterseiten folgen über
 * {@link IndexScope}. Entschieden wird nie hier, sondern in {@link Exclusions}.
 */
public interface ExclusionMarks {

    /** Wurzel für den ganzen Bereich. */
    String WHOLE_SPACE = IndexMarks.WHOLE_SPACE;

    /** Nichts ausgeschlossen, nichts merkbar; für Läufe ohne Ablage. */
    ExclusionMarks NONE = new ExclusionMarks() {
        @Override
        public Set<String> roots(String sourceId, String spaceKey) {
            return Set.of();
        }

        @Override
        public Set<String> spaces(String sourceId) {
            return Set.of();
        }

        @Override
        public void mark(String sourceId, String spaceKey, String pageId) {
            throw new UnsupportedOperationException("Ohne Ablage für Ausschlüsse");
        }

        @Override
        public void unmark(String sourceId, String spaceKey, String pageId) {
        }
    };

    /** Die ausgeschlossenen Wurzeln des Bereichs: Seiten-IDs, {@link #WHOLE_SPACE} für den ganzen Bereich. */
    Set<String> roots(String sourceId, String spaceKey);

    /** Die Bereiche der Quelle mit mindestens einem Ausschluss. */
    Set<String> spaces(String sourceId);

    /** Schließt die Seite samt Unterseiten aus, mit {@code pageId == null} den ganzen Bereich. */
    void mark(String sourceId, String spaceKey, String pageId);

    /** Hebt genau diesen Ausschluss auf; Ausschlüsse darüber oder darunter bleiben. */
    void unmark(String sourceId, String spaceKey, String pageId);

    /** Ob genau diese Wurzel ausgeschlossen ist, mit {@code pageId == null} der ganze Bereich. */
    default boolean marked(String sourceId, String spaceKey, String pageId) {
        return roots(sourceId, spaceKey).contains(pageId == null ? WHOLE_SPACE : pageId);
    }
}
