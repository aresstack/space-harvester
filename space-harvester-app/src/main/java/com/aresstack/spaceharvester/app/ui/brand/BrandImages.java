package com.aresstack.spaceharvester.app.ui.brand;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die beiden Markenbilder aus dem Klassenpfad: das freigestellte Raumschiff (Anwendungssymbol) und die Weltraumszene
 * (Willkommen, Info). Beide Dateien liegen unverändert als Original unter {@link #DIRECTORY}; alle kleineren Fassungen
 * entstehen beim Start daraus, immer gleich, ohne erzeugte Bilddateien im Repository.
 */
public final class BrandImages {

    public static final String DIRECTORY = "/com/aresstack/spaceharvester/app/images/";
    public static final String SHIP = DIRECTORY + "space-harvester-ship.png";
    public static final String SCENE = DIRECTORY + "space-harvester.png";

    /** Ab dieser Deckkraft zählt ein Pixel zum Schiff; darunter liegt nur der Glanz der Triebwerke. */
    private static final int LARGE_THRESHOLD = 24;
    /**
     * Für kleine Symbole wird enger zugeschnitten: Der schwache Schein fällt weg, das Schiff füllt die Kachel und
     * bleibt bei 16 Pixeln erkennbar. Das Motiv selbst bleibt unverändert.
     */
    private static final int SMALL_THRESHOLD = 128;
    private static final int SMALL_ICON = 32;

    private static final Map<String, BufferedImage> ORIGINALS = new ConcurrentHashMap<>();
    private static final Map<Integer, BufferedImage> ICONS = new ConcurrentHashMap<>();

    private BrandImages() {
    }

    /** Das Raumschiff quadratisch in {@code size} × {@code size} Pixeln, Hintergrund transparent. */
    public static BufferedImage icon(int size) {
        return ICONS.computeIfAbsent(size, BrandImages::renderIcon);
    }

    /** Die Weltraumszene im Original (1:1, Ecken außerhalb der abgerundeten Kachel schwarz). */
    public static BufferedImage scene() {
        return original(SCENE);
    }

    /** Das freigestellte Raumschiff im Original. */
    public static BufferedImage ship() {
        return original(SHIP);
    }

    private static BufferedImage renderIcon(int size) {
        BufferedImage ship = ship();
        Rectangle box = opaqueBounds(ship, size <= SMALL_ICON ? SMALL_THRESHOLD : LARGE_THRESHOLD);
        // Quadrat um das Schiff, mittig; kleine Symbole ohne Rand, große mit etwas Luft.
        int side = Math.max(box.width, box.height);
        side += size <= SMALL_ICON ? 0 : side / 32;
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = square.createGraphics();
        g.drawImage(ship, (side - box.width) / 2 - box.x, (side - box.height) / 2 - box.y, null);
        g.dispose();
        return scale(square, size, size);
    }

    /**
     * Verkleinert in Halbierungsschritten und erst zuletzt auf das Ziel; ein Schritt von 1254 auf 16 Pixel ließe
     * sonst Kanten und Lichter ausfransen. Gerechnet wird mit vormultipliziertem Alpha, damit der halbtransparente
     * Schein keine dunklen Säume bekommt.
     */
    public static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        while (w / 2 >= width && h / 2 >= height) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h);
        }
        BufferedImage result = draw(current, width, height);
        BufferedImage plain = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = plain.createGraphics();
        g.drawImage(result, 0, 0, null);
        g.dispose();
        return plain;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    static Rectangle opaqueBounds(BufferedImage image, int threshold) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) >= threshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? new Rectangle(0, 0, image.getWidth(), image.getHeight())
                : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static BufferedImage original(String resource) {
        return ORIGINALS.computeIfAbsent(resource, BrandImages::read);
    }

    private static BufferedImage read(String resource) {
        try (InputStream in = BrandImages.class.getResourceAsStream(resource)) {
            BufferedImage image = in == null ? null : ImageIO.read(in);
            if (image == null) {
                throw new IllegalStateException("Markenbild fehlt im Klassenpfad: " + resource);
            }
            BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return argb;
        } catch (IOException e) {
            throw new UncheckedIOException("Markenbild nicht lesbar: " + resource, e);
        }
    }
}
