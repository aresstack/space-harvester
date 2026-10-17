package com.aresstack.enterpriseai.ui.comic.control;

import org.junit.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The comic split pane owns the left side's width policy: clamped into its bounds on every move,
 * collapsible to a divider-less zero width, reopened at the remembered width (issue #36, test 2).
 */
public class ComicSplitPaneTest {

    private static ComicSplitPane build() {
        return new ComicSplitPane(new JPanel(), new JPanel(), 280, 700);
    }

    @Test
    public void dividerLocationIsClampedIntoTheWidthBounds() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicSplitPane split = build();
                split.setDividerLocation(50);
                assertEquals("below the minimum snaps to the minimum", 280,
                        split.getDividerLocation());

                split.setDividerLocation(9000);
                assertEquals("beyond the maximum snaps to the maximum", 700,
                        split.getDividerLocation());

                split.setDividerLocation(444);
                assertEquals("a width inside the bounds is kept as-is", 444,
                        split.getDividerLocation());
            }
        });
    }

    @Test
    public void collapseRemovesTheDividerAndOpenRestoresTheRememberedWidth() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicSplitPane split = build();
                split.setDividerLocation(500); // the user's width

                split.collapseLeft();
                assertTrue(split.isLeftCollapsed());
                assertEquals("no divider while collapsed", 0, split.getDividerSize());
                assertEquals("left side takes no width while collapsed", 0,
                        split.getDividerLocation());

                split.openLeft();
                assertFalse(split.isLeftCollapsed());
                assertTrue("the divider is back", split.getDividerSize() > 0);
                assertEquals("reopens at the remembered width", 500, split.getDividerLocation());
            }
        });
    }

    @Test
    public void aPersistedPreferredWidthIsClampedAndUsedOnOpen() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicSplitPane split = build();
                split.collapseLeft();
                split.setPreferredLeftWidth(10000); // e.g. a corrupt persisted value
                assertEquals(700, split.getPreferredLeftWidth());

                split.openLeft();
                assertEquals(700, split.getDividerLocation());
            }
        });
    }

    @Test
    public void userMovesNotifyTheWidthListenerWithTheClampedWidth() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicSplitPane split = build();
                final List<Integer> widths = new ArrayList<Integer>();
                split.setLeftWidthListener(new ComicSplitPane.LeftWidthListener() {
                    public void leftWidthChanged(int width) {
                        widths.add(Integer.valueOf(width));
                    }
                });

                split.setDividerLocation(400);
                assertEquals(Integer.valueOf(400), widths.get(widths.size() - 1));

                split.setDividerLocation(100);
                assertEquals("the listener sees the clamped value",
                        Integer.valueOf(280), widths.get(widths.size() - 1));

                int before = widths.size();
                split.collapseLeft();
                split.openLeft();
                assertEquals("open/collapse are not user moves", before, widths.size());
            }
        });
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
