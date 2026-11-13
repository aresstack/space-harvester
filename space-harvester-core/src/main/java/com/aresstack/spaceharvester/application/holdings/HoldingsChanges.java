package com.aresstack.spaceharvester.application.holdings;

import java.util.Collection;

/**
 * Was Schreibwege dem Bestand melden (ADR 0027): der Seiten-Cache nach jedem Commit, Beschaffung, Abgleich und Export
 * zu Beginn und Ende des Ladens. Eine Umsetzung merkt nur vor und kehrt sofort zurück, denn der Melder hält oft eine
 * Schreibsperre; sie wirft nicht.
 */
public interface HoldingsChanges {

    /** Diese Seiten eines Space wurden geschrieben oder entfernt, oder ihr Laden ist zu Ende. */
    void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds);

    /** Der Space hat sich als Ganzes geändert, etwa durch eine neue bestätigte Generation. */
    void spaceChanged(String sourceId, String spaceKey);

    /** Alles einer Quelle wurde entfernt oder ersetzt. */
    void sourceChanged(String sourceId);

    /** Das Laden dieser Seiten eines Space hat begonnen. */
    void loading(String sourceId, String spaceKey, Collection<String> pageIds);

    /** Meldet nichts, etwa ohne Bestand. */
    HoldingsChanges NONE = new HoldingsChanges() {
        @Override
        public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
        }

        @Override
        public void spaceChanged(String sourceId, String spaceKey) {
        }

        @Override
        public void sourceChanged(String sourceId) {
        }

        @Override
        public void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
        }
    };
}
