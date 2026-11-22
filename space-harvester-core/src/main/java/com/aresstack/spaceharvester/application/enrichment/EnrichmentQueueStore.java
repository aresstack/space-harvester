package com.aresstack.spaceharvester.application.enrichment;

import java.util.List;
import java.util.Objects;

/**
 * Die Warteschlange der KI-Anreicherung über einen Neustart hinweg (Angelo 22.11.2026): je Gruppe die wartenden und
 * die gescheiterten Berechnungen ihres Teils. Nur Schlüssel (Quelle, Seite, Prozessor, erzwungen), nie Text: Den
 * Snapshot liest der Teil erst beim Rechnen, die KI-Freigabe prüft jede Berechnung bei der Ausführung.
 */
public interface EnrichmentQueueStore {

    /** Ohne Ablage: Die Warteschlange lebt nur im Speicher. */
    EnrichmentQueueStore NONE = new EnrichmentQueueStore() {
        @Override
        public void save(Saved saved) {
        }

        @Override
        public void delete(String groupId) {
        }

        @Override
        public List<Saved> all() {
            return List.of();
        }

        @Override
        public void removeSource(String sourceId) {
        }
    };

    /** Eine Berechnung: Prozessor und Seite der Quelle ihrer Gruppe. */
    record Entry(String processorId, String pageId, boolean force) {

        public Entry {
            Objects.requireNonNull(processorId, "processorId");
            Objects.requireNonNull(pageId, "pageId");
        }
    }

    /** Was eine Gruppe noch zu tun hat und was ihr Teil nicht konnte. */
    record Saved(String groupId, String sourceId, String partId, List<Entry> work, List<Entry> failed) {

        public Saved {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(partId, "partId");
            work = List.copyOf(work);
            failed = List.copyOf(failed);
        }
    }

    /** Legt den Stand einer Gruppe ab; ersetzt den vorigen ganz oder gar nicht. */
    void save(Saved saved);

    /** Vergisst eine Gruppe. */
    void delete(String groupId);

    /** Alle abgelegten Gruppen; Unlesbares fehlt. */
    List<Saved> all();

    /** Vergisst alle Gruppen der Quelle („Daten bereinigen“, „Quelle entfernen“). */
    void removeSource(String sourceId);
}
