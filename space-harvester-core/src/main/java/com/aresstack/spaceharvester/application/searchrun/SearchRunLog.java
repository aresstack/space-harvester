package com.aresstack.spaceharvester.application.searchrun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port des Suchlauf-Protokolls (wie {@code KnowledgeSearchRunPort} im Enterprise AI Client): Läufe werden einmal
 * geschrieben und nie geändert. Pflichten jeder Umsetzung prüft {@code SearchRunLogContract} in den Testquellen.
 *
 * <p>Nicht übernommen sind {@code append}/{@code events} (Ereignisse je Kandidat) und {@code recordFunnel}/{@code funnel}
 * (Trichter einer Recherche): Beides wertet der Client für den Chat aus und nennt einzelne Seiten.
 */
public interface SearchRunLog {

    /** Höchstens so viele Läufe hält eine Ablage; die ältesten fallen zuerst weg. */
    int MAX_RUNS = 10_000;
    /** Höchstens so viele Teilläufe liefert {@link #parts}. */
    int MAX_PARTS = 200;
    /** Höchstens so viele Läufe liefert {@link #recent}. */
    int MAX_LIMIT = 500;

    /**
     * Legt einen vollständigen Lauf ab.
     *
     * @throws IllegalStateException wenn es einen Lauf mit dieser Kennung schon gibt
     */
    void record(SearchRun run);

    /** Der Lauf, wie er abgelegt wurde. */
    Optional<SearchRun> find(SearchRunId id);

    /** Die Teilläufe eines Laufs ({@link SearchRun#partOf()}), nach Beginn. */
    List<SearchRun> parts(SearchRunId id);

    /**
     * Die jüngsten Läufe, neueste zuerst.
     *
     * @param owner nur Läufe dieses Eigentümers oder {@code null} für alle
     * @param limit 1 bis {@value #MAX_LIMIT}
     */
    List<SearchRun> recent(String owner, int limit);

    /**
     * Entfernt alle Läufe, die vor {@code before} begonnen haben.
     *
     * @return wie viele entfernt wurden
     */
    int deleteBefore(Instant before);

    /** Kein Protokoll: Nichts wird abgelegt, nichts gefunden. */
    SearchRunLog NONE = new SearchRunLog() {
        @Override
        public void record(SearchRun run) {
        }

        @Override
        public Optional<SearchRun> find(SearchRunId id) {
            return Optional.empty();
        }

        @Override
        public List<SearchRun> parts(SearchRunId id) {
            return List.of();
        }

        @Override
        public List<SearchRun> recent(String owner, int limit) {
            return List.of();
        }

        @Override
        public int deleteBefore(Instant before) {
            return 0;
        }
    };

    /**
     * Legt den Lauf ab, ohne die Suche scheitern zu lassen (wie im Client: „the log never fails a search“). Ins Log
     * kommt nur der Ausnahmetyp, nie Suchtext oder Seiten.
     *
     * @return ob der Lauf abgelegt ist
     */
    static boolean recordQuietly(SearchRunLog log, SearchRun run) {
        if (log == null || log == NONE) {
            return false;
        }
        try {
            log.record(run);
            return true;
        } catch (RuntimeException e) {
            System.getLogger(SearchRunLog.class.getName()).log(System.Logger.Level.INFO,
                    "Suchlauf nicht protokolliert ({0})", e.getClass().getName());
            return false;
        }
    }
}
