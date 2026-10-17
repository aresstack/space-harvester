package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.IllegalComponentStateException;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowStateListener;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * Round corners for a FRAMELESS window, as the operating system draws them for a decorated window under
 * Windows 11 (the askai {@code arch} window is decorated, so it gets them for free): the window's shape is a
 * rounded rectangle ({@link Window#setShape}) and the ink contour of its surface follows the same radius
 * ({@link ComicBorder#roundedWindowBorder}). Maximized, shape and contour are square like a native maximized
 * window. Where the platform cannot shape windows, both stay square, so no corner of the rectangle shows
 * behind a rounded contour.
 */
public final class ComicWindowShape {

    private ComicWindowShape() {
    }

    /**
     * Keep {@code window} and the contour of its {@code surface} (the content pane carrying the grip padding)
     * rounded with {@code radius} pixels while the window is not maximized.
     */
    public static void install(final Window window, final JComponent surface, ComicPalette palette, int padding,
                               final int radius) {
        if (window == null || surface == null || palette == null) {
            throw new IllegalArgumentException("window, surface and palette must not be null");
        }
        final ComicBorder square = ComicBorder.windowBorder(palette, padding);
        final ComicBorder rounded = ComicBorder.roundedWindowBorder(palette, padding, radius);
        final Runnable update = new Runnable() {
            @Override
            public void run() {
                apply(window, surface, square, rounded, radius);
            }
        };
        window.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                update.run();
            }

            @Override
            public void componentShown(ComponentEvent event) {
                update.run();
            }
        });
        if (window instanceof Frame) {
            window.addWindowStateListener(new WindowStateListener() {
                @Override
                public void windowStateChanged(WindowEvent event) {
                    update.run();
                }
            });
        }
        if (window instanceof RootPaneContainer) {
            JRootPane root = ((RootPaneContainer) window).getRootPane();
            root.addPropertyChangeListener(ComicWindowDragger.NORMAL_BOUNDS, new PropertyChangeListener() {
                @Override
                public void propertyChange(PropertyChangeEvent event) {
                    update.run(); // maximiert/wiederhergestellt durch ComicWindowDragger
                }
            });
        }
        update.run();
    }

    /** The window shape for a {@code width}×{@code height} window; {@code null} (rectangular) when maximized. */
    static Shape shapeFor(int width, int height, int radius, boolean maximized) {
        if (maximized || radius <= 0 || width <= 0 || height <= 0) {
            return null;
        }
        return new RoundRectangle2D.Double(0, 0, width, height, radius * 2d, radius * 2d);
    }

    private static void apply(Window window, JComponent surface, ComicBorder square, ComicBorder rounded,
                              int radius) {
        boolean maximized = ComicWindowDragger.isMaximized(window);
        Shape shape = shapeFor(window.getWidth(), window.getHeight(), radius, maximized);
        boolean shaped = shape != null && supportsShaping(window) && setShape(window, shape);
        if (!shaped) {
            setShape(window, null);
        }
        ComicBorder border = shaped ? rounded : square;
        if (surface.getBorder() != border) {
            surface.setBorder(border);
            surface.repaint();
        }
    }

    private static boolean supportsShaping(Window window) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        GraphicsDevice device = configuration != null ? configuration.getDevice()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        return device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT);
    }

    private static boolean setShape(Window window, Shape shape) {
        try {
            window.setShape(shape);
            return true;
        } catch (UnsupportedOperationException | IllegalComponentStateException unsupported) {
            return false; // eckig bleiben
        }
    }
}
