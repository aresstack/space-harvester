package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Bildschirmfotos des Reiters „Verweise“ mit beiden Richtungen (Paket V2) zur Sichtprüfung, 1280 und 960 px; die
 * Zeile unten zeigt den Text, den der Reiter in die Statuszeile gibt. Läuft nur mit {@code SCREENSHOTS_DIR}.
 */
class ReferencesScreenshotTest {

    @Test
    void renderReferencesTab() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        PageNavigation.References references = new PageNavigation.References("wiki", "1",
                List.of(new PageNode("2", "Plan", "DEV", null),
                        new PageNode("3", "Ziel", "DEV", null),
                        new PageNode("4", "Nachtlauf", "DEV", null)),
                List.of(new PageNode("5", "Start", "DEV", null),
                        new PageNode("6", "Betrieb", "DEV", null),
                        new PageNode("7", null, null, null)),
                new PageLinks.Coverage(184, 210));
        for (int shot = 0; shot < 4; shot++) {
            int width = shot % 2 == 0 ? 1280 : 960;
            boolean withSubpages = shot >= 2;
            JPanel[] surface = new JPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                References view = new References();
                JLabel status = Comic.muted(" ");
                view.onStatus(status::setText);
                view.show(references);
                if (withSubpages) {
                    view.belowLoading(2);
                    view.below(List.of(
                            new PageNavigation.SubpageReference(new PageNode("11", "Zahlungen", "DEV", null), true,
                                    new PageNode("8", "Kassenbuch", "DEV", null)),
                            new PageNavigation.SubpageReference(new PageNode("11", "Zahlungen", "DEV", null), true,
                                    new PageNode("2", "Plan", "DEV", null)),
                            new PageNavigation.SubpageReference(new PageNode("12",
                                    "Belege und Buchungskennzeichen im Jahresabschluss", "DEV", null), false,
                                    new PageNode("9", "Revision", "OPS", null))));
                }
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.setBackground(Comic.SURFACE);
                panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
                panel.add(view, BorderLayout.CENTER);
                panel.add(status, BorderLayout.SOUTH);
                panel.setSize(width, 420);
                panel.addNotify();
                panel.validate();
                layout(panel);
                surface[0] = panel;
            });
            SwingUtilities.invokeAndWait(() -> { });
            Path file = out.resolve((withSubpages ? "verweise-unterseiten-" : "verweise-") + width + ".png");
            SwingUtilities.invokeAndWait(() -> write(surface[0], file));
        }
    }

    /** Doppelt so groß gemalt, damit die Schrift scharf bleibt. */
    private static void write(JPanel panel, Path file) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(panel.getWidth() * 2,
                panel.getHeight() * 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(2, 2);
        panel.printAll(g);
        g.dispose();
        try {
            javax.imageio.ImageIO.write(image, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void layout(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layout(child);
            }
        }
    }
}
