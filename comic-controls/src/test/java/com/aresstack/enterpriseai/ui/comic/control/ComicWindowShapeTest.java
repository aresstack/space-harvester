package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The rounded shape and contour of the frameless window (headless: the arithmetic and the border). */
public class ComicWindowShapeTest {

    @Test
    public void theWindowIsARoundedRectangleWithTheWindowRadius() {
        Shape shape = ComicWindowShape.shapeFor(1040, 720, ResearchUiMetrics.RADIUS_WINDOW, false);
        assertTrue(shape instanceof RoundRectangle2D);
        RoundRectangle2D round = (RoundRectangle2D) shape;
        assertEquals(1040d, round.getWidth(), 0d);
        assertEquals(720d, round.getHeight(), 0d);
        assertEquals("arc is the diameter of the corner", 16d, round.getArcWidth(), 0d);
        assertTrue("the middle of an edge belongs to the window", shape.contains(520, 0.5));
        assertTrue("the very corner pixel does not", !shape.contains(0.5, 0.5));
    }

    @Test
    public void maximizedOrWithoutRadiusTheWindowStaysRectangular() {
        assertNull(ComicWindowShape.shapeFor(1920, 1040, 8, true));
        assertNull(ComicWindowShape.shapeFor(1040, 720, 0, false));
        assertNull(ComicWindowShape.shapeFor(0, 0, 8, false));
    }

    @Test
    public void theRoundedContourKeepsTheGripPaddingOfTheSquareOne() {
        ComicPalette palette = ComicPalette.defaultPalette();
        JPanel surface = new JPanel();
        assertEquals("switching between maximized and normal does not move the content",
                ComicBorder.windowBorder(palette, 4).getBorderInsets(surface),
                ComicBorder.roundedWindowBorder(palette, 4, 8).getBorderInsets(surface));
    }
}
