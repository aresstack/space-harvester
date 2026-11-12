package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.JobRecovery;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.util.Objects;

/**
 * Wiederanlauf nach Verbindungsabbruch im Dienst (ADR 0025 Punkt 17). Abgleiche, Bilder und Anhänge laufen wie ein Klick
 * über {@link com.aresstack.spaceharvester.application.jobcontrol.JobActions} wieder an. Ein Export nur, solange der
 * Dienst seine Arbeit noch hält: die trägt Lese- und Exportprüfung des Auftraggebers vom Start. Ohne sie, etwa nach
 * einem Neustart, ließe sich die Prüfung nur aus den abgelegten Angaben ohne Zugang des Auftraggebers bauen; dann
 * bleibt der Export angehalten, bis der Client ihn mit seinem Zugang fortsetzt.
 */
final class ServerJobRecovery implements JobRecovery {

    private final Jobs jobs;
    private final JobRecovery actions;

    /** @param actions führt die Aktion aus, gewöhnlich über {@code JobActions#perform} */
    ServerJobRecovery(Jobs jobs, JobRecovery actions) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void perform(JobStatus status, JobAction action) {
        if (status.type() == JobType.EXPORT && !jobs.resumable(status.id(), status.owner())) {
            return;
        }
        actions.perform(status, action);
    }
}
