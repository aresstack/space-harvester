package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import org.junit.jupiter.api.Test;

/**
 * Der Trenner sieht in beiden Lagen gleich aus: eine Tuschelinie entlang der Mitte und der gelbe Griff in der Mitte
 * der Linie; keine Punkte des Standard-Teilers, kein Rahmen des Look-and-feel.
 */
class ComicDividerTest {

    private final ComicPalette palette = ComicPalette.defaultPalette();

    @Test
    void aLyingDividerDrawsTheLineAndTheGripAcross() throws Exception {
        BufferedImage image = paint(JSplitPane.VERTICAL_SPLIT, 400, ComicDivider.THICKNESS);
        int middle = ComicDivider.THICKNESS / 2;
        // Griff in der Mitte der Linie, Tuschelinie links und rechts davon.
        assertThat(new Color(image.getRGB(200, middle))).isEqualTo(palette.getAccentYellow());
        assertThat(new Color(image.getRGB(40, middle))).isEqualTo(palette.getInk());
        assertThat(new Color(image.getRGB(360, middle))).isEqualTo(palette.getInk());
        // Außerhalb der Linie nur die Fläche.
        assertThat(new Color(image.getRGB(40, 0))).isEqualTo(Color.WHITE);
    }

    @Test
    void aStandingDividerDrawsTheLineAndTheGripDown() throws Exception {
        BufferedImage image = paint(JSplitPane.HORIZONTAL_SPLIT, ComicDivider.THICKNESS, 400);
        int middle = ComicDivider.THICKNESS / 2;
        assertThat(new Color(image.getRGB(middle, 200))).isEqualTo(palette.getAccentYellow());
        assertThat(new Color(image.getRGB(middle, 40))).isEqualTo(palette.getInk());
        assertThat(new Color(image.getRGB(middle, 360))).isEqualTo(palette.getInk());
        assertThat(new Color(image.getRGB(0, 40))).isEqualTo(Color.WHITE);
    }

    @Test
    void installReplacesTheStandardDividerWithoutAFrame() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSplitPane pane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JPanel(), new JPanel());
            ComicDivider.install(pane, palette);
            assertThat(pane.getBorder()).isNull();
            assertThat(pane.getDividerSize()).isEqualTo(ComicDivider.THICKNESS);
            assertThat(((BasicSplitPaneUI) pane.getUI()).getDivider()).isInstanceOf(ComicDivider.class);
            assertThat(((BasicSplitPaneUI) pane.getUI()).getDivider().getBorder()).isNull();
        });
    }

    private BufferedImage paint(int orientation, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            JSplitPane pane = new JSplitPane(orientation, new JPanel(), new JPanel());
            pane.setBackground(Color.WHITE);
            ComicDivider.install(pane, palette);
            var divider = ((BasicSplitPaneUI) pane.getUI()).getDivider();
            divider.setSize(width, height);
            var g = image.createGraphics();
            divider.paint(g);
            g.dispose();
        });
        return image;
    }
}
