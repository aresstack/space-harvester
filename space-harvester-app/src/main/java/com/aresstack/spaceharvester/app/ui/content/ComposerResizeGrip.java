package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JComponent;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Der Ziehgriff am oberen Rand des Composers: nur sichtbar, solange die Maus über dem Composer steht.
 * Ziehen nach oben oder unten legt die Normalhöhe des leeren Editors fest; lange Texte wachsen darüber hinaus.
 */
final class ComposerResizeGrip extends JComponent {
    static final int HEIGHT = 9;
    /** Die Fläche, auf der der Griff reagiert: nur die Mitte der oberen Linie. */
    private static final int AREA_WIDTH = 72;
    private static final int BAR_WIDTH = 36;
    private static final int BAR_HEIGHT = 4;

    private boolean shown;
    private boolean dragging;
    private int pressedScreenY;
    private int pressedHeight;

    ComposerResizeGrip(IntSupplier currentHeight, IntConsumer resized, Runnable finished) {
        setOpaque(false);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        setToolTipText("Ziehen: Normalgröße des Eingabefelds festlegen");
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragging = true;
                pressedScreenY = e.getYOnScreen();
                pressedHeight = currentHeight.getAsInt();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging) {
                    // Der Composer steht unten: nach oben ziehen macht ihn höher.
                    resized.accept(pressedHeight + pressedScreenY - e.getYOnScreen());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragging) {
                    dragging = false;
                    finished.run();
                }
            }
        };
        addMouseListener(drag);
        addMouseMotionListener(drag);
    }

    /** Zeigt den Griff, solange die Maus über dem Composer steht oder gerade gezogen wird. */
    void setShown(boolean hovered) {
        boolean visible = hovered || dragging;
        if (visible != shown) {
            shown = visible;
            repaint();
        }
    }

    boolean shown() {
        return shown;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(AREA_WIDTH, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (!shown) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(dragging ? Comic.BLUE : Comic.LINE);
            g2.fillRoundRect((getWidth() - BAR_WIDTH) / 2, (getHeight() - BAR_HEIGHT) / 2,
                    BAR_WIDTH, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
        } finally {
            g2.dispose();
        }
    }
}
