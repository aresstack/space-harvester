package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der eine Reiter für Sitzungen und Dokumente: Beschriftung immer ganz sichtbar, Kreuz im Reiter. */
class ClosableTabTest {

    @Test
    void theLabelKeepsItsFullWidthAndTheCrossSitsInsideTheTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String label = "Erfundener Host A";
            ClosableTab tab = new ClosableTab(null, label, "verbunden", "Trennen und schließen", () -> { });
            ComposerToggleButton plain = new ComposerToggleButton(null, label, "verbunden");
            int width = tab.getPreferredSize().width;
            assertThat(width).as("um das Kreuz breiter, der Text wird nie gekürzt")
                    .isGreaterThanOrEqualTo(plain.getPreferredSize().width + ClosableTab.CROSS);
            tab.setSize(tab.getPreferredSize());
            tab.doLayout();
            java.awt.Rectangle cross = tab.cross().getBounds();
            assertThat(cross.x).isGreaterThanOrEqualTo(0);
            assertThat(cross.x + cross.width).as("Kreuz im Reiter").isLessThanOrEqualTo(tab.getWidth());
            assertThat(cross.y).isGreaterThanOrEqualTo(0);
            assertThat(cross.y + cross.height).isLessThanOrEqualTo(tab.getHeight());
            // Der Text endet vor dem Kreuz: Die Fläche für Symbol und Text reicht für die ganze Beschriftung.
            java.awt.Insets insets = tab.getInsets();
            int textRoom = tab.getWidth() - insets.left - insets.right;
            assertThat(textRoom).isGreaterThanOrEqualTo(tab.getFontMetrics(tab.getFont()).stringWidth(label));
            assertThat(tab.getWidth() - insets.right).as("Text endet vor dem Kreuz").isLessThanOrEqualTo(cross.x);
            assertThat(tab.cross().getToolTipText()).isEqualTo("Trennen und schließen");
        });
    }

    @Test
    void theCrossClosesInOneStepWithoutSelectingTheTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] closed = {0};
            int[] selected = {0};
            ClosableTab tab = new ClosableTab(null, "Sitzung", "verbunden", "Trennen und schließen",
                    () -> closed[0]++);
            tab.addActionListener(e -> selected[0]++);
            tab.cross().doClick();
            assertThat(closed[0]).isEqualTo(1);
            assertThat(selected[0]).isZero();
        });
    }

    /** Kein Bereich des Command Deck baut sich einen eigenen Reiter mit Kreuz (Angelo, 23.11.2026). */
    @Test
    void noAreaOfTheCommandDeckBuildsItsOwnTabWithACross() throws IOException {
        Path deck = Path.of("src/main/java/com/aresstack/spaceharvester/app/ui/deck");
        try (Stream<Path> files = Files.walk(deck)) {
            List<String> own = files.filter(file -> file.toString().endsWith(".java")).filter(file -> {
                try {
                    String source = Files.readString(file);
                    return source.contains("new ComposerToggleButton(dot(") || source.contains("tabOf");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(file -> file.getFileName().toString()).toList();
            assertThat(own).as("Sitzungs-Reiter nur über ClosableTab").isEmpty();
        }
    }
}
