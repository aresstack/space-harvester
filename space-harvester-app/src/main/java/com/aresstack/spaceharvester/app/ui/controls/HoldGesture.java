package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * Gedrückt halten wie in Edge: Hält die linke Maustaste {@link #HOLD_MS} auf einem Element, läuft eine Aktion, etwa der
 * Verlauf am Zurück-Knopf ({@link BackButton}) oder die letzten Suchbegriffe am Suchfeld ({@link RecentTermsPopup}).
 * Loslassen, Verlassen oder Ziehen (Text markieren) vorher bricht ab.
 */
public final class HoldGesture extends MouseAdapter {

    /** So lange gedrückt halten, bis die Aktion läuft (wie in Edge). */
    public static final int HOLD_MS = 450;

    private final Component target;
    private final Timer timer;

    private HoldGesture(Component target, Runnable onHold) {
        this.target = target;
        timer = new Timer(HOLD_MS, e -> {
            if (target.isEnabled() && target.isShowing()) {
                onHold.run();
            }
        });
        timer.setRepeats(false);
    }

    /** Hängt das Gedrückthalten an {@code target}; {@code onHold} läuft auf dem Swing-Thread. */
    public static HoldGesture install(Component target, Runnable onHold) {
        HoldGesture gesture = new HoldGesture(Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(onHold, "onHold"));
        target.addMouseListener(gesture);
        target.addMouseMotionListener(gesture);
        return gesture;
    }

    /** Bricht ein laufendes Gedrückthalten ab, etwa wenn die Aktion schon auf anderem Weg läuft. */
    public void cancel() {
        timer.stop();
    }

    /** Ob gerade gedrückt gehalten wird. */
    boolean running() {
        return timer.isRunning();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger() && target.isEnabled()) {
            timer.restart();
        } else {
            timer.stop();
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        timer.stop();
    }

    @Override
    public void mouseExited(MouseEvent e) {
        timer.stop();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        timer.stop();
    }
}
