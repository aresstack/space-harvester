package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.event.ApplicationEvent;
import com.aresstack.spaceharvester.app.event.TimelineEvent;
import com.aresstack.spaceharvester.app.speech.PodcastPlayer;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.content.viewer.VideoSeekBar;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.podcast.Podcast;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Podcast zur Update-Zusammenfassung (Angelo 21.11.2026): der runde Knopf oben rechts im Himmel, die Wiedergabe
 * und die Zeitleiste über der Suchleiste.
 *
 * <ul>
 *   <li>▶ startet den Auftrag „Podcast erzeugen“ oder setzt ihn fort und spielt, sobald der erste Abschnitt da ist;
 *       bis dahin kreist ein Ring. ❚❚ hält an, ▶ spielt weiter.</li>
 *   <li>Solange gespielt wird, steht statt des Trenners über der Suchleiste die Zeitleiste aus dem Video-Viewer, mit
 *       gelben Marken an den Themenanfängen; sie wächst mit, wenn Abschnitte nachkommen.</li>
 *   <li>Bricht die Wiedergabe ab oder fehlen Abschnitte, wird der Knopf selbst zum Wiederholen (blassrot, zwei
 *       Kreispfeile). Ein Klick setzt den Auftrag fort und spielt ab der Stelle weiter; klappt es, ist er wieder blau,
 *       scheitert es, blinkt er kurz dunkelrot und bleibt blassrot.</li>
 * </ul>
 *
 * Gespielt wird nur die gerade gezeigte Zusammenfassung; eine andere Wahl beendet die Wiedergabe.
 */
final class PodcastControl<S> {

    /**
     * Was die Steuerung vom Core braucht; in Tests ersetzbar.
     *
     * @param <S> wofür der Podcast entsteht: eine Update-Zusammenfassung oder eine Seite
     */
    interface Source<S> {

        /** Chat-Modell und Sprachausgabe eingerichtet. */
        boolean available();

        /** Der Schlüssel des Podcasts, ohne zu lesen (auf dem EDT). */
        String key(S subject);

        Optional<Podcast> podcast(S subject);

        JobStatus start(S subject);

        Subscription subscribe(String key, Runnable listener);

        Subscription subscribeJobs(JobListener listener);

        /** Ohne Laufzeit: der Knopf bleibt aus. */
        @SuppressWarnings("unchecked")
        static <S> Source<S> none() {
            return (Source<S>) NONE;
        }

        /** Ohne Laufzeit: der Knopf bleibt aus. */
        Source<Object> NONE = new Source<>() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String key(Object subject) {
                return "";
            }

            @Override
            public Optional<Podcast> podcast(Object subject) {
                return Optional.empty();
            }

            @Override
            public JobStatus start(Object subject) {
                throw new IllegalStateException("Keine Laufzeit");
            }

            @Override
            public Subscription subscribe(String key, Runnable listener) {
                return Subscription.NONE;
            }

