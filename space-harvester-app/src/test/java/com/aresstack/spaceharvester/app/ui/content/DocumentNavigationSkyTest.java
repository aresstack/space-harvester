package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseWheelEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abnahme Banner (läuft headless): Klick springt und das gewählte Kapitel wird aktiv, Zentrierung des aktiven
 * Kapitels, Mausrad rollt waagrecht ohne das Dokument, Aufklappen zeigt nur die Unterabschnitte des aktiven Kapitels,
 * kleines Fenster mit gekürzten Bezeichnungen, Original unverändert. Kopie des gleichnamigen Tests aus dem Enterprise
 * AI Client: statt des Lesebereichs dort der Reiter „Text“ von {@link PageDetailPanel}, statt Markdown der Klartext des
 * Lesetexts mit Zeichenpositionen; die Fälle der HTML-Lese-Ansicht entfallen (die App zeigt dort keinen Banner).
 */
class DocumentNavigationSkyTest {

    private static final String LONG = "Dieser Absatz ist absichtlich lang, damit das Dokument rollen kann. ";
    private static final String[] CHAPTERS = {"Einleitung", "Installation",
            "Betrieb und Überwachung im laufenden Rechenzentrum", "Fehlerbehebung", "Anhang"};

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    /** Klartext mit Überschriftzeilen; die Navigation entsteht beim Schreiben aus den Zeichenpositionen. */
    private static final class Doc {
        final StringBuilder text = new StringBuilder();
        final List<Node> chapters = new ArrayList<Node>();
        private final List<Node> open = new ArrayList<Node>();

        Doc title(String value) {
            text.append(value).append("\n\n");
            return this;
        }

        Doc heading(int level, String label) {
            return section(level, label, Navigation.Origin.HEADING, true);
        }

        /** Erkannter Themenabschnitt: beginnt am nächsten Absatz, ohne eigene Zeile im Text. */
        Doc topic(String label) {
            return section(1, label, Navigation.Origin.TOPIC, false);
        }

        private Doc section(int level, String label, Navigation.Origin origin, boolean line) {
            while (open.size() >= level) {
                open.remove(open.size() - 1);
            }
            Node parent = open.isEmpty() ? null : open.get(open.size() - 1);
            String id = (parent == null ? "" : parent.id + ".") + ((parent == null ? chapters : parent.children)
                    .size() + 1);
            Node node = new Node(id, label, origin, level, text.length(), parent == null ? null : parent.id);
            (parent == null ? chapters : parent.children).add(node);
            open.add(node);
            if (line) {
                node.anchor = label; // die Überschriftzeile beginnt an der Startposition
                text.append(label).append("\n\n");
            }
            return this;
        }

        Doc paragraph(String value) {
            text.append(value).append("\n\n");
            for (Node node : open) {
                if (node.anchor.isEmpty()) {
                    node.anchor = firstWords(value); // Themenabschnitt: die ersten Worte seines ersten Absatzes
                }
            }
            return this;
        }

        Navigation navigation(Navigation.Analysis analysis) {
            List<Navigation.Section> result = new ArrayList<Navigation.Section>();
            for (Node chapter : chapters) {
                result.add(chapter.section());
            }
            return new Navigation(Navigation.State.CURRENT, analysis, "", result);
        }

