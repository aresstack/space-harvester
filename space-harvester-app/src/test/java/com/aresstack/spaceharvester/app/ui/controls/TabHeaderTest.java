package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kopfleiste der Reiter, schmal (UX W4b): zuerst wird das Suchfeld schmaler (bis etwa 160 px), dann fallen Abzeichen
 * und Hinweis weg, dann kürzt sich der Titel mit „…“ und verschwindet, sobald weniger als vier Zeichen blieben.
 * Werkzeuge behalten immer ihre volle Breite; nichts überdeckt sich.
 */
class TabHeaderTest {

    private static final String HEADING = "Begriffe dieser Seite";

    private final List<IconButton> buttons = new ArrayList<>();

    /** Eine Leiste wie die des Wissensgraphen: Titel, Abzeichen, Hinweis, Suche, drei Aktionen, Kopieren, sieben Werkzeuge. */
    private TabHeader header() {
        TabHeader header = new TabHeader(HEADING);
        header.badge("Experimentell", Comic.PETROL)
                .note("nur manuell analysierte Seiten · Parserbefunde, nicht fachlich geprüft");
        header.search(InputFrame.search(new JTextField(16)));
        for (Glyph glyph : new Glyph[] {Glyph.CROSSHAIR, Glyph.NEIGHBOURS, Glyph.BACK}) {
            IconButton button = new IconButton(glyph, glyph.name());
            buttons.add(button);
            header.action(button);
        }
        header.copy(() -> "Text");
        // Wie im Reiter „Graph“: „Zurück zur Gesamtsicht“ (W4b) vorn, „Wissensgraph erzeugen“ (W5) direkt vor dem
        // Export.
        for (Glyph glyph : new Glyph[] {Glyph.OVERVIEW, Glyph.FILTER, Glyph.FIT, Glyph.TABLE, Glyph.GRAPH_PLUS,
                Glyph.EXPORT, Glyph.TRASH}) {
            IconButton button = new IconButton(glyph, glyph.name());
            buttons.add(button);
            header.tool(button);
        }
        return header;
    }

    private static void layout(TabHeader header, int width) {
        header.setSize(width, header.getPreferredSize().height);
        // Zweimal: Das Kürzen des Titels ändert seinen Text und stößt das Layout erneut an.
        for (int round = 0; round < 2; round++) {
            layoutTree(header);
        }
    }

