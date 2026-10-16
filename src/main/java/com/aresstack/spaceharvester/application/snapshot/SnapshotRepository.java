package com.aresstack.spaceharvester.application.snapshot;

import java.util.Optional;

/**
 * Speicher für native Seiten-Snapshots und die aktuellen Kopfdaten je {@link PageKey}. Umgesetzt in der Persistenz
 * (Lucene, #26); Abfragen für Seitenlisten, Baum und Suche (#27, #28) kommen als eigene Ports dazu.
 *
 * <p>Je Seite gibt es zwei Stände: den zuletzt gespeicherten {@link PageSnapshot} (Payload mit den Kopfdaten derselben
 * Antwort) und die aktuellen {@link PageHeader}-Kopfdaten. {@link #save} setzt beide; {@link #saveHeader} ändert nur
 * die aktuellen Kopfdaten, etwa nach einer reinen Labeländerung oder einem Umzug ohne neue Revision.
 *
 * <p>Pflichten jeder Umsetzung (geprüft durch den Vertragstest {@code SnapshotRepositoryContract}):
 * <ul>
 *   <li>Die Payload kommt Byte für Byte zurück, auch Bytes, die kein gültiges UTF-8 sind.</li>
 *   <li>{@link #save} und {@link #saveHeader} sind Upserts: der zuletzt gespeicherte Stand gilt, ein erneutes
 *       Speichern desselben Stands ändert nichts.</li>
 *   <li>Schlüssel sind je Quelle getrennt: dieselbe Seiten-ID zweier Quellen sind zwei Seiten.</li>
 *   <li>Gespeicherte Stände überleben einen Neustart (bei persistenten Umsetzungen).</li>
 * </ul>
 */
public interface SnapshotRepository {

    /** Speichert den Snapshot als aktuellen Stand seiner Seite; die aktuellen Kopfdaten werden die des Snapshots. */
    void save(PageSnapshot snapshot);

    /**
     * Ersetzt nur die aktuellen Kopfdaten; ein gespeicherter Snapshot bleibt samt seinen Kopfdaten unverändert. Auch
     * für Seiten ohne gespeicherten Snapshot (Inhalt noch nicht geladen).
     */
    void saveHeader(PageHeader header);

    /** Der zuletzt gespeicherte Snapshot oder leer, wenn für die Seite keiner gespeichert ist. */
    Optional<PageSnapshot> find(PageKey key);

    /** Die aktuellen Kopfdaten oder leer, wenn die Seite nicht bekannt ist. */
    Optional<PageHeader> findHeader(PageKey key);

    /**
     * Entfernt eine Seite mit Snapshot und Kopfdaten. Nur aufrufen, wenn ein bestätigt vollständiger Abgleich (#16)
     * die Seite vermisst <em>und</em> eine Einzelabfrage bestätigt, dass es sie in der Quelle nicht (mehr) gibt oder
     * sie nicht sichtbar ist. Eine in einen anderen Space verschobene Seite ist keine entfernte Seite: der Schlüssel
     * enthält keinen Space, ihre neuen Kopfdaten kommen über {@link #saveHeader} oder {@link #save}. Nie wegen eines
     * fehlgeschlagenen oder abgebrochenen Laufs.
     *
     * @return {@code true}, wenn die Seite bekannt war
     */
    boolean remove(PageKey key);
}
