package com.aresstack.spaceharvester.app.ui.deck;

import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Recognize two isolated Shift taps in one visible workspace, without intercepting shortcuts. */
final class DoubleShiftShortcut implements KeyEventDispatcher {
    private static final long MAX_INTERVAL_MS = 500;
    private final JComponent owner;
    private final Runnable action;
    private KeyboardFocusManager manager;
    private boolean shiftPressed;
    private long firstRelease = -1;

    DoubleShiftShortcut(JComponent owner, Runnable action) {
        this.owner = owner;
        this.action = action;
    }

    void attach() {
        if (manager == null) {
            manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            manager.addKeyEventDispatcher(this);
        }
    }

    void detach() {
        if (manager != null) {
            manager.removeKeyEventDispatcher(this);
            manager = null;
        }
        reset();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (manager == null || !owner.isShowing() || manager.getFocusOwner() == null
                || !SwingUtilities.isDescendingFrom(manager.getFocusOwner(), owner)) {
            reset();
            return false;
        }
        if (event.getID() == KeyEvent.KEY_TYPED) return false;
        if (event.getKeyCode() != KeyEvent.VK_SHIFT || event.isConsumed()
                || (event.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK)) != 0) {
            reset();
            return false;
        }
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            shiftPressed = true;
        } else if (event.getID() == KeyEvent.KEY_RELEASED && shiftPressed) {
            shiftPressed = false;
            long now = event.getWhen();
            if (firstRelease >= 0 && now >= firstRelease && now - firstRelease <= MAX_INTERVAL_MS) {
                reset();
                SwingUtilities.invokeLater(() -> {
                    if (owner.isShowing()) action.run();
                });
            } else {
                firstRelease = now;
            }
        }
        return false;
    }

    private void reset() {
        shiftPressed = false;
        firstRelease = -1;
    }
}
