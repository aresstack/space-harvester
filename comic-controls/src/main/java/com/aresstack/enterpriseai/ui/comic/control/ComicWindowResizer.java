package com.aresstack.enterpriseai.ui.comic.control;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Edge resizing for a FRAMELESS window: the window's content surface keeps a few pixels of its
 * own border free of children (the ink contour plus padding); the pointer inside that grip zone
 * shows the matching resize cursor, and dragging there resizes the window ancestor, never below
 * its minimum size. Maximized frames are not resizable this way.
 */
public final class ComicWindowResizer {

    /** Which edge(s) the pointer is on — a bit set of {@link #NORTH}, {@link #SOUTH}, {@link #EAST}, {@link #WEST}. */
    public static final int NONE = 0;
    public static final int NORTH = 1;
    public static final int SOUTH = 2;
    public static final int EAST = 4;
    public static final int WEST = 8;

    private ComicWindowResizer() {
    }

    /** Install edge resizing on {@code surface} with a grip zone of {@code grip} pixels. */
    public static void install(final JComponent surface, final int grip) {
        if (surface == null || grip <= 0) {
            throw new IllegalArgumentException("surface must not be null and grip must be positive");
        }
        MouseAdapter adapter = new MouseAdapter() {
            private int edges;
            private Point pressedOnScreen;
            private Rectangle startBounds;

            @Override
            public void mouseMoved(MouseEvent event) {
                Window window = SwingUtilities.getWindowAncestor(surface);
                int at = window == null || ComicWindowDragger.isMaximized(window) ? NONE
                        : edgesAt(event.getX(), event.getY(), surface.getWidth(), surface.getHeight(), grip);
                surface.setCursor(Cursor.getPredefinedCursor(cursorFor(at)));
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (pressedOnScreen == null) {
                    surface.setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                Window window = SwingUtilities.getWindowAncestor(surface);
                if (!SwingUtilities.isLeftMouseButton(event) || window == null
                        || ComicWindowDragger.isMaximized(window)) {
                    return;
                }
                edges = edgesAt(event.getX(), event.getY(), surface.getWidth(), surface.getHeight(), grip);
                if (edges == NONE) {
                    return;
                }
                pressedOnScreen = event.getLocationOnScreen();
                startBounds = window.getBounds();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                Window window = SwingUtilities.getWindowAncestor(surface);
                if (pressedOnScreen == null || window == null) {
                    return;
                }
                Point now = event.getLocationOnScreen();
                Rectangle bounds = resized(startBounds, edges, now.x - pressedOnScreen.x,
                        now.y - pressedOnScreen.y, window.getMinimumSize());
                window.setBounds(bounds);
                window.validate();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                pressedOnScreen = null;
                startBounds = null;
                edges = NONE;
            }
        };
        surface.addMouseListener(adapter);
        surface.addMouseMotionListener(adapter);
    }

    /** The edge bit set for a pointer at {@code (x, y)} inside a {@code width}×{@code height} surface. */
    public static int edgesAt(int x, int y, int width, int height, int grip) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return NONE;
        }
        int edges = NONE;
        if (y < grip) {
            edges |= NORTH;
        } else if (y >= height - grip) {
            edges |= SOUTH;
        }
        if (x < grip) {
            edges |= WEST;
        } else if (x >= width - grip) {
            edges |= EAST;
        }
        return edges;
    }

    /** The {@link Cursor} constant matching an edge bit set. */
    public static int cursorFor(int edges) {
        switch (edges) {
            case NORTH:
                return Cursor.N_RESIZE_CURSOR;
            case SOUTH:
                return Cursor.S_RESIZE_CURSOR;
            case EAST:
                return Cursor.E_RESIZE_CURSOR;
            case WEST:
                return Cursor.W_RESIZE_CURSOR;
            case NORTH | EAST:
                return Cursor.NE_RESIZE_CURSOR;
            case NORTH | WEST:
                return Cursor.NW_RESIZE_CURSOR;
            case SOUTH | EAST:
                return Cursor.SE_RESIZE_CURSOR;
            case SOUTH | WEST:
                return Cursor.SW_RESIZE_CURSOR;
            default:
                return Cursor.DEFAULT_CURSOR;
        }
    }

    /**
     * The window bounds after dragging the given edges by {@code (dx, dy)} from {@code start}, never
     * smaller than {@code minimum}: a west/north edge that would undercut the minimum stops moving.
     */
    public static Rectangle resized(Rectangle start, int edges, int dx, int dy, Dimension minimum) {
        int minWidth = minimum == null ? 1 : Math.max(1, minimum.width);
        int minHeight = minimum == null ? 1 : Math.max(1, minimum.height);
        Rectangle bounds = new Rectangle(start);
        if ((edges & EAST) != 0) {
            bounds.width = Math.max(minWidth, start.width + dx);
        } else if ((edges & WEST) != 0) {
            int width = Math.max(minWidth, start.width - dx);
            bounds.x = start.x + start.width - width;
            bounds.width = width;
        }
        if ((edges & SOUTH) != 0) {
            bounds.height = Math.max(minHeight, start.height + dy);
        } else if ((edges & NORTH) != 0) {
            int height = Math.max(minHeight, start.height - dy);
            bounds.y = start.y + start.height - height;
            bounds.height = height;
        }
        return bounds;
    }
}
