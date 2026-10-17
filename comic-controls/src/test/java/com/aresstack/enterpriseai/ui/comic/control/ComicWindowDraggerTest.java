package com.aresstack.enterpriseai.ui.comic.control;

import org.junit.Test;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;

/**
 * The arithmetic behind maximize/restore of the frameless window (the toggle itself needs a real frame,
 * which headless tests cannot create).
 */
public class ComicWindowDraggerTest {

    @Test
    public void maximizingLeavesTheTaskbarVisible() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1080);
        assertEquals(new Rectangle(0, 0, 1920, 1040),
                ComicWindowDragger.usableBounds(screen, new Insets(0, 0, 40, 0)));
        assertEquals("second monitor with a taskbar on the left", new Rectangle(1960, 0, 1880, 1080),
                ComicWindowDragger.usableBounds(new Rectangle(1920, 0, 1920, 1080), new Insets(0, 40, 0, 0)));
        assertEquals(screen, ComicWindowDragger.usableBounds(screen, null));
    }

    @Test
    public void draggingAMaximizedWindowRestoresItUnderThePointer() {
        Rectangle maximized = new Rectangle(0, 0, 1920, 1040);
        Dimension normal = new Dimension(1040, 720);

        Point middle = ComicWindowDragger.restoredLocation(maximized, normal, new Point(960, 12));
        assertEquals("grabbed in the middle, the pointer stays in the middle", new Point(960 - 520, 0), middle);

        Point right = ComicWindowDragger.restoredLocation(maximized, normal, new Point(1824, 12));
        assertEquals("grabbed near the right edge, it stays near the right edge",
                1824 - Math.round(0.95 * 1040), right.x);

        Point left = ComicWindowDragger.restoredLocation(maximized, normal, new Point(0, 12));
        assertEquals(new Point(0, 0), left);
    }

    @Test
    public void nonFramesAreNeverMaximized() {
        assertFalse(ComicWindowDragger.isMaximized(null));
        assertNull(ComicWindowDragger.restore(null));
        ComicWindowDragger.toggleMaximized(null); // no effect, no exception
        ComicWindowDragger.maximize(null);
    }
}
