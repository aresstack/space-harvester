package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sync.SyncAlreadyRunningException;
import com.aresstack.spaceharvester.application.sync.SyncMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Indexieren (Angelo, 20.11.2026): markiert einen Knoten ({@code Workspace.index}) und startet je Space den Abgleich
 * in der Betriebsart der Quelle; die markierten Seiten lädt er vollständig. Ein Anwendungsfall für das Kontextmenü des
 * Baums, wie {@link Downloads} für den Export.
 */
public final class Indexer {

    private Indexer() {
    }

    /** Ergebnis: gestartete Aufträge und je Space, der nicht startete, der Grund. */
    public record Started(List<JobStatus> jobs, List<String> failed) {
    }

    /**
     * Markiert und startet im Hintergrund.
     *
     * @param spaceKey Space der Quelle; {@code null} heißt jeder Space, den sie gerade anbietet, jeweils ganz
     * @param pageId   Seite samt Unterseiten; {@code null} heißt der ganze Space
     */
    public static void start(AppContext context, String sourceId, String spaceKey, String pageId,
                             Consumer<Started> done, Consumer<Throwable> failed) {
        SyncMode mode = context.settings() != null ? context.settings().effectiveSync(sourceId).mode()
                : SyncSettings.DEFAULT_MODE;
        start(context, sourceId, spaceKey, pageId, mode, done, failed);
    }

    /** Start an explicit index operation with a bounded acquisition mode for this run. */
    public static void start(AppContext context, String sourceId, String spaceKey, String pageId, SyncMode mode,
                             Consumer<Started> done, Consumer<Throwable> failed) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            failed.accept(new IllegalStateException("Die Laufzeit ist nicht gestartet"));
            return;
        }
        Background.run(() -> {
            List<String> keys = spaceKey != null ? List.of(spaceKey)
                    : runtime.workspace().spaces(sourceId).stream().map(Partition::key).toList();
            List<JobStatus> jobs = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (String key : keys) {
                try {
                    jobs.add(runtime.workspace().index(sourceId, key, spaceKey == null ? null : pageId, mode));
                } catch (SyncAlreadyRunningException e) {
                    problems.add(key + ": zum Indexieren markiert; ein Abgleich läuft schon, der nächste lädt alles");
                } catch (RuntimeException e) {
                    if (keys.size() == 1) {
                        throw e;
                    }
                    problems.add(key + ": " + Problems.describe(e).title());
                }
            }
            return new Started(jobs, problems);
        }, done, failed);
    }

    /** Hebt die Markierung im Hintergrund auf. */
    public static void stop(AppContext context, String sourceId, String spaceKey, String pageId, Runnable done,
                            Consumer<Throwable> failed) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            failed.accept(new IllegalStateException("Die Laufzeit ist nicht gestartet"));
            return;
        }
        Background.run(() -> {
            runtime.workspace().unindex(sourceId, spaceKey, pageId);
            return Boolean.TRUE;
        }, ignored -> done.run(), failed);
    }
}
