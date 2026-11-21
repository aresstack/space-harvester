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
    /** Sprung zurück bzw. vor um die eingestellte Zeit (Angelo, 20.11.2026); den Tooltip setzt der Player. */
    private final JButton skipBack = iconButton("Zurück", new VideoSkipIcon(false));
    private final JButton skipForward = iconButton("Vor", new VideoSkipIcon(true));
    private final JButton previousSlide = iconButton("Vorige Folie", ComposerIcons.chevronLeft());
    private final JButton nextSlide = iconButton("Nächste Folie", ComposerIcons.chevronRight());
    private final JButton download = iconButton("Herunterladen", ComposerIcons.download());
    static final String CONVERT_PPTX = "In PPTX umwandeln";
    static final String CONVERT_PDF = "In PDF umwandeln";
    /**
     * „In PPTX umwandeln“ und daneben „In PDF umwandeln“ (Angelo 20.11.2026): nur das farbige Dateisymbol, der Name
     * steht im Tooltip; sichtbar, sobald der Player weiß, wer die Aufträge startet.
     */
    private final JButton convert = iconButton(CONVERT_PPTX, FileFormatIcon.powerPoint());
    private final JButton convertPdf = iconButton(CONVERT_PDF, FileFormatIcon.pdf());
    private final JButton cancelDownload = iconButton("Download abbrechen", ComposerIcons.stop());
    private final JButton fullScreen = iconButton("Vollbild", new VideoFullScreenIcon(false));
    private final JButton audioOnly = iconButton("Nur Audio", new VideoAudioModeIcon(false));
    private final VideoSeekBar seekBar;
    /**
     * Der Platz der Folienknöpfe ◀ ▶: zeigt die Knöpfe oder, solange „Folienwechsel erkennen“ wartet oder läuft, den
     * kleinen Ring; beide gleich breit, damit beim Wechsel nichts verrutscht (UX 21.11.2026).
     */
    private final JPanel slideSlot = new JPanel(new java.awt.CardLayout());
    private final JPanel slideRingSpace = new JPanel();
    private final com.aresstack.spaceharvester.app.ui.controls.BusyOverlay slideRing =
            new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(slideRingSpace);
    private static final String SLIDE_BUTTONS = "buttons";
    private static final String SLIDE_RING = "ring";
    private Runnable onSlideRing = () -> { };
    private boolean slidesKnown;

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
        playback.add(skipBack);
        playback.add(skipForward);
        JPanel slideButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        slideButtons.setOpaque(false);
        slideButtons.add(previousSlide);
        slideButtons.add(javax.swing.Box.createHorizontalStrut(5));
        slideButtons.add(nextSlide);
        slideRingSpace.setOpaque(false);
        slideRingSpace.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        slideRingSpace.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                onSlideRing.run();
            }
        });
        slideRing.setCompact(true);
        slideRing.setVeilColor(new Color(BACKGROUND.getRGB() & 0xFFFFFF, true));
        slideRing.setHighlight(FOREGROUND);
        slideSlot.setOpaque(false);
        slideSlot.add(slideButtons, SLIDE_BUTTONS);
        slideSlot.add(slideRing, SLIDE_RING);
        slideSlot.setVisible(false);
        playback.add(slideSlot);
        JPanel transfers = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        transfers.setOpaque(false);
        download.setText("Herunterladen");
        download.setHorizontalTextPosition(SwingConstants.RIGHT);
        download.setIconTextGap(7);
        for (JButton format : new JButton[] {convert, convertPdf}) {
            // Das Symbol ist höher als die Strichsymbole: weniger Rand, damit die Knöpfe gleich hoch bleiben.
            format.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
            format.setVisible(false);
        }
        cancelDownload.setText("Abbrechen");
        cancelDownload.setHorizontalTextPosition(SwingConstants.RIGHT);
        cancelDownload.setIconTextGap(7);
        cancelDownload.setVisible(false);
        audioOnly.setText("Nur Audio");
        audioOnly.setHorizontalTextPosition(SwingConstants.RIGHT);
        audioOnly.setIconTextGap(7);
        transfers.add(audioOnly);
        transfers.add(download);
        transfers.add(convert);
        transfers.add(convertPdf);
        transfers.add(cancelDownload);
        transfers.add(fullScreen);
        buttons.add(playback, BorderLayout.WEST);
        buttons.add(transfers, BorderLayout.EAST);

        add(buttons, BorderLayout.NORTH);
        add(seekBar, BorderLayout.CENTER);
        pause.setEnabled(false);
        stop.setEnabled(false);
    }

    JButton skipBack() {
        return skipBack;
    }

    JButton skipForward() {
        return skipForward;
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
        slidesKnown = any;
        if (any) {
            String found = changes.size() + " Folienwechsel erkannt";
            slideButtons("Vorige Folie (" + found + ")", "Nächste Folie (" + found + ")", true);
        } else {
            slideSlot.setVisible(false);
        }
        revalidate();
        repaint();
    }

    /**
     * „Folienwechsel erkennen“ wartet oder läuft im Hintergrund: der kleine Ring an der Stelle der Folienknöpfe, mit
     * {@code tip}; ein Klick darauf führt {@code onClick} aus (etwa zur Auftragsverwaltung).
     */
    void slidesDetecting(String tip, Runnable onClick) {
        onSlideRing = onClick == null ? () -> { } : onClick;
        slideRingSpace.setToolTipText(tip);
        slideRing.getAccessibleContext().setAccessibleName(tip);
        ((java.awt.CardLayout) slideSlot.getLayout()).show(slideSlot, SLIDE_RING);
        slideSlot.setVisible(true);
        revalidate();
        repaint();
    }

    /** Die Erkennung kam zu keinem Ergebnis: die Folienknöpfe gesperrt, {@code reason} im Tooltip. */
    void slidesUnavailable(String reason) {
        if (!slidesKnown) {
            slideButtons(reason, reason, false);
        }
    }

    /** Weder Marken noch Erkennung: kein Platz für Folienknöpfe. */
    void noSlides() {
        if (!slidesKnown) {
            slideSlot.setVisible(false);
            revalidate();
            repaint();
        }
    }

    private void slideButtons(String previousTip, String nextTip, boolean enabled) {
        previousSlide.setToolTipText(previousTip);
        nextSlide.setToolTipText(nextTip);
        previousSlide.setEnabled(enabled);
        nextSlide.setEnabled(enabled);
        ((java.awt.CardLayout) slideSlot.getLayout()).show(slideSlot, SLIDE_BUTTONS);
        slideSlot.setVisible(true);
        revalidate();
        repaint();
    }

    /** Der kleine Ring der Folienerkennung, für {@code controls.BackgroundWait}. */
    com.aresstack.spaceharvester.app.ui.controls.BusyOverlay slideRing() {
        return slideRing;
    }

    /** Der Platz der Folienknöpfe (für Tests). */
    JPanel slideSlot() {
        return slideSlot;
    }

    /** Ob der Ring statt der Folienknöpfe steht (für Tests). */
    boolean slideRingShown() {
        return slideSlot.isVisible() && slideRing.isVisible();
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

    JButton convert() {
        return convert;
    }

    JButton convertPdf() {
        return convertPdf;
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
