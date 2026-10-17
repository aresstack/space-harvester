package com.aresstack.enterpriseai.ui.comic.paint;

import java.awt.Graphics2D;
import java.awt.geom.Line2D;

/**
 * AskAI's composer glyph set as reusable {@link StrokeIcon}s (send, stop, hamburger, gear, plus,
 * chevrons, close); pencil, refresh and microphone are own additions for list rows, the magnifier is the
 * reader toolbar's find button. The reference geometry is
 * verbatim — no icon assets, every glyph is drawn with the owning component's foreground.
 */
public final class ComposerIcons {

    private ComposerIcons() {
    }

    /** The paper-plane "send" arrow. */
    public static StrokeIcon send() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                int[] x = {2, 13, 6};
                int[] y = {2, 7, 13};
                g2.drawPolygon(x, y, 3);
                g2.drawLine(3, 7, 11, 7);
            }
        };
    }

    /** AskAI's paperclip (attach files): an open hook curving up on the left and back down on the right. */
    public static StrokeIcon paperclip() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(4, 4, 4, 11);
                g2.drawArc(4, 2, 6, 5, 90, 180);
                g2.drawLine(10, 4, 10, 12);
                g2.drawArc(3, 9, 7, 6, 0, -180);
            }
        };
    }

    /** The filled "play" triangle (a tab's autonomous work step). */
    public static StrokeIcon play() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                int[] x = {5, 13, 5};
                int[] y = {3, 8, 13};
                g2.fillPolygon(x, y, 3);
                g2.drawPolygon(x, y, 3);
            }
        };
    }

    /** The "pause" sign: two filled bars. */
    public static StrokeIcon pause() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.fillRoundRect(4, 3, 3, 10, 1, 1);
                g2.fillRoundRect(9, 3, 3, 10, 1, 1);
            }
        };
    }

    /** The filled "stop" square. */
    public static StrokeIcon stop() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.fillRoundRect(4, 4, 8, 8, 2, 2);
            }
        };
    }

    /** The microphone (dictation) exactly as in arch: capsule, cradle arc, stem and foot. */
    public static StrokeIcon microphone() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(5, 1, 6, 9, 6, 6);
                g2.drawArc(3, 5, 10, 8, 180, 180);
                g2.drawLine(8, 13, 8, 15);
                g2.drawLine(5, 15, 11, 15);
            }
        };
    }

    /** The three-line hamburger. */
    public static StrokeIcon menu() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 4, 13, 4);
                g2.drawLine(2, 8, 13, 8);
                g2.drawLine(2, 12, 13, 12);
            }
        };
    }

    /** The settings gear: ring, hub and eight ticks. */
    public static StrokeIcon gear() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                int cx = 7;
                int cy = 7;
                int r = 4;
                g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
                g2.drawOval(cx - 2, cy - 2, 4, 4);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    int x1 = (int) Math.round(cx + Math.cos(a) * r);
                    int y1 = (int) Math.round(cy + Math.sin(a) * r);
                    int x2 = (int) Math.round(cx + Math.cos(a) * (r + 2.5));
                    int y2 = (int) Math.round(cy + Math.sin(a) * (r + 2.5));
                    g2.drawLine(x1, y1, x2, y2);
                }
            }
        };
    }

    /** A "+" of the given size (AskAI's new-chat glyph), stroke scaled with the size. */
    public static StrokeIcon plus(final int size) {
        return new StrokeIcon(size, size) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.setStroke(new java.awt.BasicStroke(Math.max(1.6f, size / 8f),
                        java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                double pad = size * 0.22;
                double center = size / 2.0;
                g2.draw(new Line2D.Double(pad, center, size - pad, center));
                g2.draw(new Line2D.Double(center, pad, center, size - pad));
            }
        };
    }

    public static StrokeIcon chevronDown() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(3, 6, 7, 10);
                g2.drawLine(7, 10, 11, 6);
            }
        };
    }

    /** The mirror image of {@link #chevronDown()} (previous match). Own addition. */
    public static StrokeIcon chevronUp() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(3, 10, 7, 6);
                g2.drawLine(7, 6, 11, 10);
            }
        };
    }

    public static StrokeIcon chevronLeft() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(9, 3, 5, 7);
                g2.drawLine(5, 7, 9, 11);
            }
        };
    }

    public static StrokeIcon chevronRight() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(6, 3, 10, 7);
                g2.drawLine(10, 7, 6, 11);
            }
        };
    }

    /** A pencil (edit): a slanted shaft with a tip and a short cap line. */
    public static StrokeIcon pencil() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                int[] x = {3, 10, 12, 5, 3};
                int[] y = {10, 3, 5, 12, 12};
                g2.drawPolyline(x, y, 5);
                g2.drawLine(3, 10, 5, 12);
                g2.drawLine(9, 4, 11, 6);
            }
        };
    }

    /** Circular arrows (re-index, refresh): an open ring with an arrowhead at its end. */
    public static StrokeIcon refresh() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new java.awt.geom.Arc2D.Double(2.5, 2.5, 10, 10, 60, 280, java.awt.geom.Arc2D.OPEN));
                g2.drawLine(10, 1, 10, 4);
                g2.drawLine(10, 4, 13, 4);
            }
        };
    }

    /** The small ✕ glyph (discard). */
    public static StrokeIcon close() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(4, 4, 12, 12);
                g2.drawLine(12, 4, 4, 12);
            }
        };
    }

    /**
     * The magnifying glass (find in the reader): ring and handle with the geometry of
     * {@code ComicSearchBar.paintMagnifyingGlass} (AskAI), as an icon for the reader toolbar.
     */
    public static StrokeIcon magnifier() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawOval(2, 2, 10, 10);
                g2.drawLine(10, 10, 14, 14);
            }
        };
    }

    /** A "−" of the given size (zoom out), the counterpart of {@link #plus(int)}. Own addition. */
    public static StrokeIcon minus(final int size) {
        return new StrokeIcon(size, size) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.setStroke(new java.awt.BasicStroke(Math.max(1.6f, size / 8f),
                        java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                double pad = size * 0.22;
                double center = size / 2.0;
                g2.draw(new Line2D.Double(pad, center, size - pad, center));
            }
        };
    }

    /** Download (save): an arrow pointing down into an open tray. Own addition. */
    public static StrokeIcon download() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(7, 2, 7, 9);
                g2.drawLine(4, 6, 7, 9);
                g2.drawLine(10, 6, 7, 9);
                g2.drawLine(2, 10, 2, 13);
                g2.drawLine(2, 13, 12, 13);
                g2.drawLine(12, 13, 12, 10);
            }
        };
    }

    /** Export (save the page as a file): an arrow pointing up out of an open tray. Own addition. */
    public static StrokeIcon export() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(7, 9, 7, 2);
                g2.drawLine(4, 5, 7, 2);
                g2.drawLine(10, 5, 7, 2);
                g2.drawLine(2, 10, 2, 13);
                g2.drawLine(2, 13, 12, 13);
                g2.drawLine(12, 13, 12, 10);
            }
        };
    }

    /** A picture (images of a page): frame, a mountain and a sun. Own addition. */
    public static StrokeIcon picture() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(1, 2, 13, 11, 3, 3);
                g2.drawPolyline(new int[] {2, 6, 9, 11, 13}, new int[] {12, 7, 10, 8, 11}, 5);
                g2.drawOval(9, 4, 2, 2);
            }
        };
    }

    /**
     * The push pin (pin a preview tab in the reader): round head, collar, a slanted needle. Own addition, AskAI and
     * MainframeMate have no pin glyph.
     */
    public static StrokeIcon pin() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawOval(7, 1, 6, 6);
                g2.drawLine(8, 6, 7, 7);
                g2.drawLine(4, 6, 9, 11);
                g2.drawLine(6, 9, 2, 13);
            }
        };
    }

    /** The "i" in a circle (page information in the reader toolbar). Own addition. */
    public static StrokeIcon info() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawOval(1, 1, 13, 13);
                g2.fillOval(7, 3, 2, 2);
                g2.drawLine(8, 7, 8, 11);
            }
        };
    }

    /** A sheet with a folded corner (a file attachment in the reader). Own addition. */
    public static StrokeIcon document() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {9, 3, 3, 12, 12, 9, 9, 12}, new int[] {1, 1, 14, 14, 4, 1, 4, 4}, 8);
                g2.drawLine(5, 8, 10, 8);
                g2.drawLine(5, 11, 10, 11);
            }
        };
    }

    /** A box with an arrow leaving it to the upper right (open with the system application). Own addition. */
    public static StrokeIcon openExternal() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {7, 2, 2, 13, 13}, new int[] {3, 3, 13, 13, 8}, 5);
                g2.drawLine(8, 7, 13, 2);
                g2.drawPolyline(new int[] {9, 13, 13}, new int[] {2, 2, 6}, 3);
            }
        };
    }

    /** An open book (show in the reader). Own addition. */
    public static StrokeIcon openBook() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {7, 4, 1, 1, 4, 7}, new int[] {4, 2, 3, 12, 11, 13}, 6);
                g2.drawPolyline(new int[] {7, 10, 13, 13, 10, 7}, new int[] {4, 2, 3, 12, 11, 13}, 6);
                g2.drawLine(7, 4, 7, 13);
            }
        };
    }

    /** A slanted text marker over a line (show or hide the acronym marks in the reader). Own addition. */
    public static StrokeIcon marker() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolygon(new int[] {9, 13, 7, 3}, new int[] {1, 5, 11, 7}, 4);
                g2.drawPolyline(new int[] {3, 2, 5, 7}, new int[] {7, 10, 10, 11}, 4);
                g2.drawLine(1, 14, 14, 14);
            }
        };
    }
}