    private static void layoutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static Rectangle inHeader(TabHeader header, Component component) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), header);
    }

    /** Prüft die Regeln für eine Breite und liefert den gezeigten Titel ({@code null}: ausgeblendet). */
    private String check(TabHeader header, int width) {
        layout(header, width);
        for (IconButton button : buttons) {
            assertThat(button.getWidth()).as("Werkzeug %s volle Breite bei %d px", button.getToolTipText(), width)
                    .isEqualTo(button.getPreferredSize().width);
        }
        assertThat(header.copyButton().getWidth()).isEqualTo(header.copyButton().getPreferredSize().width);
        JComponent search = header.searchPart();
        int searchPref = search.getPreferredSize().width;
        assertThat(search.getWidth()).as("Suchfeld bei %d px", width)
                .isBetween(Math.min(TabHeader.SEARCH_MIN, searchPref), searchPref);
        assertThat(search.getComponent(0).getWidth()).isEqualTo(search.getWidth());

        Rectangle left = header.leftPart().getBounds();
        Rectangle field = search.getBounds();
        Rectangle right = header.rightPart().getBounds();
        assertThat(left.x + left.width).as("links vor dem Suchfeld").isLessThanOrEqualTo(field.x);
        assertThat(field.x + field.width).as("Suchfeld vor den Werkzeugen").isLessThanOrEqualTo(right.x);
        Rectangle title = inHeader(header, header.titleLabel());
        if (title.width > 0) {
            assertThat(title.x + title.width).as("Titel innerhalb der linken Seite")
                    .isLessThanOrEqualTo(left.x + left.width);
        }
        for (IconButton button : buttons) {
            Rectangle tool = inHeader(header, button);
            assertThat(tool.intersects(field)).as("Werkzeug überdeckt das Suchfeld nicht").isFalse();
            assertThat(tool.intersects(left)).as("Werkzeug überdeckt den Titel nicht").isFalse();
        }

        double fieldCentre = field.getCenterY();
        for (IconButton button : buttons) {
            assertThat(inHeader(header, button).getCenterY()).as("Knopf %s senkrecht mittig zum Suchfeld",
                    button.getToolTipText()).isCloseTo(fieldCentre, org.assertj.core.data.Offset.offset(1.0));
        }

        if (title.width > 0) {
            assertThat(title.getCenterY()).as("Titel senkrecht mittig zum Suchfeld bei %d px", width)
                    .isCloseTo(fieldCentre, org.assertj.core.data.Offset.offset(1.0));
        }

        String shown = header.titleShown();
        if (shown != null && !shown.equals(HEADING)) {
            assertThat(shown).endsWith(TabHeader.ELLIPSIS);
            String prefix = shown.substring(0, shown.length() - TabHeader.ELLIPSIS.length());
            assertThat(prefix.codePointCount(0, prefix.length())).as("nie nur „…“")
                    .isGreaterThanOrEqualTo(TabHeader.TITLE_MIN_CHARS);
            assertThat(HEADING).startsWith(prefix);
        }
        if (search.getWidth() > Math.min(TabHeader.SEARCH_MIN, searchPref)) {
            assertThat(shown).as("solange das Suchfeld noch schrumpfen kann, steht der Titel ganz").isEqualTo(HEADING);
        }
        if (shown == null || !shown.equals(HEADING)) {
            assertThat(search.getWidth()).as("der Titel weicht erst, wenn das Suchfeld schon schmal ist")
                    .isEqualTo(Math.min(TabHeader.SEARCH_MIN, searchPref));
        }
        assertThat(header.titleText()).as("titleText bleibt ungekürzt").isEqualTo(HEADING);
        return shown;
    }

    @Test
    void wideHeaderShowsEverythingAtPreferredWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TabHeader header = header();
            String shown = check(header, header.getPreferredSize().width + 40);
            assertThat(shown).isEqualTo(HEADING);
            assertThat(header.searchPart().getWidth()).isEqualTo(header.searchPart().getPreferredSize().width);
            assertThat(header.leftPart().getComponent(1).getWidth()).as("Abzeichen sichtbar").isPositive();
        });
    }

    @Test
    void at900PixelsSearchShrinksFirstAndToolsKeepTheirWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> check(header(), 900));
    }

    @Test
    void shrinkOrderSearchThenTitleThenHidden() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TabHeader header = header();
            layout(header, 1200);
            int rightWidth = header.rightPart().getPreferredSize().width;
            int titleWidth = header.titleLabel().getPreferredSize().width;
            int floor = rightWidth + TabHeader.SEARCH_MIN + 12 + 8;
            // Suchfeld schon am Minimum, Titel passt gerade noch ganz.
            assertThat(check(header, floor + titleWidth + 8)).isEqualTo(HEADING);
            // Etwas schmaler: Titel gekürzt mit „…“, mindestens vier Zeichen.
            String truncated = check(header, floor + titleWidth / 2);
            assertThat(truncated).isNotNull().endsWith(TabHeader.ELLIPSIS).isNotEqualTo(HEADING);
            // Kaum Platz: Titel ganz weg statt „B…“ oder „…“.
            assertThat(check(header, floor + 20)).isNull();
            // Jede Breite dazwischen hält die Regeln ein.
            for (int width = floor; width <= floor + titleWidth + 300; width += 7) {
                check(header, width);
            }
        });
    }

    /** Ein Hinweis, der auch neben dem schmalsten Suchfeld nicht passt, kostet das Suchfeld keine Breite. */
    @Test
    void searchDoesNotShrinkForANoteThatIsDroppedAnyway() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TabHeader header = header();
            layout(header, 1600);
            JComponent left = header.leftPart();
            int titleWidth = left.getComponent(0).getPreferredSize().width;
            int badgeWidth = left.getComponent(1).getPreferredSize().width;
            int noteWidth = left.getComponent(2).getPreferredSize().width;
            int searchPref = header.searchPart().getPreferredSize().width;
            assertThat(noteWidth + 8).as("der Hinweis passt auch mit schmalstem Suchfeld nicht")
                    .isGreaterThan(searchPref - TabHeader.SEARCH_MIN + 20);
            int width = header.rightPart().getPreferredSize().width + 12 + searchPref + 8 + titleWidth + 8
                    + badgeWidth + 8 + 10;
            assertThat(check(header, width)).isEqualTo(HEADING);
            assertThat(header.searchPart().getWidth()).as("Suchfeld behält seine bevorzugte Breite")
                    .isEqualTo(searchPref);
            assertThat(left.getComponent(1).getWidth()).as("Abzeichen ganz sichtbar").isEqualTo(badgeWidth);
            assertThat(left.getComponent(2).getWidth()).as("Hinweis fällt weg").isZero();
        });
    }

    /** Status-Abzeichen und Knöpfe stehen auf einer Linie mit dem Suchfeld. */
    @Test
    void statusAndToolsAreVerticallyCentredWithTheSearchField() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TabHeader header = header();
            header.status("Aktuell", Comic.PETROL);
            for (int width : new int[] {1600, 1280, 960}) {
                check(header, width);
                Rectangle field = header.searchPart().getBounds();
                Rectangle status = null;
                for (Component part : header.rightPart().getComponents()) {
                    if (part instanceof javax.swing.JLabel label && "Aktuell".equals(label.getText())) {
                        status = inHeader(header, label);
                    }
                }
                assertThat(status).isNotNull();
                assertThat(status.getCenterY()).as("Stand mittig bei %d px", width)
                        .isCloseTo(field.getCenterY(), org.assertj.core.data.Offset.offset(1.0));
            }
        });
    }

    /**
     * Bei 900 px bleibt die Leiste wie im Reiter „Graph“ mit „Wissensgraph erzeugen“ eine Zeile (W5, UX): Ihre Höhe ist
     * die einer Zeile, jeder Teil liegt darin, und der Hinweis läuft nicht ins Suchfeld, sondern fällt weg.
     */
    @Test
    void at900PixelsTheHeaderStaysOneLineAndTheNoteNeverRunsIntoTheSearchField() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TabHeader header = header();
            check(header, 900);
            int row = 0;
            for (JComponent part : new JComponent[] {header.leftPart(), header.searchPart(), header.rightPart()}) {
                row = Math.max(row, part.getPreferredSize().height);
                assertThat(part.getY()).isGreaterThanOrEqualTo(0);
                assertThat(part.getY() + part.getHeight()).isLessThanOrEqualTo(header.getHeight());
            }
            assertThat(header.getPreferredSize().height).as("eine Zeile").isEqualTo(row);
            JComponent left = header.leftPart();
            Rectangle field = header.searchPart().getBounds();
            for (Component shown : left.getComponents()) {
                if (shown.getWidth() > 0) {
                    assertThat(inHeader(header, shown).intersects(field)).as("%s überdeckt das Suchfeld nicht",
                            shown).isFalse();
                }
            }
            Rectangle note = inHeader(header, left.getComponent(2));
            assertThat(note.width == 0 || note.x + note.width <= field.x).as("der Hinweis endet vor dem Suchfeld")
                    .isTrue();
        });
    }

    @Test
    void veryNarrowHeaderKeepsToolsAndSearchWithoutOverlap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TabHeader header = header();
            layout(header, 1200);
            int narrow = header.rightPart().getPreferredSize().width + TabHeader.SEARCH_MIN + 12;
            assertThat(check(header, narrow)).isNull();
        });
    }

    @Test
    void headerWithoutSearchStillTruncatesTheTitle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TabHeader header = new TabHeader(HEADING).copy(() -> "Text");
            layout(header, 1000);
            assertThat(header.titleShown()).isEqualTo(HEADING);
            int rightWidth = header.rightPart().getPreferredSize().width;
            layout(header, rightWidth + 12 + 8 + 30);
            String shown = header.titleShown();
            assertThat(shown == null || shown.endsWith(TabHeader.ELLIPSIS)
                    && shown.length() - 1 >= TabHeader.TITLE_MIN_CHARS).isTrue();
            assertThat(header.copyButton().getWidth()).isEqualTo(header.copyButton().getPreferredSize().width);
        });
    }
}
