package com.aresstack.spaceharvester.application.sync;

import java.util.Set;

/**
 * Was indexiert wird (Angelo 20.11.2026): markierte Wurzeln je Space, eine Seite samt allen Unterseiten oder der ganze
 * Space. Seiten darunter lädt jeder Abgleich vollständig, auch neue; alles Übrige läuft in der Betriebsart der Quelle.
 * Gesetzt über „Indexieren“ im Kontextmenü des Baums.
 */
public interface IndexMarks {

    /** Wurzel für den ganzen Space. */
    String WHOLE_SPACE = "";

    /** Nichts markiert, nichts merkbar; für Abgleiche ohne Ablage. */
    IndexMarks NONE = new IndexMarks() {
        @Override
        public Set<String> roots(String sourceId, String spaceKey) {
            return Set.of();
        }

        @Override
        public void mark(String sourceId, String spaceKey, String pageId) {
            throw new UnsupportedOperationException("Ohne Ablage für Indexier-Markierungen");
        }

        @Override
        public void unmark(String sourceId, String spaceKey, String pageId) {
        }
    };

    /** Die markierten Wurzeln des Space: Seiten-IDs, {@link #WHOLE_SPACE} für den ganzen Space. */
    Set<String> roots(String sourceId, String spaceKey);

    /** Markiert die Seite samt Unterseiten, mit {@code pageId == null} den ganzen Space. */
    void mark(String sourceId, String spaceKey, String pageId);

    /** Hebt die Markierung genau dieser Wurzel auf; Markierungen darüber oder darunter bleiben. */
    void unmark(String sourceId, String spaceKey, String pageId);

    /** Ob genau diese Wurzel markiert ist, mit {@code pageId == null} der ganze Space. */
    default boolean marked(String sourceId, String spaceKey, String pageId) {
        return roots(sourceId, spaceKey).contains(pageId == null ? WHOLE_SPACE : pageId);
    }
}
