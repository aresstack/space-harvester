package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;

/** Render a document one page at a time without retaining rendered pages in memory. */
interface RenderedPages extends Closeable {

    int pageCount();

    double maxScale(int page);

    BufferedImage render(int page, double scale) throws IOException;
}
