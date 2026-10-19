package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.ImageIcon;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Der Bildweg des Anhang-Viewers: dekodiert Bilddaten ({@link Loaded}) und erkennt animierte GIFs.
 *
 * <p>Kopie von {@code ReaderImageStore} aus dem Enterprise AI Client (exedra ui.reader), nur der Teil, den der
 * Anhang-Viewer braucht: {@code Loaded.of}, das Dekodieren und die GIF-Erkennung. Weggelassen sind der Bildspeicher
 * der Diashow, die KI-Beschreibung und die Einmalcode-Abfrage. SVG zeichnet die App nicht selbst (kein Batik im
 * Klassenpfad): Ein SVG-Anhang meldet „SVG nicht darstellbar“ und bleibt über Speichern und Öffnen erreichbar.</p>
 */
public final class ReaderImageStore {

    /**
     * Höchstens so viele Pixel dekodiert die App (wie {@code PdfPages.MAX_PIXELS} im Core): ein PNG von wenigen KB mit
     * 40000 × 40000 px legte sonst Gigabytes an.
     */
    public static final long MAX_PIXELS = 40_000_000L;

    private ReaderImageStore() {
    }

    /**
     * Die Größe laut Bildkopf, ohne die Pixel zu dekodieren; {@code null}, wenn ImageIO das Format nicht kennt.
     */
    static int[] dimensions(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Ob das Bild laut Kopf mehr als {@link #MAX_PIXELS} Pixel hat; solche Bilder dekodiert die App nicht. */
    public static boolean tooLarge(byte[] data) {
        int[] size = data == null ? null : dimensions(data);
        return size != null && (long) size[0] * size[1] > MAX_PIXELS;
    }

    /** Wie {@link #decode}, aber {@code null} für Bilder über {@link #MAX_PIXELS}. */
    public static BufferedImage decodeBounded(byte[] data) {
        return data == null || tooLarge(data) ? null : decode(data);
    }

    /** Ein geladenes Bild: Original-Bytes (zum Speichern) und dekodiertes Bild (erstes Bild eines GIFs). */
    static final class Loaded {
        /** Text, wenn die Quelle keinen Grund nennt. */
        static final String FAILED_TEXT = "Bild konnte nicht geladen werden";
        /** Grund, wenn der Lader keinen nennt (alter Lader ohne Grund, {@code null}-Bytes). */
        static final String NO_REASON = "Der Lader hat keinen Grund gemeldet (Protokoll).";

        private final byte[] bytes;
        private final BufferedImage image;
        private final String failure;

        private Loaded(byte[] bytes, BufferedImage image, String failure) {
            this.bytes = bytes;
            this.image = image;
            this.failure = image != null ? null : failure == null || failure.trim().isEmpty() ? NO_REASON : failure;
        }

        static Loaded of(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return new Loaded(null, null, bytes == null ? null : "Bild: leere Antwort (0 Bytes)");
            }
            boolean svg = isSvg(bytes);
            int[] size = svg ? null : dimensions(bytes);
            if (size != null && (long) size[0] * size[1] > MAX_PIXELS) {
                return new Loaded(null, null, "Bild zu groß zum Anzeigen (" + size[0] + " × " + size[1]
                        + " Pixel, höchstens " + MAX_PIXELS / 1_000_000 + " Megapixel)");
            }
            BufferedImage image = svg ? null : decode(bytes);
            return new Loaded(image == null ? null : bytes, image,
                    "Bilddaten nicht lesbar (" + bytes.length + " Bytes, " + (svg ? "SVG nicht darstellbar)"
                            : "kein PNG, JPEG, GIF oder SVG)"));
        }

        boolean ok() {
            return image != null;
        }

        /** Warum das Bild fehlt (für die Oberfläche); {@code null}, wenn es geladen ist. */
        String failure() {
            return failure;
        }

        byte[] bytes() {
            return bytes;
        }

        BufferedImage image() {
            return image;
        }

        boolean isAnimatedGif() {
            return bytes != null && isGif(bytes) && ReaderImageStore.isAnimatedGif(bytes);
        }
    }

    private static boolean isGif(byte[] data) {
        return data.length > 3 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
    }

    private static boolean isSvg(byte[] data) {
        int length = Math.min(data.length, 2048);
        return new String(data, 0, length, StandardCharsets.UTF_8).contains("<svg");
    }

    /**
     * Dekodiert wie MainframeMate {@code ImageThumbnailPanel.decodeToBufferedImage}: {@link ImageIO}, sonst (etwa bei
     * manchen GIFs) über {@link ImageIcon} und {@link PixelGrabber}; animierte GIFs liefern ihr erstes Bild.
     */
    static BufferedImage decode(byte[] data) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image != null) {
                return image;
            }
        } catch (Exception ignored) {
            // weiter mit dem Toolkit-Weg
        }
        ImageIcon icon = new ImageIcon(data);
        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) {
            return null;
        }
        int[] pixels = new int[w * h];
        PixelGrabber grabber = new PixelGrabber(icon.getImage(), 0, 0, w, h, pixels, 0, w);
        try {
            grabber.grabPixels();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        return image;
    }

    /**
     * Aus MainframeMate {@code ImageStripPanel.isAnimatedGif}: mehr als ein Image-Descriptor-Block. Zwei Fehler des
     * Vorbilds behoben (dort wurde ein mit ImageIO geschriebenes animiertes GIF nie erkannt): der Lauf beginnt hinter
     * Logical Screen Descriptor und globaler Farbtabelle statt mitten darin, und der Image Descriptor ist 10 statt 9
     * Bytes lang (das gepackte Feld ist sein letztes Byte).
     */
    static boolean isAnimatedGif(byte[] data) {
        if (data.length < 13) {
            return false;
        }
        int imageCount = 0;
        int i = 13; // "GIF89a" / "GIF87a" und Logical Screen Descriptor
        int screen = data[10] & 0xFF;
        if ((screen & 0x80) != 0) {
            i += 3 * (1 << ((screen & 0x07) + 1)); // globale Farbtabelle
        }
        while (i < data.length) {
            int b = data[i] & 0xFF;
            if (b == 0x2C) { // Image Descriptor
                imageCount++;
                if (imageCount > 1) {
                    return true;
                }
                i += 10;
                if (i > data.length) {
                    break;
                }
                int packed = data[i - 1] & 0xFF;
                if ((packed & 0x80) != 0) {
                    i += 3 * (1 << ((packed & 0x07) + 1));
                }
                if (i >= data.length) {
                    break;
                }
                i++; // LZW minimum code size
                i = skipSubBlocks(data, i);
            } else if (b == 0x21) { // Extension
                i = skipSubBlocks(data, i + 2);
            } else if (b == 0x3B) { // Trailer
                break;
            } else {
                i++;
            }
        }
        return false;
    }

    private static int skipSubBlocks(byte[] data, int start) {
        int i = start;
        while (i < data.length) {
            int blockSize = data[i] & 0xFF;
            i++;
            if (blockSize == 0) {
                break;
            }
            i += blockSize;
        }
        return i;
    }
}
