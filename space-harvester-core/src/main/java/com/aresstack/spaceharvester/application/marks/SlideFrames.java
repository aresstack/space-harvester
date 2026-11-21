package com.aresstack.spaceharvester.application.marks;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Ob zwei Standbilder dieselbe Folie zeigen. Der Index eines Videos kennt nur Bildgrößen; ein großes Bild entsteht
 * aber auch, wenn der Encoder das Bild neu aufbaut oder nur der Mauszeiger wandert. Deshalb vergleicht dies die
 * Bilder selbst: im Raster der Blockmittel in Graustufen, in dem das Kompressionsrauschen verschwindet. Weichen nur
 * wenige Stellen von der Größe eines Mauszeigers ab (alte und neue Stelle, Hervorhebung unter dem Zeiger), ist es
 * dieselbe Folie; getippter Text, ein neuer Punkt oder ein Popup sind größer und bleiben eine eigene Folie.
 *
 * <p>Gemessen an drei Aufzeichnungen (191 Marken): Bei gleichen Folien wich kein Blockmittel um mehr als 5 Graustufen
 * ab, bei echten Änderungen um mehr als 12; ein Zeiger belegte höchstens 2 × 2 Blöcke.</p>
 */
public final class SlideFrames {

    /**
     * Die Werte des Vergleichs; die App stellt sie ein ({@code space-harvester-app.video.*}).
     *
     * @param blockPixels   Kantenlänge eines Blocks in Pixeln
     * @param changedGray   ab dieser Abweichung seines Mittels in Graustufen gilt ein Block als geändert
     * @param pointerBlocks so viele Blöcke breit und hoch ist eine Stelle von der Größe eines Mauszeigers höchstens
     * @param pointerSpots  an höchstens so vielen solchen Stellen darf sich dieselbe Folie unterscheiden
     * @param maxFrames     so viele Marken hat ein Video höchstens; so viele Raster hält der Vergleich vor
     */
    public record Thresholds(int blockPixels, float changedGray, int pointerBlocks, int pointerSpots, int maxFrames) {

        /** Die gemessenen Werte (siehe oben). */
        public static final Thresholds DEFAULT = new Thresholds(16, 8, 3, 4, 200);

        public Thresholds {
            if (blockPixels < 1 || changedGray < 0 || pointerBlocks < 1 || pointerSpots < 0 || maxFrames < 1) {
                throw new IllegalArgumentException("Ungültige Werte für den Vergleich von Standbildern");
            }
        }
    }

    private SlideFrames() {
    }

    /** Das Raster der Blockmittel eines Standbilds in Graustufen, Zeile für Zeile. */
    public record Grid(int columns, int rows, float[] means) {

        public Grid {
            Objects.requireNonNull(means, "means");
            if (columns * rows != means.length) {
                throw new IllegalArgumentException("Raster passt nicht zu seinen Maßen");
            }
        }
    }

    /**
     * Das Raster eines Standbilds.
     *
     * @throws IOException wenn die Bytes kein lesbares Bild sind
     */
    public static Grid grid(byte[] image, int blockPixels) throws IOException {
        BufferedImage picture = ImageIO.read(new ByteArrayInputStream(image));
        if (picture == null) {
            throw new IOException("kein lesbares Bild");
        }
        int columns = picture.getWidth() / blockPixels;
        int rows = picture.getHeight() / blockPixels;
        int width = columns * blockPixels;
        float[] means = new float[columns * rows];
        int[] line = new int[width];
        for (int y = 0; y < rows * blockPixels; y++) {
            picture.getRGB(0, y, width, 1, line, 0, width);
            int row = y / blockPixels * columns;
            for (int x = 0; x < width; x++) {
                int rgb = line[x];
                // Helligkeit nach ITU-R BT.601, wie die Graustufen gängiger Bildbibliotheken.
                means[row + x / blockPixels] += ((rgb >> 16 & 0xff) * 299 + (rgb >> 8 & 0xff) * 587 + (rgb & 0xff) * 114)
                        / 1000f;
            }
        }
        for (int i = 0; i < means.length; i++) {
            means[i] /= blockPixels * blockPixels;
        }
        return new Grid(columns, rows, means);
    }

    /** Ob beide Standbilder dieselbe Folie zeigen; Bilder anderer Größe nie. */
    public static boolean same(Grid a, Grid b, Thresholds thresholds) {
        if (a.columns() != b.columns() || a.rows() != b.rows()) {
            return false;
        }
        boolean[] changed = new boolean[a.means().length];
        for (int i = 0; i < changed.length; i++) {
            changed[i] = Math.abs(a.means()[i] - b.means()[i]) > thresholds.changedGray();
        }
        int spots = 0;
        Deque<Integer> open = new ArrayDeque<>();
        for (int start = 0; start < changed.length; start++) {
            if (!changed[start]) {
                continue;
            }
            if (++spots > thresholds.pointerSpots()) {
                return false;
            }
            // Eine Stelle: zusammenhängende geänderte Blöcke, auch über Eck.
            int left = Integer.MAX_VALUE;
            int right = -1;
            int top = Integer.MAX_VALUE;
            int bottom = -1;
            changed[start] = false;
            open.push(start);
            while (!open.isEmpty()) {
                int block = open.pop();
                int x = block % a.columns();
                int y = block / a.columns();
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && nx < a.columns() && ny >= 0 && ny < a.rows()
                                && changed[ny * a.columns() + nx]) {
                            changed[ny * a.columns() + nx] = false;
                            open.push(ny * a.columns() + nx);
                        }
                    }
                }
            }
            if (right - left + 1 > thresholds.pointerBlocks() || bottom - top + 1 > thresholds.pointerBlocks()) {
                return false;
            }
        }
        return true;
    }
}
