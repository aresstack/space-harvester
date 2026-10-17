package com.aresstack.spaceharvester.app.ui;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Rendert jeden Bereich des Hauptfensters ohne Fenster als PNG ({@code --screenshot}); dient der Sichtprüfung in CI
 * und beim Entwickeln. Ohne Bildschirm (headless) fehlen Texte in Listen mit zusammengesetzten Zellen; für
 * getreue Bilder unter Xvfb starten.
 */
public final class Screenshots {

    static final int WIDTH = 1280;
    static final int HEIGHT = 820;

    private Screenshots() {
    }

    public static void render(AppContext context, Path directory) throws Exception {
        Files.createDirectories(directory);
        MainWindow[] window = new MainWindow[1];
        JPanel[] surface = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            window[0] = new MainWindow(context);
            surface[0] = window[0].content(() -> { }, () -> { });
            surface[0].setSize(WIDTH, HEIGHT);
            if (!GraphicsEnvironment.isHeadless()) {
                // Mit Bildschirm (z. B. Xvfb) in ein echtes Fenster setzen: Zellrenderer aus mehreren Komponenten
                // werden nur in angezeigten Fenstern gelayoutet.
                JFrame frame = new JFrame("Space Harvester");
                frame.setUndecorated(true);
                frame.setContentPane(surface[0]);
                frame.setSize(WIDTH, HEIGHT);
                frame.setVisible(true);
            }
        });
        for (AppContext.Section section : AppContext.Section.values()) {
            SwingUtilities.invokeAndWait(() -> window[0].show(section));
            // Hintergrundarbeit (Verbindungsprüfung, Auftragsliste) abwarten und erneut layouten.
            Thread.sleep(1500);
            SwingUtilities.invokeAndWait(() -> write(surface[0], directory.resolve(String.format(Locale.ROOT,
                    "%d-%s.png", section.ordinal() + 1, section.name().toLowerCase(Locale.ROOT)))));
        }
    }

    static void write(JComponent component, Path file) {
        // Zweimal: umbrechende Texte kennen ihre Höhe erst, wenn sie ihre Breite haben.
        layout(component);
        layout(component);
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        component.printAll(g);
        g.dispose();
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void layout(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layout(child);
            }
        }
    }
}
