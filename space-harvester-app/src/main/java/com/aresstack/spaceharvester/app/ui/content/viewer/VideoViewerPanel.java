package com.aresstack.spaceharvester.app.ui.content.viewer;


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

/**
 * Play BSCW videos through an authenticated range bridge without exposing source credentials to VLC.
 * Keep native video and Swing controls in separate opaque regions to prevent AWT mixing artifacts.
 */
public final class VideoViewerPanel extends JPanel implements AutoCloseable {

    private static final long START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long SEEK_FEEDBACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
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
    private final Timer progress = new Timer(350, event -> updateProgress());
    private final ExecutorService seekWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "space-harvester-video-seek");
        thread.setDaemon(true);
        return thread;
    });
    private final String fileName;
    private final long fileSize;
    private final MediaStreamBridge.ChunkSource reader;

    private EmbeddedMediaPlayerComponent player;
    private MediaStreamBridge bridge;
    private MediaStreamBridge audioBridge;
    private SwingWorker<Mp4AudioStream, Void> audioPreparation;
    /** Reads the MP4 index once after the first playback start and marks the slide changes. */
    private SwingWorker<SlideIndex, Void> slideDetection;

    /** The slide changes and the length of a video, read from its MP4 index. */
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

    public VideoViewerPanel(String fileName, long size, String mediaType,
                            MediaStreamBridge.ChunkSource reader) {
        super(new BorderLayout(0, 0));
        this.fileName = fileName;
        this.fileSize = size;
        this.reader = reader;
        setOpaque(true);
        setBackground(new java.awt.Color(0x171E27));
        message.setHorizontalAlignment(SwingConstants.CENTER);
        message.setOpaque(true);
        message.setBackground(new java.awt.Color(0x171E27));
        message.setForeground(new java.awt.Color(0xE6EDF4));
        message.setBorder(BorderFactory.createEmptyBorder(9, 8, 9, 8));
        add(message, BorderLayout.NORTH);
        add(transport, BorderLayout.SOUTH);
        display.setOpaque(true);
        display.setBackground(new Color(0x171E27));
        audioDisplay.setOpaque(true);
        audioDisplay.setBackground(new Color(0x171E27));
        JLabel audioTitle = new JLabel("Nur Audio – Videobild ausgeschaltet", SwingConstants.CENTER);
        audioTitle.setForeground(new Color(0xD6E8F6));
        audioTitle.setFont(audioTitle.getFont().deriveFont(Font.BOLD, 17f));
        audioDisplay.add(audioTitle, BorderLayout.CENTER);
        display.add(audioDisplay, AUDIO_CARD);
        add(display, BorderLayout.CENTER);
        transport.play().addActionListener(event -> play());
        transport.pause().addActionListener(event -> pause());
        transport.stop().addActionListener(event -> stop());
        transport.download().addActionListener(event -> chooseDownload());
        transport.cancelDownload().addActionListener(event -> cancelDownload());
        transport.fullScreen().addActionListener(event -> fullScreen.toggle());
        transport.audioOnly().addActionListener(event -> toggleAudioOnly());
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
            bridge = new MediaStreamBridge(reader, size, mediaType);
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
                return Mp4AudioOnlySource.open(reader, fileSize);
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
                    audioBridge = new MediaStreamBridge(audioSource, audioSource.length(), "video/mp4");
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
        return audioOnly ? "Nur Audio über BSCW-Streaming" : "Wiedergabe über BSCW-Streaming";
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

    /** Commit a seek only after the user releases the timeline; execute libVLC's potentially slow call off the EDT. */
    private void seek(double share) {
        if (closed || !started || player == null || audioPreparation != null) {
            return;
        }
        if (!transport.seekBar().canSeek()) {
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
        try {
            seekWorker.execute(() -> {
                try {
                    if (!closed && player != null && audioPreparation == null) {
                        if (duration > 0) {
                            player.mediaPlayer().controls().setTime(target);
                        } else {
                            player.mediaPlayer().controls().setPosition((float) requestedShare);
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

    private void playbackStarted() {
        if (closed) {
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
     * background: their marks appear before anything is played, and whoever listens ({@link #onSlides}) keeps a
     * still frame of every slide. Other formats simply show no marks.
     */
    public void findSlides() {
        boolean mp4 = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
        if (slideDetection != null || knownSlides != null || closed || !mp4 || fileSize < 16) {
            return;
        }
        slideDetection = new SwingWorker<SlideIndex, Void>() {
            @Override
            protected SlideIndex doInBackground() throws IOException {
                return new SlideIndex(SlideChanges.load(reader, fileSize), SlideChanges.durationMillis(reader, fileSize));
            }

            @Override
            protected void done() {
                if (closed || isCancelled()) {
                    return;
                }
                try {
                    SlideIndex index = get();
                    transport.slides(index.changes());
                    if (!started && index.durationMillis() > 0) {
                        transport.seekBar().update(0, index.durationMillis());
                    }
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

    /** Slide changes already known when the page opened, or {@code null}. */
    private java.util.List<Long> knownSlides;

    /**
     * Show the slide changes the page already knows (its slide attachments) as soon as it opens, instead of
     * {@link #findSlides}; whoever listens ({@link #onSlides}) gets them to complete missing still frames. Without a
     * known length the length comes from the MP4 header once.
     *
     * @param changes        slide changes in milliseconds, without the first slide at 0
     * @param durationMillis length of the video, or 0 if unknown
     */
    public void knownSlides(java.util.List<Long> changes, long durationMillis) {
        if (closed || started || changes.isEmpty()) {
            return;
        }
        knownSlides = java.util.List.copyOf(changes);
        transport.slides(knownSlides);
        onSlides.accept(knownSlides);
        if (durationMillis > 0) {
            transport.seekBar().update(0, durationMillis);
            return;
        }
        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() throws IOException {
                return SlideChanges.durationMillis(reader, fileSize);
            }

            @Override
            protected void done() {
                try {
                    long duration = get();
                    if (!closed && !started && duration > 0) {
                        transport.seekBar().update(0, duration);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException unavailable) {
                    // Without a readable header the marks appear once playback reports the length.
                }
            }
        }.execute();
    }

    /** The length of the video as VLC reports it, or 0 while unknown. */
    public long durationMillis() {
        return transport.seekBar().durationMillis();
    }

    /** Whoever wants the slide changes once they are found, e.g. to keep a still frame of every slide. */
    private java.util.function.Consumer<java.util.List<Long>> onSlides = changes -> { };

    /** {@code listener} gets the slide changes in milliseconds once, on the EDT, when they are found or known. */
    public void onSlides(java.util.function.Consumer<java.util.List<Long>> listener) {
        onSlides = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Jump to the next slide, or to the start of the current or previous one like a player's previous button. */
    private void jumpToSlide(boolean forward) {
        java.util.List<Long> changes = transport.seekBar().slides();
        long duration = transport.seekBar().durationMillis();
        long position = transport.seekBar().displayedMillis();
        if (changes.isEmpty() || duration <= 0 || position < 0) {
            return;
        }
        long target = forward ? SlideChanges.next(changes, position) : SlideChanges.previous(changes, position);
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
        actuallyPlaying = false;
        started = false;
        startRequestedAtNanos = 0;
        seekRequestedAtNanos = 0;
        transport.play().setEnabled(true);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(false);
        transport.seekBar().reset();
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
        transport.seekBar().reset();
        message.setText(reason);
    }

    /** Keep the timeline responsive while native playback fetches a newly requested range. */
    private void updateProgress() {
        if (closed || player == null || !started || audioPreparation != null) {
            return;
        }
        if (startRequestedAtNanos != 0 && !actuallyPlaying
                && System.nanoTime() - startRequestedAtNanos > START_TIMEOUT_NANOS) {
            playbackFailed("VLC startet nicht. Installation, Videoformat und BSCW-Verbindung prüfen.");
            return;
        }
        try {
            long duration = player.mediaPlayer().status().length();
            long current = player.mediaPlayer().status().time();
            float share = player.mediaPlayer().status().position();
            boolean hasFraction = share >= 0 && share <= 1;
            if (seekRequestedAtNanos != 0) {
                boolean reached = duration > 0
                        ? Math.abs(current - Math.round(requestedSeekShare * duration)) <= 2500
                        : hasFraction && Math.abs(share - requestedSeekShare) < 0.02;
                if (reached) {
                    seekRequestedAtNanos = 0;
                    message.setText(actuallyPlaying ? playbackMessage() : "Pausiert");
                } else if (System.nanoTime() - seekRequestedAtNanos < SEEK_FEEDBACK_TIMEOUT_NANOS) {
                    if (!transport.seekBar().isDragging()) {
                        showSeekProgress(duration, current, hasFraction ? share : -1, true);
                    }
                    return;
                } else {
                    seekRequestedAtNanos = 0;
                    message.setText("Position nicht erreicht. BSCW-Verbindung oder Seek-Fähigkeit prüfen.");
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
        JFileChooser chooser = new JFileChooser();
        String safeName = fileName == null ? "video.mp4"
                : fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        chooser.setSelectedFile(new java.io.File(safeName.isBlank() ? "video.mp4" : safeName));
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
