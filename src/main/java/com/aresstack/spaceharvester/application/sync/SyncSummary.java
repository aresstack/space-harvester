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
 * @param removed           entfernte Seiten (gelöscht oder nicht mehr sichtbar)
 * @param movedOut          in einen anderen Space verschobene Seiten
 * @param unverified        fehlende Seiten, deren Fehlen nicht bestätigt werden konnte; bleiben als veraltet erhalten
 * @param failedPageIds     Seiten, deren Abgleich fehlgeschlagen ist; bleiben als veraltet erhalten
 * @param generation        neu bestätigte Generation oder {@code null}
 * @param spaceFingerprint  {@code spaceFingerprint} der neuen Generation oder {@code null}
 * @param notConfirmed      warum keine Generation bestätigt wurde, sonst {@code null}
 */
public record SyncSummary(String sourceId, String spaceKey, Inventory.Status inventory, int listed, int added,
                          int contentChanged, int metadataChanged, int attributesChanged, int unchanged, int removed,
                          int movedOut, int unverified, List<String> failedPageIds, Long generation,
                          String spaceFingerprint, String notConfirmed) {

    public SyncSummary {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(inventory, "inventory");
        failedPageIds = List.copyOf(failedPageIds);
    }

    static SyncSummary of(SpaceSync.SyncRun run) {
        SyncPlan plan = run.plan();
        int removed = 0;
        int movedOut = 0;
        int unverified = 0;
        for (PageChange missing : plan.changes(PageChange.Kind.MISSING)) {
            RemovalCheck.Result removal = plan.removals().get(missing.pageId());
            if (removal != null && removal.verdict() == RemovalCheck.Verdict.GONE) {
                removed++;
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
                plan.changes(PageChange.Kind.UNCHANGED).size(), removed, movedOut, unverified,
                run.failedPageIds().stream().sorted().toList(), confirmed == null ? null : confirmed.generation(),
                confirmed == null ? null : confirmed.spaceFingerprint(), run.notConfirmed());
    }
}
