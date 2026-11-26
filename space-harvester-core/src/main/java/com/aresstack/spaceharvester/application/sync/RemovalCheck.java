package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fragt jede in einer bestätigten Inventur fehlende Seite einzeln bei der Quelle nach, bevor sie entfernt wird.
 *
 * <p>Eine Listung ist kein atomarer Schnappschuss: Wird während der Paginierung eine Seite angelegt oder gelöscht,
 * können sich die Stapel verschieben und eine vorhandene Seite fehlt. Erst die Einzelabfrage unterscheidet gelöscht,
 * für das technische Konto nicht mehr sichtbar, in einen anderen Space verschoben und doch vorhanden.
 *
 * <p>Nach ADR 0014, Punkt 4, ist „nicht gefunden“ nur dann eine Löschung, wenn die Quelle das autoritativ bestätigt
 * ({@link com.aresstack.spaceharvester.application.sources.HarvestSource#confirmsDeletion()}); Confluence antwortet
 * auch mit 404, wenn das technische Konto die Seite nicht mehr sehen darf.
 *
 * <p>Findet die Quelle eine fehlende Seite, führt sie aber unter einer anderen ID, die diese Inventur listet, ist die
 * alte ID nur eine veraltete Doppelung (etwa nach einer geänderten Adressbildung der Quelle, bei BSCW Ordner mit und
 * ohne Schrägstrich am Ende): Sie gilt als gelöscht, die Seite selbst bleibt unter ihrer heutigen ID. Sonst hielte die
 * alte ID jede Generation als „doch vorhanden“ auf.
 *
 * <p>Eine Seite außerhalb des Umfangs einer Quelle mit Startpunkten
 * ({@link com.aresstack.spaceharvester.application.sources.HarvestSource#outsideScope}) gilt ohne Einzelabfrage als
 * gelöscht: Es gibt sie noch, sie gehört aber nicht mehr in den Katalog.
 */
public class RemovalCheck {

    public enum Verdict {
        /** Quelle bestätigt die Löschung autoritativ: aus Cache, Volltext- und Vektorindex entfernen. */
        DELETED,
        /**
         * Quelle meldet „nicht gefunden“, ohne eine Löschung zu bestätigen: sofort für jede Ausgabe sperren und aus den
         * Indizes nehmen; die Rohdaten erst nach einer Frist entfernen.
         */
        INACCESSIBLE,
        /** Seite liegt jetzt in einem anderen Space: hier entfernen, der andere Space übernimmt sie. */
        MOVED_OUT,
        /** Seite ist doch in diesem Space: die Inventur hat sie übersehen, keine Generation bestätigen. */
        STILL_PRESENT,
        /**
         * Nicht prüfbar (Quelle nicht erreichbar, Zugriff verweigert, Fähigkeit fehlt, …): behalten und als veraltet
         * führen.
         */
        UNVERIFIED
    }

    /**
     * @param pageId          ID der Seite
     * @param verdict         Ergebnis
     * @param current Seite laut Einzelabfrage bei {@link Verdict#MOVED_OUT} und {@link Verdict#STILL_PRESENT}, sonst
     *                {@code null}
     * @param problem Grund bei {@link Verdict#UNVERIFIED}, sonst {@code null}; ohne Zugangsdaten
     */
    public record Result(String pageId, Verdict verdict, PageMetadataListing.Entry current, String problem) {

        public Result {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(verdict, "verdict");
        }

        /** Space laut Einzelabfrage oder {@code null}. */
        public String currentSpaceKey() {
            return current == null ? null : current.page().partitionKey();
        }

        /** Ob die Seite aus diesem Space der Generation fällt. */
        public boolean removes() {
            return verdict == Verdict.DELETED || verdict == Verdict.INACCESSIBLE || verdict == Verdict.MOVED_OUT;
        }
    }

    /** Grund bei {@link Verdict#UNVERIFIED} für Seiten außerhalb des eingestellten Umfangs. */
    static final String OUTSIDE_SCOPE = "außerhalb des eingestellten Umfangs";

    private final SourceQueries queries;

    public RemovalCheck(SourceQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /** Prüft alle {@link PageChange.Kind#MISSING} des Plans; leer, wenn keine fehlen. */
    public Map<String, Result> verify(SyncPlan plan) {
        Map<String, Result> results = new LinkedHashMap<>();
        List<PageChange> missing = plan.changes(PageChange.Kind.MISSING);
        if (missing.isEmpty()) {
            return results;
        }
        Set<String> listed = new HashSet<>();
        plan.inventory().pages().forEach(entry -> listed.add(entry.page().pageId()));
        return verify(plan.inventory().sourceId(), plan.inventory().spaceKey(),
                missing.stream().map(PageChange::pageId).toList(), listed);
    }

    /**
     * Prüft die Seiten {@code pageIds}, die in einer vollständigen Liste mit den IDs {@code listed} fehlen; so auch für
     * das Nachholen mit reiner ID-Liste ({@link ChangeSync}).
     */
    Map<String, Result> verify(String sourceId, String spaceKey, java.util.Collection<String> pageIds,
                               Set<String> listed) {
        Map<String, Result> results = new LinkedHashMap<>();
        if (pageIds.isEmpty()) {
            return results;
        }
        boolean confirmsDeletion = queries.confirmsDeletion(sourceId);
        for (String pageId : pageIds) {
            results.put(pageId, verify(sourceId, spaceKey, pageId, confirmsDeletion, listed));
        }
        return results;
    }

    private Result verify(String sourceId, String spaceKey, String pageId, boolean confirmsDeletion,
                          Set<String> listed) {
        try {
            if (queries.outsideScope(sourceId, pageId)) {
                // Außerhalb von Startpunkten und Tiefe: Ein kleinerer Umfang beweist keine Löschung (ADR 0037,
                // Punkt 12). Die Seite bleibt; ob sie entfällt, entscheidet erst „Bereinigen“.
                return new Result(pageId, Verdict.UNVERIFIED, null, OUTSIDE_SCOPE);
            }
            PageMetadataListing.Entry current = queries.pageMetadata(sourceId, pageId).entry();
            String currentId = current.page().pageId();
            if (!pageId.equals(currentId) && listed.contains(currentId)) {
                // Dieselbe Seite unter ihrer heutigen ID, und die listet die Inventur: die alte ID ist eine Doppelung.
                return new Result(pageId, Verdict.DELETED, null, null);
            }
            Verdict verdict = spaceKey.equals(current.page().partitionKey())
                    ? Verdict.STILL_PRESENT : Verdict.MOVED_OUT;
            return new Result(pageId, verdict, current, null);
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                return new Result(pageId, confirmsDeletion ? Verdict.DELETED : Verdict.INACCESSIBLE, null, null);
            }
            return new Result(pageId, Verdict.UNVERIFIED, null, e.reason() + ": " + e.getMessage());
        }
    }
}
