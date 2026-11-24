package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class QrCodesTest {

    @Test
    void longTextIsSplitWithoutLosingAnythingAndNeverInsideACharacter() {
        String text = "Grüße aus dem Zoo 🦒 – ".repeat(200);
        List<String> parts = QrCodes.parts(text, QrCodes.TEXT_PART_BYTES);
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(String.join("", parts)).isEqualTo(text);
        for (String part : parts) {
            assertThat(part.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(QrCodes.TEXT_PART_BYTES);
            assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
        }
        List<java.awt.image.BufferedImage> images = QrCodes.textImages(text, 4);
        assertThat(images).hasSize(parts.size());
        StringBuilder read = new StringBuilder();
        for (java.awt.image.BufferedImage image : images) {
            int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            var bitmap = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                    new com.google.zxing.RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)));
            try {
                read.append(new com.google.zxing.qrcode.QRCodeReader().decode(bitmap,
                        java.util.Map.of(com.google.zxing.DecodeHintType.CHARACTER_SET, "UTF-8")).getText());
            } catch (com.google.zxing.ReaderException e) {
                throw new AssertionError(e);
            }
        }
        assertThat(read.toString()).as("die Teile ergeben zusammen genau den Text").isEqualTo(text);
    }

    @Test
    void shortAndEmptyTextGiveOneCode() {
        assertThat(QrCodes.parts("", 10)).containsExactly("");
        assertThat(QrCodes.textImages("Die Giraffe frisst Akazienblätter.", 1)).hasSize(1);
    }
}
