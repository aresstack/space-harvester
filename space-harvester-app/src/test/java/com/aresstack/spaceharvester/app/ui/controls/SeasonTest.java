package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import javax.swing.Icon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Angelo 28.11.2026: Vom 1. Advent bis einschließlich 6. Januar zeigt die App dezent Weihnachten (Mütze auf dem Logo,
 * Schnee in der Titelleiste), von selbst und abschaltbar.
 */
class SeasonTest {

    private static final Icon PLAIN = new Icon() {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
        }

        @Override
        public int getIconWidth() {
            return 24;
        }

        @Override
        public int getIconHeight() {
            return 24;
        }
    };

    @AfterEach
    void restore() {
        Season.today(null);
        Season.enabled(true);
    }

    @Test
    void theSeasonRunsFromTheFirstAdventThroughEpiphanyEveryYear() {
        assertThat(Season.firstAdvent(2026)).isEqualTo(LocalDate.of(2026, 11, 29));
        assertThat(Season.firstAdvent(2027)).isEqualTo(LocalDate.of(2027, 11, 28));
        // Fällt der 25. Dezember auf einen Sonntag, ist der 4. Advent der Sonntag davor.
        assertThat(Season.firstAdvent(2022)).isEqualTo(LocalDate.of(2022, 11, 27));
        // Der späteste 1. Advent: Heiligabend ist selbst der 4. Advent.
        assertThat(Season.firstAdvent(2023)).isEqualTo(LocalDate.of(2023, 12, 3));

        assertThat(Season.christmas(LocalDate.of(2026, 11, 28))).isFalse();
        assertThat(Season.christmas(LocalDate.of(2026, 11, 29))).isTrue();
        assertThat(Season.christmas(LocalDate.of(2026, 12, 24))).isTrue();
        assertThat(Season.christmas(LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(Season.christmas(LocalDate.of(2027, 1, 6))).as("einschließlich 6. Januar").isTrue();
        assertThat(Season.christmas(LocalDate.of(2027, 1, 7))).isFalse();
        assertThat(Season.christmas(LocalDate.of(2027, 7, 1))).isFalse();
    }

    @Test
    void theSwitchTurnsTheSeasonOff() {
        Season.today(() -> LocalDate.of(2026, 12, 10));
        assertThat(Season.christmasNow()).isTrue();
        Season.enabled(false);
        assertThat(Season.christmasNow()).isFalse();
        Season.enabled(true);
        Season.today(() -> LocalDate.of(2026, 6, 10));
        assertThat(Season.christmasNow()).isFalse();
    }

    @Test
    void theHOfHarvesterWearsARedHatAndTheBarCarriesSnowOnlyInTheSeason() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var perspectives = new com.aresstack.spaceharvester.app.ui.perspective.Perspectives();
            perspectives.add(perspective("harvester", "Space Harvester"));
            perspectives.add(perspective("deck", "Command Deck"));
            PerspectiveSwitcher switcher = new PerspectiveSwitcher(PLAIN, perspectives);
            switcher.setSize(switcher.getPreferredSize());
            layoutAll(switcher);

            Season.today(() -> LocalDate.of(2026, 6, 10));
            assertThat(switcher.hatLetter()).as("außerhalb der Weihnachtszeit keine Mütze").isNull();
            assertThat(red(switcher)).isZero();
            assertThat(snow()).isZero();

            Season.today(() -> LocalDate.of(2026, 12, 10));
            java.awt.Rectangle letter = switcher.hatLetter();
            assertThat(letter).isNotNull();
            assertThat(red(switcher)).as("rote Mütze").isGreaterThan(10);
            assertThat(snow()).as("Schnee in der Leiste").isPositive();
            // Die Mütze sitzt auf dem „H“: Alles Rote liegt bei ihm, nicht beim Logo am Anfang.
            BufferedImage image = paint(switcher);
            java.awt.Component title = switcher.getComponent(1);
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    if (isRed(image.getRGB(x, y))) {
                        assertThat(x - title.getX()).isBetween(letter.x - SantaHat.WIDTH, letter.x + SantaHat.WIDTH + 4);
                        assertThat(y - title.getY()).isLessThan(letter.y + letter.height);
                    }
                }
            }

            perspectives.select("deck");
            layoutAll(switcher);
            assertThat(switcher.hatLetter()).as("ohne „Harvester“ im Titel keine Mütze").isNull();
            perspectives.select("harvester");

            Season.enabled(false);
            assertThat(switcher.hatLetter()).isNull();
            assertThat(red(switcher)).isZero();
            assertThat(snow()).isZero();
        });
    }

    private static com.aresstack.spaceharvester.app.ui.perspective.Perspective perspective(String id, String title) {
        return new com.aresstack.spaceharvester.app.ui.perspective.Perspective(id, title, "", true,
                new javax.swing.JPanel(), new javax.swing.JPanel(), null, null, () -> { }, () -> { });
    }

    private static void layoutAll(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    private static BufferedImage paint(java.awt.Component component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
        return image;
    }

    private static boolean isRed(int argb) {
        Color color = new Color(argb, true);
        return color.getAlpha() > 200 && color.getRed() > 150 && color.getGreen() < 110 && color.getBlue() < 110;
    }

    private static int red(java.awt.Component component) {
        BufferedImage image = paint(component);
        int red = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (isRed(image.getRGB(x, y))) {
                    red++;
                }
            }
        }
        return red;
    }

    private static int snow() {
        BufferedImage image = new BufferedImage(600, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        Snow.paint(g, 600, 48);
        g.dispose();
        return count(image);
    }

    private static int count(BufferedImage image) {
        int count = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
