package com.aresstack.spaceharvester.application.searchrun;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Lesen des Suchlauf-Protokolls mit Rechten. Ein Lauf enthält den Suchtext; ihn lesen nur sein Aufrufer (gleicher
 * {@link AccessContext#jobOwner()}, also auch nicht derselbe Endnutzer über einen anderen Client) und Verwalter. Für alle
 * anderen gibt es den Lauf nicht, wie bei fremden Aufträgen. Seiten nennt ein Lauf ohnehin nicht.
 */
public final class SearchRuns {

    private final SearchRunLog log;
    private final Predicate<AccessContext> administrators;

    /**
     * @param administrators wer alle Läufe lesen darf (im Server Clients mit {@code manage-configuration}); nie ein
     *                       durchgereichter Endnutzer
     */
    public SearchRuns(SearchRunLog log, Predicate<AccessContext> administrators) {
        this.log = Objects.requireNonNull(log, "log");
        this.administrators = Objects.requireNonNull(administrators, "administrators");
    }

    /** Ob Läufe protokolliert werden. */
    public boolean enabled() {
        return log != SearchRunLog.NONE;
    }

    /**
     * Ein Lauf mit seinen Teilläufen; leer, wenn es ihn nicht gibt oder er dem Aufrufer nicht gehört.
     *
     * @param id Kennung, auch ungültig (dann leer)
     */
    public Optional<View> find(AccessContext caller, String id) {
        SearchRunId runId = SearchRunId.parse(id);
        if (runId == null || caller == null) {
            return Optional.empty();
        }
        Optional<SearchRun> run = log.find(runId).filter(found -> readable(caller, found));
        // Teilläufe gehören demselben Aufrufer; geprüft wird trotzdem jeder.
        return run.map(found -> new View(found, log.parts(runId).stream()
                .filter(part -> readable(caller, part)).toList()));
    }

    /**
     * Die jüngsten Läufe des Aufrufers, für Verwalter aller Aufrufer; neueste zuerst.
     *
     * @param limit 1 bis {@value SearchRunLog#MAX_LIMIT}
     */
    public List<SearchRun> recent(AccessContext caller, int limit) {
        if (limit < 1 || limit > SearchRunLog.MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + SearchRunLog.MAX_LIMIT + " liegen");
        }
        if (caller == null) {
            return List.of();
        }
        return log.recent(administrator(caller) ? null : caller.jobOwner(), limit);
    }

    private boolean readable(AccessContext caller, SearchRun run) {
        return caller.jobOwner().equals(run.owner()) || administrator(caller);
    }

    private boolean administrator(AccessContext caller) {
        return caller.kind() != AccessContext.PrincipalKind.END_USER && administrators.test(caller);
    }

    /** Ein Lauf und seine Teilläufe (etwa die Suchanfragen einer progressiven Suche). */
    public record View(SearchRun run, List<SearchRun> parts) {

        public View {
            Objects.requireNonNull(run, "run");
            parts = List.copyOf(parts);
        }
    }
}
