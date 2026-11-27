package com.aresstack.askai.browser.render;

/** An axis-aligned box in CSS pixels, relative to the viewport at capture time. */
public final class RenderedBox {

    public final double x;
    public final double y;
    public final double width;
    public final double height;

    public RenderedBox(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public double centerX() {
        return x + width / 2.0;
    }

    public double centerY() {
        return y + height / 2.0;
    }

    public double area() {
        return Math.max(0, width) * Math.max(0, height);
    }

    public boolean contains(double pointX, double pointY) {
        return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
    }

    @Override
    public String toString() {
        return "[" + (int) x + "," + (int) y + " " + (int) width + "x" + (int) height + "]";
    }
}
