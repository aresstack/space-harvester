package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/** Gezeichnete Symbole des Arbeitsbereichs Dateien (keine Schriftzeichen, siehe Glyph). */
final class FilesIcons {

    private static final Color FOLDER_FILL = new Color(0xFFE08A);
    private static final Color FOLDER_EDGE = new Color(0xC99A00);
    private static final Color PAPER_EDGE = new Color(0x8A8F98);

    private FilesIcons() {
    }

    /** Gelber Ordner, wie im Explorer üblich. */
    static Icon folder() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                g2.setColor(FOLDER_FILL);
                g2.fillRoundRect(1, 4, 14, 10, 3, 3);
                g2.fillRoundRect(1, 2, 6, 4, 2, 2);
                g2.setColor(FOLDER_EDGE);
                g2.drawRoundRect(1, 4, 14, 10, 3, 3);
                g2.drawLine(2, 2, 6, 2);
                g2.drawLine(6, 2, 8, 4);
                g2.drawLine(1, 3, 1, 4);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** Blatt mit Eselsohr. */
    static Icon file() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                int[] xs = {3, 10, 13, 13, 3};
                int[] ys = {1, 1, 4, 15, 15};
                g2.setColor(Color.WHITE);
                g2.fillPolygon(xs, ys, xs.length);
                g2.setColor(PAPER_EDGE);
                g2.drawPolygon(xs, ys, xs.length);
                g2.drawLine(10, 1, 10, 4);
                g2.drawLine(10, 4, 13, 4);
                g2.drawLine(5, 8, 11, 8);
                g2.drawLine(5, 11, 11, 11);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** Ordner mit Plus: „Neuer Ordner“. */
    static StrokeIcon newFolder() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(1, 4, 1, 13);
                g2.drawLine(1, 13, 9, 13);
                g2.drawLine(1, 4, 5, 4);
                g2.drawLine(5, 4, 7, 6);
                g2.drawLine(7, 6, 14, 6);
                g2.drawLine(14, 6, 14, 8);
                g2.drawLine(12, 10, 12, 15);
                g2.drawLine(10, 12, 15, 12);
            }
        };
    }

    /** Blatt mit Plus: „Neue Datei“. */
    static StrokeIcon newFile() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(3, 1, 9, 1);
                g2.drawLine(9, 1, 12, 4);
                g2.drawLine(12, 4, 12, 8);
                g2.drawLine(3, 1, 3, 14);
                g2.drawLine(3, 14, 8, 14);
                g2.drawLine(12, 10, 12, 15);
                g2.drawLine(10, 12, 15, 12);
            }
        };
    }

    /** Haus: zum Benutzerordner und zu den anderen Orten. */
    static StrokeIcon places() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 8, 8, 2);
                g2.drawLine(8, 2, 14, 8);
                g2.drawLine(4, 7, 4, 14);
                g2.drawLine(12, 7, 12, 14);
                g2.drawLine(4, 14, 12, 14);
                g2.drawLine(7, 14, 7, 10);
                g2.drawLine(7, 10, 9, 10);
                g2.drawLine(9, 10, 9, 14);
            }
        };
    }

    /** Diskette: „Speichern“. */
    static StrokeIcon save() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 2, 12, 2);
                g2.drawLine(12, 2, 14, 4);
                g2.drawLine(14, 4, 14, 14);
                g2.drawLine(14, 14, 2, 14);
                g2.drawLine(2, 14, 2, 2);
                g2.drawRect(5, 2, 6, 4);
                g2.drawRect(4, 9, 8, 5);
            }
        };
    }

    /** Orangefarbener Punkt: ungespeicherte Änderungen. */
    static Icon unsaved() {
        return dot(new Color(0xE08A00));
    }

    static Icon dot(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x + 2, y + 2, 8, 8);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }
        };
    }
}
