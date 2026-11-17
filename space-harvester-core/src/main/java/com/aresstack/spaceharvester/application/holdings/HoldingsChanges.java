package com.aresstack.spaceharvester.application.holdings;

import java.util.Collection;
import java.util.Objects;

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

    /**
     * Meldet weiter wie dieser Empfänger und ruft danach {@code afterChange} bei jeder Änderung (nicht beim Beginn des
     * Ladens), etwa damit die Hintergrundfüllung nach neuen Lücken sucht, statt den Katalog in Abständen abzusuchen.
     * {@code afterChange} kehrt sofort zurück und wirft nicht.
     */
    default HoldingsChanges andThen(Runnable afterChange) {
        Objects.requireNonNull(afterChange, "afterChange");
        HoldingsChanges first = this;
        return new HoldingsChanges() {
            @Override
            public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
                first.pagesChanged(sourceId, spaceKey, pageIds);
                afterChange.run();
            }

            @Override
            public void spaceChanged(String sourceId, String spaceKey) {
                first.spaceChanged(sourceId, spaceKey);
                afterChange.run();
            }

            @Override
            public void sourceChanged(String sourceId) {
                first.sourceChanged(sourceId);
                afterChange.run();
            }

            @Override
            public void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
                first.loading(sourceId, spaceKey, pageIds);
            }
        };
    }

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
