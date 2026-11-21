package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.FolderField;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der Ausgabeordner steht als Zeile wie die übrigen Felder der Einstellungen: gleiche Kante, gleiche Breite. */
class OutputFolderRowTest {

    @Test
    void theFolderFieldStartsAndEndsWhereTheOtherFieldsDo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FolderField folder = new FolderField(Comic.field(24));
            folder.addButton(InputFrame.flatButton("Öffnen", "Den Ausgabeordner anzeigen"));
            folder.path(Path.of("Downloads").toAbsolutePath());
            JComboBox<String> other = Comic.combo();
            other.addItem("Hell");
            SettingDialogs.Rows rows = new SettingDialogs.Rows();
            rows.add("Ordner", folder, SettingsView.OUTPUT_HINT);
            rows.add("Hintergrund", other, null);
            JComponent plate = Comic.plate("Ausgabeordner", Comic.GREEN, rows);
            plate.setSize(new Dimension(760, plate.getPreferredSize().height));
            layout(plate);

            assertThat(folder.getX()).isEqualTo(other.getX());
            assertThat(folder.getWidth()).isEqualTo(other.getWidth());
            assertThat(folder.browseButton().getWidth()).isGreaterThan(0);

            String target = System.getenv("OUTPUTFOLDER_SCREENSHOT");
            if (target != null) {
                BufferedImage image = new BufferedImage(plate.getWidth() * 2, plate.getHeight() * 2,
                        BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = image.createGraphics();
                g.scale(2, 2);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, plate.getWidth(), plate.getHeight());
                plate.paint(g);
                g.dispose();
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(target));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        });
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
