package com.aresstack.spaceharvester.app.ui.content.viewer;

import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.io.IOException;

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
    private final Timer progress = new Timer(500, event -> updateProgress());

    private EmbeddedMediaPlayerComponent player;
    private MediaStreamBridge bridge;
    private boolean started;
    private boolean closed;
    private boolean refreshingProgress;

    public VideoViewerPanel(String fileName, long size, String mediaType,
                            MediaStreamBridge.ChunkSource reader) {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        message.setHorizontalAlignment(SwingConstants.CENTER);
        message.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(message, BorderLayout.NORTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        controls.setOpaque(false);
        controls.add(play);
        controls.add(pause);
        controls.add(stop);
        controls.add(position);
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
        progress.start();

        if (size < 1) {
            unavailable("Die Dateigröße ist unbekannt; Streaming ist nicht möglich.");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            unavailable("Video-Wiedergabe ist ohne grafische Oberfläche nicht verfügbar.");
            return;
        }
        try {
            player = new EmbeddedMediaPlayerComponent();
            bridge = new MediaStreamBridge(reader, size, mediaType);
            add(player, BorderLayout.CENTER);
            message.setText("Video „" + fileName + "“ – zum Starten „Abspielen“ wählen.");
        } catch (RuntimeException | LinkageError | IOException failure) {
            if (player != null) {
                player.release();
                player = null;
            }
            unavailable("Für die Videowiedergabe wird eine lokale VLC-Installation benötigt.");
        }
    }

    private void play() {
        if (closed || player == null || bridge == null) {
            return;
        }
        try {
            if (!started) {
                player.mediaPlayer().media().play(bridge.address().toASCIIString());
                started = true;
            } else {
                player.mediaPlayer().controls().play();
            }
            message.setText("Wiedergabe über BSCW-Streaming");
        } catch (RuntimeException error) {
            message.setText("Video nicht abspielbar: " + error.getMessage());
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
        started = false;
        position.setValue(0);
    }

    private void updateProgress() {
        if (closed || !started || player == null || position.getValueIsAdjusting()) {
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
    public void close() {
        closed = true;
        progress.stop();
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
