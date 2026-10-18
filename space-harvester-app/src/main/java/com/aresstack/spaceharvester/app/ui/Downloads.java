package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.sources.Partition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Herunterladen: beschafft je Space einmal den JSON-Datenbestand als Export-Auftrag ({@code Workspace.startExport}),
 * sofort und ohne Dialog, Formatwahl oder Rückfrage. Exportiert (PDF, Word, Markdown) wird danach unter Aufträge. Ein
 * Anwendungsfall für Explorer (Kontextmenü und Download-Symbol, auch an Seiten: Seite samt Unterseiten) und
 * Wissensquellen verwalten.
 */
public final class Downloads {

    /** Format des Datenbestands; die übrigen Formate entstehen beim Exportieren aus ihm. */
    public static final String FORMAT = "json";

    private Downloads() {
    }

    /** Ergebnis: gestartete Aufträge und je Space, der nicht startete, der Grund. */
    public record Started(List<JobStatus> jobs, List<String> failed) {
    }

    /**
     * Startet im Hintergrund je Space einen Download.
     *
     * @param spaceKeys Spaces der Quelle; leer heißt alle, die sie gerade anbietet
     */
    public static void start(AppContext context, String sourceId, List<String> spaceKeys, Consumer<Started> done,
                             Consumer<Throwable> failed) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            failed.accept(new IllegalStateException("Die Laufzeit ist nicht gestartet"));
            return;
        }
        Background.run(() -> {
            List<String> keys = spaceKeys.isEmpty()
                    ? runtime.workspace().spaces(sourceId).stream().map(Partition::key).toList() : spaceKeys;
            List<JobStatus> jobs = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (String key : keys) {
                try {
                    jobs.add(runtime.workspace().startExport(sourceId, key, List.of(FORMAT)));
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

    /** Startet im Hintergrund den Download einer Seite mit allen Unterseiten (ein Auftrag). */
    public static void startPage(AppContext context, String sourceId, String spaceKey, String pageId,
                                 Consumer<Started> done, Consumer<Throwable> failed) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            failed.accept(new IllegalStateException("Die Laufzeit ist nicht gestartet"));
            return;
        }
        Background.run(() -> new Started(List.of(runtime.workspace().startExport(sourceId, spaceKey, pageId,
                List.of(FORMAT))), List.of()), done, failed);
    }
}
