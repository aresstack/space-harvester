package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.jobs.ChildJobs;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Die Anfragen von „Zusammenfassen“ als Unteraufträge unter dem Auftrag (Angelo 22.11.2026), über {@link ChildJobs}:
 * je Teil und je Zusammenführen ein eigener Auftrag, zusammen höchstens {@code ai.parallel} Anfragen zugleich.
 */
final class SummaryChildJobs implements ThematicSummarizer.Runner {

    private final ChildJobs children;

    SummaryChildJobs(Jobs jobs, SummaryModel model, Clock clock, JobType type, String parentId, String sourceId,
                     String partitionKey, String owner) {
        this(jobs, model, clock, type, parentId, sourceId, partitionKey, owner, null);
    }

    /** @param area Bereich des Oberauftrags in Worten; die Unteraufträge zeigen denselben in der Spalte „Space“ */
    SummaryChildJobs(Jobs jobs, SummaryModel model, Clock clock, JobType type, String parentId, String sourceId,
                     String partitionKey, String owner, String area) {
        this.children = new ChildJobs(jobs, model.parallel(), clock, type, parentId, sourceId, partitionKey, owner,
                area, ChangeLog.PARENT_PARAMETER, ChangeLog.STEP_PARAMETER, ChangeLog.CHILD_START_GRACE, "summary-");
    }

    @Override
    public <T, R> List<R> run(List<T> items, IntFunction<String> title, BiFunction<T, JobContext, R> work,
                              JobContext context) {
        return children.run(items, title, work, context);
    }

    @Override
    public <T, R> List<R> run(List<T> items, IntFunction<String> title, ToIntFunction<T> extent,
                              BiFunction<T, JobContext, R> work, JobContext context) {
        return children.run(items, title, extent, work, context);
    }
}