            @Override
            public Subscription subscribeJobs(JobListener listener) {
                return Subscription.NONE;
            }
        };
    }

    /** Die Texte am Knopf, je nachdem, wofür der Podcast entsteht. */
    record Labels(String listen, String missing, String name, boolean hideUnusable) {

        /** Der Podcast der Update-Zusammenfassung. */
        static final Labels SUMMARY = new Labels("Die Zusammenfassung als Podcast hören",
                "Podcast: erst eine Zusammenfassung erstellen", "updates", false);
        /**
         * Der Erklär-Podcast einer Seite (Angelo 21.11.2026); ohne Chat-Modell oder Sprachausgabe bleibt der Knopf
         * weg, statt auf jeder Seite ausgegraut zu stehen.
         */
        static final Labels PAGE = new Labels("Diese Seite und ihre Unterseiten als Podcast erklären lassen",
                "Podcast: erst eine Seite öffnen", "page", true);
    }

    private final Supplier<Source<S>> sources;
    private final Labels labels;
    private final SourceUpdatesView.Runner runner;
    private final PodcastOrb orb = new PodcastOrb();
    private final PodcastPlayer player;
    private final VideoSeekBar timeline;
    private Consumer<ApplicationEvent<?>> events = event -> { };

    private Source<S> source = Source.none();
    private S summary;
    private String key;
    private Subscription stages = Subscription.NONE;
    private Subscription jobs = Subscription.NONE;
    /** Zähler der gezeigten Zusammenfassung: Antworten zu einer früheren verfallen. */
    private int generation;
    /** Der Benutzer will hören; spielt los, sobald etwas hörbar ist. */
    private boolean wanted;
    /** Ein Klick auf den roten Knopf läuft; scheitert er, blinkt der Knopf. */
    private boolean retrying;
    private long resumeAt;
    private boolean timelineShown;
    private Podcast last;
    private boolean refreshRunning;
    private boolean refreshPending;

    PodcastControl(Supplier<Source<S>> sources, SourceUpdatesView.Runner runner) {
        this(sources, runner, Labels.SUMMARY);
    }

    PodcastControl(Supplier<Source<S>> sources, SourceUpdatesView.Runner runner, Labels labels) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.labels = Objects.requireNonNull(labels, "labels");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.player = new PodcastPlayer(new PlayerEvents());
        this.timeline = new VideoSeekBar(share -> player.seek(Math.round(share * player.total()))).light(this::titleAt);
        timeline.setName(labels.name() + ".podcastTimeline");
        orb.setName(labels.name() + ".podcastOrb");
        orb.addActionListener(event -> clicked());
        summary(null);
    }

    /** Wohin die Zeitleiste gemeldet wird (das Hauptfenster stellt sie über die Suchleiste). */
    void events(Consumer<ApplicationEvent<?>> events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    PodcastOrb orb() {
        return orb;
    }

    VideoSeekBar timeline() {
        return timeline;
    }

    /** Das Gezeigte (Zusammenfassung oder Seite), {@code null} ohne; etwas anderes beendet die Wiedergabe. */
    void summary(S shown) {
        Source<S> current = shown == null ? Source.none()
                : Objects.requireNonNullElse(sources.get(), Source.<S>none());
        String shownKey = shown == null ? null : key(current, shown);
        if (shownKey != null && shownKey.equals(key) && current == source) {
            summary = shown;
            return;
        }
        generation++;
        stop();
        stages.close();
        jobs.close();
        stages = Subscription.NONE;
        jobs = Subscription.NONE;
        source = current;
        summary = shown;
        key = shownKey;
        last = null;
        resumeAt = 0;
        retrying = false;
        orb.setState(PodcastOrb.State.PLAY);
        boolean usable = shown != null && available(current);
        orb.setEnabled(usable);
        if (labels.hideUnusable()) {
            orb.setVisible(usable);
        }
        orb.setToolTipText(shown == null ? labels.missing()
                : usable ? labels.listen()
                : "Podcast: kein Chat-Modell oder keine Sprachausgabe (TTS) eingerichtet");
        if (!usable) {
            return;
        }
        int mine = generation;
        String watched = key;
        stages = current.subscribe(watched, () -> SwingUtilities.invokeLater(() -> {
            if (mine == generation) {
                refresh();
            }
        }));
        jobs = current.subscribeJobs(new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (status.type() == JobType.UPDATE_PODCAST && watched != null && watched.equals(status.parameter(
                        com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.PODCAST_PARAMETER))) {
                    SwingUtilities.invokeLater(() -> {
                        if (mine == generation) {
                            refresh();
                        }
                    });
                }
            }
        });
        refresh();
    }

    private static <S> String key(Source<S> source, S subject) {
        try {
            String key = source.key(subject);
            return key == null || key.isEmpty() ? null : key;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean available(Source<?> source) {
        try {
            return source.available();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --- Knopf --------------------------------------------------------------------------------------------------

    private void clicked() {
        if (summary == null) {
            return;
        }
        switch (orb.state()) {
            case PLAY -> {
                if (player.playing() && player.paused()) {
                    player.resume();
                    orb.setState(PodcastOrb.State.PAUSE);
                } else {
                    play(resumeAt);
                }
            }
            case PAUSE -> {
                player.pause();
                orb.setState(PodcastOrb.State.PLAY);
            }
            case WAITING -> {
                // Warten abbrechen: nichts spielt los, der Auftrag erzeugt im Hintergrund weiter.
                wanted = false;
                if (player.playing()) {
                    player.pause();
                }
                orb.setState(PodcastOrb.State.PLAY);
            }
            case FAILED -> {
                retrying = true;
                play(resumeAt);
            }
        }
    }

    /** Spielt ab {@code from}, sobald etwas hörbar ist; setzt den Auftrag fort, falls noch etwas fehlt. */
    private void play(long from) {
        wanted = true;
        resumeAt = from;
        orb.setState(PodcastOrb.State.WAITING);
        orb.setToolTipText("Podcast wird vorbereitet");
        showTimeline(true);
        Source<S> current = source;
        S shown = summary;
        int mine = generation;
        runner.run(() -> {
            Optional<Podcast> podcast = current.podcast(shown);
            if (podcast.isEmpty() || !podcast.get().complete() && !podcast.get().running()) {
                current.start(shown);
            }
            return Boolean.TRUE;
        }, started -> {
            if (mine == generation) {
                refresh();
            }
        }, failure -> {
            if (mine == generation) {
                failed("Podcast nicht gestartet: " + Problems.describe(failure).title());
            }
        });
    }

    private void stop() {
        wanted = false;
        player.stop();
        showTimeline(false);
    }

    private void failed(String message) {
        wanted = false;
        if (retrying) {
            orb.flashFailure();
        } else {
            orb.setState(PodcastOrb.State.FAILED);
        }
        retrying = false;
        orb.setToolTipText(message + " · Klick: erneut versuchen");
    }

    // --- Stand des Podcasts -------------------------------------------------------------------------------------

    /** Liest den Stand im Hintergrund; höchstens ein Lauf zur Zeit und ein vorgemerkter Nachlauf. */
    private void refresh() {
        if (summary == null) {
            return;
        }
        if (refreshRunning) {
            refreshPending = true;
            return;
        }
        refreshRunning = true;
        Source<S> current = source;
        S shown = summary;
        int mine = generation;
        runner.run(() -> {
            Optional<Podcast> podcast = current.podcast(shown);
            podcast.ifPresent(found -> player.tracks(tracks(found), found.running()));
            return podcast;
        }, podcast -> {
            finishRefresh();
            if (mine == generation) {
                apply(podcast.orElse(null));
            }
        }, failure -> {
            finishRefresh();
            if (mine == generation && wanted) {
                failed("Podcast nicht gelesen: " + Problems.describe(failure).title());
            }
        });
    }

    private void finishRefresh() {
        refreshRunning = false;
        if (refreshPending) {
            refreshPending = false;
            refresh();
        }
    }

    static List<PodcastPlayer.Track> tracks(Podcast podcast) {
        List<PodcastPlayer.Track> tracks = new ArrayList<>();
        for (Podcast.Part part : podcast.parts()) {
            String title = part.segmentTitle() == null || part.segmentTitle().equals(part.topicTitle())
                    ? part.topicTitle() : part.topicTitle() + " · " + part.segmentTitle();
            tracks.add(new PodcastPlayer.Track(part.audio(), part.startsTopic(), title));
        }
        return tracks;
    }

    private void apply(Podcast podcast) {
        last = podcast;
        timeline.slides(player.topicStarts());
        if (!player.playing() || player.total() > 0 && !timeline.isDragging()) {
            timeline.update(player.position(), player.total());
        }
        if (!wanted || podcast == null) {
            return;
        }
        if (podcast.playable() && !player.playing()) {
            player.play(resumeAt);
        } else if (!podcast.playable() && !podcast.running()) {
            failed("Noch kein Abschnitt hörbar");
        }
    }

    private String titleAt(long millis) {
        return player.titleAt(millis);
    }

    private void showTimeline(boolean show) {
        if (show == timelineShown) {
            return;
        }
        timelineShown = show;
        events.accept(show ? TimelineEvent.show(timeline) : TimelineEvent.hide(timeline));
    }

    /** Rückmeldungen der Wiedergabe, auf den EDT gebracht. */
    private final class PlayerEvents implements PodcastPlayer.Listener {

        @Override
        public void position(long millis, long total) {
            int mine = generation;
            SwingUtilities.invokeLater(() -> {
                if (mine != generation) {
                    return;
                }
                if (!timeline.isDragging()) {
                    timeline.update(millis, total);
                }
                resumeAt = millis;
                if (retrying) {
                    // Das Wiederholen hat geklappt: wieder blau.
                    retrying = false;
                    orb.setToolTipText(labels.listen());
                }
                if (orb.state() != PodcastOrb.State.PAUSE && !player.paused()) {
                    orb.setState(PodcastOrb.State.PAUSE);
                }
            });
        }

        @Override
        public void waiting(boolean waiting) {
            int mine = generation;
            SwingUtilities.invokeLater(() -> {
                if (mine == generation && wanted) {
                    orb.setState(waiting ? PodcastOrb.State.WAITING : PodcastOrb.State.PAUSE);
                }
            });
        }

        @Override
        public void ended() {
            int mine = generation;
            SwingUtilities.invokeLater(() -> {
                if (mine == generation) {
                    wanted = false;
                    resumeAt = 0;
                    orb.setState(PodcastOrb.State.PLAY);
                    showTimeline(false);
                }
            });
        }

        @Override
        public void failed(String message, long at) {
            int mine = generation;
            SwingUtilities.invokeLater(() -> {
                if (mine == generation) {
                    resumeAt = at;
                    PodcastControl.this.failed(message);
                }
            });
        }
    }

    // --- für Tests ----------------------------------------------------------------------------------------------

    PodcastPlayer player() {
        return player;
    }

    boolean timelineShown() {
        return timelineShown;
    }

    Podcast last() {
        return last;
    }
}
