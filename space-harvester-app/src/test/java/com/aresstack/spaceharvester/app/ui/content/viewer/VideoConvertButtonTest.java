package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * „In PPTX umwandeln“ und „In PDF umwandeln“ stehen neben „Herunterladen“ in der Leiste, nur als farbiges Dateisymbol;
 * der Name steht im Tooltip (Angelo 20.11.2026).
 */
final class VideoConvertButtonTest {

    @Test
    void theConvertButtonsSitNextToDownloadAsColouredIconsWithTheNameInTheTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTransportBar bar = new VideoTransportBar(position -> { });
            JButton download = bar.download();
            JButton convert = bar.convert();
            JButton pdf = bar.convertPdf();
            assertThat(convert.getText()).as("kein Text im Knopf").isNullOrEmpty();
            assertThat(pdf.getText()).isNullOrEmpty();
            assertThat(convert.getToolTipText()).isEqualTo("In PPTX umwandeln");
            assertThat(pdf.getToolTipText()).isEqualTo("In PDF umwandeln");
            assertThat(convert.getAccessibleContext().getAccessibleName()).isEqualTo("In PPTX umwandeln");
            assertThat(pdf.getAccessibleContext().getAccessibleName()).isEqualTo("In PDF umwandeln");
            assertThat(convert.getIcon()).isInstanceOf(FileFormatIcon.class);
            assertThat(pdf.getIcon()).isInstanceOf(FileFormatIcon.class);
            assertThat(convert.getBackground()).isEqualTo(download.getBackground());
            assertThat(convert.getPreferredSize().height).as("gleich hoch wie die übrigen Knöpfe")
                    .isEqualTo(download.getPreferredSize().height);
            Container row = download.getParent();
            int at = Arrays.asList(row.getComponents()).indexOf(download);
            assertThat(row.getComponent(at + 1)).isSameAs(convert);
            assertThat(row.getComponent(at + 2)).isSameAs(pdf);
            assertThat(convert.isVisible()).isFalse();
            assertThat(pdf.isVisible()).isFalse();
        });
    }

    @Test
    void theIconsPaintTheirOwnColourAndGreyWhenDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (FileFormatIcon icon : new FileFormatIcon[] {FileFormatIcon.powerPoint(), FileFormatIcon.pdf()}) {
                JButton button = new JButton();
                BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                icon.paintIcon(button, image.getGraphics(), 0, 0);
                // Im Schild unter dem Kürzel: die Farbe des Formats.
                assertThat(new Color(image.getRGB(9, 15))).isEqualTo(icon.color());
                button.setEnabled(false);
                icon.paintIcon(button, image.getGraphics(), 0, 0);
                assertThat(new Color(image.getRGB(9, 15))).isNotEqualTo(icon.color());
            }
            assertThat(FileFormatIcon.powerPoint().color()).isNotEqualTo(FileFormatIcon.pdf().color());
        });
    }

    @Test
    void theButtonsAppearOnceSomeoneStartsTheJobsAndSayWhichFormat() throws Exception {
        String shot = System.getenv("VIDEOBAR_SCREENSHOT");
        SwingUtilities.invokeAndWait(() -> {
            VideoTransportBar bar = new VideoTransportBar(position -> { });
            bar.convert().setVisible(true);
            bar.convertPdf().setVisible(true);
            if (shot != null && !shot.isBlank()) {
                bar.setSize(900, bar.getPreferredSize().height);
                layoutAll(bar);
                BufferedImage image = new BufferedImage(900 * 2, bar.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = image.createGraphics();
                g.scale(2, 2);
                bar.paint(g);
                g.dispose();
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(shot));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            assertThat(bar.convert().isVisible()).isTrue();
        });
    }

    private static void layoutAll(java.awt.Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }
}
