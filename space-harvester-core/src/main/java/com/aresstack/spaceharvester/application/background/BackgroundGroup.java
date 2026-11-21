package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import java.util.List;
import java.util.Objects;

/**
 * Eine Hintergrundgruppe (der „Oberauftrag“): ihre Unteraufträge, je Space einer. Die Gruppe ist kein eigener Auftrag
 * und belegt keinen Platz; ihr Zustand ergibt sich aus den Unteraufträgen.
 *
 * @param parts die Unteraufträge in der Reihenfolge ihres Starts
 */
public record BackgroundGroup(String groupId, String kind, String title, List<JobStatus> parts) {

    public BackgroundGroup {
        Objects.requireNonNull(groupId, "groupId");
        parts = List.copyOf(parts);
    }

    public List<String> jobIds() {
        return parts.stream().map(JobStatus::id).toList();
    }

    /** Kein Unterauftrag wartet oder läuft mehr (auch angehaltene zählen als beendet). */
    public boolean finished() {
        return parts.stream().allMatch(part -> part.state().terminal());
    }

    /** Mindestens ein Unterauftrag ist angehalten und keiner wartet oder läuft. */
    public boolean paused() {
        return finished() && parts.stream().anyMatch(part -> part.state() == JobState.PAUSED);
    }

    /** Jeder Unterauftrag ist fehlerfrei durchgelaufen. */
    public boolean completed() {
        return !parts.isEmpty() && parts.stream().allMatch(part -> part.state() == JobState.COMPLETED);
    }

    /** Summe der geschriebenen Elemente aller Unteraufträge, etwa geladene Seiten. */
    public long written() {
        return parts.stream().mapToLong(part -> part.progress().written()).sum();
    }

    /** Summe der gescheiterten Elemente aller Unteraufträge. */
    public long failed() {
        return parts.stream().mapToLong(part -> part.progress().failed()).sum();
    }
}
