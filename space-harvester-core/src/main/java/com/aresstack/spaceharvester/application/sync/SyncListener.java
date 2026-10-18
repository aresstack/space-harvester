package com.aresstack.spaceharvester.application.sync;

/**
 * Rückmeldungen eines Sync-Laufs an seinen Auftrag: Zähler und sichere Abbruchstellen. Ein Abbruch über
 * {@link #checkpoint()} verwirft alles Vorgemerkte; der Cache bleibt beim Stand der bisherigen Generation.
 */
public interface SyncListener {

    /** Ohne Rückmeldung. */
    SyncListener NONE = new SyncListener() {
    };

    /** Die Inventur ist fertig; {@code pages} Seiten wurden gelesen. */
    default void inventoried(int pages) {
    }

    /** Die Inventur ist fertig, mit ihren Seiten; ruft standardmäßig {@link #inventoried(int)}. */
    default void inventoried(Inventory inventory) {
        inventoried(inventory.pages().size());
    }

    /** Eine Seite wurde abgeglichen; {@code written}, wenn dafür etwas vorgemerkt wurde. */
    default void pageDone(PageChange change, boolean written, boolean failed) {
    }

    /**
     * Sichere Stelle für einen Abbruch, vor jedem Inhaltsabruf und vor dem Commit. Eine Ausnahme hier beendet den Lauf
     * ohne Commit.
     */
    default void checkpoint() {
    }
}
