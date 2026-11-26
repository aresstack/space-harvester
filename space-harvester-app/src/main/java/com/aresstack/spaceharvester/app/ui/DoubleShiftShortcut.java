package com.aresstack.spaceharvester.app.ui;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;
import javax.swing.SwingUtilities;

/** Recognize isolated Shift taps without consuming or altering ordinary keyboard input. */
final class DoubleShiftShortcut implements KeyEventDispatcher, AutoCloseable {
    private final Window window;
    private final Runnable action;
    private final TapSequence taps = new TapSequence();
    private final PropertyChangeListener resetOnFocusChange = event -> taps.reset();
    private KeyboardFocusManager manager;
    private boolean queued;

    DoubleShiftShortcut(Window window, Runnable action) {
        this.window = window;
        this.action = action;
    }

    void install() {
        if (manager != null) return;
        manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        manager.addPropertyChangeListener("activeWindow", resetOnFocusChange);
        manager.addPropertyChangeListener("focusOwner", resetOnFocusChange);
        manager.addKeyEventDispatcher(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!active()) {
            taps.reset();
            return false;
        }
        if (taps.accept(event) && !queued) {
            queued = true;
            SwingUtilities.invokeLater(() -> {
                queued = false;
                if (active()) action.run();
            });
        }
        // Never steal key presses or releases from editors, chat or the terminal.
        return false;
    }

    private boolean active() {
        return manager != null && window.isShowing() && manager.getActiveWindow() == window
                && manager.getFocusOwner() != null
                && SwingUtilities.getWindowAncestor(manager.getFocusOwner()) == window;
    }

    @Override
    public void close() {
        if (manager != null) {
            manager.removeKeyEventDispatcher(this);
            manager.removePropertyChangeListener("activeWindow", resetOnFocusChange);
            manager.removePropertyChangeListener("focusOwner", resetOnFocusChange);
            manager = null;
        }
        taps.reset();
    }

    /** Keep the recognition state independent of Swing window ownership. */
    static final class TapSequence {
        private static final long MAX_INTERVAL_MS = 500;
        private static final long MAX_PRESS_MS = 500;
        private long firstRelease = -1;
        private long pressedAt = -1;
        private int location;
        private boolean overlapping;

        boolean accept(KeyEvent event) {
            if (event.isConsumed() || event.getKeyCode() != KeyEvent.VK_SHIFT
                    || (event.getModifiersEx() & ~InputEvent.SHIFT_DOWN_MASK) != 0) {
                reset();
                return false;
            }
            long when = event.getWhen();
            if (event.getID() == KeyEvent.KEY_PRESSED) {
                if (overlapping) return false;
                if (pressedAt < 0) {
                    pressedAt = when;
                    location = event.getKeyLocation();
                } else if (location != event.getKeyLocation()) {
                    reset();
                    overlapping = true;
                }
                return false;
            }
            if (event.getID() != KeyEvent.KEY_RELEASED) return false;
            if (overlapping) {
                if ((event.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) == 0) reset();
                return false;
            }
            if (pressedAt < 0 || location != event.getKeyLocation()
                    || when < pressedAt || when - pressedAt > MAX_PRESS_MS
                    || (event.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0) {
                reset();
                return false;
            }
            pressedAt = -1;
            if (firstRelease >= 0 && when >= firstRelease && when - firstRelease <= MAX_INTERVAL_MS) {
                reset();
                return true;
            }
            firstRelease = when;
            return false;
        }

        void reset() {
            firstRelease = -1;
            pressedAt = -1;
            overlapping = false;
        }
    }
}
