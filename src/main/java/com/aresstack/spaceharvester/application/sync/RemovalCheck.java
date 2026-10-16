package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fragt jede in einer bestätigten Inventur fehlende Seite einzeln bei der Quelle nach, bevor sie entfernt wird.
 *
 * <p>Eine Listung ist kein atomarer Schnappschuss: Wird während der Paginierung eine Seite angelegt oder gelöscht,
 * können sich die Stapel verschieben und eine vorhandene Seite fehlt. Erst die Einzelabfrage unterscheidet gelöscht
 * bzw. nicht mehr sichtbar ({@code NOT_FOUND}), in einen anderen Space verschoben und doch vorhanden.
 */
public class RemovalCheck {

    public enum Verdict {
        /** Quelle meldet die Seite als nicht vorhanden oder für das Dienstkonto nicht sichtbar: entfernen. */
        GONE,
        /** Seite liegt jetzt in einem anderen Space: hier entfernen, der andere Space übernimmt sie. */
        MOVED_OUT,
        /** Seite ist doch in diesem Space: die Inventur hat sie übersehen, keine Generation bestätigen. */
        STILL_PRESENT,
        /** Nicht prüfbar (Quelle nicht erreichbar, Fähigkeit fehlt, …): behalten und als veraltet führen. */
        UNVERIFIED
    }

    /**
     * @param pageId          ID der Seite
     * @param verdict         Ergebnis
     * @param currentSpaceKey Space laut Einzelabfrage bei {@link Verdict#MOVED_OUT} und {@link Verdict#STILL_PRESENT},
     *                        sonst {@code null}
     * @param problem         Grund bei {@link Verdict#UNVERIFIED}, sonst {@code null}; ohne Zugangsdaten
     */
    public record Result(String pageId, Verdict verdict, String currentSpaceKey, String problem) {

        public Result {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(verdict, "verdict");
        }

        public boolean removes() {
            return verdict == Verdict.GONE || verdict == Verdict.MOVED_OUT;
        }
    }

    private final SourceQueries queries;

    public RemovalCheck(SourceQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /** Prüft alle {@link PageChange.Kind#MISSING} des Plans; leer, wenn keine fehlen. */
    public Map<String, Result> verify(SyncPlan plan) {
        Map<String, Result> results = new LinkedHashMap<>();
        for (PageChange change : plan.changes(PageChange.Kind.MISSING)) {
            results.put(change.pageId(), verify(plan.inventory().sourceId(), plan.inventory().spaceKey(),
                    change.pageId()));
        }
        return results;
    }

    private Result verify(String sourceId, String spaceKey, String pageId) {
        try {
            PageMetadata page = queries.pageMetadata(sourceId, pageId).entry().page();
            Verdict verdict = spaceKey.equals(page.partitionKey()) ? Verdict.STILL_PRESENT : Verdict.MOVED_OUT;
            return new Result(pageId, verdict, page.partitionKey(), null);
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                return new Result(pageId, Verdict.GONE, null, null);
            }
            return new Result(pageId, Verdict.UNVERIFIED, null, e.reason() + ": " + e.getMessage());
        }
    }
}
