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

    /**
     * Die Quelle war beim Inhaltsabruf {@link PlanApplier#CONNECTION_LOST_AFTER} Mal nacheinander nicht erreichbar oder
     * drosselt. Ein Auftrag hält sich hier an ({@link com.aresstack.spaceharvester.application.jobs.JobContext#pause}),
     * statt die übrigen Seiten als Fehler zu zählen; eine Ausnahme beendet den Lauf wie {@link #checkpoint()}.
     * Standardmäßig läuft er weiter.
     */
    default void connectionLost() {
    }
}
