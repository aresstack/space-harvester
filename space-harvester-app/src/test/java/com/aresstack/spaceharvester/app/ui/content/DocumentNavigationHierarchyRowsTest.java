package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #110 (headless): the expanded outline shows one horizontal cloud row per level of the active path, each
 * with its own scroll state; the wheel moves only the row under the pointer. Kopie des gleichnamigen Tests aus dem
 * Enterprise AI Client, auf das Datenmodell der App ({@link Navigation}) und JUnit 5 umgestellt.
 */
class DocumentNavigationHierarchyRowsTest {

    private static final int ROW = ResearchUiMetrics.CLOUD_GAP_V + ResearchUiMetrics.CLOUD_CHIP_HEIGHT;

    private static Navigation.Section section(String id, String label, int level, String parent,
                                              Navigation.Section... children) {
        return section(id, label, Navigation.Origin.HEADING, level, parent, children);
    }

    private static Navigation.Section section(String id, String label, Navigation.Origin origin, int level,
                                              String parent, Navigation.Section... children) {
        return new Navigation.Section(id, label, origin, level, 0, 50, "", parent, Arrays.asList(children));
    }

    private static List<Navigation.Section> leaves(String parent, int level, int count, String label) {
        List<Navigation.Section> result = new ArrayList<Navigation.Section>();
        for (int i = 1; i <= count; i++) {
            result.add(section(parent + "." + i, label + " " + parent + "." + i, level, parent));
        }
        return result;
    }

    /**
     * 1, 2, 3 (3.1, 3.2 (3.2.1, 3.2.2 (3.2.2.1, 3.2.2.2), 3.2.3, 3.2.4), 3.3 detected, 3.4), 4 (twelve wide steps, the
     * fifth again with twelve), 5.
     */
    private static Navigation navigation() {
        Navigation.Section deepest = section("3.2.2", "Abschnitt 3.2.2", 3, "3.2",
                section("3.2.2.1", "Teil 3.2.2.1", 4, "3.2.2"), section("3.2.2.2", "Teil 3.2.2.2", 4, "3.2.2"));
        Navigation.Section middle = section("3.2", "Abschnitt 3.2", 2, "3",
                section("3.2.1", "Abschnitt 3.2.1", 3, "3.2"), deepest,
                section("3.2.3", "Abschnitt 3.2.3", 3, "3.2"), section("3.2.4", "Abschnitt 3.2.4", 3, "3.2"));
        Navigation.Section chapter3 = section("3", "Kapitel 3", 1, null,
                section("3.1", "Abschnitt 3.1", 2, "3"), middle,
                section("3.3", "Erkanntes Thema", Navigation.Origin.TOPIC, 2, "3"),
                section("3.4", "Abschnitt 3.4", 2, "3"));
        List<Navigation.Section> steps = leaves("4", 2, 12, "Ein recht ausführlicher Arbeitsschritt");
        steps.set(4, section("4.5", "Ein recht ausführlicher Arbeitsschritt 4.5", 2, "4",
                leaves("4.5", 3, 12, "Eine ebenso ausführliche Teilaufgabe").toArray(new Navigation.Section[0])));
        Navigation.Section chapter4 = section("4", "Kapitel 4", 1, null, steps.toArray(new Navigation.Section[0]));
        return new Navigation(Navigation.State.CURRENT, Navigation.Analysis.HEADINGS, "", Arrays.asList(
                section("1", "Kapitel 1", 1, null), section("2", "Kapitel 2", 1, null), chapter3, chapter4,
                section("5", "Kapitel 5", 1, null)));
    }

