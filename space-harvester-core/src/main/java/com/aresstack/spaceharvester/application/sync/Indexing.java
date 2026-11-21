package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.jobs.JobStatus;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indexieren (Angelo 20.11.2026): markiert eine Seite samt Unterseiten oder einen ganzen Space ({@link IndexMarks})
 * und startet gleich einen Abgleich, der sie vollständig lädt. Danach und nach jedem späteren Abgleich, der dort Seiten
 * geladen hat, holt die Folgearbeit Bilder und Anhänge der markierten Wurzeln, nachrangig als eigene Aufträge, erst
 * wenn die Seiten da sind.
 */
public final class Indexing implements SyncCommitted {

    /** Bilder und Anhänge einer Wurzel beschaffen ({@code pageId == null}: ganzer Space), als eigene Aufträge. */
    @FunctionalInterface
    public interface Assets {

        void collect(String sourceId, String spaceKey, String pageId);
    }

    private final IndexMarks marks;
    private final SpaceSyncJobs syncs;
    private final Assets assets;
    /** Spaces, deren Markierung neu ist: Bilder und Anhänge folgen dem nächsten Abgleich auch ohne geladene Seite. */
    private final Set<String> fresh = ConcurrentHashMap.newKeySet();

    public Indexing(IndexMarks marks, SpaceSyncJobs syncs, Assets assets) {
        this.marks = Objects.requireNonNull(marks, "marks");
        this.syncs = Objects.requireNonNull(syncs, "syncs");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    public IndexMarks marks() {
        return marks;
    }

    /**
     * Markiert und startet den Abgleich des Space in der Betriebsart {@code mode}; die markierten Seiten lädt er
     * vollständig, den Rest wie gewohnt.
     *
     * @param pageId Wurzel samt Unterseiten, {@code null} für den ganzen Space
     * @throws SyncAlreadyRunningException wenn der Space gerade abgleicht; die Markierung gilt dann ab dem laufenden
     *                                     oder nächsten Abgleich
     */
    public JobStatus index(String sourceId, String spaceKey, String pageId, String owner, SyncMode mode) {
        marks.mark(sourceId, spaceKey, pageId);
        fresh.add(key(sourceId, spaceKey));
        return syncs.start(sourceId, spaceKey, owner, mode);
    }

    /** Hebt die Markierung genau dieser Wurzel auf; Geladenes bleibt und wird weiter aktuell gehalten. */
    public void unindex(String sourceId, String spaceKey, String pageId) {
        marks.unmark(sourceId, spaceKey, pageId);
    }

    @Override
    public void committed(SpaceSync.SyncRun run) {
        String sourceId = run.plan().inventory().sourceId();
        String spaceKey = run.plan().inventory().spaceKey();
        Set<String> roots = marks.roots(sourceId, spaceKey);
        boolean newlyMarked = fresh.remove(key(sourceId, spaceKey));
        if (roots.isEmpty() || !newlyMarked && run.plan().changes().stream().noneMatch(Indexing::loaded)) {
            return;
        }
        for (String root : roots) {
            assets.collect(sourceId, spaceKey, IndexMarks.WHOLE_SPACE.equals(root) ? null : root);
        }
    }

    private static boolean loaded(PageChange change) {
        return change.kind() == PageChange.Kind.NEW || change.kind() == PageChange.Kind.CONTENT_CHANGED;
    }

    private static String key(String sourceId, String spaceKey) {
        return sourceId + "/" + spaceKey;
    }
}
