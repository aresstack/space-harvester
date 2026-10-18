package com.aresstack.spaceharvester.application.sync;

import java.util.List;
import java.util.Objects;

/**
 * Delta eines Sync-Laufs: wie viele Seiten in welcher Art abgeglichen wurden und was daraus geworden ist. Enthält nur
 * Zähler und Kennungen, keine Titel oder Inhalte.
 *
 * @param sourceId          Quellen-ID
 * @param spaceKey          Space-Key
 * @param inventory         Zustand der Inventur
 * @param listed            gelesene Seiten
 * @param added             neue Seiten
 * @param contentChanged    Seiten mit neuer Revision
 * @param metadataChanged   Seiten mit neuen Metadaten bei gleicher Revision (umbenannt, verschoben)
 * @param attributesChanged Seiten mit nur neuen Attributen (Labels)
 * @param unchanged         unveränderte Seiten
 * @param removed           entfernte Seiten, deren Löschung die Quelle bestätigt hat
 * @param withheld          in diesem Lauf gesperrte Seiten: für das technische Konto nicht mehr sichtbar, ohne
 *                          bestätigte Löschung (ADR 0014)
 * @param purged            gesperrte Seiten, deren Rohdaten dieser Lauf nach Ablauf der Frist entfernt hat
 * @param movedOut          in einen anderen Space verschobene Seiten
 * @param unverified        fehlende Seiten, deren Fehlen nicht bestätigt werden konnte; bleiben als veraltet erhalten
 * @param failedPageIds     Seiten, deren Abgleich fehlgeschlagen ist; bleiben als veraltet erhalten oder fehlen
 * @param missingPageIds    davon die neuen Seiten, für die der Cache gar keinen Stand führt
 * @param generation        neu bestätigte Generation oder {@code null}
 * @param complete          ob die neue Generation für jede gelistete Seite einen Stand führt; {@code false} auch ohne
 *                          Generation
 * @param spaceFingerprint  {@code spaceFingerprint} der neuen Generation oder {@code null}
 * @param notConfirmed      warum keine Generation bestätigt wurde, sonst {@code null}
 */
public record SyncSummary(String sourceId, String spaceKey, Inventory.Status inventory, int listed, int added,
                          int contentChanged, int metadataChanged, int attributesChanged, int unchanged, int removed,
                          int withheld, int purged, int movedOut, int unverified, List<String> failedPageIds, List<String> missingPageIds,
                          Long generation, boolean complete, String spaceFingerprint, String notConfirmed) {

    public SyncSummary {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(inventory, "inventory");
        failedPageIds = List.copyOf(failedPageIds);
        missingPageIds = List.copyOf(missingPageIds);
    }

    static SyncSummary of(SpaceSync.SyncRun run) {
        SyncPlan plan = run.plan();
        int removed = 0;
        int withheld = 0;
        int movedOut = 0;
        int unverified = 0;
        for (PageChange missing : plan.changes(PageChange.Kind.MISSING)) {
            RemovalCheck.Result removal = plan.removals().get(missing.pageId());
            if (removal != null && removal.verdict() == RemovalCheck.Verdict.DELETED) {
                removed++;
            } else if (removal != null && removal.verdict() == RemovalCheck.Verdict.INACCESSIBLE) {
                withheld++;
            } else if (removal != null && removal.verdict() == RemovalCheck.Verdict.MOVED_OUT) {
                movedOut++;
            } else {
                unverified++;
            }
        }
        SyncBaseline confirmed = run.confirmed();
        return new SyncSummary(plan.inventory().sourceId(), plan.inventory().spaceKey(), plan.inventory().status(),
                plan.inventory().pages().size(), plan.changes(PageChange.Kind.NEW).size(),
                plan.changes(PageChange.Kind.CONTENT_CHANGED).size(),
                plan.changes(PageChange.Kind.METADATA_CHANGED).size(),
                plan.changes(PageChange.Kind.ATTRIBUTES_CHANGED).size(),
                plan.changes(PageChange.Kind.UNCHANGED).size(), removed, withheld,
                confirmed == null ? 0 : plan.purges().size(), movedOut, unverified,
                run.failedPageIds().stream().sorted().toList(),
                confirmed == null ? List.of() : List.copyOf(confirmed.missingPageIds()),
                confirmed == null ? null : confirmed.generation(), confirmed != null && confirmed.complete(),
                confirmed == null ? null : confirmed.spaceFingerprint(), run.notConfirmed());
    }
}