    private static void layout(Component component) {
        component.doLayout();
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                layout(child);
            }
        }
    }

    private static void relayout(DocumentNavigationSky sky) {
        for (int i = 0; i < 3; i++) {
            sky.validate();
            layout(sky);
        }
    }

    private static DocumentNavigationSky sky(int width, int height) {
        DocumentNavigationSky sky = new DocumentNavigationSky();
        sky.setNavigation(navigation());
        sky.setSize(width, height);
        relayout(sky);
        return sky;
    }

    private static List<String> ids(List<Navigation.Section> sections) {
        List<String> ids = new ArrayList<String>();
        for (Navigation.Section section : sections) {
            ids.add(section.id());
        }
        return ids;
    }

    private static MouseWheelEvent wheel(JComponent row, int clicks) {
        return new MouseWheelEvent(row, MouseWheelEvent.MOUSE_WHEEL, 0L, 0, 30, 20, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, clicks);
    }

    private static void onEdt(Runnable test) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                test.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    @Test
    void oneRowPerLevelWithOnlyTheDirectChildrenOfTheActiveParent() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(700, 600);
            sky.setActive("3", "3.2.2");
            assertTrue(sky.rowSectionsForTest().isEmpty(), "collapsed: no rows");
            sky.setExpanded(true);
            relayout(sky);
            List<List<Navigation.Section>> rows = sky.rowSectionsForTest();
            assertEquals(3, rows.size());
            assertEquals(Arrays.asList("3.1", "3.2", "3.3", "3.4"), ids(rows.get(0)));
            assertEquals(Arrays.asList("3.2.1", "3.2.2", "3.2.3", "3.2.4"), ids(rows.get(1)));
            assertEquals(Arrays.asList("3.2.2.1", "3.2.2.2"), ids(rows.get(2)));
            assertEquals("3", sky.activeIdForTest(0));
            assertEquals("3.2", sky.activeIdForTest(1));
            assertEquals("3.2.2", sky.activeIdForTest(2));
            assertNull(sky.activeIdForTest(3));
            // all rows lie on top of each other, each spans the full width: no indentation
            for (int depth = 1; depth <= 3; depth++) {
                JComponent row = sky.rowForTest(depth);
                assertEquals(0, row.getX());
                assertEquals((depth - 1) * ROW, row.getY());
                assertEquals(sky.rowForTest(1).getWidth(), row.getWidth());
            }
        });
    }

    @Test
    void changingTheActiveSubsectionRebuildsThePath() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(700, 600);
            sky.setExpanded(true);
            sky.setActive("3", null);
            relayout(sky);
            assertEquals(1, sky.rowSectionsForTest().size());
            assertNull(sky.activeIdForTest(1));

            sky.setActive("3", "3.2.1");
            relayout(sky);
            assertEquals(2, sky.rowSectionsForTest().size());
            assertEquals(Arrays.asList("3.2.1", "3.2.2", "3.2.3", "3.2.4"), ids(sky.rowSectionsForTest().get(1)));

            sky.setActive("3", "3.1"); // a sibling without children: its children are not shown, neither are 3.2's
            relayout(sky);
            assertEquals(1, sky.rowSectionsForTest().size());
            assertEquals("3.1", sky.activeIdForTest(1));

            sky.setActive("4", "4.5.2");
            relayout(sky);
            assertEquals(2, sky.rowSectionsForTest().size());
            assertEquals("4.1", sky.rowSectionsForTest().get(0).get(0).id());
            assertEquals("4.5.1", sky.rowSectionsForTest().get(1).get(0).id());
        });
    }

    @Test
    void wheelMovesOnlyTheRowBelowThePointer() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(500, 600);
            sky.setExpanded(true);
            sky.setActive("4", "4.5.3");
            relayout(sky);
            int chapters = sky.rowOffsetForTest(0);
            int first = sky.rowOffsetForTest(1);
            int second = sky.rowOffsetForTest(2);

            JComponent row2 = sky.rowForTest(2);
            MouseWheelEvent event = wheel(row2, 2);
            row2.dispatchEvent(event);
            assertTrue(event.isConsumed(), "wheel consumed");
            assertTrue(sky.rowOffsetForTest(2) > second);
            assertTrue(sky.rowManualForTest(2));
            assertEquals(first, sky.rowOffsetForTest(1), "row 1 stays");
            assertEquals(chapters, sky.rowOffsetForTest(0), "chapters stay");
            assertFalse(sky.rowManualForTest(1));

            int movedSecond = sky.rowOffsetForTest(2);
            JComponent row1 = sky.rowForTest(1);
            row1.dispatchEvent(wheel(row1, -1));
            assertTrue(sky.rowOffsetForTest(1) < first);
            assertEquals(movedSecond, sky.rowOffsetForTest(2), "row 2 stays");
            assertEquals(chapters, sky.rowOffsetForTest(0), "chapters stay");
        });
    }

    @Test
    void wideRowScrollsToBothEnds() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(500, 600);
            sky.setExpanded(true);
            sky.setActive("4", "4.5.6");
            relayout(sky);
            JComponent row = sky.rowForTest(2);
            int width = row.getWidth();
            for (int i = 0; i < 100; i++) {
                row.dispatchEvent(wheel(row, 3));
            }
            int right = sky.rowOffsetForTest(2);
            Rectangle last = sky.chipBoundsForTest(2, 11);
            assertTrue(last.x >= 0 && last.x + last.width <= width, "last cloud reachable: " + last);
            row.dispatchEvent(wheel(row, 3));
            assertEquals(right, sky.rowOffsetForTest(2), "stops at the right end");
            for (int i = 0; i < 100; i++) {
                row.dispatchEvent(wheel(row, -3));
            }
            Rectangle firstChip = sky.chipBoundsForTest(2, 0);
            assertTrue(firstChip.x >= 0 && firstChip.x + firstChip.width <= width, "first cloud reachable: " + firstChip);
        });
    }

    @Test
    void newRowCentersItsActiveEntryOrStandsBelowItsParent() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(500, 600);
            sky.setExpanded(true);
            sky.setActive("4", "4.5.6");
            relayout(sky);
            // wide rows: the active cloud stands in the middle
            for (int depth = 1; depth <= 2; depth++) {
                int index = depth == 1 ? 4 : 5;
                Rectangle chip = sky.chipBoundsForTest(depth, index);
                double center = chip.x + chip.width / 2.0;
                int width = sky.rowForTest(depth).getWidth();
                assertEquals(width / 2.0, center, 1.5, "row " + depth + " centered");
            }

            // a row that fits stands as a group below its parent, wholly inside the row
            DocumentNavigationSky wide = sky(900, 600);
            wide.setExpanded(true);
            wide.setActive("3", "3.2.2");
            relayout(wide);
            JComponent row = wide.rowForTest(2);
            Rectangle first = wide.chipBoundsForTest(2, 0);
            Rectangle last = wide.chipBoundsForTest(2, 3);
            assertTrue(first.x >= 0 && last.x + last.width <= row.getWidth(), "fits: " + first + " " + last + " in " + row.getWidth());
            double anchor = wide.anchorForTest(2);
            assertTrue(anchor >= first.x && anchor <= last.x + last.width, "parent " + anchor + " above the group " + first.x + ".." + (last.x + last.width));
        });
    }

    @Test
    void manualOffsetSurvivesWhileTheRowsParentStays() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(1000, 600);
            sky.setExpanded(true);
            sky.setActive("4", "4.5.3");
            relayout(sky);
            JComponent row1 = sky.rowForTest(1);
            JComponent row2 = sky.rowForTest(2);
            row1.dispatchEvent(wheel(row1, 1));
            row2.dispatchEvent(wheel(row2, 1));
            int first = sky.rowOffsetForTest(1);
            int second = sky.rowOffsetForTest(2);

            sky.setActive("4", "4.5.4"); // the neighbour, still in view
            relayout(sky);
            assertEquals(first, sky.rowOffsetForTest(1), "row 1 unchanged");
            assertEquals(second, sky.rowOffsetForTest(2), "row 2 keeps its manual position");
            assertTrue(sky.rowManualForTest(2));

            sky.setActive("4", "4.5.12"); // out of view: recentered
            relayout(sky);
            assertFalse(sky.rowManualForTest(2));
            Rectangle chip = sky.chipBoundsForTest(2, 11);
            assertTrue(chip.x >= 0 && chip.x + chip.width <= row2.getWidth(), "active cloud visible");
            assertEquals(first, sky.rowOffsetForTest(1), "row 1 still unchanged");

            sky.setActive("4", "4.6"); // row 2 now belongs to another parent: it disappears, row 1 keeps its place
            relayout(sky);
            assertEquals(1, sky.rowSectionsForTest().size());
        });
    }

    @Test
    void clickOnASubsectionMakesItActiveAndReportsIt() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(700, 600);
            List<Navigation.Section> selected = new ArrayList<Navigation.Section>();
            sky.setListener(selected::add);
            sky.setExpanded(true);
            sky.setActive("3", null);
            relayout(sky);
            sky.clickRowForTest(1, 1);
            relayout(sky);
            assertEquals("3.2", selected.get(0).id());
            assertEquals("3.2", sky.activeSectionId());
            assertEquals(2, sky.rowSectionsForTest().size());
            sky.clickRowForTest(2, 1);
            relayout(sky);
            assertEquals("3.2.2", selected.get(1).id());
            assertEquals("3", sky.activeChapterId());
            assertEquals(3, sky.rowSectionsForTest().size());
        });
    }

    @Test
    void detectedSectionsKeepTheirLook() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(700, 600);
            sky.setExpanded(true);
            sky.setActive("3", null);
            relayout(sky);
            Navigation.Section detected = sky.rowSectionsForTest().get(0).get(2);
            Navigation.Section heading = sky.rowSectionsForTest().get(0).get(1);
            assertTrue(DocumentNavigationSky.fontFor(detected).isItalic());
            assertFalse(DocumentNavigationSky.fontFor(heading).isItalic());
            assertTrue(DocumentNavigationSky.tooltipFor(detected).contains(DocumentNavigationSky.GENERATED_HINT));
            assertEquals(heading.label(), DocumentNavigationSky.tooltipFor(heading));
        });
    }

    @Test
    void coveredHeightGrowsByTheRowsAndStaysCapped() throws Exception {
        onEdt(() -> {
            DocumentNavigationSky sky = sky(700, 600);
            sky.setActive("3", "3.2.2");
            relayout(sky);
            int collapsed = sky.coveredHeight();
            assertEquals(ResearchUiMetrics.SKY_PADDING_TOP + ResearchUiMetrics.CLOUD_CHIP_HEIGHT + 8, collapsed);
            sky.setExpanded(true);
            relayout(sky);
            assertEquals(collapsed + 3 * ROW, sky.coveredHeight());

            DocumentNavigationSky small = sky(700, 300);
            small.setActive("3", "3.2.2");
            small.setExpanded(true);
            relayout(small);
            javax.swing.JScrollPane rows = small.rowsScrollForTest();
            int rowsTop = ResearchUiMetrics.SKY_PADDING_TOP + ResearchUiMetrics.CLOUD_CHIP_HEIGHT;
            int cap = Math.max(2 * ROW, 300 * ResearchUiMetrics.SKY_EXPANDED_MAX_PERCENT / 100 - rowsTop);
            assertTrue(rows.getHeight() <= cap && rows.getHeight() < 3 * ROW, "capped: " + rows.getHeight());
            assertEquals(rows.getY() + rows.getHeight() + 8, small.coveredHeight());
            assertEquals(Integer.valueOf(small.coveredHeight()),
                    small.getClientProperty(DocumentNavigationSky.TOP_INSET_PROPERTY));

            small.setExpanded(false);
            relayout(small);
            assertEquals(collapsed, small.coveredHeight());
        });
    }
}
