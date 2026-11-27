package com.aresstack.askai.browser.render;

/** Viewport and full-document dimensions at capture time (CSS pixels). */
public final class RenderedPageViewport {

    public final int viewportWidth;
    public final int viewportHeight;
    public final int documentWidth;
    public final int documentHeight;

    public RenderedPageViewport(int viewportWidth, int viewportHeight, int documentWidth,
                                int documentHeight) {
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.documentWidth = documentWidth;
        this.documentHeight = documentHeight;
    }
}
