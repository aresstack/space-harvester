package com.aresstack.enterpriseai.ui.comic.control;

import org.junit.Test;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;

/** Edge detection, cursors and the resize arithmetic of the frameless window resizer. */
public class ComicWindowResizerTest {

    @Test
    public void edgesAreDetectedInsideTheGripZoneOnly() {
        assertEquals(ComicWindowResizer.NONE, ComicWindowResizer.edgesAt(100, 100, 400, 300, 6));
        assertEquals(ComicWindowResizer.NORTH, ComicWindowResizer.edgesAt(100, 2, 400, 300, 6));
        assertEquals(ComicWindowResizer.SOUTH | ComicWindowResizer.EAST,
                ComicWindowResizer.edgesAt(397, 298, 400, 300, 6));
        assertEquals(ComicWindowResizer.NORTH | ComicWindowResizer.WEST,
                ComicWindowResizer.edgesAt(0, 0, 400, 300, 6));
        assertEquals("outside the surface", ComicWindowResizer.NONE,
                ComicWindowResizer.edgesAt(-1, 10, 400, 300, 6));
    }

    @Test
    public void cursorsMatchTheEdges() {
        assertEquals(Cursor.DEFAULT_CURSOR, ComicWindowResizer.cursorFor(ComicWindowResizer.NONE));
        assertEquals(Cursor.E_RESIZE_CURSOR, ComicWindowResizer.cursorFor(ComicWindowResizer.EAST));
        assertEquals(Cursor.NW_RESIZE_CURSOR,
                ComicWindowResizer.cursorFor(ComicWindowResizer.NORTH | ComicWindowResizer.WEST));
        assertEquals(Cursor.SE_RESIZE_CURSOR,
                ComicWindowResizer.cursorFor(ComicWindowResizer.SOUTH | ComicWindowResizer.EAST));
    }

    @Test
    public void resizingKeepsTheOppositeEdgeAndTheMinimumSize() {
        Rectangle start = new Rectangle(100, 100, 400, 300);
        Dimension minimum = new Dimension(200, 150);

        assertEquals(new Rectangle(100, 100, 450, 300),
                ComicWindowResizer.resized(start, ComicWindowResizer.EAST, 50, 999, minimum));
        assertEquals("west edge moves, right edge stays", new Rectangle(130, 100, 370, 300),
                ComicWindowResizer.resized(start, ComicWindowResizer.WEST, 30, 0, minimum));
        assertEquals("never below the minimum", new Rectangle(300, 100, 200, 300),
                ComicWindowResizer.resized(start, ComicWindowResizer.WEST, 350, 0, minimum));
        assertEquals(new Rectangle(100, 120, 420, 280), ComicWindowResizer.resized(start,
                ComicWindowResizer.NORTH | ComicWindowResizer.EAST, 20, 20, minimum));
    }
}
