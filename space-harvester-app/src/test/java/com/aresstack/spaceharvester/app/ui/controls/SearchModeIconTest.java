package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Die Lupe als Umschalter der Suche (Angelo 28.11.2026: „W“ hinter der Lupe, gleich groß wie die einfache Lupe). */
class SearchModeIconTest {

    @Test
    void theWordIconIsAsLargeAsThePlainOneAndItsLetterLiesBehindTheGlass() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SearchModeIcon icon = new SearchModeIcon();
            java.awt.Dimension plain = icon.getPreferredSize();
            BufferedImage page = paint(icon);
            icon.word(true);
            assertThat(icon.getPreferredSize()).as("nicht breiter als die einfache Lupe").isEqualTo(plain);
            BufferedImage word = paint(icon);

            // Die Lupe selbst steht in beiden Symbolen an derselben Stelle: ihr Griff unten rechts ist gleich.
            int cx = plain.width / 2 - 1;
            int cy = plain.height / 2 - 1;
            assertThat(word.getRGB(cx + 6, cy + 6)).isEqualTo(page.getRGB(cx + 6, cy + 6));

            // Das „W“ liegt ganz im Glas, in dessen oberem Teil (der Blick geht von unten durch die Lupe), und ist
            // nach oben schmaler (nach hinten gekippt).
            Rectangle2D letter = SearchModeIcon.letter(cx, cy - SearchModeIcon.LETTER_RISE).getBounds2D();
            int radius = SearchModeIcon.RADIUS;
            assertThat(letter.getMinX()).isGreaterThan(cx - radius);
            assertThat(letter.getMaxX()).isLessThan(cx + radius);
            assertThat(letter.getMinY()).isGreaterThan(cy - radius);
            assertThat(letter.getCenterY()).as("im oberen Teil des Glases").isLessThan(cy);
            assertThat(differs(page, word, cx - 3, cy - 4, 7, 6)).as("scheint durch das Glas").isTrue();
            assertThat(differs(page, word, 0, 0, plain.width, cy - radius - 1)).as("nichts über dem Glas").isFalse();
        });
    }

    private static boolean differs(BufferedImage a, BufferedImage b, int x, int y, int width, int height) {
        for (int row = Math.max(0, y); row < Math.min(a.getHeight(), y + height); row++) {
            for (int column = Math.max(0, x); column < Math.min(a.getWidth(), x + width); column++) {
                if (a.getRGB(column, row) != b.getRGB(column, row)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BufferedImage paint(SearchModeIcon icon) {
        icon.setSize(icon.getPreferredSize());
        BufferedImage image = new BufferedImage(icon.getWidth(), icon.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        icon.paint(g);
        g.dispose();
        return image;
    }
}
