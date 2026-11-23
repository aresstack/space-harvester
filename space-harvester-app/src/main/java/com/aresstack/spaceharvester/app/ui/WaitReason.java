package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.jobs.JobStage;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.util.List;
import java.util.Optional;

/**
 * Worauf ein wartender Auftrag wartet, in Worten für den Tooltip seines Balkens (Angelo 23.11.2026: „7 Aufträge laufen
 * und 7 warten. Man weiß nicht wirklich warum“). Abgeleitet aus dem Stand aller Aufträge und der Einstellung
 * „Vorbereitung“ nach denselben Regeln, nach denen der Core den nächsten startet ({@code Jobs#next}): Downloads haben
 * eigene Plätze für die Vorbereitung; Folgeaufträge (Anreicherung, Zusammenfassen …) und Hintergrundaufträge belegen
 * zusammen höchstens alle Plätze bis auf einen; Hintergrund startet nur, wenn kein anderer Auftrag wartet; ein Teil
 * mit Vorgänger wartet, bis dieser fertig ist.
 */
final class WaitReason {

    private WaitReason() {
    }

    /**
     * @param job          der Auftrag
     * @param all          alle Aufträge der Liste, auch der andere Reiter
     * @param preparations die Plätze „Vorbereitung“ ({@code jobs.parallel-preparations})
     * @return der Grund, nur für einen wartenden Auftrag
     */
    static Optional<String> of(JobStatus job, List<JobStatus> all, int preparations) {
        if (job.state() != JobState.QUEUED || job.cancelRequested()) {
            return Optional.empty();
        }
        if (job.stage() == JobStage.AWAITING) {
            return Optional.of("startet, sobald der Auftrag fertig ist, dem er folgt");
        }
        String after = job.parameter(Jobs.AFTER);
        if (after != null && !after.isBlank()) {
            // Mehrere Vorstufen stehen durch Komma getrennt (BackgroundJobs#startSteps); Jobs#held prüft jede einzeln
            // (Codex #1174).
            java.util.Set<String> ids = java.util.Arrays.stream(after.split(",")).map(String::trim)
                    .filter(id -> !id.isEmpty()).collect(java.util.stream.Collectors.toSet());
            Optional<JobStatus> before = all.stream()
                    .filter(other -> ids.contains(other.id()) && !other.state().terminal()).findFirst();
            if (before.isPresent()) {
                return Optional.of("wartet auf den vorigen Teil (" + Texts.state(before.get()) + ")");
            }
        }
        int slots = Math.max(1, preparations);
        long running = all.stream().filter(other -> other.state() == JobState.RUNNING && !other.type().download()
                && !ownLane(other)).count();
        if (job.type().download()) {
            long preparing = all.stream().filter(other -> other.state() == JobState.RUNNING
                    && other.stage() == JobStage.PREPARING).count();
            return Optional.of(occupied("Plätze für die Vorbereitung", preparing, slots));
        }
        long followUps = all.stream().filter(other -> other.state() == JobState.RUNNING && !ownLane(other)
                && (other.type().followUp() || other.type().background())).count();
        int spare = Math.max(1, slots - 1);
        if (job.type().background()) {
            if (slots <= 1) {
                return Optional.of("bei nur einem Platz unter „Vorbereitung“ läuft kein Hintergrund");
            }
            long others = all.stream().filter(other -> other.state() == JobState.QUEUED && !other.type().background()
                    && other.stage() != JobStage.AWAITING && !other.cancelRequested()).count();
            if (others > 0) {
                return Optional.of("Hintergrund hat Nachrang: " + Texts.count(others, "anderer Auftrag wartet",
                        "andere Aufträge warten") + " auf einen Platz");
            }
            return Optional.of(running >= slots ? occupied("Plätze", running, slots)
                    : "Folge- und Hintergrundaufträge belegen höchstens " + (slots - 1) + " von " + slots
                    + " Plätzen, jetzt " + followUps);
        }
        if (running >= slots) {
            return Optional.of(occupied("Plätze", running, slots));
        }
        if (job.type().followUp()) {
            return Optional.of("Folge- und Hintergrundaufträge belegen höchstens " + spare + " von " + slots
                    + " Plätzen, jetzt " + followUps);
        }
        return Optional.of("startet gleich");
    }

    /** „alle 6 Plätze belegt (6 laufen) · Anzahl unter „Vorbereitung““. */
    private static String occupied(String what, long busy, int slots) {
        return "alle " + slots + " " + what + " belegt (" + Texts.count(busy, "läuft", "laufen")
                + ") · Anzahl unter „Vorbereitung“";
    }

    /** Teile auf eigener Spur belegen keinen Platz ({@link Jobs#OWN_LANE}). */
    private static boolean ownLane(JobStatus job) {
        return job.type().background() && "true".equals(job.parameter(Jobs.OWN_LANE));
    }
}
