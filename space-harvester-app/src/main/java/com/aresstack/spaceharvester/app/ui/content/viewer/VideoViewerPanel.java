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

    private final JLabel message = new JLabel(" ");
    private final VideoTransportBar transport = new VideoTransportBar(this::seek);
    private final VideoFullScreenController fullScreen =
            new VideoFullScreenController(this, this::reattachVideoSurface, transport::setFullScreen);
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
    private SwingWorker<Void, Long> downloadTask;
    private volatile boolean closed;
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
        transport.play().addActionListener(event -> play());
        transport.pause().addActionListener(event -> pause());
        transport.stop().addActionListener(event -> stop());
        transport.download().addActionListener(event -> chooseDownload());
        transport.cancelDownload().addActionListener(event -> cancelDownload());
        transport.fullScreen().addActionListener(event -> fullScreen.toggle());
        transport.fullScreen().setEnabled(false);
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
            add(player, BorderLayout.CENTER);
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

    /** Rebind the existing video player to its new native window after entering or leaving full-screen. */
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
                        bridge != null && bridge.lastFailure() != null
                                ? bridge.lastFailure()
                                : "VLC konnte die Videodaten nicht abspielen. Netzwerk/Codec prüfen."));
            }
        });
    }

    private void play() {
        if (closed || player == null || bridge == null) {
            return;
        }
        try {
            transport.play().setEnabled(false);
            startRequestedAtNanos = System.nanoTime();
            message.setText("Video wird gestartet …");
            boolean accepted = true;
            if (started) {
                player.mediaPlayer().controls().play();
            } else {
                accepted = player.mediaPlayer().media().play(bridge.address().toASCIIString());
            }
            if (!accepted) {
                playbackFailed("VLC hat den Wiedergabestart abgelehnt.");
            } else {
                started = true;
            }
        } catch (RuntimeException failure) {
            playbackFailed("Video nicht abspielbar: " + failure.getMessage());
        }
    }

    private void pause() {
        if (!closed && started && player != null) {
            player.mediaPlayer().controls().pause();
        }
    }

    private void stop() {
        if (!closed && player != null) {
            player.mediaPlayer().controls().stop();
        }
        playbackStopped();
    }

    /** Commit a seek only after the user releases the timeline; execute libVLC's potentially slow call off the EDT. */
    private void seek(double share) {
        if (closed || !started || player == null) {
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
                    if (!closed && player != null) {
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
        started = true;
        actuallyPlaying = true;
        startRequestedAtNanos = 0;
        transport.play().setEnabled(false);
        transport.pause().setEnabled(true);
        transport.stop().setEnabled(true);
        message.setText("Wiedergabe über BSCW-Streaming");
    }

    private void playbackPaused() {
        if (closed) {
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
        if (closed) {
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
        if (closed) {
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
        if (closed || player == null || !started) {
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
                    message.setText(actuallyPlaying ? "Wiedergabe über BSCW-Streaming" : "Pausiert");
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
        if (closed) {
            return;
        }
        fullScreen.close();
        closed = true;
        progress.stop();
        seekWorker.shutdownNow();
        transport.play().setEnabled(false);
        transport.pause().setEnabled(false);
        transport.stop().setEnabled(false);
        transport.fullScreen().setEnabled(false);
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
    }
}
