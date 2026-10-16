package com.aresstack.spaceharvester.application.snapshot;

import java.util.Optional;

/**
 * Speicher für native Seiten-Snapshots, je {@link PageKey} der zuletzt gespeicherte Stand. Umgesetzt in der
 * Persistenz (Lucene, #26); Abfragen für Seitenlisten, Baum und Suche (#27, #28) kommen als eigene Ports dazu.
 *
 * <p>Pflichten jeder Umsetzung (geprüft durch den Vertragstest {@code SnapshotRepositoryContract}):
 * <ul>
 *   <li>Die Payload kommt Byte für Byte zurück, auch Bytes, die kein gültiges UTF-8 sind.</li>
 *   <li>{@link #save} ist ein Upsert: der zuletzt gespeicherte Stand gilt, ein erneutes Speichern desselben Stands
 *       ändert nichts.</li>
 *   <li>Schlüssel sind je Quelle getrennt: dieselbe Seiten-ID zweier Quellen sind zwei Seiten.</li>
 *   <li>Gespeicherte Stände überleben einen Neustart (bei persistenten Umsetzungen).</li>
 * </ul>
 */
public interface SnapshotRepository {

    /** Speichert den Snapshot als aktuellen Stand seiner Seite. */
    void save(PageSnapshot snapshot);

    /** Der aktuelle Stand oder leer, wenn die Seite nicht gespeichert ist. */
    Optional<PageSnapshot> find(PageKey key);

    /**
     * Entfernt eine Seite. Nur aus einem bestätigt vollständigen Abgleich aufrufen (#16), nie wegen eines
     * fehlgeschlagenen oder abgebrochenen Laufs.
     *
     * @return {@code true}, wenn die Seite gespeichert war
     */
    boolean remove(PageKey key);
}
