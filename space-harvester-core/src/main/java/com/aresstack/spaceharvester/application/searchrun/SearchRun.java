package com.aresstack.spaceharvester.application.searchrun;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Was ein Suchlauf tatsächlich bekam (wie {@code KnowledgeSearchRun} im Enterprise AI Client): Anfrage, Beginn,
 * Dauer, jede gefragte Quelle mit ihrem Ausgang und die Trefferzahlen je {@link Stage}. Unveränderlich.
 *
 * <p>Gezählt werden nur Seiten, die der Aufrufer sehen darf: Eine Stufe, deren Seiten noch ungeprüft sind (etwa die Funde
 * der Quellen für einen Endnutzer mit delegierter Anmeldung), fehlt im Lauf, statt eine Zahl zu nennen. Eine von der
 * Quelle gemeldete Gesamtzahl gehört wie im Client nie dazu.
 *
 * @param id         Kennung
 * @param partOf     der übergeordnete Lauf (progressive oder semantische Suche), dessen Teil dieser ist, oder
 *                   {@code null}
 * @param kind       welche Suche
 * @param owner      {@link AccessContext#jobOwner()} des Aufrufers; nur er und Verwalter lesen den Lauf
 * @param query      der Suchtext, wie die Suche ihn bekam (wie im Client); erscheint nie im Log
 * @param startedAt  Beginn
 * @param duration   Dauer bis zur Antwort
 * @param sources    jede gefragte Quelle, auch die ausgefallenen, in der Reihenfolge der Suche
 * @param stages     Trefferzahlen in der Reihenfolge von {@link Stage}, jede Stufe höchstens einmal
 * @param complete   ob die Suche vollständig war (alle Quellen geantwortet, alles geprüft)
 * @param completion Abschlussgrund der progressiven Suche ({@code QUALITY_REACHED} …) oder {@code null}
 */
public record SearchRun(SearchRunId id, SearchRunId partOf, Kind kind, String owner, String query, Instant startedAt,
                        Duration duration, List<SourceOutcome> sources, List<StageCount> stages, boolean complete,
                        String completion) {

    /** Längster gespeicherter Suchtext; längere werden gekürzt. */
    public static final int MAX_QUERY_LENGTH = 1000;
    /** Höchstens so viele Quellen je Lauf. */
    public static final int MAX_SOURCES = 200;

    public SearchRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        query = query.strip();
        if (query.length() > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH);
        }
        if (duration.isNegative()) {
            duration = Duration.ZERO;
        }
        sources = List.copyOf(sources.size() > MAX_SOURCES ? sources.subList(0, MAX_SOURCES) : sources);
        Map<Stage, Integer> counts = new EnumMap<>(Stage.class);
        for (StageCount count : stages) {
            counts.putIfAbsent(count.stage(), count.count());
        }
        List<StageCount> ordered = new ArrayList<>(counts.size());
        counts.forEach((stage, count) -> ordered.add(new StageCount(stage, count)));
        stages = List.copyOf(ordered);
    }

    /** Die Zahl einer Stufe, leer, wenn sie in diesem Lauf nicht erhoben wurde. */
    public OptionalInt count(Stage stage) {
        for (StageCount count : stages) {
            if (count.stage() == stage) {
                return OptionalInt.of(count.count());
            }
        }
        return OptionalInt.empty();
    }

    /** Eigentümer eines Laufs dieses Aufrufers. */
    public static String ownerOf(AccessContext caller) {
        return caller == null ? "-" : caller.jobOwner();
    }

    @Override
    public String toString() {
        // Ohne Suchtext und Eigentümer: toString landet leicht im Log.
        return "SearchRun[" + id + ", " + kind + ", " + stages + "]";
    }

    /** Welche Suche lief. */
    public enum Kind {
        /** Schlagwortsuche über den Cache (ADR 0013), ein Stapel. */
        KEYWORD,
        /** Semantische Suche ({@code sort=relevance}). */
        RELEVANCE,
        /** Progressive Suche (ADR 0017); ihre Suchanfragen sind eigene Läufe mit {@code partOf}. */
        PROGRESSIVE
    }

    /** Stufen eines Laufs; neue kommen ans Ende. */
    public enum Stage {
        /** Suchanfragen an die Quellen (aus einer Frage oder als Varianten); keine Seitenzahl. */
        QUERIES,
        /** Geprüfte Funde der Quellen, jede Seite einmal. */
        DISCOVERED,
        /** Davon zum Laden in den Cache ausgewählt. */
        LOAD_QUEUED,
        /** Beim Antworten noch nicht im Cache oder noch ohne aktuelles Embedding. */
        PENDING,
        /** Aus dem Cache bestätigte Treffer (Volltextindex). */
        CONFIRMED,
        /** Ausgelieferte Treffer insgesamt. */
        DELIVERED
    }

    /** Eine Trefferzahl. */
    public record StageCount(Stage stage, int count) {

        public StageCount {
            Objects.requireNonNull(stage, "stage");
            if (count < 0) {
                throw new IllegalArgumentException("count darf nicht negativ sein");
            }
        }
    }

    /**
     * Ausgang einer gefragten Quelle.
     *
     * @param reason bei {@link Status#UNAVAILABLE} ein fester Code (etwa {@code UNAVAILABLE}, {@code RATE_LIMITED}),
     *               nie ein Text der Quelle; sonst {@code null}
     */
    public record SourceOutcome(String sourceId, Status status, String reason) {

        public SourceOutcome {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(status, "status");
        }

        public enum Status {
            /** Die Quelle hat selbst gesucht. */
            SEARCHED,
            /** Für die Quelle hat der Index des Caches gesucht. */
            INDEXED,
            /** Weder eigene Suche noch Index. */
            NOT_SUPPORTED,
            /** Die Quelle hat nicht oder fehlerhaft geantwortet. */
            UNAVAILABLE
        }
    }
}