        private static String firstWords(String value) {
            String[] words = value.split(" ");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(5, words.length); i++) {
                out.append(i == 0 ? "" : " ").append(words[i]);
            }
            return out.toString();
        }
    }

    private static final class Node {
        final String id;
        final String label;
        final Navigation.Origin origin;
        final int level;
        final int start;
        final String parentId;
        final List<Node> children = new ArrayList<Node>();
        String anchor = "";

        Node(String id, String label, Navigation.Origin origin, int level, int start, String parentId) {
            this.id = id;
            this.label = label;
            this.origin = origin;
            this.level = level;
            this.start = start;
            this.parentId = parentId;
        }

        Navigation.Section section() {
            List<Navigation.Section> kids = new ArrayList<Navigation.Section>();
            for (Node child : children) {
                kids.add(child.section());
            }
            return new Navigation.Section(id, label, origin, level, start, -1, anchor, parentId, kids);
        }
    }

    private static Doc handbook() {
        Doc doc = new Doc().title("Handbuch");
        for (String chapter : CHAPTERS) {
            doc.heading(1, chapter);
            for (int i = 0; i < 6; i++) {
                doc.paragraph(LONG + LONG + LONG);
            }
            doc.heading(2, chapter + " Details").paragraph(LONG);
        }
        return doc;
    }

    private static void layout(Component component) {
        component.doLayout();
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                layout(child);
            }
        }
    }

    private static void relayout(Component component) {
        for (int i = 0; i < 3; i++) {
            ((Container) component).validate();
            layout(component);
        }
    }

    private PageDetailPanel panel(String text, Navigation navigation, int width, int height) throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        final PageDetailPanel[] holder = new PageDetailPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            PageDetailPanel panel = new PageDetailPanel(context);
            panel.tabs().select("Text");
            panel.reader().setText(text);
            panel.reader().setCaretPosition(0);
            panel.showNavigation(navigation);
            panel.setSize(width, height);
            for (int i = 0; i < 4; i++) {
                panel.validate();
                layout(panel);
            }
            holder[0] = panel;
        });
        return holder[0];
    }

    private PageDetailPanel panel(Doc doc, int width, int height) throws Exception {
        return panel(doc.text.toString(), doc.navigation(Navigation.Analysis.HEADINGS), width, height);
    }

    private static void assertDirectlyBelow(String what, int below, DocumentNavigationSky sky) {
        assertTrue(below >= sky.coveredHeight()
                        && below <= sky.coveredHeight() + 3 * NavigableDocumentPage.TARGET_GAP,
                what + " direkt unter dem Banner: " + below + " vs " + sky.coveredHeight());
    }

    @Test
    void clickJumpsAndTheChosenChapterBecomesActive() throws Exception {
        Doc doc = handbook();
        String text = doc.text.toString();
        PageDetailPanel panel = panel(doc, 700, 400);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            NavigableDocumentPage page = panel.readerPage();
            assertNotNull(sky);
            assertTrue(sky.isVisible() && sky.coveredHeight() > 0, "Banner sichtbar");
            JViewport viewport = page.scroll().getViewport();
            assertEquals(0, viewport.getViewPosition().y);

            sky.clickChapterForTest(3);
            Navigation.Section target = sky.navigation().chapters().get(3);
            assertEquals("Fehlerbehebung", target.label());
            assertTrue(viewport.getViewPosition().y > 0, "gerollt");
            assertEquals(target.id(), sky.activeChapterId());
            int y = page.positionOf(target);
            assertDirectlyBelow("Abschnitt steht", y - viewport.getViewPosition().y, sky);

            // Scroll-Synchronisierung: ein Stück zurückrollen macht das vorige Kapitel aktiv.
            int previous = page.positionOf(sky.navigation().chapters().get(1));
            viewport.setViewPosition(new java.awt.Point(0, previous));
            assertEquals(sky.navigation().chapters().get(1).id(), sky.activeChapterId());
            assertEquals(sky.targetOffsetForTest(sky.activeChapterId()), sky.stripOffsetForTest(),
                    "aktives Kapitel zentriert");
            assertEquals(text, panel.reader().getText(), "Original unverändert");
        });
    }

    @Test
    void jumpHitLandsBelowTheBannerAndKeepsItsPosition() throws Exception {
        Doc doc = handbook();
        String text = doc.text.toString();
        PageDetailPanel panel = panel(doc, 700, 400);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            JTextPane reader = panel.reader();
            JViewport viewport = panel.readerPage().scroll().getViewport();
            int covered = sky.coveredHeight();
            // vom Ende her nach oben springen (wie jumpTo): der Treffer darf nicht hinter dem Banner landen
            viewport.setViewPosition(new java.awt.Point(0,
                    Math.max(0, viewport.getView().getHeight() - viewport.getExtentSize().height)));
            assertTrue(viewport.getViewPosition().y > 0);
            int start = text.indexOf("Installation Details");
            reader.select(start, start + "Installation Details".length());
            reader.scrollRectToVisible(rect(reader, start));
            panel.readerPage().revealBelowSky(start);
            int top = rect(reader, start).y - viewport.getViewPosition().y;
            assertTrue(top >= covered, "Treffer unter dem Banner: " + top + " vs " + covered);
            assertTrue(top < viewport.getExtentSize().height, "Treffer im Bild");
            assertEquals(covered, sky.coveredHeight(), "Banner bleibt oben");
        });
    }

    private static java.awt.Rectangle rect(JTextPane reader, int offset) {
        try {
            return reader.modelToView2D(offset).getBounds();
        } catch (javax.swing.text.BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void wheelOverTheBannerScrollsSidewaysNotTheDocument() throws Exception {
        PageDetailPanel panel = panel(handbook(), 360, 400);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            JViewport viewport = panel.readerPage().scroll().getViewport();
            int before = sky.stripOffsetForTest();
            MouseWheelEvent wheel = new MouseWheelEvent(sky, MouseWheelEvent.MOUSE_WHEEL, 0L, 0, 50, 10, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1);
            sky.dispatchEvent(wheel);
            assertTrue(wheel.isConsumed(), "Rad verbraucht");
            assertTrue(sky.manualForTest());
            assertTrue(sky.stripOffsetForTest() > before, "waagrecht gerollt");
            assertEquals(0, viewport.getViewPosition().y, "Dokument steht");
        });
    }

    @Test
    void expandingShowsOnlyTheActiveChaptersSubsections() throws Exception {
        PageDetailPanel panel = panel(handbook(), 700, 400);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            sky.clickChapterForTest(1);
            sky.setExpanded(true);
            assertEquals(1, sky.rowSectionsForTest().size());
            assertEquals(1, sky.rowSectionsForTest().get(0).size());
            assertEquals("Installation Details", sky.rowSectionsForTest().get(0).get(0).label());
            sky.setExpanded(false);
            assertTrue(sky.rowSectionsForTest().isEmpty());
        });
    }

    @Test
    void smallWindowShortensLongLabels() throws Exception {
        PageDetailPanel panel = panel(handbook(), 260, 220);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            assertTrue(sky.isVisible());
            String text = sky.chipTextForTest(2);
            assertTrue(text.endsWith("…"), "gekürzt: " + text);
            assertFalse(text.equals(sky.navigation().chapters().get(2).label()));
            assertTrue(sky.coveredHeight() < 220 / 2, "Banner lässt Platz für das Dokument");
        });
    }

    @Test
    void theBannerFloatsAboveTheTextWithoutNarrowingItAndHidesWithoutNavigation() throws Exception {
        PageDetailPanel panel = panel(handbook(), 700, 400);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            NavigableDocumentPage page = panel.readerPage();
            JTextPane reader = panel.reader();
            int width = reader.getWidth();
            assertTrue(width > 0);
            assertEquals(page.getWidth(), sky.getWidth(), "Banner über die volle Breite");
            assertEquals(page.getWidth(), page.scroll().getWidth(), "Text über die volle Breite");
            assertEquals(10 + sky.coveredHeight(), reader.getInsets().top, "oben Platz für den Banner");

            panel.showNavigation(null);
            relayout(panel);
            assertFalse(sky.isVisible(), "ohne Navigation kein Banner");
            assertEquals(0, sky.coveredHeight());
            assertEquals(10, reader.getInsets().top);
            assertEquals(width, reader.getWidth(), "Breite des Texts unverändert");

            panel.showNavigation(new Navigation(Navigation.State.MISSING, Navigation.Analysis.HEADINGS,
                    "no-model", List.of()));
            relayout(panel);
            assertFalse(sky.isVisible(), "leere Navigation: kein Banner");
            assertNull(sky.navigation());
        });
    }

    private static Doc manySteps() {
        Doc doc = new Doc().title("Handbuch");
        doc.heading(1, "Einleitung").paragraph(LONG);
        doc.heading(1, "Installation").paragraph(LONG);
        for (int i = 1; i <= 12; i++) {
            doc.heading(2, "Schritt " + i).paragraph(LONG + LONG);
            for (int k = 1; k <= 3; k++) {
                doc.heading(3, "Teil " + i + "." + k).paragraph(LONG);
            }
        }
        doc.heading(1, "Betrieb").paragraph(LONG);
        return doc;
    }

    @Test
    void expandedLevelsAreRowsThatScrollSidewaysAndClickJumpsToTheSubsection() throws Exception {
        PageDetailPanel panel = panel(manySteps(), 700, 420);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            NavigableDocumentPage page = panel.readerPage();
            JViewport document = page.scroll().getViewport();
            sky.clickChapterForTest(1); // Installation
            sky.setExpanded(true);
            relayout(panel);
            int documentY = document.getViewPosition().y;

            // #110: the twelve steps are one horizontal row, not a list below each other
            assertEquals(1, sky.rowSectionsForTest().size());
            assertEquals(12, sky.rowSectionsForTest().get(0).size());

            // the wheel over the row moves only the row, never the document
            javax.swing.JComponent row = sky.rowForTest(1);
            int before = sky.rowOffsetForTest(1);
            int chapters = sky.stripOffsetForTest();
            MouseWheelEvent wheel = new MouseWheelEvent(row, MouseWheelEvent.MOUSE_WHEEL, 0L, 0, 20, 20, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 2);
            row.dispatchEvent(wheel);
            assertTrue(wheel.isConsumed(), "wheel consumed");
            assertTrue(sky.rowOffsetForTest(1) > before, "row scrolled sideways");
            assertEquals(chapters, sky.stripOffsetForTest(), "chapters stay");
            assertEquals(documentY, document.getViewPosition().y, "Dokument steht");

            // FA-12: Klick auf einen Unterabschnitt springt dorthin, der Abschnitt wird aktiv, seine Teile erscheinen
            Navigation.Section step8 = sky.rowSectionsForTest().get(0).get(7);
            assertEquals("Schritt 8", step8.label());
            sky.clickRowForTest(1, 7);
            relayout(panel);
            assertEquals(step8.id(), sky.activeSectionId());
            assertEquals(sky.navigation().chapters().get(1).id(), sky.activeChapterId());
            assertEquals(2, sky.rowSectionsForTest().size());
            assertEquals("Teil 8.1", sky.rowSectionsForTest().get(1).get(0).label());
            assertDirectlyBelow("Unterabschnitt", page.positionOf(step8) - document.getViewPosition().y, sky);

            // the second level scrolls on its own as well and leaves the first and the document alone
            int first = sky.rowOffsetForTest(1);
            int y = document.getViewPosition().y;
            javax.swing.JComponent parts = sky.rowForTest(2);
            MouseWheelEvent partsWheel = new MouseWheelEvent(parts, MouseWheelEvent.MOUSE_WHEEL, 0L, 0, 20, 20, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1);
            parts.dispatchEvent(partsWheel);
            assertTrue(partsWheel.isConsumed());
            assertEquals(first, sky.rowOffsetForTest(1));
            assertEquals(y, document.getViewPosition().y, "Dokument steht");

            // scrolling the document on into step 9 rebuilds the path below the first row
            Navigation.Section step9 = sky.rowSectionsForTest().get(0).get(8);
            document.setViewPosition(new java.awt.Point(0, page.positionOf(step9) - sky.coveredHeight()));
            relayout(panel);
            assertEquals(step9.id(), sky.activeIdForTest(1));
            assertEquals("Teil 9.1", sky.rowSectionsForTest().get(1).get(0).label());
        });
    }

    @Test
    void detectedTopicSectionsAreJumpable() throws Exception {
        String[] passages = {
                "Die Anwendung übernimmt Belege über die Import-Schnittstelle. Der Import prüft jeden Beleg. " + LONG,
                "Belege werden validiert. Danach wird jeder Beleg vom Import verarbeitet. " + LONG,
                "Ein Urlaubsantrag ist etwas anderes. Der Antrag auf Urlaub geht an die Personalstelle. " + LONG,
                "Urlaub muss genehmigt werden. Der Antrag wird geprüft. " + LONG};
        Doc doc = new Doc();
        for (int p = 0; p < passages.length; p++) {
            if (p == 0) {
                doc.topic("Import von Belegen");
            } else if (p == 2) {
                doc.topic("Urlaubsantrag");
            }
            for (int i = 0; i < 4; i++) {
                doc.paragraph(passages[p]);
            }
        }
        Navigation semantic = doc.navigation(Navigation.Analysis.SEMANTIC);
        assertEquals(2, semantic.chapters().size());

        PageDetailPanel panel = panel(doc.text.toString(), semantic, 700, 400);
        SwingUtilities.invokeAndWait(() -> {
            DocumentNavigationSky sky = panel.navigationSky();
            NavigableDocumentPage page = panel.readerPage();
            JViewport document = page.scroll().getViewport();
            Navigation.Section holiday = sky.navigation().chapters().get(1);
            assertTrue(holiday.isGenerated());
            assertTrue(DocumentNavigationSky.tooltipFor(holiday).endsWith("(automatisch erkannter Abschnitt)"));
            sky.clickChapterForTest(1);
            assertEquals(holiday.id(), sky.activeChapterId());
            assertTrue(document.getViewPosition().y > 0, "gerollt");
            assertDirectlyBelow("Themenabschnitt", page.positionOf(holiday) - document.getViewPosition().y, sky);
        });
    }
}
