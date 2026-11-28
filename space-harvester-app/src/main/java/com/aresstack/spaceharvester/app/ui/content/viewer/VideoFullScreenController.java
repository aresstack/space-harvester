package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Move a single, already-running video view into a full-screen window and back.
 * Never create another media player or alter the media's playback state.
 */
final class VideoFullScreenController implements AutoCloseable {

    private final JPanel videoView;
    private final Runnable beforeTransfer;
    private final Runnable attachVideoSurface;
    private final Consumer<Boolean> fullScreenChanged;

    private Container originalParent;
    private Object originalConstraints;
    private int originalIndex;
    private JPanel placeholder;
    private JFrame fullScreenWindow;
    private GraphicsDevice display;
    private KeyEventDispatcher escapeHandler;
    private boolean transferring;

    VideoFullScreenController(JPanel videoView, Runnable beforeTransfer, Runnable attachVideoSurface,
                              Consumer<Boolean> fullScreenChanged) {
        this.videoView = videoView;
        this.beforeTransfer = beforeTransfer;
        this.attachVideoSurface = attachVideoSurface;
        this.fullScreenChanged = fullScreenChanged;
    }

    static void installDoubleClick(Component videoSurface, Runnable toggle) {
        videoSurface.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    toggle.run();
                }
            }
        });
    }

    boolean isFullScreen() {
        return fullScreenWindow != null;
    }

    boolean isTransferring() {
        return transferring;
    }

    void toggle() {
        assertEventThread();
        if (isFullScreen()) {
            exit(true);
        } else {
            enter();
        }
    }

    private void enter() {
        if (GraphicsEnvironment.isHeadless() || videoView.getParent() == null) {
            return;
        }
        originalParent = videoView.getParent();
        originalIndex = originalParent.getComponentZOrder(videoView);
        originalConstraints = originalParent.getLayout() instanceof BorderLayout
                ? ((BorderLayout) originalParent.getLayout()).getConstraints(videoView) : null;
        GraphicsConfiguration configuration = videoView.getGraphicsConfiguration();
        display = configuration == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                : configuration.getDevice();

        JFrame window = new JFrame("Video-Vollbild");
        window.setUndecorated(true);
        window.setBackground(AppColors.of("app.VideoFullScreenController.enter"));
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                exit(true);
            }
        });

        placeholder = new JPanel(new BorderLayout());
        placeholder.setBackground(AppColors.of("app.VideoFullScreenController.windowClosing"));
        fullScreenWindow = window;
        transferring = true;
        try {
            beforeTransfer.run();
            originalParent.remove(videoView);
            putBack(originalParent, placeholder);
            window.getContentPane().setLayout(new BorderLayout());
            window.getContentPane().setBackground(AppColors.of("app.VideoFullScreenController.windowClosing"));
            window.getContentPane().add(videoView, BorderLayout.CENTER);
            originalParent.revalidate();
            originalParent.repaint();
            if (display.isFullScreenSupported()) {
                display.setFullScreenWindow(window);
            } else {
                window.setBounds(display.getDefaultConfiguration().getBounds());
                window.setVisible(true);
            }
            installEscapeKey();
            fullScreenChanged.accept(true);
        } catch (RuntimeException failure) {
            exit(false);
            reattachLater();
            throw failure;
        } finally {
            transferring = false;
        }
        reattachLater();
    }

    /** Restore the embedded view without restarting video when another media mode takes over. */
    void leaveForMediaSwitch() {
        assertEventThread();
        exit(false);
    }

    private void exit(boolean restorePlayback) {
        if (fullScreenWindow == null) {
            return;
        }
        JFrame window = fullScreenWindow;
        fullScreenWindow = null;
        transferring = true;
        try {
            if (restorePlayback) {
                beforeTransfer.run();
            }
            uninstallEscapeKey();
            window.getContentPane().remove(videoView);
            if (display != null && display.getFullScreenWindow() == window) {
                display.setFullScreenWindow(null);
            }
            if (originalParent != null) {
                if (placeholder != null) {
                    originalParent.remove(placeholder);
                }
                putBack(originalParent, videoView);
                originalParent.revalidate();
                originalParent.repaint();
            }
            window.dispose();
            fullScreenChanged.accept(false);
        } finally {
            transferring = false;
            originalParent = null;
            originalConstraints = null;
            placeholder = null;
            display = null;
        }
        if (restorePlayback) {
            reattachLater();
        }
    }

    private void putBack(Container parent, Component component) {
        if (parent.getLayout() instanceof BorderLayout) {
            parent.add(component, originalConstraints == null ? BorderLayout.CENTER : originalConstraints);
        } else {
            parent.add(component, Math.min(originalIndex, parent.getComponentCount()));
        }
    }

    private void installEscapeKey() {
        escapeHandler = event -> {
            JFrame window = fullScreenWindow;
            if (window == null || !window.isActive() || event.getID() != KeyEvent.KEY_PRESSED
                    || event.getKeyCode() != KeyEvent.VK_ESCAPE) {
                return false;
            }
            SwingUtilities.invokeLater(() -> exit(true));
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escapeHandler);
    }

    private void uninstallEscapeKey() {
        if (escapeHandler != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escapeHandler);
            escapeHandler = null;
        }
    }

    private void reattachLater() {
        SwingUtilities.invokeLater(() -> {
            if (videoView.isDisplayable()) {
                attachVideoSurface.run();
            }
        });
    }

    private static void assertEventThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Video full-screen transitions require the Swing event thread");
        }
    }

    @Override
    public void close() {
        assertEventThread();
        exit(false);
    }
}
