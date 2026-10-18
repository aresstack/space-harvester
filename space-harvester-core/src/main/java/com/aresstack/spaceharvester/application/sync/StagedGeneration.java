package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;

/**
 * Vorgemerkte Änderungen eines Sync-Laufs an einem Space. Dieselben Schreibvorgänge wie im
 * {@link com.aresstack.spaceharvester.application.snapshot.SnapshotRepository}, aber erst mit {@link #commit}
 * sichtbar, und zwar gemeinsam mit der neuen Generation: Leser sehen entweder den alten Stand samt alter Generation
 * oder den neuen samt neuer, nie eine Mischung.
 *
 * <p>Ohne Commit (Fehler, Abbruch, {@link #close} vorher) bleibt nichts davon sichtbar. Die Umsetzung (#26) entscheidet,
 * wie sie vormerkt (z. B. Lucene-Dokumente mit Generationsmarke oder ungespeicherter Writer); große Payloads müssen
 * dafür nicht im Speicher gehalten werden.
 */
public interface StagedGeneration extends AutoCloseable {

    /** Wie {@code SnapshotRepository.save}, sichtbar erst nach {@link #commit}. */
    void save(PageSnapshot snapshot);

    /** Wie {@code SnapshotRepository.saveHeader}, sichtbar erst nach {@link #commit}. */
    void saveHeader(PageHeader header);

    /** Wie {@code SnapshotRepository.remove}, wirksam erst nach {@link #commit}. */
    void remove(PageKey key);

    /**
     * Sperrt eine Seite für jede Ausgabe (ADR 0014, {@link RemovalCheck.Verdict#INACCESSIBLE}), wirksam erst nach
     * {@link #commit}: Sie ist danach wie entfernt nicht mehr sichtbar. Einen Revisionsverlauf behält die Umsetzung bis
     * zum endgültigen {@link #remove} nach Ablauf der Frist.
     */
    void withhold(PageKey key);

    /**
     * Macht alle vorgemerkten Änderungen und {@code next} als bestätigte Generation in einem Schritt sichtbar.
     *
     * @throws SyncConflictException wenn {@code next.generation()} nicht genau um eins über der aktuell bestätigten
     *                               liegt (bzw. nicht 1 ist, solange keine existiert), etwa weil ein paralleler Lauf
     *                               schneller war; dann wird nichts sichtbar
     */
    void commit(SyncBaseline next);

    /** Verwirft alle nicht bestätigten Änderungen; nach {@link #commit} wirkungslos. */
    @Override
    void close();
}
