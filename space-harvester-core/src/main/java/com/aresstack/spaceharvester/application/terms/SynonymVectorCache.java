package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vektoren der Synonymerkennung (SY2, ADR 0019): je Embedding-Modell und Eingabe (SHA-256 über Begriff und
 * Fundstellen) ein Vektor, mit den Seiten, aus denen die Eingabe stammt. Ein unveränderter Begriff geht so nicht noch
 * einmal an das Modell. Fällt eine Seite aus der KI-Freigabe (ADR 0028), fallen alle Vektoren mit ihr weg.
 * Daneben der Stand je Quelle nach dem letzten vollständigen automatischen Lauf ({@link SynonymCandidates#state}),
 * damit ein Neustart keinen unnötigen Lauf auslöst.
 */
public interface SynonymVectorCache {

    /** Ein Vektor mit den Seiten seiner Eingabe. */
    record Entry(float[] vector, Set<PageKey> pages) {

        public Entry {
            vector = vector.clone();
            pages = Set.copyOf(pages);
        }

        @Override
        public float[] vector() {
            return vector.clone();
        }
    }

    /** @return der Vektor oder leer */
    Optional<float[]> find(String modelId, String inputSha256);

    /** Legt Vektoren eines Modells ab, Schlüssel ist der Eingabe-Hash. */
    void putAll(String modelId, Map<String, Entry> entries);

    /** Vergisst alle Vektoren, deren Eingabe aus einer dieser Seiten stammt. */
    void withdraw(Collection<PageKey> pages);

    /** Vergisst alle Vektoren mit Seiten dieser Quelle und ihren Stand („Daten bereinigen“, Freigabe entzogen). */
    void withdrawSource(String sourceId);

    /** @return der Stand der Quelle nach ihrem letzten vollständigen automatischen Lauf oder leer */
    Optional<String> state(String sourceId);

    /** Merkt den Stand; {@code null} vergisst ihn. */
    void state(String sourceId, String state);

    /** Im Speicher, etwa ohne Datenverzeichnis und für Tests. */
    static SynonymVectorCache inMemory() {
        return new SynonymVectorCache() {
            private final Map<String, Entry> vectors = new HashMap<>();
            private final Map<String, String> states = new HashMap<>();

            @Override
            public synchronized Optional<float[]> find(String modelId, String inputSha256) {
                return Optional.ofNullable(vectors.get(modelId + '\u0000' + inputSha256)).map(Entry::vector);
            }

            @Override
            public synchronized void putAll(String modelId, Map<String, Entry> entries) {
                entries.forEach((sha, entry) -> vectors.put(modelId + '\u0000' + sha, entry));
            }

            @Override
            public synchronized void withdraw(Collection<PageKey> pages) {
                Set<PageKey> gone = Set.copyOf(pages);
                vectors.values().removeIf(entry -> entry.pages().stream().anyMatch(gone::contains));
            }

            @Override
            public synchronized void withdrawSource(String sourceId) {
                vectors.values().removeIf(entry -> entry.pages().stream()
                        .anyMatch(page -> page.sourceId().equals(sourceId)));
                states.remove(sourceId);
            }

            @Override
            public synchronized Optional<String> state(String sourceId) {
                return Optional.ofNullable(states.get(sourceId));
            }

            @Override
            public synchronized void state(String sourceId, String state) {
                if (state == null) {
                    states.remove(sourceId);
                } else {
                    states.put(sourceId, state);
                }
            }
        };
    }
}
