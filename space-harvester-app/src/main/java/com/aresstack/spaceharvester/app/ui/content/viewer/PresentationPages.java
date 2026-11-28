package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/** Render PPT/PPTX slides through Apache POI without involving a document conversion service. */
final class PresentationPages implements RenderedPages {

    private static final double MAX_PIXELS = 40_000_000d;
    private final SlideShow<?, ?> presentation;

    private PresentationPages(SlideShow<?, ?> presentation) {
        this.presentation = presentation;
    }

    static PresentationPages open(byte[] original) throws IOException {
        if (original == null || original.length == 0) {
            throw new IOException("Die Präsentation ist leer");
        }
        return new PresentationPages(SlideShowFactory.create(new ByteArrayInputStream(original)));
    }

    @Override
    public int pageCount() {
        return presentation.getSlides().size();
    }

    @Override
    public double maxScale(int page) {
        Dimension size = presentation.getPageSize();
        double area = Math.max(1d, (double) size.width * size.height);
        return Math.sqrt(MAX_PIXELS / area);
    }

    @Override
    public BufferedImage render(int page, double scale) throws IOException {
        if (page < 0 || page >= pageCount()) {
            throw new IOException("Ungültige Foliennummer: " + (page + 1));
        }
        Dimension size = presentation.getPageSize();
        double effective = Math.max(0.1d, Math.min(scale, maxScale(page)));
        int width = Math.max(1, (int) Math.ceil(size.width * effective));
        int height = Math.max(1, (int) Math.ceil(size.height * effective));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(AppColors.of("app.PresentationPages.render"));
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.scale(effective, effective);
            Slide<?, ?> slide = presentation.getSlides().get(page);
            slide.draw(graphics);
        } catch (RuntimeException failure) {
            throw new IOException("Die PowerPoint-Folie lässt sich nicht zeichnen", failure);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    @Override
    public void close() throws IOException {
        presentation.close();
    }
}
