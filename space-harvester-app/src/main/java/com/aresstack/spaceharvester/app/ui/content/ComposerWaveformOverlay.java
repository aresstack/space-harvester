package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Die Wellenform der Aufnahme über dem Editor, aus askai-java8 (arch, {@code ComposerWaveformOverlay}): kein
 * Hintergrund, nur die Live-Balken (dunkles Comic-Grün, neueste rechts) als Berge von einer flachen Grundlinie, damit
 * Text und Cursor sichtbar und bearbeitbar bleiben. Dazu die rote Signalschwelle, die der Benutzer während der
 * Aufnahme hoch- und runterzieht: darunter gilt der Pegel als Hintergrundrauschen (blasse Balken), darüber als
 * Sprache. Ein Klick auf die Balken schaltet zwischen „erst komplett“ und „live mitschreiben“ um (Angelo 26.11.2026,
 * wie ChatGPT). Nur das Greifband der Linie und die Balken nehmen Mausereignisse an, alles andere geht an den Editor.
 */
final class ComposerWaveformOverlay extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final Color WAVE = ResearchUiPalette.WAVE_GREEN;
    private static final Color GATE = ResearchUiPalette.DANGER_RED;
    private static final int BAR_WIDTH = 3;
    private static final int BAR_GAP = 3;
    private static final int PAD_V = 6;
    private static final int GRAB_BAND = 5;
    private static final int HISTORY = 120;

    private final int[] levels = new int[HISTORY]; // Ringpuffer der letzten Pegel (0–100)
    private int levelCount;
    private int levelHead;
    private boolean active;
    private int thresholdPercent = 8;
    private boolean dragging;
    private boolean live;
    private String liveName = "";
    private String completeName = "";
    private Runnable toggle = () -> { };

    ComposerWaveformOverlay() {
        setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (onGate(event.getY())) {
                    dragging = true;
                    applyDrag(event.getY());
                }
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (!onGate(event.getY()) && onBars(event.getX(), event.getY())) {
                    toggle.run();
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragging) {
                    applyDrag(event.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragging = false;
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                Cursors.set(ComposerWaveformOverlay.this, Cursor.getPredefinedCursor(onGate(event.getY())
                        ? Cursor.N_RESIZE_CURSOR : Cursor.HAND_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        setToolTipText("");
    }

    /** Die gewählte Art der Spracheingabe und ihre beiden Namen (für den Tooltip). */
    void setLive(boolean value, String liveLabel, String completeLabel) {
        live = value;
        liveName = liveLabel;
        completeName = completeLabel;
        repaint();
    }

    /** Wird bei einem Klick auf die Balken ausgeführt. */
    void onToggle(Runnable action) {
        toggle = action == null ? () -> { } : action;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (onGate(event.getY())) {
            return "Signalschwelle – ziehen: alles darunter gilt als Hintergrundrauschen";
        }
        return "Spracheingabe: " + (live ? liveName : completeName) + " – Klick: "
                + (live ? completeName : liveName);
    }

    /** Aufnahme läuft: einschalten leert den Verlauf, ausschalten blendet alles aus. */
    void setActive(boolean value) {
        if (active != value) {
            active = value;
            levelCount = 0;
            levelHead = 0;
            repaint();
        }
    }

    boolean isActive() {
        return active;
    }

    /** Ein Live-Pegel (0–100, dB-Skala). */
    void pushLevel(int level) {
        levels[levelHead] = Math.max(0, Math.min(100, level));
        levelHead = (levelHead + 1) % levels.length;
        levelCount = Math.min(levelCount + 1, levels.length);
        if (active) {
            repaint();
        }
    }

    int thresholdPercent() {
        return thresholdPercent;
    }

    /** Nur das Greifband der Schwelle ist bedienbar; der Rest gehört dem Editor. */
    @Override
    public boolean contains(int x, int y) {
        return active && (onGate(y) || onBars(x, y));
    }

    private boolean onGate(int y) {
        return Math.abs(y - thresholdY()) <= GRAB_BAND;
    }

    /** Die Fläche der gezeigten Balken: von der Grundlinie bis oben, so breit wie die Balken reichen. */
    private boolean onBars(int x, int y) {
        int slots = Math.max(1, (getWidth() - 8) / (BAR_WIDTH + BAR_GAP));
        int shown = Math.min(levelCount, slots);
        int left = getWidth() - 8 - shown * (BAR_WIDTH + BAR_GAP);
        return shown > 0 && x >= left && x <= getWidth() && y >= PAD_V && y <= getHeight() - PAD_V;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (!active || getHeight() <= 0) {
            return; // nie ein Hintergrund, nur die Balken während der Aufnahme
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int usable = Math.max(1, getHeight() - 2 * PAD_V);
            int baselineY = getHeight() - PAD_V;
            int slots = Math.max(1, (getWidth() - 8) / (BAR_WIDTH + BAR_GAP));
            int shown = Math.min(levelCount, slots);
            for (int i = 0; i < shown; i++) {
                int sampleIndex = (levelHead - shown + i + levels.length * 2) % levels.length;
                int level = levels[sampleIndex];
                int barHeight = Math.max(2, level * usable / 100);
                int x = getWidth() - 8 - (shown - i) * (BAR_WIDTH + BAR_GAP);
                boolean aboveGate = level >= thresholdPercent;
                g2.setColor(withAlpha(WAVE, aboveGate ? 205 : 70));
                g2.fillRoundRect(x, baselineY - barHeight, BAR_WIDTH, barHeight, BAR_WIDTH, BAR_WIDTH);
            }
            int gateY = thresholdY();
            g2.setColor(withAlpha(GATE, dragging ? 235 : 165));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                    new float[] {5f, 4f}, 0f));
            g2.drawLine(4, gateY, getWidth() - 4, gateY);
        } finally {
            g2.dispose();
        }
    }

    private int thresholdY() {
        int usable = Math.max(1, getHeight() - 2 * PAD_V);
        return getHeight() - PAD_V - thresholdPercent * usable / 100;
    }

    private void applyDrag(int y) {
        int usable = Math.max(1, getHeight() - 2 * PAD_V);
        int percent = Math.max(0, Math.min(95, (getHeight() - PAD_V - y) * 100 / usable));
        if (percent != thresholdPercent) {
            thresholdPercent = percent;
            repaint();
        }
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
