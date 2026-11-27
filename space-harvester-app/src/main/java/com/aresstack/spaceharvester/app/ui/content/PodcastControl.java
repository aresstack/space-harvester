package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.event.ApplicationEvent;
import com.aresstack.spaceharvester.app.event.ContentOverlayEvent;
import com.aresstack.spaceharvester.app.event.TimelineEvent;
import com.aresstack.spaceharvester.app.speech.PodcastPlayer;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.content.viewer.VideoSeekBar;
import com.aresstack.spaceharvester.app.ui.controls.CopyButton;
import com.aresstack.spaceharvester.app.ui.controls.PictureStage;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.podcast.Podcast;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final System.Logger LOG = System.getLogger(PodcastControl.class.getName());

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

        /**
         * Erstellt den Podcast neu: alles Abgelegte fällt weg, ein neuer Auftrag beginnt (Angelo 22.11.2026).
         *
         * @throws IllegalStateException während der Podcast entsteht
         */
        default JobStatus renew(S subject) {
            throw new UnsupportedOperationException("Dieser Podcast kann nicht neu erstellt werden");
        }

        /** Ob {@link #renew} geht. */
        default boolean renewable() {
            return false;
        }

        Subscription subscribe(String key, Runnable listener);

        Subscription subscribeJobs(JobListener listener);

        /**
         * Wovon Thema {@code topic} des Podcasts erzählt, als Nummern der Stellen in der Ansicht; leer, wenn es keine
         * bestimmte Stelle gibt. Liest Dateien, nie auf dem EDT.
         */
        default List<Integer> focus(S subject, int topic) {
            return List.of();
        }

        /**
         * Der gesprochene Text des Podcasts, soweit er schon geschrieben ist; leer ohne. Liest Dateien, nie auf dem
         * EDT.
         */
        default Optional<String> transcript(S subject) {
            return Optional.empty();
        }

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
    /** Die Zeitleiste; sie steht über der Suchleiste, solange der Podcast spielt. */
    private final JPanel timelineRow = new JPanel(new TimelineLayout());
    /**
     * Der Untertitel (Angelo 22.11.2026): der gerade gesprochene Satz, zwischen den Zeitangaben der Zeitleiste und
     * im Platz darunter.
     */
    private final com.aresstack.spaceharvester.app.ui.controls.SubtitleLine subtitle =
            new com.aresstack.spaceharvester.app.ui.controls.SubtitleLine();
    /** Der Schalter „Untertitel“ rechts in der Leiste unten; sichtbar, solange die Zeitleiste steht. */
    private final com.aresstack.spaceharvester.app.ui.IconButton subtitleToggle =
            new com.aresstack.spaceharvester.app.ui.IconButton(
                    com.aresstack.spaceharvester.app.ui.controls.SubtitleLine.icon(true), SUBTITLES_ON);

    /**
     * Der Schalter „Minimieren“ ganz rechts in der Leiste unten (Angelo 27.11.2026): nimmt den Player über der
     * Suchleiste weg, die Wiedergabe läuft weiter; derselbe Knopf klappt ihn wieder nach oben auf.
     */
    private final com.aresstack.spaceharvester.app.ui.IconButton minimizeToggle =
            new com.aresstack.spaceharvester.app.ui.IconButton(
                    com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.chevronDown(), MINIMIZE);

    static final String MINIMIZE = "Podcast-Player in die Leiste minimieren";
    static final String EXPAND = "Podcast-Player aufklappen";
    /** Gemerkt im App-State: 1 = Player minimiert, 0 = aufgeklappt (Vorgabe). */
    static final String MINIMIZED_KEY = "podcast.minimized";
    static final String SUBTITLES_ON = "Untertitel ausblenden";
    static final String SUBTITLES_OFF = "Untertitel einblenden";
    /** Gemerkt im App-State: 1 = Untertitel an (Vorgabe), 0 = aus. */
    static final String SUBTITLES_KEY = "podcast.subtitles";
    /** Links und rechts bleibt der Platz der Zeitangaben frei. */
    static final int SUBTITLE_SIDE = 72;
    /** Oberkante des Untertitels: die Zeile der Zeitangaben unter dem Balken. */
    static final int SUBTITLE_TOP = 25;

    /** Die Zeitleiste oben in voller Breite; der Untertitel liegt über ihrer Zeile der Zeitangaben und darunter. */
    private final class TimelineLayout implements java.awt.LayoutManager {

        @Override
        public void layoutContainer(java.awt.Container parent) {
            int width = parent.getWidth();
            timeline.setBounds(0, 0, width, timeline.getPreferredSize().height);
            subtitle.setBounds(SUBTITLE_SIDE, SUBTITLE_TOP, Math.max(0, width - 2 * SUBTITLE_SIDE),
                    subtitle.getPreferredSize().height);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
            int height = timeline.getPreferredSize().height;
            if (subtitle.isVisible()) {
                height = Math.max(height, SUBTITLE_TOP + subtitle.getPreferredSize().height + 4);
            }
            return new java.awt.Dimension(timeline.getPreferredSize().width, height);
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
            return new java.awt.Dimension(0, preferredLayoutSize(parent).height);
        }

        @Override
        public void addLayoutComponent(String name, java.awt.Component component) {
        }

        @Override
        public void removeLayoutComponent(java.awt.Component component) {
        }
    }

    /** Ob Untertitel gezeigt werden; gilt für alle Podcasts und über den Neustart. */
    static boolean subtitles() {
        return com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(SUBTITLES_KEY, 1) != 0;
    }

    /** Der Schalter „Untertitel“ für die Leiste unten, rechtsbündig. */
    JComponent subtitleToggle() {
        return subtitleToggle;
    }

    /** Der Schalter „Minimieren | Aufklappen“ für die Leiste unten, rechts neben „Untertitel“. */
    JComponent minimizeToggle() {
        return minimizeToggle;
    }

    /** Was rechts in der Leiste unten steht: „Untertitel“ und „Minimieren | Aufklappen“. */
    JComponent footTools() {
        return com.aresstack.spaceharvester.app.ui.Comic.row(subtitleToggle, minimizeToggle);
    }

    /** Ob der Player minimiert ist; gilt für alle Podcasts und über den Neustart. */
    static boolean minimized() {
        return com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(MINIMIZED_KEY, 0) != 0;
    }

    private void toggleMinimized() {
        com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(MINIMIZED_KEY, minimized() ? 0 : 1);
        applyMinimized();
    }

    /**
     * Stellt Schalter und Zeitleiste auf die gemerkte Einstellung: minimiert steht nur der Knopf zum Aufklappen in
     * der Leiste, ohne „Untertitel“ (der gehört zum Player); die Wiedergabe bleibt, wie sie ist.
     */
    private void applyMinimized() {
        boolean small = minimized();
        String tip = small ? EXPAND : MINIMIZE;
        minimizeToggle.setIcon(small ? com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.chevronUp()
                : com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.chevronDown());
        minimizeToggle.setToolTipText(tip);
        minimizeToggle.getAccessibleContext().setAccessibleName(tip);
        minimizeToggle.setVisible(timelineShown);
        subtitleToggle.setVisible(timelineShown && !small);
        if (timelineShown) {
            events.accept(small ? TimelineEvent.hide(timelineRow) : TimelineEvent.show(timelineRow));
        }
    }

    com.aresstack.spaceharvester.app.ui.controls.SubtitleLine subtitle() {
        return subtitle;
    }

    private void toggleSubtitles() {
        com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(SUBTITLES_KEY, subtitles() ? 0 : 1);
        applySubtitles();
        subtitleAt(at());
    }

    /** Stellt Schalter und Zeile auf die gemerkte Einstellung (auch die eines anderen Podcasts). */
    private void applySubtitles() {
        boolean on = subtitles();
        if (on != subtitlesOn || subtitleToggle.getToolTipText() == null) {
            subtitlesOn = on;
            subtitleToggle.setIcon(com.aresstack.spaceharvester.app.ui.controls.SubtitleLine.icon(on));
            String tip = on ? SUBTITLES_ON : SUBTITLES_OFF;
            subtitleToggle.setToolTipText(tip);
            subtitleToggle.getAccessibleContext().setAccessibleName(tip);
        }
        if (subtitle.isVisible() != on) {
            subtitle.setVisible(on);
            timelineRow.revalidate();
            timelineRow.repaint();
        }
    }

    private boolean subtitlesOn = true;

    /** Der Satz, der an {@code millis} gesprochen wird; ohne Wiedergabe oder ausgeschaltet bleibt die Zeile leer. */
    private void subtitleAt(long millis) {
        applySubtitles();
        Podcast podcast = last;
        int track = podcast == null || !timelineShown || !subtitlesOn ? -1 : player.trackAt(millis);
        if (track < 0 || track >= podcast.parts().size()) {
            subtitle.show("", 0);
            return;
        }
        long[] within = player.inTrack(millis);
        PodcastSubtitles.Cue cue = PodcastSubtitles.at(podcast.parts().get(track).lines(), within[0], within[1]);
        subtitle.show(cue.text(), cue.share());
    }
    /**
     * „Transkript kopieren“ für die Leiste unten neben der Lupe (Angelo 22.11.2026); sichtbar, sobald es zum
     * Gezeigten einen Podcast gibt.
     */
    private final CopyButton transcriptCopy = CopyButton.deferred(COPY_TRANSCRIPT, this::transcript);
    /** Die Bühne für das Bild des laufenden Themas über dem Inhaltsbereich (Angelo 22.11.2026). */
    private final PictureStage stage = new PictureStage(this::hidePicture);
    private final Map<Path, PictureStage.Picture> loaded = new HashMap<>();
    /** Das Bild, das zum laufenden Abschnitt gehört (auch während es lädt); {@code null} ohne. */
    private Podcast.Picture runningPicture;
    private boolean stageShown;
    /** Das Bild, das der Benutzer mit „Esc“ von der Bühne genommen hat: es kommt erst mit dem nächsten wieder. */
    private Podcast.Picture hiddenPicture;
    private Consumer<ApplicationEvent<?>> events = event -> { };
    /** Bekommt die Stellen des laufenden Themas, leer ohne Thema oder nach dem Abspielen (Angelo 22.11.2026). */
    private Consumer<List<Integer>> follower = statements -> { };
    private Runnable stored = () -> { };
    /** Das Thema, dem die Ansicht gerade folgt; -1 = keinem. */
    private int followedTopic = -1;
    /** Öffnet und schließt das Bild des laufenden Kapitels in der Ansicht; ohne bleibt es bei der Bühne. */
    private Cue cue;
    /** Das Kapitel, dessen Bild gerade offen ist; -1 = keines. */
    private int openedTopic = -1;
    /** Das Kapitel, dessen Bild der Benutzer selbst geschlossen hat: es geht in diesem Kapitel nicht wieder auf. */
    private int dismissedTopic = -1;

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
        timelineRow.setOpaque(false);
        // Der Untertitel liegt über der Zeitleiste (zuerst hinzugefügt = oben), fängt aber keine Maus.
        timelineRow.add(subtitle);
        timelineRow.add(timeline);
        subtitleToggle.setName(labels.name() + ".podcastSubtitles");
        subtitleToggle.setVisible(false);
        subtitleToggle.addActionListener(event -> toggleSubtitles());
        applySubtitles();
        minimizeToggle.setName(labels.name() + ".podcastMinimize");
        minimizeToggle.addActionListener(event -> toggleMinimized());
        applyMinimized();
        timelineRow.setName(labels.name() + ".podcastTimelineRow");
        transcriptCopy.setName(labels.name() + ".podcastTranscript");
        transcriptCopy.setVisible(false);
        stage.setName(labels.name() + ".podcastStage");
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

    /** Was über der Suchleiste steht: die Zeitleiste. */
    JComponent timelineRow() {
        return timelineRow;
    }

    // --- Bilder der Themen --------------------------------------------------------------------------------------

    /** „Esc“ auf der Bühne: Dieses Bild bleibt weg, das nächste erscheint wieder. */
    private void hidePicture() {
        hiddenPicture = runningPicture;
        showPicture();
    }

    // --- Transkript ---------------------------------------------------------------------------------------------

    static final String COPY_TRANSCRIPT = "Transkript des Podcasts kopieren";

    /** Der Knopf „Transkript kopieren“ für die Leiste unten neben der Lupe. */
    JComponent transcriptCopy() {
        return transcriptCopy;
    }

    /** Ob es zum Gezeigten einen Podcast gibt, dessen Text man kopieren oder speichern kann. */
    boolean hasTranscript() {
        return transcriptCopy.isVisible();
    }

    /**
     * Liest den gesprochenen Text des gezeigten Podcasts im Hintergrund und gibt ihn {@code receiver} auf dem EDT;
     * leer, wenn noch nichts geschrieben ist. Wechselt inzwischen das Gezeigte, kommt nichts.
     */
    void transcript(Consumer<String> receiver) {
        Objects.requireNonNull(receiver, "receiver");
        if (summary == null) {
            return;
        }
        Source<S> current = source;
        S shown = summary;
        int mine = generation;
        runner.run(() -> current.transcript(shown).orElse(""), text -> {
            if (mine == generation) {
                receiver.accept(text);
            }
        }, failure -> LOG.log(System.Logger.Level.INFO, "Transkript nicht gelesen ({0})",
                failure.getClass().getName()));
    }

    /** Das Bild des Abschnitts an {@code millis}; ohne Bild oder ohne Wiedergabe bleibt der Inhalt frei. */
    private void pictureAt(long millis) {
        pictureOf(player.trackAt(millis));
    }

    /** Das Bild des Abschnitts {@code track} (Reihenfolge der Tracks = Reihenfolge der Abschnitte). */
    void pictureOf(int track) {
        Podcast podcast = last;
        int index = podcast == null || !timelineShown ? -1 : track;
        Podcast.Picture next = index < 0 || index >= podcast.parts().size() ? null
                : podcast.parts().get(index).picture();
        if (!Objects.equals(next, runningPicture)) {
            runningPicture = next;
            hiddenPicture = null;
            showPicture();
        }
    }

    /** Ab diesem Anteil eines Kapitels geht sein Bild auf (Angelo 22.11.2026: anteilig, nie eine feste Zeit). */
    static final double OPEN_SHARE = 0.2;
    /** Ab diesem Anteil schließt es wieder, damit es vor dem nächsten Kapitel an seine Stelle zurückgeschrumpft ist. */
    static final double CLOSE_SHARE = 0.85;

    /** Die Ansicht zeigt die Bilder an ihrer Stelle im Text und öffnet sie im Betrachter. */
    interface Cue {
        /** Öffnet das Bild von Kapitel {@code topic}; {@code false}, wenn es (noch) keines gibt. */
        boolean open(int topic);

        /** Schließt das offene Bild wieder an seine Stelle. */
        void close();
    }

    /**
     * Statt der Bühne öffnet die Ansicht das Bild des laufenden Kapitels selbst, nach {@link #OPEN_SHARE} des Kapitels,
     * und schließt es nach {@link #CLOSE_SHARE} (Angelo 22.11.2026).
     */
    void cue(Cue cue) {
        this.cue = Objects.requireNonNull(cue, "cue");
        showStage(false);
    }

    /** Der Benutzer hat das Bild selbst geschlossen: in diesem Kapitel nicht wieder öffnen. */
    void pictureClosed() {
        if (openedTopic >= 0) {
            dismissedTopic = openedTopic;
            openedTopic = -1;
        }
    }

    private void cueAt(long millis) {
        if (cue == null) {
            return;
        }
        Podcast podcast = last;
        int track = player.trackAt(millis);
        int topic = podcast == null || !timelineShown || track < 0 || track >= podcast.parts().size() ? -1
                : podcast.parts().get(track).topic();
        if (topic != dismissedTopic) {
            dismissedTopic = -1;
        }
        double share = share(millis, player.topicStarts(), player.total());
        boolean wantOpen = topic >= 0 && topic != dismissedTopic && share >= OPEN_SHARE
                && share < CLOSE_SHARE;
        if (wantOpen && openedTopic != topic) {
            if (openedTopic >= 0) {
                cue.close();
            }
            openedTopic = cue.open(topic) ? topic : -1;
        } else if (!wantOpen && openedTopic >= 0) {
            openedTopic = -1;
            cue.close();
        }
    }

    /** Wie weit {@code millis} im laufenden Kapitel steht, 0 am Anfang, 1 am Ende. */
    static double share(long millis, List<Long> topicStarts, long total) {
        long start = 0;
        long end = total;
        for (long at : topicStarts) {
            if (at <= millis) {
                start = at;
            } else {
                end = at;
                break;
            }
        }
        return end <= start ? 0 : Math.max(0, Math.min(1, (double) (millis - start) / (end - start)));
    }

    /**
     * Wer der Wiedergabe folgt: Die Leseansicht rollt zur Stelle, von der das laufende Thema erzählt, und markiert sie
     * gelb wie die Suche (Angelo 22.11.2026: „dass einem dieser Podcast quasi den Bericht vorliest“).
     */
    void follow(Consumer<List<Integer>> follower) {
        this.follower = Objects.requireNonNull(follower, "follower");
    }

    /** Wer erfährt, dass der Core eine Stufe dieses Podcasts abgelegt hat (etwa ein fertiges Bild), auf dem EDT. */
    void stored(Runnable listener) {
        this.stored = Objects.requireNonNull(listener, "listener");
    }

    private void followAt(long millis) {
        followTrack(player.trackAt(millis));
    }

    /** Meldet die Stellen des Themas von Abschnitt {@code track}, sobald das Thema wechselt. */
    void followTrack(int track) {
        Podcast podcast = last;
        int topic = podcast == null || !timelineShown || track < 0 || track >= podcast.parts().size() ? -1
                : podcast.parts().get(track).topic();
        if (topic == followedTopic) {
            return;
        }
        followedTopic = topic;
        if (topic < 0) {
            follower.accept(List.of());
            return;
        }
        int mine = generation;
        Source<S> current = source;
        S shown = summary;
        runner.run(() -> current.focus(shown, topic), statements -> {
            if (mine == generation && followedTopic == topic) {
                follower.accept(statements);
            }
        }, failure -> LOG.log(System.Logger.Level.INFO, "Podcast-Stelle nicht gelesen ({0})",
                failure.getClass().getName()));
    }

    /** Zeigt das Bild des laufenden Abschnitts auf der Bühne oder nimmt die Bühne weg; lädt es bei Bedarf. */
    private void showPicture() {
        // Öffnet die Ansicht die Bilder selbst (cue), bleibt die Bühne aus.
        Podcast.Picture wantedPicture = cue == null && !Objects.equals(runningPicture, hiddenPicture)
                ? runningPicture : null;
        if (wantedPicture == null) {
            showStage(false);
            return;
        }
        PictureStage.Picture ready = loaded.get(wantedPicture.file());
        if (ready != null) {
            stage.show(ready);
            showStage(true);
            return;
        }
        int mine = generation;
        runner.run(() -> read(wantedPicture), picture -> {
            if (mine != generation) {
                return;
            }
            loaded.put(wantedPicture.file(), picture);
            if (wantedPicture.equals(runningPicture) && !wantedPicture.equals(hiddenPicture)) {
                stage.show(picture);
                showStage(true);
            }
        }, failure -> {
            // Ein unlesbares Bild ist kein Fehler des Podcasts: Der Inhalt bleibt einfach sichtbar.
            LOG.log(System.Logger.Level.INFO, "Podcast-Bild nicht gelesen ({0})", failure.getClass().getName());
            if (mine == generation && wantedPicture.equals(runningPicture)) {
                showStage(false);
            }
        });
    }

    /** Liest ein Bild von der Platte: SVG als Schaubild, alles andere als Rasterbild. */
    static PictureStage.Picture read(Podcast.Picture picture) throws java.io.IOException {
        if (picture.vector()) {
            return new PictureStage.Picture(null, com.aresstack.spaceharvester.application.diagrams.VectorDiagram
                    .of(java.nio.file.Files.readAllBytes(picture.file()))
                    .orElseThrow(() -> new java.io.IOException("Kein lesbares SVG")), picture.caption());
        }
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(picture.file().toFile());
        if (image == null) {
            throw new java.io.IOException("Kein lesbares Bild");
        }
        return new PictureStage.Picture(image, null, picture.caption());
    }

    private void showStage(boolean show) {
        if (show == stageShown) {
            return;
        }
        stageShown = show;
        if (!show) {
            stage.clear();
        }
        events.accept(show ? ContentOverlayEvent.show(stage) : ContentOverlayEvent.hide(stage));
    }

    PictureStage stage() {
        return stage;
    }

    boolean stageShown() {
        return stageShown;
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
        forget();
        stages.close();
        jobs.close();
        stages = Subscription.NONE;
        jobs = Subscription.NONE;
        source = current;
        summary = shown;
        key = shownKey;
        last = null;
        transcriptCopy.setVisible(false);
        runningPicture = null;
        hiddenPicture = null;
        loaded.clear();
        showStage(false);
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
                stored.run();
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

    /** Ob „Podcast neu erstellen“ für das Gezeigte geht: Laufzeit, Chat-Modell und Sprachausgabe, kein laufender Auftrag. */
    boolean renewable() {
        Podcast podcast = last;
        return summary != null && source.renewable() && available(source) && (podcast == null || !podcast.running());
    }

    /**
     * „Podcast neu erstellen“ (Angelo 22.11.2026): hält die Wiedergabe an, lässt Plan, Texte, Audio und Bilder neu
     * entstehen und spielt ab, sobald der erste Abschnitt hörbar ist.
     */
    void renew() {
        if (summary == null) {
            return;
        }
        stop();
        forget();
        last = null;
        runningPicture = null;
        loaded.clear();
        resumeAt = 0;
        retrying = false;
        orb.setState(PodcastOrb.State.WAITING);
        orb.setToolTipText("Podcast wird neu erstellt");
        // Der Wunsch abzuspielen gilt ab hier; ein Klick auf den wartenden Knopf nimmt ihn zurück (wie bei play).
        wanted = true;
        Source<S> current = source;
        S shown = summary;
        int mine = generation;
        runner.run(() -> current.renew(shown), started -> {
            if (mine == generation && wanted) {
                play(0);
            }
        }, failure -> {
            if (mine == generation) {
                failed("Podcast nicht neu erstellt: " + Problems.describe(failure).title());
            }
        });
    }

    private void stop() {
        wanted = false;
        player.stop();
        showTimeline(false);
    }

    /**
     * Ein anderer oder neu entstehender Podcast: Player und Zeitleiste vergessen den vorigen, sonst stünde die Zeitleiste
     * bis zum ersten Ton auf dessen Ende (Angelo 23.11.2026: „er zeigt vorne die ganze Zeit die Endzeit an“).
     */
    private void forget() {
        player.reset();
        timeline.reset();
        timeline.slides(List.of());
        subtitleAt(0);
    }

    /** Wo die Wiedergabe steht; vor dem Start (oder nach dem Ende) dort, wo sie beginnen wird. */
    private long at() {
        return player.playing() ? player.position() : resumeAt;
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
        // Text gibt es, sobald Abschnitte geplant sind; ob schon einer geschrieben ist, zeigt erst das Lesen.
        transcriptCopy.setVisible(podcast != null && podcast.parts().stream().anyMatch(part -> part.segment() >= 0));
        long at = at();
        subtitleAt(at);
        timeline.slides(player.topicStarts());
        // Ein Bild, das erst jetzt fertig wurde, erscheint zum laufenden Thema.
        pictureAt(at);
        followAt(at);
        cueAt(at);
        if (!player.playing() || player.total() > 0 && !timeline.isDragging()) {
            timeline.update(at, player.total());
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
        subtitleAt(at());
        if (show) {
            applyMinimized();
        } else {
            minimizeToggle.setVisible(false);
            subtitleToggle.setVisible(false);
            events.accept(TimelineEvent.hide(timelineRow));
        }
        if (!show) {
            runningPicture = null;
            showStage(false);
            followTrack(-1);
            if (openedTopic >= 0 && cue != null) {
                cue.close();
            }
            openedTopic = -1;
            dismissedTopic = -1;
        }
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
                pictureAt(millis);
                followAt(millis);
                cueAt(millis);
                subtitleAt(millis);
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
