package com.aresstack.spaceharvester.app.ui.content.viewer;


import com.aresstack.spaceharvester.app.settings.VideoSettings;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Play source videos through a range bridge without exposing source credentials to VLC.
 * Keep native video and Swing controls in separate opaque regions to prevent AWT mixing artifacts.
 */
public final class VideoViewerPanel extends JPanel implements AutoCloseable {

    /** Fristen, Toleranzen und Takt des Players ({@code space-harvester-app.video.*}). */
    private final VideoSettings settings;
    private static final String VIDEO_CARD = "video";
    private static final String AUDIO_CARD = "audio";

    private final JLabel message = new JLabel(" ");
    private final CardLayout displayLayout = new CardLayout();
    private final JPanel display = new JPanel(displayLayout);
    private final JPanel audioDisplay = new JPanel(new BorderLayout());
    private final VideoTransportBar transport = new VideoTransportBar(this::seek);
    private final VideoFullScreenController fullScreen =
            new VideoFullScreenController(this, this::prepareForFullScreenTransfer,
                    this::completeFullScreenTransfer, transport::setFullScreen);
    private final Timer progress;
    private final ExecutorService seekWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "space-harvester-video-seek");
        thread.setDaemon(true);
        return thread;
    });
    private final String fileName;
    private final String mediaType;
    private final long fileSize;
    private final MediaStreamBridge.ChunkSource reader;

    private EmbeddedMediaPlayerComponent player;
    private MediaStreamBridge bridge;
    private MediaStreamBridge audioBridge;
    private SwingWorker<Mp4AudioStream, Void> audioPreparation;
    /** Reads the MP4 index once after the first playback start and marks the slide changes. */
    private SwingWorker<SlideIndex, Void> slideDetection;

    /** The slide changes and the length of a video, read from its MP4 index. */
    /** The marks on the timeline; the same marks again change nothing. */
    private java.util.List<Long> shownMarks = java.util.List.of();

    private record SlideIndex(java.util.List<Long> changes, long durationMillis) {
    }
    private volatile boolean switchingMedia;
    private long resumePositionMillis;
    private boolean pauseAfterSwitch;
    private boolean resumeVideoAfterFullScreen;
    private boolean preparationHadPlayback;
    private boolean preparationWasPlaying;
    private long preparationPositionMillis;
    private SwingWorker<Void, Long> downloadTask;
    private volatile boolean closed;
    private volatile boolean audioOnly;
    private boolean started;
    private boolean actuallyPlaying;
    private long startRequestedAtNanos;
    private long seekRequestedAtNanos;
    private double requestedSeekShare;
    /**
     * Ein Sprung vor dem ersten Abspielen: Das Video startet an der Stelle und hält sofort an, dann zeigt es ein Bild
     * weiter ({@link #playbackStarted}).
     */
    private volatile boolean pauseWhenStarted;
    /**
     * Das zuletzt angesprungene Ziel im angehaltenen Video; {@code -1} ohne. Von ihm rechnen „nächste“ und „vorige
     * Folie“, denn VLC meldet nach einem Sprung im angehaltenen Video erst verzögert die neue Position.
     */
    private volatile long pausedTarget = -1;
    /** Die Länge des Videos aus seinem MP4-Index; mit ihr bleiben die Marken auch nach Stopp oder Fehler sichtbar. */
    private long indexDurationMillis;

    public VideoViewerPanel(String fileName, long size, String mediaType,
                            MediaStreamBridge.ChunkSource reader) {
        this(fileName, size, mediaType, reader, VideoSettings.DEFAULT);
    }

    /** @param settings Fristen, Toleranzen, Takt und Marken des Players */
    public VideoViewerPanel(String fileName, long size, String mediaType,
                            MediaStreamBridge.ChunkSource reader, VideoSettings settings) {
        super(new BorderLayout(0, 0));
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.progress = new Timer(settings.number(VideoSettings.Value.PROGRESS_MILLIS), event -> updateProgress());
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.fileSize = size;
        this.reader = reader;
        setOpaque(true);
        setBackground(AppColors.of("app.VideoViewerPanel.slideIndex"));
        message.setHorizontalAlignment(SwingConstants.CENTER);
        message.setOpaque(true);
        message.setBackground(AppColors.of("app.VideoViewerPanel.slideIndex"));
        message.setForeground(AppColors.of("app.VideoViewerPanel.slideIndex2"));
        message.setBorder(BorderFactory.createEmptyBorder(9, 8, 9, 8));
        add(message, BorderLayout.NORTH);
        add(transport, BorderLayout.SOUTH);
        display.setOpaque(true);
        display.setBackground(AppColors.of("app.VideoViewerPanel.slideIndex"));
        audioDisplay.setOpaque(true);
        audioDisplay.setBackground(AppColors.of("app.VideoViewerPanel.slideIndex"));
        JLabel audioTitle = new JLabel("Nur Audio – Videobild ausgeschaltet", SwingConstants.CENTER);
        audioTitle.setForeground(AppColors.of("app.VideoViewerPanel.slideIndex3"));
        audioTitle.setFont(audioTitle.getFont().deriveFont(Font.BOLD, 17f));
        audioDisplay.add(audioTitle, BorderLayout.CENTER);
        display.add(audioDisplay, AUDIO_CARD);
        add(display, BorderLayout.CENTER);
        transport.play().addActionListener(event -> play());
        transport.pause().addActionListener(event -> pause());
        transport.stop().addActionListener(event -> stop());
        transport.download().addActionListener(event -> chooseDownload());
        transport.cancelDownload().addActionListener(event -> cancelDownload());
        transport.convert().addActionListener(event -> onConvert.accept(false));
        transport.convertPdf().addActionListener(event -> onConvert.accept(true));
        transport.fullScreen().addActionListener(event -> fullScreen.toggle());
        transport.audioOnly().addActionListener(event -> toggleAudioOnly());
        transport.skipBack().addActionListener(event -> skip(false));
        transport.skipForward().addActionListener(event -> skip(true));
        long step = settings.get(VideoSettings.Value.SKIP_SECONDS);
        transport.skipBack().setToolTipText(step + " s zurück");
        transport.skipForward().setToolTipText(step + " s vor");
        transport.previousSlide().addActionListener(event -> jumpToSlide(false));
        transport.nextSlide().addActionListener(event -> jumpToSlide(true));
        transport.fullScreen().setEnabled(false);
        transport.audioOnly().setEnabled(false);
        transport.seekBar().reset();

        if (size < 1) {
            transport.download().setEnabled(false);
            unavailable("Die Dateigröße ist unbekannt; Streaming und Download sind nicht möglich.");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            unavailable("Video-Wiedergabe ist ohne grafische Oberfläche nicht verfügbar.");
            return;
        }
        try {
            if (!VlcRuntimeLocator.configure()) {
                unavailable("Keine VLC-Installation gefunden. VLC installieren oder -Dspaceharvester.vlc.path setzen.");
                return;
            }
            player = new EmbeddedMediaPlayerComponent();
            bridge = new MediaStreamBridge(reader, size, mediaType, settings);
            registerPlaybackEvents();
            player.mediaPlayer().input().enableMouseInputHandling(false);
            VideoFullScreenController.installDoubleClick(
                    player.videoSurfaceComponent(), fullScreen::toggle);
            display.add(player, VIDEO_CARD);
            displayLayout.show(display, VIDEO_CARD);
            transport.audioOnly().setEnabled(mediaType != null && "video/mp4".equalsIgnoreCase(mediaType)
                    || fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"));
            transport.fullScreen().setEnabled(true);
            message.setText("Video „" + fileName + "“ – zum Starten Abspielen wählen.");
            progress.start();
        } catch (RuntimeException | LinkageError | IOException failure) {
            if (player != null) {
                player.release();
                player = null;
            }
            if (bridge != null) {
                bridge.close();
                bridge = null;
            }
            unavailable("VLC kann nicht initialisiert werden: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " – " + failure.getMessage()));
        }
    }

    /** Prepare a virtual MP4 containing only the indexed audio track. */
    private void toggleAudioOnly() {
        if (closed || player == null || audioPreparation != null) {
            return;
        }
        if (audioOnly) {
            switchPlaybackMode(false);
        } else if (audioBridge != null) {
            switchPlaybackMode(true);
        } else {
            prepareAudioOnlyStream();
        }
    }

    private void prepareAudioOnlyStream() {
        suspendVideoForAudioPreparation();
        transport.audioOnly().setEnabled(false);
        transport.play().setEnabled(false);
        transport.pause().setEnabled(false);
        message.setText("MP4-Audiospur wird ohne Videodaten vorbereitet …");
        audioPreparation = new SwingWorker<Mp4AudioStream, Void>() {
            @Override
            protected Mp4AudioStream doInBackground() throws IOException {
                return Mp4AudioOnlySource.open(reader, fileSize, settings);
            }

            @Override
            protected void done() {
                if (closed || audioPreparation != this) {
                    return;
                }
                audioPreparation = null;
                transport.audioOnly().setEnabled(true);
                try {
                    Mp4AudioStream audioSource = get();
                    audioBridge = new MediaStreamBridge(audioSource, audioSource.length(), "video/mp4", settings);
                    switchPlaybackMode(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    releaseAudioPreparation();
                    message.setText("Vorbereitung der Audiospur unterbrochen.");
                } catch (CancellationException cancelled) {
                    releaseAudioPreparation();
                    message.setText("Vorbereitung der Audiospur abgebrochen.");
                } catch (ExecutionException | IOException failure) {
                    releaseAudioPreparation();
                    message.setText("Nur Audio ist für dieses MP4 nicht verfügbar: "
                            + failure.getClass().getSimpleName()
                            + (failure.getCause() instanceof IOException
                            ? " – " + failure.getCause().getMessage() : ""));
                }
            }
        };
        audioPreparation.execute();
    }

    /** Suspend video traffic before indexing the requested audio stream. */
    private void suspendVideoForAudioPreparation() {
        preparationHadPlayback = started;
        preparationWasPlaying = actuallyPlaying;
        preparationPositionMillis = 0;
        if (!started) {
            return;
        }
        try {
            preparationPositionMillis = Math.max(0, player.mediaPlayer().status().time());
        } catch (RuntimeException ignored) {
            // Resume at the beginning when the player cannot report its position.
        }
        switchingMedia = true;
        actuallyPlaying = false;
        player.mediaPlayer().controls().stop();
    }

    private void releaseAudioPreparation() {
        switchingMedia = false;
        started = false;
        actuallyPlaying = false;
        startRequestedAtNanos = 0;
        transport.play().setEnabled(true);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(false);
        preparationHadPlayback = false;
        preparationPositionMillis = 0;
    }

    /** Switch between two independent byte-range sources without loading video for audio playback. */
    private void switchPlaybackMode(boolean audio) {
        if (closed || player == null || (audio && audioBridge == null)) {
            return;
        }
        long position = preparationPositionMillis;
        boolean wasStarted = preparationHadPlayback || started;
        boolean wasPaused = preparationHadPlayback ? !preparationWasPlaying : started && !actuallyPlaying;
        if (wasStarted && !preparationHadPlayback) {
            try {
                position = Math.max(0, player.mediaPlayer().status().time());
            } catch (RuntimeException ignored) {
                // Resume from the beginning when the native player has no valid timestamp.
            }
        }
        if (audio && fullScreen.isFullScreen()) {
            // Stop before moving the native surface; audio mode will start a new source below.
            switchingMedia = true;
            player.mediaPlayer().controls().stop();
            fullScreen.leaveForMediaSwitch();
        }
        preparationHadPlayback = false;
        preparationPositionMillis = 0;
        audioOnly = audio;
        transport.setAudioOnly(audio);
        transport.fullScreen().setEnabled(!audio);
        displayLayout.show(display, audio ? AUDIO_CARD : VIDEO_CARD);
        display.revalidate();
        display.repaint();
        if (!audio) {
            SwingUtilities.invokeLater(this::reattachVideoSurface);
        }
        if (!wasStarted) {
            transport.play().setEnabled(true);
            transport.pause().setEnabled(false);
            transport.stop().setEnabled(false);
            message.setText(audio ? "Nur Audio – zum Starten Abspielen wählen."
                    : "Video – zum Starten Abspielen wählen.");
            return;
        }

        switchingMedia = true;
        // VLC starts the new stream at the saved position itself; seeking after "playing" first played from the
        // beginning and then stalled until the seek was served.
        resumePositionMillis = 0;
        pauseAfterSwitch = wasPaused;
        actuallyPlaying = false;
        startRequestedAtNanos = System.nanoTime();
        seekRequestedAtNanos = 0;
        transport.play().setEnabled(false);
        message.setText(audio ? "Audio wird geladen …" : "Video wird geladen …");
        try {
            player.mediaPlayer().controls().stop();
            boolean accepted = player.mediaPlayer().media().play(activeBridge().address().toASCIIString(),
                    startAt(position));
            if (!accepted) {
                switchingMedia = false;
                playbackFailed("VLC hat den Wechsel des Medienmodus abgelehnt.");
            } else {
                started = true;
            }
        } catch (RuntimeException failure) {
            switchingMedia = false;
            playbackFailed("Medienmodus konnte nicht gewechselt werden.");
        }
    }

    /** The VLC media option that starts playback at a position, or none at the beginning. */
    static String[] startAt(long positionMillis) {
        return positionMillis <= 0 ? new String[0]
                : new String[]{String.format(java.util.Locale.ROOT, ":start-time=%.3f", positionMillis / 1000.0)};
    }

    private MediaStreamBridge activeBridge() {
        return audioOnly ? audioBridge : bridge;
    }

    private void resumePlaybackAfterSwitch() {
        long position = resumePositionMillis;
        boolean pause = pauseAfterSwitch;
        resumePositionMillis = 0;
        pauseAfterSwitch = false;
        switchingMedia = false;
        if (position == 0 && !pause) {
            return;
        }
        try {
            seekWorker.execute(() -> {
                try {
                    if (!closed && player != null) {
                        if (position > 0) {
                            player.mediaPlayer().controls().setTime(position);
                        }
                        if (pause) {
                            player.mediaPlayer().controls().setPause(true);
                        }
                    }
                } catch (RuntimeException ignored) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed) {
                            message.setText("Wiedergabeposition konnte nicht wiederhergestellt werden.");
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Skip a pending resume when playback is closing.
        }
    }

    private String playbackMessage() {
        return audioOnly ? "Nur Audio aus der Quelle" : "Wiedergabe aus der Quelle";
    }

    /** Stop native rendering before AWT replaces the video Canvas window handle. */
    private void prepareForFullScreenTransfer() {
        if (closed || audioOnly || player == null || !started || switchingMedia) {
            return;
        }
        try {
            resumePositionMillis = Math.max(0, player.mediaPlayer().status().time());
        } catch (RuntimeException unavailable) {
            resumePositionMillis = 0;
        }
        pauseAfterSwitch = !actuallyPlaying;
        switchingMedia = true;
        resumeVideoAfterFullScreen = true;
        actuallyPlaying = false;
        seekRequestedAtNanos = 0;
        player.mediaPlayer().controls().stop();
    }

    /** Start the saved stream after the new Canvas handle is available. */
    private void completeFullScreenTransfer() {
        if (closed || player == null || audioOnly) {
            return;
        }
        reattachVideoSurface();
        if (!resumeVideoAfterFullScreen) {
            return;
        }
        resumeVideoAfterFullScreen = false;
        long position = resumePositionMillis;
        resumePositionMillis = 0;
        try {
            startRequestedAtNanos = System.nanoTime();
            if (!player.mediaPlayer().media().play(bridge.address().toASCIIString(), startAt(position))) {
                switchingMedia = false;
                playbackFailed("VLC konnte die Bildausgabe nach dem Vollbildwechsel nicht starten.");
            }
        } catch (RuntimeException unavailable) {
            switchingMedia = false;
            playbackFailed("VLC konnte die Bildausgabe nach dem Vollbildwechsel nicht starten: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    /** Rebind the native video output to the current AWT window. */
    private void reattachVideoSurface() {
        if (closed || player == null || !player.isDisplayable()) {
            return;
        }
        try {
            player.mediaPlayer().videoSurface().attachVideoSurface();
        } catch (RuntimeException failure) {
            message.setText("Videooberfläche konnte nicht aktualisiert werden: "
                    + failure.getClass().getSimpleName());
        }
    }

    private void registerPlaybackEvents() {
        player.mediaPlayer().events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> playbackStarted());
            }

            @Override
            public void paused(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> playbackPaused());
            }

            /**
             * Die App schaltet nie stumm und hat keinen Stummschalter. Eine frühere Fassung schaltete beim Springen im
             * angehaltenen Video stumm, und das blieb im Player hängen; meldet VLC „stumm“, ist der Ton deshalb sofort
             * wieder an.
             */
            @Override
            public void muted(MediaPlayer mediaPlayer, boolean muted) {
                if (muted) {
                    mediaPlayer.submit(() -> mediaPlayer.audio().setMute(false));
                }
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> playbackStopped());
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> playbackStopped());
            }

            @Override
            public void error(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> playbackFailed(
                        activeBridge() != null && activeBridge().lastFailure() != null
                                ? activeBridge().lastFailure()
                                : "VLC konnte die Mediendaten nicht abspielen. Netzwerk/Codec prüfen."));
            }
        });
    }

    private void play() {
        if (closed || player == null || bridge == null || audioPreparation != null) {
            return;
        }
        try {
            transport.play().setEnabled(false);
            startRequestedAtNanos = System.nanoTime();
            message.setText(audioOnly ? "Audio wird gestartet …" : "Video wird gestartet …");
            boolean accepted = true;
            pauseWhenStarted = false;
            pausedTarget = -1;
            if (started) {
                player.mediaPlayer().controls().play();
            } else {
                accepted = player.mediaPlayer().media().play(activeBridge().address().toASCIIString());
            }
            if (!accepted) {
                playbackFailed("VLC hat den Wiedergabestart abgelehnt.");
            } else {
                started = true;
            }
        } catch (RuntimeException failure) {
            playbackFailed((audioOnly ? "Audio" : "Video") + " nicht abspielbar: " + failure.getMessage());
        }
    }

    private void pause() {
        if (!closed && started && player != null && audioPreparation == null) {
            player.mediaPlayer().controls().pause();
        }
    }

    private void stop() {
        if (audioPreparation != null) {
            audioPreparation.cancel(true);
            audioPreparation = null;
            transport.audioOnly().setEnabled(true);
        }
        preparationHadPlayback = false;
        if (!closed && player != null) {
            switchingMedia = false;
            player.mediaPlayer().controls().stop();
        }
        playbackStopped();
    }

    /**
     * Commit a seek only after the user releases the timeline; execute libVLC's potentially slow call off the EDT.
     * Angehalten springt das Video genau an die Stelle und geht dann ein Bild weiter, damit VLC das Bild von dort
     * zeigt; noch nicht gestartet startet es an der Stelle und hält dort an.
     */
    private void seek(double share) {
        if (closed || player == null || audioPreparation != null) {
            return;
        }
        if (!transport.seekBar().canSeek()) {
            return;
        }
        if (!started) {
            startPausedAt(Math.round(Math.max(0, Math.min(1, share)) * transport.seekBar().durationMillis()));
            return;
        }
        final double requestedShare = Math.max(0, Math.min(1, share));
        final long duration = transport.seekBar().durationMillis();
        final long target = Math.round(requestedShare * duration);
        requestedSeekShare = requestedShare;
        seekRequestedAtNanos = System.nanoTime();
        if (duration > 0) {
            transport.seekBar().update(target, duration);
        } else {
            transport.seekBar().updateFraction(requestedShare);
        }
        message.setText("Position wird geladen …");
        boolean paused = !actuallyPlaying;
        pausedTarget = paused && duration > 0 ? target : -1;
        try {
            seekWorker.execute(() -> {
                try {
                    if (!closed && player != null && audioPreparation == null) {
                        if (duration > 0) {
                            player.mediaPlayer().controls().setTime(target);
                        } else {
                            player.mediaPlayer().controls().setPosition((float) requestedShare);
                        }
                        if (paused) {
                            // Angehalten zeigt VLC nach dem Sprung noch das alte Bild: ein Bild weiter, bis VLC die
                            // neue Stelle meldet; vorher ginge es nur von der alten Stelle ein Bild weiter.
                            stepUntil(duration > 0 ? target : -1);
                        }
                    }
                } catch (RuntimeException failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed) {
                            playbackFailed("Springen nicht möglich: " + failure.getMessage());
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Ignore seeks submitted while the viewer is closing.
        }
    }

    /**
     * Abseits des EDT: geht im angehaltenen Video ein Bild weiter, und so lange weitere, bis VLC {@code target} oder
     * eine spätere Stelle meldet, höchstens die Frist für einen Sprung. VLC führt den Sprung asynchron aus; ein
     * einzelnes Bild weiter kam oft noch von der alten Stelle, also vor dem Ziel (bei einer Marke die vorige Folie).
     * Ohne Ziel ein Bild weiter.
     */
    private void stepUntil(long target) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(settings.get(VideoSettings.Value.SEEK_FEEDBACK_MILLIS));
        do {
            player.mediaPlayer().controls().nextFrame();
            if (target < 0) {
                return;
            }
            try {
                Thread.sleep(settings.get(VideoSettings.Value.POLL_MILLIS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (!closed && player != null && pausedTarget == target && System.nanoTime() - deadline < 0
                && player.mediaPlayer().status().time() < target);
    }

    /** Hält an (falls {@code pause}) und zeigt ein Bild weiter; abseits des EDT. */
    private void stepFrame(boolean pause) {
        try {
            seekWorker.execute(() -> {
                if (!closed && player != null) {
                    if (pause) {
                        player.mediaPlayer().controls().setPause(true);
                    }
                    player.mediaPlayer().controls().nextFrame();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Der Player schließt gerade.
        }
    }

    /** Ein Sprung vor dem ersten Abspielen: startet das Video an der Stelle, es hält dort sofort an. */
    private void startPausedAt(long target) {
        if (bridge == null) {
            return;
        }
        requestedSeekShare = transport.seekBar().durationMillis() > 0
                ? target / (double) transport.seekBar().durationMillis() : 0;
        seekRequestedAtNanos = System.nanoTime();
        transport.seekBar().update(target, transport.seekBar().durationMillis());
        message.setText("Position wird geladen …");
        try {
            pauseWhenStarted = true;
            pausedTarget = target;
            startRequestedAtNanos = System.nanoTime();
            if (player.mediaPlayer().media().play(activeBridge().address().toASCIIString(), startAt(target))) {
                started = true;
            } else {
                pauseWhenStarted = false;
                playbackFailed("VLC hat den Wiedergabestart abgelehnt.");
            }
        } catch (RuntimeException failure) {
            pauseWhenStarted = false;
            playbackFailed((audioOnly ? "Audio" : "Video") + " nicht abspielbar: " + failure.getMessage());
        }
    }

    private void playbackStarted() {
        if (closed) {
            return;
        }
        if (pauseWhenStarted && !switchingMedia) {
            // Ein Sprung vor dem ersten Abspielen: an der Stelle anhalten und ein Bild weiter, damit es zu sehen ist.
            pauseWhenStarted = false;
            started = true;
            startRequestedAtNanos = 0;
            stepFrame(true);
            return;
        }
        if (switchingMedia) {
            resumePlaybackAfterSwitch();
        }
        started = true;
        actuallyPlaying = true;
        startRequestedAtNanos = 0;
        transport.play().setEnabled(false);
        transport.pause().setEnabled(true);
        transport.stop().setEnabled(true);
        message.setText(playbackMessage());
    }

    /**
     * Find the slide changes of a screen-recorded presentation from the MP4 index when the page opens, once and in the
     * background: their marks appear before anything is played, and whoever listens ({@link #onSlides}) gets the same
     * marks, e.g. for one virtual attachment per slide. Other formats simply show no marks.
     */
    public void findSlides() {
        boolean mp4 = "video/mp4".equalsIgnoreCase(mediaType)
                || fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
        if (slideDetection != null || closed || !mp4 || fileSize < 16) {
            return;
        }
        slideDetection = new SwingWorker<SlideIndex, Void>() {
            @Override
            protected SlideIndex doInBackground() throws IOException {
                return new SlideIndex(SlideChanges.load(reader, fileSize, settings),
                        SlideChanges.durationMillis(reader, fileSize));
            }

            @Override
            protected void done() {
                if (closed || isCancelled()) {
                    return;
                }
                try {
                    SlideIndex index = get();
                    marks(index.changes(), index.durationMillis());
                    onSlides.accept(index.changes());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException unavailable) {
                    // Without a readable index the video simply has no slide marks.
                }
            }
        };
        slideDetection.execute();
    }

    /**
     * Show marks that are already known (stored when they were found) instead of {@link #findSlides}: the index is not
     * read again, only the movie header for the length that places the marks.
     */
    public void showMarks(java.util.List<Long> changes) {
        if (closed || changes.isEmpty() || changes.equals(shownMarks)) {
            return;
        }
        slideDetection = new SwingWorker<SlideIndex, Void>() {
            @Override
            protected SlideIndex doInBackground() throws IOException {
                return new SlideIndex(changes, SlideChanges.durationMillis(reader, fileSize));
            }

            @Override
            protected void done() {
                if (closed || isCancelled()) {
                    return;
                }
                try {
                    marks(changes, get().durationMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException unavailable) {
                    // Without a readable header the marks appear once playback reports the length.
                    marks(changes, 0);
                }
            }
        };
        slideDetection.execute();
    }

    /** The marks on the timeline; with a known length they are placed before anything is played. */
    private void marks(java.util.List<Long> changes, long durationMillis) {
        shownMarks = java.util.List.copyOf(changes);
        if (durationMillis > 0) {
            indexDurationMillis = durationMillis;
        }
        transport.slides(changes);
        if (!started && durationMillis > 0) {
            transport.seekBar().update(0, durationMillis);
        }
    }

    /** „In PPTX/PDF umwandeln“: wer die Aufträge startet ({@code true} für PDF); ohne ihn bleiben die Knöpfe verborgen. */
    private java.util.function.Consumer<Boolean> onConvert = pdf -> { };

    /**
     * Zeigt neben „Herunterladen“ die Knöpfe „In PPTX umwandeln“ und „In PDF umwandeln“ (Angelo 20.11.2026);
     * {@code listener} läuft auf dem EDT, wenn einer gedrückt wird, mit {@code true} für PDF.
     */
    public void onConvert(java.util.function.Consumer<Boolean> listener) {
        onConvert = java.util.Objects.requireNonNull(listener, "listener");
        transport.convert().setVisible(true);
        transport.convertPdf().setVisible(true);
        transport.revalidate();
        transport.repaint();
    }

    /** Der Knopf „In PPTX umwandeln“ (für Tests). */
    javax.swing.JButton convertButton() {
        return transport.convert();
    }

    /** Der Knopf „In PDF umwandeln“ (für Tests). */
    javax.swing.JButton convertPdfButton() {
        return transport.convertPdf();
    }

    /** Whoever wants the slide changes once they are found, e.g. for one virtual attachment per slide. */
    private java.util.function.Consumer<java.util.List<Long>> onSlides = changes -> { };

    /** {@code listener} gets the slide changes in milliseconds once, on the EDT, when they are found. */
    public void onSlides(java.util.function.Consumer<java.util.List<Long>> listener) {
        onSlides = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Jump to the next slide, or to the start of the current or previous one like a player's previous button. */
    /**
     * Springt um die eingestellte Zeit zurück bzw. vor (Einstellungen › Video, Vorgabe 5 s), höchstens bis zum Anfang
     * bzw. Ende; angehalten vom zuletzt angesprungenen Ziel aus, wie „nächste Folie“.
     */
    private void skip(boolean forward) {
        long duration = transport.seekBar().durationMillis();
        long position = !actuallyPlaying && pausedTarget >= 0 ? pausedTarget : transport.seekBar().displayedMillis();
        if (duration <= 0 || position < 0) {
            return;
        }
        long step = settings.get(VideoSettings.Value.SKIP_SECONDS) * 1000L;
        long target = Math.max(0, Math.min(duration - 1, position + (forward ? step : -step)));
        seek(target / (double) duration);
    }

    private void jumpToSlide(boolean forward) {
        java.util.List<Long> changes = transport.seekBar().slides();
        long duration = transport.seekBar().durationMillis();
        long position = !actuallyPlaying && pausedTarget >= 0 ? pausedTarget : transport.seekBar().displayedMillis();
        if (changes.isEmpty() || duration <= 0 || position < 0) {
            return;
        }
        long target = forward ? SlideChanges.next(changes, position, settings)
                : SlideChanges.previous(changes, position, settings);
        if (target >= 0) {
            seek(target / (double) duration);
        }
    }

    private void playbackPaused() {
        if (closed || audioPreparation != null) {
            return;
        }
        actuallyPlaying = false;
        startRequestedAtNanos = 0;
        transport.play().setEnabled(true);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(true);
        message.setText("Pausiert");
    }

    private void playbackStopped() {
        if (closed || switchingMedia) {
            return;
        }
        pauseWhenStarted = false;
        pausedTarget = -1;
        actuallyPlaying = false;
        started = false;
        startRequestedAtNanos = 0;
        seekRequestedAtNanos = 0;
        transport.play().setEnabled(true);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(false);
        resetTimeline();
        message.setText("Wiedergabe gestoppt");
    }

    private void playbackFailed(String reason) {
        if (closed || audioPreparation != null) {
            return;
        }
        actuallyPlaying = false;
        started = false;
        startRequestedAtNanos = 0;
        seekRequestedAtNanos = 0;
        transport.play().setEnabled(true);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(false);
        resetTimeline();
        message.setText(reason);
    }

    /**
     * Die Zeitleiste auf den Anfang; ist die Länge aus dem MP4-Index bekannt, bleiben die Marken sichtbar und
     * anspringbar, statt mit der Leiste zu verschwinden.
     */
    private void resetTimeline() {
        transport.seekBar().reset();
        if (indexDurationMillis > 0) {
            transport.seekBar().update(0, indexDurationMillis);
        }
    }

    /** Keep the timeline responsive while native playback fetches a newly requested range. */
    private void updateProgress() {
        if (closed || player == null || !started || audioPreparation != null) {
            return;
        }
        if (startRequestedAtNanos != 0 && !actuallyPlaying
                && System.nanoTime() - startRequestedAtNanos
                > TimeUnit.MILLISECONDS.toNanos(settings.get(VideoSettings.Value.START_TIMEOUT_MILLIS))) {
            playbackFailed("VLC startet nicht. Installation, Videoformat und Zugriff auf die Quelle prüfen.");
            return;
        }
        try {
            long duration = player.mediaPlayer().status().length();
            long current = player.mediaPlayer().status().time();
            float share = player.mediaPlayer().status().position();
            boolean hasFraction = share >= 0 && share <= 1;
            if (seekRequestedAtNanos != 0) {
                boolean reached = duration > 0
                        ? Math.abs(current - Math.round(requestedSeekShare * duration))
                        <= settings.get(VideoSettings.Value.SEEK_REACHED_MILLIS)
                        : hasFraction && Math.abs(share - requestedSeekShare)
                        < settings.get(VideoSettings.Value.SEEK_REACHED_PERMILLE) / 1000.0;
                if (reached) {
                    seekRequestedAtNanos = 0;
                    message.setText(actuallyPlaying ? playbackMessage() : "Pausiert");
                } else if (System.nanoTime() - seekRequestedAtNanos
                        < TimeUnit.MILLISECONDS.toNanos(settings.get(VideoSettings.Value.SEEK_FEEDBACK_MILLIS))) {
                    if (!transport.seekBar().isDragging()) {
                        showSeekProgress(duration, current, hasFraction ? share : -1, true);
                    }
                    return;
                } else {
                    seekRequestedAtNanos = 0;
                    message.setText("Position nicht erreicht. Quellenzugriff oder Seek-Fähigkeit prüfen.");
                }
            }
            if (!transport.seekBar().isDragging()) {
                showSeekProgress(duration, current, hasFraction ? share : -1, false);
            }
        } catch (RuntimeException failure) {
            message.setText("Wiedergabestatus nicht verfügbar.");
        }
    }

    private void showSeekProgress(long duration, long current, double share, boolean pending) {
        if (duration > 0) {
            long elapsed = pending ? Math.round(requestedSeekShare * duration) : current;
            transport.seekBar().update(elapsed, duration);
        } else if (pending) {
            transport.seekBar().updateFraction(requestedSeekShare);
        } else if (share >= 0) {
            transport.seekBar().updateFraction(share);
        }
    }

    /** Ask for a destination only after the user explicitly chooses to download. */
    private void chooseDownload() {
        if (closed || fileSize < 1 || downloadTask != null) {
            return;
        }
        JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create(null);
        String safeName = fileName == null ? "video.mp4"
                : fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        chooser.setSelectedFile(new java.io.File(chooser.getCurrentDirectory(),
                safeName.isBlank() ? "video.mp4" : safeName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (java.nio.file.Files.exists(target)
                && JOptionPane.showConfirmDialog(this, "Die Datei existiert bereits. Überschreiben?",
                "Video speichern", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        startDownload(target);
    }

    private void cancelDownload() {
        if (downloadTask != null) {
            downloadTask.cancel(true);
        }
    }

    /** Download without publishing a partial file under the final filename. */
    private void startDownload(Path target) {
        transport.download().setEnabled(false);
        transport.cancelDownload().setEnabled(true);
        transport.showDownloadCancellation(true);
        message.setText("Video wird heruntergeladen …");
        SwingWorker<Void, Long> task = new SwingWorker<Void, Long>() {
            @Override
            protected Void doInBackground() throws IOException {
                VideoFileDownloader.download(reader, fileSize, target, this::isCancelled, this::publish);
                return null;
            }

            @Override
            protected void process(List<Long> chunks) {
                if (!chunks.isEmpty() && !closed) {
                    long transferred = chunks.get(chunks.size() - 1);
                    int percentage = (int) (transferred * 100.0 / fileSize);
                    message.setText("Video herunterladen: " + percentage + " %");
                }
            }

            @Override
            protected void done() {
                downloadTask = null;
                transport.cancelDownload().setEnabled(false);
                transport.showDownloadCancellation(false);
                if (closed) {
                    return;
                }
                transport.download().setEnabled(true);
                try {
                    get();
                    message.setText("Video gespeichert: " + target.getFileName());
                } catch (CancellationException cancelled) {
                    message.setText("Video-Download abgebrochen");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    message.setText("Video-Download unterbrochen");
                } catch (ExecutionException failed) {
                    message.setText("Video-Download fehlgeschlagen: "
                            + (failed.getCause() == null ? "unbekannter Fehler"
                            : failed.getCause().getMessage()));
                }
            }
        };
        downloadTask = task;
        task.execute();
    }

    private void unavailable(String reason) {
        message.setText(reason);
        transport.play().setEnabled(false);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(false);
        transport.fullScreen().setEnabled(false);
        transport.audioOnly().setEnabled(false);
        transport.seekBar().reset();
    }

    public String status() {
        return message.getText();
    }

    @Override
    public void removeNotify() {
        try {
            if (!fullScreen.isTransferring()) {
                close();
            }
        } finally {
            super.removeNotify();
        }
    }

    @Override
    public void close() {
        if (slideDetection != null) {
            slideDetection.cancel(true);
        }
        if (closed) {
            return;
        }
        fullScreen.close();
        closed = true;
        progress.stop();
        seekWorker.shutdownNow();
        if (audioPreparation != null) {
            audioPreparation.cancel(true);
            audioPreparation = null;
        }
        transport.play().setEnabled(false);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(false);
        transport.fullScreen().setEnabled(false);
        transport.audioOnly().setEnabled(false);
        if (downloadTask != null) {
            downloadTask.cancel(true);
            downloadTask = null;
        }
        if (player != null) {
            player.mediaPlayer().controls().stop();
            player.release();
            player = null;
        }
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        if (audioBridge != null) {
            audioBridge.close();
            audioBridge = null;
        }
    }
}
