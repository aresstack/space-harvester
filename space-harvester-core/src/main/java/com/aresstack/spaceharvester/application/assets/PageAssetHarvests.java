package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * „Anhänge ablegen“ einer Seite als Auftrag „Anhänge beschaffen“ (Fundstelle 24, Angelo 21.11.2026: nichts läuft
 * unsichtbar): Der Klick legt alle Anhänge der Seite ab, die noch nicht in ihrer aktuellen Version abgelegt sind
 * ({@link Assets#harvest(PageAttachmentList, java.util.function.Consumer)}), und lässt sich in der Auftragsliste
 * verfolgen und abbrechen. Abgelegte zählen als geschrieben, schon vorhandene als bewusst ausgelassen, gescheiterte
 * rot mit Grund. Fortsetzen und erneut starten laufen im selben Eintrag und holen nur, was fehlt.
 *
 * <p>Die Anhangsliste liefert der Aufrufer, weil nur er die Rechte und die virtuellen Anhänge eines Videos kennt;
 * nach einem Neustart setzt deshalb nicht der Core von selbst fort, sondern der Benutzer.
 */
public final class PageAssetHarvests {

    /** Parameter, der den Auftrag von der Anhangbeschaffung eines Exports unterscheidet. */
    public static final String TASK = "task";
    /** Wert von {@link #TASK}. */
    public static final String PAGE_HARVEST = "page-harvest";
    private static final String COMPONENT = "page-assets";

    private final Jobs jobs;
    private final Assets assets;

    public PageAssetHarvests(Jobs jobs, Assets assets) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    /** Ob der Auftrag „Anhänge ablegen“ einer Seite ist. */
    public static boolean pageHarvest(JobStatus status) {
        return status.type() == JobType.ATTACHMENTS && PAGE_HARVEST.equals(status.parameter(TASK));
    }

    /** Startet den Auftrag für die Anhänge einer Seite; ein Klick wartet nie hinter Hintergrund. */
    public JobStatus start(PageAttachmentList list, String owner) {
        Objects.requireNonNull(list, "list");
        return jobs.submitInteractive(JobType.ATTACHMENTS, list.sourceId(), list.spaceKey(),
                list.attachments().pageId(), owner, Map.of(TASK, PAGE_HARVEST), task(() -> list));
    }

    /**
     * Setzt einen angehaltenen, abgebrochenen oder beendeten Auftrag im selben Eintrag fort; auch einen von vor dem
     * letzten Neustart. Schon Abgelegtes kommt aus der Ablage.
     *
     * @param list liefert die aktuelle Anhangsliste der Seite, erst wenn der Auftrag läuft
     */
    public JobStatus resume(String jobId, String owner, Supplier<PageAttachmentList> list) {
        JobStatus current = jobs.status(jobId, owner);
        if (!pageHarvest(current)) {
            throw new JobNotResumableException(jobId, "kein Auftrag „Anhänge ablegen“");
        }
        if (jobs.resumable(jobId, owner)) {
            // Auch ohne Neustart mit frischer Anhangsliste: Neue Anhänge kommen dazu, geänderte mit aktuellen Angaben.
            return jobs.resume(jobId, owner, previous -> task(list));
        }
        return jobs.rerun(jobId, owner, task(list));
    }

    private JobTask task(Supplier<PageAttachmentList> list) {
        return JobTask.blocking(context -> {
            PageAttachmentList current = list.get();
            context.discovered(current.attachments().attachments().size());
            // Ein Download-Auftrag: erst ein Platz der parallelen Downloads, dann die Übertragungen (wie beim Export).
            context.awaitDownloadSlot();
            AssetHarvest result = assets.harvest(current, entry -> {
                context.processed(1);
                switch (entry.status()) {
                    case STORED -> context.written(1);
                    case FAILED -> {
                        context.failed(1);
                        context.record(new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.SOURCE,
                                COMPONENT, entry.code(), "Ein Anhang ließ sich nicht ablegen: " + entry.code(), null,
                                null, null));
                    }
                    default -> {
                        // schon in dieser Version abgelegt: bewusst ausgelassen
                    }
                }
                context.checkpoint();
            });
            if (!result.listComplete()) {
                // Die Quelle lieferte nicht alle Anhänge: fertig, aber unvollständig.
                context.incomplete();
            }
        });
    }
}
