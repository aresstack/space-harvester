package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Das Quellenformular passt in die Breite seines Fensters: keine Beschriftung ist abgeschnitten, kein Feld ragt
 * rechts hinaus, und es gibt keinen waagerechten Scrollbalken (Angelo, 09.11.).
 */
class SourceFormLayoutTest {

    @TempDir
    Path home;

    @Test
    void theFormFillsTheDialogWidthWithoutHorizontalScrolling() throws Exception {
        check(null, false, System.getenv("SOURCEFORM_SCREENSHOT"));
    }

    /** Mit KI-Dienst steht die Freigabe (lange Häkchen-Zeile) im Hauptteil; auch sie sprengt die Breite nicht. */
    @Test
    void theReleaseRowFitsWhenAnAiServiceIsConfigured() throws Exception {
        String screenshot = System.getenv("SOURCEFORM_SCREENSHOT");
        check("""
                space-harvester:
                  ai:
                    base-url: https://ai.example.invalid/v1
                    embedding-model: text-embedding-3-small
                """, false, screenshot == null ? null : screenshot.replace(".png", "-ki.png"));
    }

    /** Im KeePass-Modus sitzt der Koppeln-Knopf IM Auswahlfeld des Eintrags (InputFrame), nicht daneben. */
    @Test
    void theKeePassRowKeepsItsButtonInsideTheField() throws Exception {
        String screenshot = System.getenv("SOURCEFORM_SCREENSHOT");
        check(null, true, screenshot == null ? null : screenshot.replace(".png", "-keepass.png"));
    }

