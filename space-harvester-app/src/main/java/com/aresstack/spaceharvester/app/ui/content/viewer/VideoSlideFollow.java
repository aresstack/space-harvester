package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.ui.controls.BackgroundWait;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Folgt „Folienwechsel erkennen“ des offenen Videos im Player (Angelo 21.11.2026, Form vom UX-Manager): Ring an der
 * Stelle der Folienknöpfe, solange der Hintergrundauftrag wartet oder läuft; danach die Knöpfe oder ein Hinweis mit dem
 * Weg zum Wiederholen. Nur Observer (ADR 0027); alles auf dem EDT.
 *
 * <p>Jede Folge, jedes Ende und jedes Warten auf eine Wiederholung zählt {@link #generation} hoch. Eine Meldung, die
 * noch für einen früheren Abschnitt eingereiht war (etwa ein spätes „läuft“ nach dem Ende), erkennt sich daran und
 * ändert nichts (Architekt 21.11.2026).
 */
public final class VideoSlideFollow {

    /** Hinweis, wenn nur ein Platz eingestellt ist: dann läuft kein Hintergrundauftrag. */
    static final String SINGLE_SLOT_SENTENCE = "Folienerkennung braucht einen zweiten Platz";
    /** Link zur Einstellung „Vorbereitung“ in der Auftragsverwaltung. */
    static final String SINGLE_SLOT_LINK = "„Vorbereitung“ in der Auftragsverwaltung einstellen";

    /** Was die Folge von der App braucht; Meldungen kommen auf dem EDT. */
    public interface Host {
        /** Jede Änderung der Gruppe. */
        Subscription watch(String groupId, Consumer<BackgroundGroup> listener);

        /** Einmal das Ende der Gruppe. */
        Subscription whenFinished(String groupId, Consumer<BackgroundGroup> listener);

        /** Ob nur ein Platz eingestellt ist; abseits des EDT gelesen, die Antwort kommt auf dem EDT. */
        void singleSlot(Consumer<Boolean> answer);

        /** Die gespeicherten Marken des Videos; abseits des EDT gelesen, die Antwort kommt auf dem EDT. */
        void marks(Consumer<List<Long>> found);

        /** Die Erkennung hat Marken gespeichert: Liste, Bilder und Marken im Player gelten neu. */
        void marksStored();

        /** Zeigt den Auftrag im Reiter „Hintergrund“. */
        void openJob(String jobId);
    }

    private final VideoViewerPanel viewer;
    private final Host host;
    private final BackgroundWait wait;
    private Subscription watch = Subscription.NONE;
    private long generation;

    public VideoSlideFollow(VideoViewerPanel viewer, Host host) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.host = Objects.requireNonNull(host, "host");
        this.wait = new BackgroundWait(viewer.slideRing(), host::whenFinished);
    }

    /** Folgt der Gruppe, wie sie gerade steht. */
    public void follow(BackgroundGroup group) {
        long mine = next();
        Runnable openJobs = openJobs(group);
        if (group.finished()) {
            ended(group, openJobs);
            return;
        }
        host.singleSlot(single -> {
            if (mine != generation) {
                return;
            }
            if (Boolean.TRUE.equals(single) && !running(group)) {
                waitForASecondSlot(group, openJobs);
            } else {
                ring(group, openJobs);
            }
        });
    }

    /** Hört auf zu folgen; eingereihte Meldungen ändern danach nichts mehr. */
    public void stop() {
        next();
    }

    private long next() {
        wait.stop();
        watch.close();
        watch = Subscription.NONE;
        return ++generation;
    }

    private void ring(BackgroundGroup group, Runnable openJobs) {
        long mine = generation;
        viewer.slidesDetecting(running(group), openJobs);
        watch = host.watch(group.groupId(), changed -> {
            if (mine == generation && !changed.finished()) {
                viewer.slidesDetecting(running(changed), openJobs);
            }
        });
        wait.follow(group.groupId(), "", done -> {
            if (mine == generation) {
                next();
                ended(done, openJobs);
            }
        });
    }

    /**
     * Nur ein Platz: der Teil wartet, bis ein zweiter eingestellt ist. Kein Ring, sondern ein gelber Hinweis mit dem Weg
     * zur Einstellung; sobald der Teil läuft oder endet, folgt der Player wieder.
     */
    private void waitForASecondSlot(BackgroundGroup group, Runnable openJobs) {
        long mine = generation;
        viewer.slidesUnavailable(SINGLE_SLOT_SENTENCE, false, SINGLE_SLOT_LINK, openJobs);
        watch = host.watch(group.groupId(), changed -> {
            if (mine == generation && (changed.finished() || running(changed))) {
                follow(changed);
            }
        });
    }

    /**
     * Die Erkennung ist fertig: mit Marken ersetzen die Folienknöpfe den Ring ohne Meldung; ohne Marken verschwindet
     * er. Abgebrochen oder angehalten zeigt der Player einen gelben Hinweis, gescheitert einen roten mit dem Grund. Wird
     * der Lauf im Reiter „Hintergrund“ wiederholt, folgt der Player wieder, ohne das Video neu zu öffnen.
     */
    private void ended(BackgroundGroup group, Runnable openJobs) {
        long mine = next();
        if (group.completed()) {
            host.marks(marks -> {
                if (mine != generation) {
                    return;
                }
                if (marks.isEmpty()) {
                    viewer.noSlides();
                } else {
                    host.marksStored();
                }
            });
            return;
        }
        boolean failed = group.parts().stream().anyMatch(part -> part.state() == JobState.FAILED);
        viewer.slidesUnavailable(sentence(group), failed, VideoViewerPanel.RETRY_IN_BACKGROUND, openJobs);
        watch = host.watch(group.groupId(), changed -> {
            if (mine == generation && !changed.finished()) {
                follow(changed);
            }
        });
    }

    private Runnable openJobs(BackgroundGroup group) {
        String jobId = group.parts().isEmpty() ? null : group.parts().get(0).id();
        return () -> {
            if (jobId != null) {
                host.openJob(jobId);
            }
        };
    }

    private static boolean running(BackgroundGroup group) {
        return group.parts().stream().anyMatch(part -> part.state() == JobState.RUNNING);
    }

    /** Der Hinweis zu einem Lauf ohne Ergebnis: Abbruch, Anhalten oder der echte Grund des Fehlers. */
    static String sentence(BackgroundGroup group) {
        for (JobStatus part : group.parts()) {
            if (part.state() == JobState.FAILED) {
                String reason = part.error() == null || part.error().message() == null
                        || part.error().message().isBlank() ? "Auftrag gescheitert" : part.error().message();
                return "Folienwechsel nicht erkannt: " + reason;
            }
        }
        boolean paused = group.parts().stream().anyMatch(part -> part.state() == JobState.PAUSED);
        return "Folienwechsel nicht erkannt: Auftrag " + (paused ? "angehalten" : "abgebrochen");
    }
}
