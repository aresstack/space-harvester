package com.aresstack.spaceharvester.source.network;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * QR-Codes als Schwarz-Weiß-Bilder, gemeinsam für Zertifikate ({@link CertificateQrCodes}) und beliebigen Text wie eine
 * Chat-Nachricht. Langer Text wird nie abgeschnitten, sondern auf mehrere Codes verteilt.
 */
public final class QrCodes {

    /** Ruhezone um den Code in Modulen; vier verlangt die Norm, Telefone lesen damit auch vom Bildschirm. */
    static final int QUIET_ZONE = 4;
    /**
     * Höchstens so viele UTF-8-Bytes je Code eines Textes: deutlich unter der Grenze eines Codes, damit ein Telefon
     * ihn auch vom Bildschirm sicher liest.
     */
    public static final int TEXT_PART_BYTES = 800;

    private QrCodes() {
    }

    /** Ein Code für den Text; leer, wenn er auch mit der kleinsten Fehlerkorrektur nicht passt. */
    public static Optional<BufferedImage> image(String text, Charset charset, int modulePixels) {
        return matrix(text, charset).map(matrix -> render(matrix, Math.max(1, modulePixels)));
    }

    /** Die Codes eines Textes in Teilen zu höchstens {@link #TEXT_PART_BYTES} UTF-8-Bytes, ohne etwas wegzulassen. */
    public static List<BufferedImage> textImages(String text, int modulePixels) {
        List<BufferedImage> images = new ArrayList<>();
        for (String part : parts(text, TEXT_PART_BYTES)) {
            images.add(image(part, StandardCharsets.UTF_8, modulePixels)
                    .orElseThrow(() -> new IllegalStateException("Textteil passt nicht in einen Code")));
        }
        return images;
    }

    /**
     * Teilt den Text in Stücke zu höchstens {@code maxBytes} UTF-8-Bytes, nie mitten in einem Zeichen; aneinandergehängt
     * ergeben sie genau den Text. Leerer Text ergibt ein leeres Stück.
     */
    static List<String> parts(String text, int maxBytes) {
        String value = text == null ? "" : text;
        List<String> parts = new ArrayList<>();
        int start = 0;
        int bytes = 0;
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes && i > start) {
                parts.add(value.substring(start, i));
                start = i;
                bytes = 0;
            }
            bytes += size;
            i += Character.charCount(codePoint);
        }
        parts.add(value.substring(start));
        return parts;
    }

    private static Optional<BitMatrix> matrix(String text, Charset charset) {
        for (ErrorCorrectionLevel level : List.of(ErrorCorrectionLevel.M, ErrorCorrectionLevel.L)) {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, level);
            hints.put(EncodeHintType.MARGIN, QUIET_ZONE);
            hints.put(EncodeHintType.CHARACTER_SET, charset.name());
            try {
                // Größe 0: ein Pixel je Modul, das Hochskalieren übernimmt render.
                return Optional.of(new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints));
            } catch (WriterException | IllegalArgumentException e) {
                // zu groß für diese Stufe
            }
        }
        return Optional.empty();
    }

    private static BufferedImage render(BitMatrix matrix, int scale) {
        int width = matrix.getWidth() * scale;
        int height = matrix.getHeight() * scale;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, matrix.get(x / scale, y / scale) ? black : white);
            }
        }
        return image;
    }
}
