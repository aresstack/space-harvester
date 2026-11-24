package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** Render a document one page at a time without retaining rendered pages in memory. */
interface RenderedPages extends Closeable {

    int pageCount();

    double maxScale(int page);

    BufferedImage render(int page, double scale) throws IOException;

    /**
     * Wo ein wörtlicher Auszug auf den Seiten steht ({@link Passages}): die erste Seite mit Treffer und darauf je Zeile
     * ein Rechteck als Anteil der Seite (0…1, oben links); {@code null}, wenn er nicht vorkommt oder die Seiten keinen
     * Text haben. Ohne Textebene (etwa Folien) immer {@code null}.
     */
    default Passage find(String excerpt) throws IOException {
        return null;
    }

    /** Eine gefundene Stelle: Seite (ab 0) und ihre Rechtecke als Anteil der Seite. */
    record Passage(int page, List<Rectangle2D> boxes) {

        public Passage {
            boxes = List.copyOf(boxes);
        }
    }
}
