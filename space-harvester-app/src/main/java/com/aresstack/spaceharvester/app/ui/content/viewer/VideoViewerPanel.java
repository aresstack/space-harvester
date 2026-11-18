package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Play remote video through an authenticated byte-range bridge without giving VLC source credentials.
 * Start playback only on an explicit user action and release every native resource on page change.
 */
public final class VideoViewerPanel extends JPanel implements AutoCloseable {

    private final JLabel message = new JLabel(" ");
    private final JSlider position = new JSlider(0, 1000);
    private final JButton play = new JButton("Abspielen");
    private final JButton pause = new JButton("Pause");
    private final JButton stop = new JButton("Stopp");
    private final JButton download = new JButton("Herunterladen");
    private final JButton cancelDownload = new JButton("Download abbrechen");
    private final Timer progress = new Timer(500, event -> updateProgress());

    private EmbeddedMediaPlayerComponent player;
    private MediaStreamBridge bridge;
    private boolean started;
    private boolean actuallyPlaying;
    private long startRequestedAtNanos;
    private static final long START_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    private boolean closed;
    private boolean refreshingProgress;
    private final String fileName;
    private final long fileSize;
    private final MediaStreamBridge.ChunkSource reader;
    private SwingWorker<Void, Long> downloadTask;

    public VideoViewerPanel(String fileName, long size, String mediaType,
                            MediaStreamBridge.ChunkSource reader) {
        super(new BorderLayout(0, 6));
        this.fileName = fileName;
        this.fileSize = size;
        this.reader = reader;
        setOpaque(false);
        message.setHorizontalAlignment(SwingConstants.CENTER);
        message.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(message, BorderLayout.NORTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        controls.setOpaque(false);
        play.setIcon(ComposerIcons.play());
        pause.setIcon(ComposerIcons.pause());
        stop.setIcon(ComposerIcons.stop());
        play.setToolTipText("Video abspielen oder fortsetzen");
        pause.setToolTipText("Wiedergabe pausieren");
        stop.setToolTipText("Wiedergabe stoppen");
        pause.setEnabled(false);
        stop.setEnabled(false);
        controls.add(play);
        controls.add(pause);
        controls.add(stop);
        controls.add(position);
        controls.add(download);
        controls.add(cancelDownload);
        cancelDownload.setEnabled(false);
        add(controls, BorderLayout.SOUTH);
        position.setPreferredSize(new java.awt.Dimension(250, 28));
        position.addChangeListener(event -> {
            if (!refreshingProgress && !position.getValueIsAdjusting() && started && player != null) {
                player.mediaPlayer().controls().setPosition(position.getValue() / 1000f);
            }
        });
        play.addActionListener(event -> play());
        pause.addActionListener(event -> pause());
        stop.addActionListener(event -> stop());
        download.addActionListener(event -> chooseDownload());
        cancelDownload.addActionListener(event -> {
            if (downloadTask != null) {
                downloadTask.cancel(true);
            }
        });
        progress.start();

        if (size < 1) {
            download.setEnabled(false);
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
                public void error(MediaPlayer mediaPlayer) {
                    SwingUtilities.invokeLater(() -> playbackFailed(
                            bridge != null && bridge.lastFailure() != null
                                    ? bridge.lastFailure()
                                    : "VLC konnte die Videodaten nicht abspielen. Netzwerk/Codec prüfen."));
                }
            });
            add(player, BorderLayout.CENTER);
            message.setText("Video „" + fileName + "“ – zum Starten „Abspielen“ wählen.");
        } catch (RuntimeException | LinkageError | IOException failure) {
            if (player != null) {
                player.release();
                player = null;
            }
            unavailable("VLC kann nicht initialisiert werden: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " – " + failure.getMessage()));
        }
    }

    /** Ask for a destination only after the user explicitly chooses to download the video. */
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

    /** Download on a worker thread; publish no partially downloaded video under its final filename. */
    private void startDownload(Path target) {
        download.setEnabled(false);
        cancelDownload.setEnabled(true);
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
                cancelDownload.setEnabled(false);
                if (closed) {
                    return;
                }
                download.setEnabled(true);
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

    private void play() {
        if (closed || player == null || bridge == null) {
            return;
        }
        try {
            play.setEnabled(false);
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
        } catch (RuntimeException error) {
            playbackFailed("Video nicht abspielbar: " + error.getMessage());
        }
    }

    private void playbackStarted() {
        if (closed) {
            return;
        }
        started = true;
        actuallyPlaying = true;
        startRequestedAtNanos = 0;
        play.setEnabled(false);
        pause.setEnabled(true);
        stop.setEnabled(true);
        message.setText("Wiedergabe über BSCW-Streaming");
    }

    private void playbackPaused() {
        if (closed) {
            return;
        }
        actuallyPlaying = false;
        startRequestedAtNanos = 0;
        play.setEnabled(true);
        pause.setEnabled(false);
        stop.setEnabled(true);
        message.setText("Pausiert");
    }

    private void playbackStopped() {
        if (closed) {
            return;
        }
        actuallyPlaying = false;
        started = false;
        startRequestedAtNanos = 0;
        play.setEnabled(true);
        pause.setEnabled(false);
        stop.setEnabled(false);
        position.setValue(0);
        message.setText("Wiedergabe gestoppt");
    }

    private void playbackFailed(String reason) {
        if (closed) {
            return;
        }
        actuallyPlaying = false;
        started = false;
        startRequestedAtNanos = 0;
        play.setEnabled(true);
        pause.setEnabled(false);
        stop.setEnabled(false);
        message.setText(reason);
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

    private void updateProgress() {
        if (closed) {
            return;
        }
        if (startRequestedAtNanos != 0 && !actuallyPlaying
                && System.nanoTime() - startRequestedAtNanos > START_TIMEOUT_NANOS) {
            playbackFailed("VLC startet nicht. Installation, Videoformat und BSCW-Verbindung prüfen.");
            return;
        }
        if (!actuallyPlaying || player == null || position.getValueIsAdjusting()) {
            return;
        }
        try {
            float value = player.mediaPlayer().status().position();
            if (value >= 0 && value <= 1) {
                refreshingProgress = true;
                try {
                    position.setValue((int) Math.round(value * 1000));
                } finally {
                    refreshingProgress = false;
                }
            }
        } catch (RuntimeException error) {
            message.setText("Wiedergabestatus nicht verfügbar.");
        }
    }

    private void unavailable(String reason) {
        message.setText(reason);
        play.setEnabled(false);
        pause.setEnabled(false);
        stop.setEnabled(false);
        position.setEnabled(false);
    }

    public String status() {
        return message.getText();
    }

    @Override
    public void removeNotify() {
        try {
            close();
        } finally {
            super.removeNotify();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        progress.stop();
        play.setEnabled(false);
        pause.setEnabled(false);
        stop.setEnabled(false);
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
