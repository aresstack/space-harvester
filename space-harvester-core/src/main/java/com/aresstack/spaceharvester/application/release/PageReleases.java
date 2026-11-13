package com.aresstack.spaceharvester.application.release;

import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Die Liste der für die KI freigegebenen Seiten (ADR 0028): Ausnahmen von einer Quelle, die nicht in
 * {@code ai.external-processing-sources} steht. Der Benutzer trägt Seiten durch seine Interaktion mit dem Chat ein; eine
 * Seite der Liste ist für alle KI-Auswertungen freigegeben. {@link #contains} liest nur eine Hash-Menge im Speicher
 * und ist für jede Berechnung billig.
 *
 * <p>Änderungen meldet die Liste an ihre Beobachter (ADR 0027), nach dem Speichern und außerhalb ihrer Sperre.
 */
public interface PageReleases {

    /**
     * Ein Eintrag.
     *
     * @param key        Quelle und Seite
     * @param spaceKey   Space der Seite, zum Bündeln in den Einstellungen
     * @param node       Wurzel der Auswahl, über die sie hineinkam: die Seiten-ID einer Seite samt Unterseiten oder
     *                   {@code null} für den ganzen Space
     * @param origin     wodurch sie hineinkam, etwa {@link #CHAT}
     * @param releasedAt wann sie zuerst eingetragen wurde
     */
    record Entry(PageKey key, String spaceKey, String node, String origin, Instant releasedAt) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(releasedAt, "releasedAt");
            if (node != null && node.isBlank()) {
                throw new IllegalArgumentException("node darf nicht leer sein");
            }
        }
    }

    /** Eine Änderung der Liste: neu eingetragene und entfernte Einträge, nie beide leer. */
    record Change(List<Entry> added, List<Entry> removed) {

        public Change {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }
    }

    /** Herkunft eines Eintrags aus dem Chat: die beim Senden links gewählten Seiten. */
    String CHAT = "chat";

    /** Ob die Seite auf der Liste steht. */
    boolean contains(PageKey key);

    /** Alle Einträge, in der Reihenfolge des Eintragens. */
    List<Entry> entries();

    /** Die Einträge einer Quelle, in der Reihenfolge des Eintragens. */
    default List<Entry> entries(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        return entries().stream().filter(entry -> entry.key().sourceId().equals(sourceId)).toList();
    }

    /**
     * Trägt Seiten ein. Schon eingetragene bleiben mit ihrem ersten Eintrag (Knoten, Herkunft, Zeitpunkt).
     *
     * @return die neu eingetragenen Einträge; leer, wenn alle schon auf der Liste standen
     */
    List<Entry> add(Collection<Entry> entries);

    /**
     * Entfernt Seiten von der Liste.
     *
     * @return die entfernten Einträge; Seiten, die nicht auf der Liste standen, fehlen darin
     */
    List<Entry> remove(Collection<PageKey> keys);

    /** Entfernt alle Einträge einer Quelle („Daten bereinigen“) und meldet sie wie {@link #remove}. */
    void removeSource(String sourceId);

    /** Meldet einen Beobachter für jede Änderung an. */
    Subscription subscribe(Consumer<Change> listener);

    /** Eine Liste nur im Speicher, etwa ohne Cache-Verzeichnis oder im Test. */
    static PageReleases inMemory() {
        return new InMemoryPageReleases(List.of(), InMemoryPageReleases.Journal.NONE);
    }
}
