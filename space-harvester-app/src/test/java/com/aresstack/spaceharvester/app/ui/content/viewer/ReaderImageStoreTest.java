package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Pixelgrenze des Bildwegs: die Größe kommt aus dem Bildkopf, riesige Bilder werden nicht dekodiert. */
public class ReaderImageStoreTest {

    @Test
    void anImageAboveTheLimitIsRefusedWithAMarkerBeforeDecoding() {
        byte[] huge = png(40_000, 40_000);
        assertThat(ReaderImageStore.dimensions(huge)).containsExactly(40_000, 40_000);
        assertThat(ReaderImageStore.tooLarge(huge)).isTrue();
        assertThat(ReaderImageStore.decodeBounded(huge)).isNull();
        ReaderImageStore.Loaded loaded = ReaderImageStore.Loaded.of(huge);
        assertThat(loaded.ok()).isFalse();
        assertThat(loaded.failure()).startsWith("Bild zu groß").contains("40000 × 40000");

        byte[] small = png(4, 3);
        assertThat(ReaderImageStore.tooLarge(small)).isFalse();
        assertThat(ReaderImageStore.Loaded.of(small).ok()).isTrue();
    }

    /**
     * Ein PNG mit der Größe {@code width} × {@code height} im Kopf; die Pixeldaten sind die eines 1 × 1-Bildes, sodass
     * nur das Lesen des Kopfes gelingt (die CRC des Kopfes prüft ImageIO beim Lesen der Größe nicht).
     */
    public static byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean small = (long) width * height <= 1_000_000;
            ImageIO.write(new BufferedImage(small ? width : 1, small ? height : 1, BufferedImage.TYPE_INT_RGB), "png",
                    out);
            byte[] bytes = out.toByteArray();
            if (!small) {
                // Signatur (8), Länge (4), "IHDR" (4), dann Breite und Höhe
                ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height);
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
