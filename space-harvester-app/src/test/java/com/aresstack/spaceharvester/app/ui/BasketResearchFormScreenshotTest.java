package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Bildschirmfotos der Eingabe „Buch schreiben“ zur Sichtprüfung: zu, „Erweitert“ aufgeklappt und größer gezogen.
 * Erfundene Inhalte. Läuft nur mit der Umgebungsvariable {@code SCREENSHOTS_DIR}, allein gestartet und mit Bildschirm
 * (etwa {@code xvfb-run -a -s "-screen 0 1920x1080x24"}).
 */
class BasketResearchFormScreenshotTest {

    @Test
    void renderBookForm() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        System.setProperty("java.awt.headless", "false");
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm (Xvfb)");
        Path out = Path.of(directory);
        Files.createDirectories(out);
        JFrame[] owner = new JFrame[1];
        BasketResearchForm form = BasketResearchForm.book();
        SwingUtilities.invokeAndWait(() -> {
            owner[0] = new JFrame("Space Harvester");
            owner[0].setSize(1600, 1000);
            owner[0].setVisible(true);
            form.mission.setText("Wie pflegt man eine Streuobstwiese über das Jahr, und was bringt sie der Artenvielfalt?");
            form.included.setText("Obstbaumschnitt\nMahd");
            form.excluded.setText("Gewerblicher Obstbau");
        });
        SwingUtilities.invokeLater(() -> form.ask(owner[0], "Streuobst"));
        JDialog dialog = waitForDialog();
        settle();
        SwingUtilities.invokeAndWait(() -> write(dialog.getRootPane(), out.resolve("buch-form-zu.png")));
        SwingUtilities.invokeAndWait(() -> find(dialog, "Erweitert").orElseThrow().doClick());
        settle();
        SwingUtilities.invokeAndWait(() -> write(dialog.getRootPane(), out.resolve("buch-form-erweitert.png")));
        SwingUtilities.invokeAndWait(() -> {
            dialog.setSize(dialog.getWidth() + 300, dialog.getHeight() + 150);
            dialog.validate();
        });
        settle();
        SwingUtilities.invokeAndWait(() -> write(dialog.getRootPane(), out.resolve("buch-form-groesser.png")));
        SwingUtilities.invokeAndWait(() -> {
            dialog.dispose();
            owner[0].dispose();
        });
    }

    private static JDialog waitForDialog() throws Exception {
        for (int i = 0; i < 100; i++) {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()) {
                    return dialog;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("kein Dialog");
    }

    private static Optional<AbstractButton> find(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) {
                return Optional.of(button);
            }
            if (child instanceof Container container) {
                Optional<AbstractButton> found = find(container, text);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(150);
        }
    }

    private static void write(JComponent component, Path file) {
        try {
            component.validate();
            BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            component.paint(g);
            g.dispose();
            ImageIO.write(image, "png", file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
