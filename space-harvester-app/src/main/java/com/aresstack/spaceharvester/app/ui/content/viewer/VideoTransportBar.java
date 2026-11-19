package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.function.DoubleConsumer;

/**
 * Keep playback controls in one opaque dock beneath VLC's heavyweight AWT video surface.
 * Avoid translucent Swing overlays above native Canvas, which can flicker or obscure buttons.
 */
final class VideoTransportBar extends JPanel {

    private static final Color BACKGROUND = new Color(0x171E27);
    private static final Color BUTTON = new Color(0x253241);
    private static final Color FOREGROUND = new Color(0xF1F5F9);

    private final JButton play = iconButton("Abspielen", ComposerIcons.play());
    private final JButton pause = iconButton("Pausieren", ComposerIcons.pause());
    private final JButton stop = iconButton("Stoppen", ComposerIcons.stop());
    private final JButton previousSlide = iconButton("Vorige Folie", ComposerIcons.chevronLeft());
    private final JButton nextSlide = iconButton("Nächste Folie", ComposerIcons.chevronRight());
    private final JButton download = iconButton("Herunterladen", ComposerIcons.download());
    private final JButton cancelDownload = iconButton("Download abbrechen", ComposerIcons.stop());
    private final JButton fullScreen = iconButton("Vollbild", new VideoFullScreenIcon(false));
    private final JButton audioOnly = iconButton("Nur Audio", new VideoAudioModeIcon(false));
    private final VideoSeekBar seekBar;

    VideoTransportBar(DoubleConsumer seek) {
        super(new BorderLayout(0, 2));
        setOpaque(true);
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(5, 12, 4, 12));
        seekBar = new VideoSeekBar(seek);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        JPanel playback = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        playback.setOpaque(false);
        playback.add(play);
        playback.add(pause);
        playback.add(stop);
        playback.add(previousSlide);
        playback.add(nextSlide);
        previousSlide.setVisible(false);
        nextSlide.setVisible(false);
        JPanel transfers = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        transfers.setOpaque(false);
        download.setText("Herunterladen");
        download.setHorizontalTextPosition(SwingConstants.RIGHT);
        download.setIconTextGap(7);
        cancelDownload.setText("Abbrechen");
        cancelDownload.setHorizontalTextPosition(SwingConstants.RIGHT);
        cancelDownload.setIconTextGap(7);
        cancelDownload.setVisible(false);
        audioOnly.setText("Nur Audio");
        audioOnly.setHorizontalTextPosition(SwingConstants.RIGHT);
        audioOnly.setIconTextGap(7);
        transfers.add(audioOnly);
        transfers.add(download);
        transfers.add(cancelDownload);
        transfers.add(fullScreen);
        buttons.add(playback, BorderLayout.WEST);
        buttons.add(transfers, BorderLayout.EAST);

        add(buttons, BorderLayout.NORTH);
        add(seekBar, BorderLayout.CENTER);
        pause.setEnabled(false);
        stop.setEnabled(false);
    }

    JButton previousSlide() {
        return previousSlide;
    }

    JButton nextSlide() {
        return nextSlide;
    }

    /** Show slide marks and the slide buttons once slide changes are known. */
    void slides(java.util.List<Long> changes) {
        seekBar.slides(changes);
        boolean any = changes != null && !changes.isEmpty();
        previousSlide.setVisible(any);
        nextSlide.setVisible(any);
        if (any) {
            String found = changes.size() + " Folienwechsel erkannt";
            previousSlide.setToolTipText("Vorige Folie (" + found + ")");
            nextSlide.setToolTipText("Nächste Folie (" + found + ")");
        }
        revalidate();
        repaint();
    }

    JButton play() {
        return play;
    }

    JButton pause() {
        return pause;
    }

    JButton stop() {
        return stop;
    }

    JButton download() {
        return download;
    }

    JButton cancelDownload() {
        return cancelDownload;
    }

    JButton fullScreen() {
        return fullScreen;
    }

    JButton audioOnly() {
        return audioOnly;
    }

    void setAudioOnly(boolean active) {
        String label = active ? "Video anzeigen" : "Nur Audio";
        VideoAudioModeIcon icon = new VideoAudioModeIcon(active);
        audioOnly.setText(label);
        audioOnly.setIcon(icon);
        audioOnly.setDisabledIcon(icon);
        audioOnly.setToolTipText(active ? "Videobild wieder einschalten" : "Videobild ausschalten; Ton weiter abspielen");
        audioOnly.getAccessibleContext().setAccessibleName(label);
    }

    void setFullScreen(boolean active) {
        String label = active ? "Vollbild beenden (Esc)" : "Vollbild (Doppelklick)";
        VideoFullScreenIcon icon = new VideoFullScreenIcon(active);
        fullScreen.setIcon(icon);
        fullScreen.setDisabledIcon(icon);
        fullScreen.setToolTipText(label);
        fullScreen.getAccessibleContext().setAccessibleName(label);
    }

    VideoSeekBar seekBar() {
        return seekBar;
    }

    void showDownloadCancellation(boolean active) {
        cancelDownload.setVisible(active);
        revalidate();
        repaint();
    }

    private static JButton iconButton(String label, javax.swing.Icon icon) {
        JButton button = new JButton(icon);
        button.setUI(new BasicButtonUI());
        button.setBackground(BUTTON);
        button.setForeground(FOREGROUND);
        button.setDisabledIcon(icon);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setToolTipText(label);
        button.getAccessibleContext().setAccessibleName(label);
        button.addPropertyChangeListener("enabled", event ->
                button.setForeground(button.isEnabled() ? FOREGROUND : new Color(0x788796)));
        return button;
    }
}
