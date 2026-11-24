package com.aresstack.spaceharvester.source.network;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Iterator;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.ImageIO;

/**
 * QR-Codes für einzelne Zertifikate (Strang ZX): Ein Code je Zertifikat, Inhalt ist der PEM-Text
 * ({@code -----BEGIN CERTIFICATE-----} …), den auch eine Scanner-App auf dem Telefon als Datei speichern und Android
 * unter „Zertifikat installieren“ annehmen kann. Lesen nimmt nur Codes an, deren Inhalt ein X.509-Zertifikat im
 * PEM-Format ist; private Schlüssel kommen hier nie vor.
 *
 * <p>Nutzt nur {@code zxing core}; die Anbindung an {@link BufferedImage} steht hier, damit {@code zxing javase} mit
 * seinen Abhängigkeiten entfällt.
 */
public final class CertificateQrCodes {

    /** Pixel je Modul in den gespeicherten Bildern. */
    static final int MODULE_PIXELS = 6;

    private CertificateQrCodes() {
    }

    /**
     * Der Code eines Zertifikats als Schwarz-Weiß-Bild mit Ruhezone; leer, wenn der PEM-Text auch mit der kleinsten
     * Fehlerkorrektur nicht in einen Code passt.
     *
     * @param modulePixels Pixel je Modul (mindestens 1)
     */
    public static Optional<BufferedImage> image(X509Certificate certificate, int modulePixels) {
        return QrCodes.image(text(certificate), StandardCharsets.ISO_8859_1, modulePixels);
    }

    /** Der Code als PNG; leer wie bei {@link #image}. */
    public static Optional<byte[]> png(X509Certificate certificate) {
        Optional<BufferedImage> image = image(certificate, MODULE_PIXELS);
        if (image.isEmpty()) {
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image.get(), "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("PNG nicht schreibbar", e);
        }
        return Optional.of(out.toByteArray());
    }

    /** Der Inhalt eines Codes: PEM mit Zeilenumbruch LF und Zeilen zu 64 Zeichen. */
    public static String text(X509Certificate certificate) {
        return CertificatePackage.pem(certificate).replace("\r\n", "\n");
    }

    /**
     * Alle Zertifikate aus den QR-Codes eines Bildes (Foto oder Bildschirmfoto, auch mehrere Codes); Codes mit
     * anderem Inhalt werden übergangen, doppelte zählen einmal.
     *
     * @return leer, wenn das Bild keinen Code mit einem Zertifikat enthält
     */
    public static List<X509Certificate> read(BufferedImage image) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, StandardCharsets.ISO_8859_1.name());
        List<String> texts = new ArrayList<>();
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new ImageLuminance(image)));
        try {
            for (Result result : new QRCodeMultiReader().decodeMultiple(bitmap, hints)) {
                texts.add(result.getText());
            }
        } catch (NotFoundException e) {
            // Ein einzelner, schwer lesbarer Code: der einfache Leser versucht es gründlicher.
        }
        if (texts.isEmpty()) {
            try {
                texts.add(new QRCodeReader().decode(bitmap, hints).getText());
            } catch (com.google.zxing.ReaderException e) {
                return List.of();
            }
        }
        Map<String, X509Certificate> found = new LinkedHashMap<>();
        for (String text : texts) {
            parse(text).ifPresent(certificate -> found.putIfAbsent(CertificatePackage.sha256(certificate),
                    certificate));
        }
        return List.copyOf(found.values());
    }

    /** Größte Bilddatei, die {@link #read(Path)} annimmt. */
    static final long MAX_FILE_BYTES = 30L * 1024 * 1024;
    /** Größte Pixelzahl, die {@link #read(Path)} dekodiert (etwa ein 40-Megapixel-Foto). */
    static final long MAX_PIXELS = 40_000_000L;

    /**
     * Wie {@link #read(BufferedImage)} für eine Bilddatei (PNG, JPEG, GIF, BMP); Datei und Bild sind in der Größe
     * begrenzt, damit ein übergroßes Bild den Speicher nicht sprengt.
     *
     * @throws IOException mit Grund, wenn die Datei zu groß, kein lesbares Bild oder nicht lesbar ist
     */
    public static List<X509Certificate> read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Bild zu groß (höchstens " + MAX_FILE_BYTES / (1024 * 1024) + " MB)");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("Kein lesbares Bild (PNG, JPEG, GIF oder BMP)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new IOException("Bild zu groß (höchstens " + MAX_PIXELS / 1_000_000 + " Megapixel)");
                }
                return read(reader.read(0));
            } finally {
                reader.dispose();
            }
        }
    }

    /** Ein Zertifikat aus PEM-Text; leer bei allem anderen (auch privaten Schlüsseln oder mehreren Blöcken). */
    static Optional<X509Certificate> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith("-----BEGIN CERTIFICATE-----") || !trimmed.endsWith("-----END CERTIFICATE-----")
                || trimmed.indexOf("-----BEGIN", 1) >= 0) {
            return Optional.empty();
        }
        try {
            Certificate certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.US_ASCII)));
            return certificate instanceof X509Certificate x509 ? Optional.of(x509) : Optional.empty();
        } catch (CertificateException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Helligkeit eines {@link BufferedImage} für zxing; Transparenz zählt als Weiß. */
    private static final class ImageLuminance extends LuminanceSource {

        private final byte[] luminance;

        ImageLuminance(BufferedImage image) {
            super(image.getWidth(), image.getHeight());
            int width = image.getWidth();
            int height = image.getHeight();
            luminance = new byte[width * height];
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    int argb = row[x];
                    int alpha = argb >>> 24;
                    int r = argb >> 16 & 0xFF;
                    int g = argb >> 8 & 0xFF;
                    int b = argb & 0xFF;
                    int gray = (306 * r + 601 * g + 117 * b + 0x200) >> 10;
                    gray = (gray * alpha + 0xFF * (0xFF - alpha)) / 0xFF;
                    luminance[y * width + x] = (byte) gray;
                }
            }
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            int width = getWidth();
            byte[] target = row == null || row.length < width ? new byte[width] : row;
            System.arraycopy(luminance, y * width, target, 0, width);
            return target;
        }

        @Override
        public byte[] getMatrix() {
            return luminance;
        }
    }
}
