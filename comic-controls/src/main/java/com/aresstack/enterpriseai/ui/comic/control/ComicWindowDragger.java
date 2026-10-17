package com.aresstack.enterpriseai.ui.comic.control;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Lets a FRAMELESS window be moved by dragging a handle component (the slim comic top bar): a left
 * press on the handle's free area remembers the offset, dragging moves the window ancestor along,
 * a double-click toggles a frame between maximized and its previous bounds, like a native title bar.
 * Dragging a maximized frame first restores it under the pointer (the grab point keeps its relative
 * position) and then moves it. Child controls (buttons, pills) keep their own mouse events — only the
 * handle itself drags.
 *
 * <p>Maximizing does not rely on {@link Frame#setExtendedState}: for undecorated frames the platform
 * state is unreliable (Windows reported the frame as normal again, so a second double-click maximized
 * once more instead of restoring; X11 without a window manager does not support it at all). Instead the
 * frame's bounds before maximizing are kept on its root pane and the frame covers the usable area of its
 * screen (the taskbar stays visible). A frame the platform maximized itself (e.g. Windows+Up) is
 * restored through its extended state; the platform then applies its own saved geometry later, so such
 * a frame is not dragged out of the maximized state.
 */
public final class ComicWindowDragger {

    /** Root-pane client property: the frame's bounds before {@link #maximize}, present only while maximized. */
    static final String NORMAL_BOUNDS = "comic.window.normalBounds";

    /** Pointer travel (px) before dragging a maximized frame restores it; smaller jitter stays a click. */
    static final int RESTORE_DRAG_THRESHOLD = 4;

    private ComicWindowDragger() {
    }

    /** Install drag-to-move (and double-click to maximize/restore) on {@code handle}. */
    public static void install(final JComponent handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        MouseAdapter adapter = new MouseAdapter() {
            private Point pressedOnScreen;
            private Point windowOrigin;
            private boolean restoreOnDrag;

            @Override
            public void mousePressed(MouseEvent event) {
                pressedOnScreen = null;
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                Window window = SwingUtilities.getWindowAncestor(handle);
                if (window == null) {
                    return;
                }
                pressedOnScreen = event.getLocationOnScreen();
                windowOrigin = window.getLocation();
                restoreOnDrag = isMaximized(window);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                Window window = SwingUtilities.getWindowAncestor(handle);
                if (pressedOnScreen == null || window == null) {
                    return;
                }
                Point now = event.getLocationOnScreen();
                if (restoreOnDrag) {
                    if (Math.abs(now.x - pressedOnScreen.x) < RESTORE_DRAG_THRESHOLD
                            && Math.abs(now.y - pressedOnScreen.y) < RESTORE_DRAG_THRESHOLD) {
                        return;
                    }
                    Rectangle maximized = window.getBounds();
                    Rectangle normal = restore(window);
                    restoreOnDrag = false;
                    if (normal == null) {
                        pressedOnScreen = null;
                        return;
                    }
                    windowOrigin = restoredLocation(maximized, normal.getSize(), pressedOnScreen);
                }
                window.setLocation(windowOrigin.x + now.x - pressedOnScreen.x,
                        windowOrigin.y + now.y - pressedOnScreen.y);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                pressedOnScreen = null;
                restoreOnDrag = false;
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    toggleMaximized(SwingUtilities.getWindowAncestor(handle));
                }
            }
        };
        handle.addMouseListener(adapter);
        handle.addMouseMotionListener(adapter);
    }

    /** Whether the window is a frame maximized by this class or by the platform. */
    public static boolean isMaximized(Window window) {
        return window instanceof Frame && (platformMaximized((Frame) window) || normalBounds(window) != null);
    }

    /** Maximize a normal frame, restore a maximized one; other windows are left alone. */
    public static void toggleMaximized(Window window) {
        if (!(window instanceof Frame)) {
            return;
        }
        if (isMaximized(window)) {
            restore(window);
        } else {
            maximize(window);
        }
    }

    /**
     * Let a frame cover the usable area of its screen and remember its current bounds for
     * {@link #restore}. No effect on other windows or on a frame that is already maximized.
     */
    public static void maximize(Window window) {
        if (!(window instanceof Frame) || isMaximized(window)) {
            return;
        }
        JRootPane root = rootPane(window);
        if (root == null) {
            Frame frame = (Frame) window; // ein reiner AWT-Frame hat keinen Ort für die alten Grenzen
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
            return;
        }
        root.putClientProperty(NORMAL_BOUNDS, window.getBounds());
        window.setBounds(usableBounds(window.getGraphicsConfiguration()));
        window.validate();
    }

    /**
     * Bring a maximized frame back to its bounds before maximizing.
     *
     * @return the frame's bounds afterwards when this class maximized it; {@code null} when the window was not
     *         a maximized frame or the platform maximized it (its restore completes asynchronously, with the
     *         platform's own saved geometry)
     */
    public static Rectangle restore(Window window) {
        if (!(window instanceof Frame) || !isMaximized(window)) {
            return null;
        }
        Frame frame = (Frame) window;
        Rectangle normal = normalBounds(window);
        if (platformMaximized(frame)) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.MAXIMIZED_BOTH);
        }
        if (normal == null) {
            return null;
        }
        rootPane(window).putClientProperty(NORMAL_BOUNDS, null);
        frame.setBounds(normal);
        frame.validate();
        return frame.getBounds();
    }

    /**
     * The bounds a frame maximized by this class returns to on {@link #restore}; {@code null} when it is not
     * maximized by this class.
     */
    public static Rectangle restoredBounds(Window window) {
        return normalBounds(window);
    }

    /** The bounds of a screen minus its taskbars and docks. */
    static Rectangle usableBounds(GraphicsConfiguration configuration) {
        if (configuration == null) {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle(0, 0, screen.width, screen.height);
        }
        return usableBounds(configuration.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(configuration));
    }

    /** {@code screen} minus {@code insets} (taskbar, dock). */
    static Rectangle usableBounds(Rectangle screen, Insets insets) {
        if (insets == null) {
            return new Rectangle(screen);
        }
        return new Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom);
    }

    /**
     * Where a frame restored from {@code maximized} to {@code normal} size goes so that the pointer
     * grabbing it at {@code grab} keeps its relative horizontal position and its height inside the bar.
     */
    static Point restoredLocation(Rectangle maximized, Dimension normal, Point grab) {
        double ratio = maximized.width <= 0 ? 0.5 : (grab.x - maximized.x) / (double) maximized.width;
        ratio = Math.max(0d, Math.min(1d, ratio));
        return new Point(grab.x - (int) Math.round(ratio * normal.width), maximized.y);
    }

    private static boolean platformMaximized(Frame frame) {
        return (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    private static Rectangle normalBounds(Window window) {
        JRootPane root = rootPane(window);
        Object value = root == null ? null : root.getClientProperty(NORMAL_BOUNDS);
        return value instanceof Rectangle ? new Rectangle((Rectangle) value) : null;
    }

    private static JRootPane rootPane(Window window) {
        return window instanceof RootPaneContainer ? ((RootPaneContainer) window).getRootPane() : null;
    }
}