    /**
     * Eine Meldung nach „Verbindung testen“ nimmt nur die Höhe ihres Texts ein, schon beim ersten Einblenden; das
     * Formular bleibt sichtbar und bedienbar (Angelo, 09.11. 20:19: Die Box füllte den ganzen Dialog).
     */
    @Test
    void aMessageKeepsTheFormVisibleOnFirstAppearance() throws Exception {
        AppContext context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home),
                home.resolve("app.yml"), "test");
        try {
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
            assertThat(started.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> {
                SourceForm form = new SourceForm(context);
                JComponent root = form.component();
                root.setSize(form.preferredSize());
                // Wie im angezeigten Dialog: Die noch unsichtbare Meldung wird mit Breite 0 mit ausgelegt.
                root.addNotify();
                root.validate();
                JScrollPane scroll = find(root, JScrollPane.class).get(0);
                int before = scroll.getHeight();

                form.feedback.warning("Verbindung fehlgeschlagen", "Zertifikat fehlt oder wird nicht vertraut");
                root.validate();

                int line = form.feedback.getFontMetrics(Comic.regular(12.5f)).getHeight();
                assertThat(form.feedback.getHeight()).as("Meldung nur so hoch wie Überschrift und eine Zeile Text")
                        .isLessThanOrEqualTo(3 * line + 40);
                assertThat(scroll.getY()).as("Formular beginnt oben").isZero();
                assertThat(scroll.getHeight()).as("Formular gibt nur die Höhe der Meldung ab")
                        .isGreaterThanOrEqualTo(before - form.feedback.getHeight() - 16);
                assertThat(form.test.getY() + form.test.getParent().getY() + form.test.getParent().getParent().getY())
                        .as("Knopfleiste bleibt im Fenster").isLessThan(root.getHeight());
            });
        } finally {
            context.shutdown();
        }
    }

    private void check(String yaml, boolean keePass, String screenshot) throws Exception {
        Path config = home.resolve("app.yml");
        Files.createDirectories(home);
        if (yaml != null) {
            Files.writeString(config, yaml);
        }
        AppContext context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config,
                "test");
        try {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            SourceForm form = new SourceForm(context);
            boolean ai = context.settings() != null && context.settings().ai().configured();
            assertThat(ai).as("KI-Dienst konfiguriert").isEqualTo(yaml != null);
            assertThat(form.release.isVisible()).as("Freigabe nur mit KI-Dienst im Hauptteil").isEqualTo(ai);
            assertThat(form.id.isVisible()).as("Kennung unter Erweitert").isFalse();
            assertThat(form.certificate.isVisible()).as("Zertifikat unter Erweitert").isFalse();
            if (keePass) {
                form.origin.setSelectedItem(AiSettingsPanel.KEEPASS);
                assertThat(form.pairKeePass.isVisible()).as("Koppeln-Knopf sichtbar").isTrue();
                assertThat(SwingUtilities.getAncestorOfClass(com.aresstack.spaceharvester.app.ui.controls.InputFrame.class,
                        form.pairKeePass)).as("Koppeln-Knopf im Feld").isNotNull();
                assertThat(SwingUtilities.getAncestorOfClass(com.aresstack.spaceharvester.app.ui.controls.InputFrame.class,
                        form.keePassEntry)).as("Eintrag im selben Feld")
                        .isSameAs(SwingUtilities.getAncestorOfClass(
                                com.aresstack.spaceharvester.app.ui.controls.InputFrame.class, form.pairKeePass));
            }
            JComponent root = form.component();
            Dimension size = form.preferredSize();
            assertThat(size.width).isEqualTo(720);
            root.setSize(size);
            layout(root);

            JScrollPane scroll = find(root, JScrollPane.class).get(0);
            JViewport viewport = scroll.getViewport();
            Component sheet = viewport.getView();
            assertThat(scroll.getHorizontalScrollBar().isVisible()).as("kein waagerechter Scrollbalken").isFalse();
            assertThat(sheet.getWidth()).as("Inhalt so breit wie der Sichtbereich").isEqualTo(viewport.getWidth());

            for (JLabel label : find(root, JLabel.class)) {
                if (!label.isShowing() && !visible(label)) {
                    continue;
                }
                String text = label.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                int needed = label.getFontMetrics(label.getFont()).stringWidth(text);
                assertThat(label.getWidth()).as("Beschriftung „%s“ nicht abgeschnitten", text)
                        .isGreaterThanOrEqualTo(Math.min(needed, SourceForm.Form.LABEL_WIDTH));
            }
            for (Component field : find(root, JComponent.class)) {
                if (!visible(field) || field.getWidth() == 0) {
                    continue;
                }
                if (SwingUtilities.isDescendingFrom(field, sheet) && field != sheet) {
                    java.awt.Point where = SwingUtilities.convertPoint(field.getParent(), field.getLocation(), sheet);
                    assertThat(where.x + field.getWidth()).as("%s ragt nicht rechts hinaus", field.getClass()
                            .getSimpleName()).isLessThanOrEqualTo(sheet.getWidth());
                }
            }

            // Zur Ansicht: ein Bild des Formulars (nur für die Entwicklung, nicht geprüft).
            if (screenshot != null) {
                BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = image.createGraphics();
                g.setColor(Comic.SURFACE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                root.print(g);
                g.dispose();
                try {
                    javax.imageio.ImageIO.write(image, "png", Path.of(screenshot).toFile());
                    form.sourceAdvanced(true);
                    layout(root);
                    BufferedImage open = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                    g = open.createGraphics();
                    g.setColor(Comic.SURFACE);
                    g.fillRect(0, 0, open.getWidth(), open.getHeight());
                    root.print(g);
                    g.dispose();
                    javax.imageio.ImageIO.write(open, "png", Path.of(screenshot.replace(".png", "-erweitert.png")).toFile());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        } finally {
            context.shutdown();
        }
    }

    private static boolean visible(Component component) {
        for (Component c = component; c != null; c = c.getParent()) {
            if (!c.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Legt den Baum aus wie ein sichtbares Fenster: zweimal, damit umbrechende Hinweise ihre Höhe bekommen. */
    static void layout(Component root) {
        for (int pass = 0; pass < 3; pass++) {
            root.doLayout();
            layoutChildren(root);
            root.validate();
        }
    }

    private static void layoutChildren(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutChildren(child);
            }
        }
    }

    static <T> List<T> find(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T> void collect(Component component, Class<T> type, List<T> found) {
        if (type.isInstance(component)) {
            found.add(type.cast(component));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, type, found);
            }
        }
    }
}
