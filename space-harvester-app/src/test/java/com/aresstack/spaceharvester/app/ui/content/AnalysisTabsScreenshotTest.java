package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bildschirmfotos der Analysen-Unterreiter mit der gemeinsamen Kopfleiste zur Sichtprüfung; läuft nur mit
 * der Umgebungsvariable {@code SCREENSHOTS_DIR}.
 */
class AnalysisTabsScreenshotTest {

    @Test
    void renderAnalysisTabs() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        SwingUtilities.invokeAndWait(() -> {
            PassagesTab passages = new PassagesTab();
            passages.show(PassagesTab.State.CURRENT, List.of(
                    new PassagesTab.Passage("p0", "Architektur und Komponenten", "Die Systemarchitektur gliedert "
                            + "sich in drei Schichten …", 0, 120),
                    new PassagesTab.Passage("p1", "Berechtigungen", "Rollen und Rechte je Datenprodukt …", 120,
                            300)), false);
            write(passages, out.resolve("absaetze.png"));

            TermsTab terms = new TermsTab();
            terms.show(new PageAcronyms(PageAcronyms.State.CURRENT, null, List.of(
                    new PageAcronyms.Acronym("API", 2, "Application Programming Interface", "Überblick",
                            List.of(new PageAcronyms.Occurrence(10, 13))),
                    new PageAcronyms.Acronym("HaVdB", 1, null, null, List.of())), List.of()));
            write(terms, out.resolve("begriffe.png"));

            References references = new References();
            references.show(new PageNavigation.References("wiki", "3",
                    List.of(new PageNode("7", "FK-Datenhaus Bausteinsicht", null, null)), null));
            write(references, out.resolve("verweise.png"));

            ImagesTab images = new ImagesTab();
            images.show(PassagesTab.State.MISSING, "source-not-released", List.of());
            write(images, out.resolve("bilder.png"));

            StatementsTab statements = new StatementsTab(() -> null, new StatementsTab.Runner() {
                @Override
                public <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onSuccess,
                                    java.util.function.Consumer<Throwable> onFailure) {
                    try {
                        onSuccess.accept(work.call());
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                }
            });
            statements.render(StatementsTabTest.analysed(), List.of());
            write(statements, out.resolve("aussagen.png"));
        });
    }

    static void write(JComponent component, Path file) {
        JPanel surface = new JPanel(new BorderLayout());
        surface.setBackground(com.aresstack.spaceharvester.app.ui.Comic.SURFACE);
        surface.add(component, BorderLayout.CENTER);
        surface.setSize(1280, 420);
        layout(surface);
        layout(surface);
        BufferedImage image = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        surface.printAll(g);
        g.dispose();
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
