package com.aresstack.spaceharvester.app.ui.brand;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Markenbilder kommen aus dem Klassenpfad; die Symbole entstehen daraus in jeder Größe, transparent, gefüllt. */
class BrandImagesTest {

    @TempDir
    Path out;

    @Test
    void theOriginalsLieUnchangedInTheClasspath() throws Exception {
        assertThat(sha256(BrandImages.SCENE))
                .isEqualTo("a4c510c8bf9cb02576c4eac5363ec0840fdef53780854fe9f5312b2cd15a5d7e");
        assertThat(sha256(BrandImages.SHIP))
                .isEqualTo("bce89de525f76d3059965d023b8a9d1fbffe4892ec095fab91af15979a29f913");
        assertThat(BrandImages.scene().getWidth()).isEqualTo(1254);
        assertThat(BrandImages.ship().getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void everyIconSizeIsSquareTransparentAndFilledByTheShip() throws Exception {
        List<Image> images = ApplicationIcons.images();
        assertThat(images).hasSize(ApplicationIcons.SIZES.length);
        for (int i = 0; i < images.size(); i++) {
            BufferedImage icon = (BufferedImage) images.get(i);
            int size = ApplicationIcons.SIZES[i];
            assertThat(icon.getWidth()).isEqualTo(size);
            assertThat(icon.getHeight()).isEqualTo(size);
            // Die Ecken bleiben durchsichtig (Taskleiste hell wie dunkel), das Schiff reicht über fast die ganze Breite.
            assertThat(icon.getRGB(0, 0) >>> 24).as("Ecke bei %d px", size).isLessThan(40);
            assertThat(icon.getRGB(size - 1, 0) >>> 24).as("Ecke bei %d px", size).isLessThan(40);
            Rectangle ship = BrandImages.opaqueBounds(icon, 128);
            assertThat(ship.width).as("Schiffsbreite bei %d px", size).isGreaterThanOrEqualTo(size * 8 / 10);
            ImageIO.write(icon, "png", out.resolve("icon-" + size + ".png").toFile());
        }
        assertThat(BrandImages.icon(32)).isSameAs(BrandImages.icon(32));
        assertThat(ApplicationIcons.titleLogo().getIconWidth()).isEqualTo(ApplicationIcons.TITLE_SIZE);
    }

    private static String sha256(String resource) throws Exception {
        try (InputStream in = BrandImages.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }
}
