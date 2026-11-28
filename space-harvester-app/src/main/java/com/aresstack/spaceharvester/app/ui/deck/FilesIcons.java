package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/** Gezeichnete Symbole des Arbeitsbereichs Dateien (keine Schriftzeichen, siehe Glyph). */
public final class FilesIcons {

    private static final Color FOLDER_FILL = AppColors.of("app.FilesIcons.folderFill");
    private static final Color FOLDER_EDGE = AppColors.of("app.FilesIcons.folderEdge");
    private static final Color PAPER_EDGE = AppColors.of("app.FilesIcons.paperEdge");

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
                g2.setColor(AppColors.of("bubble.userForeground"));
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

    /** Ordner mit Plus: „Neuer Ordner“ (Dateien im Deck und Favoriten-Ordner in der Seitenleiste). */
    public static StrokeIcon newFolder() {
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
        return dot(AppColors.of("app.FilesIcons.unsaved"));
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

    /** Dateiarten für die Kennfarbe des Symbols (wie die Endungs-Symbole in MainframeMate). */
    enum Kind {
        MAINFRAME(AppColors.of("app.FilesIcons.kindMainframe"), "jcl", "proc", "prc", "cntl", "cbl", "cob", "cobol", "cpy", "nat", "nsp",
                "nsn", "nss", "nsh", "nsc", "nsl", "nsa", "nsg", "nsm", "nsd", "ns3", "ns4", "ns5", "ns6", "ns7",
                "ns8", "asm", "rexx"),
        CODE(AppColors.of("app.FilesIcons.kindCode"), "java", "kt", "groovy", "gradle", "py", "js", "ts", "c", "h", "cpp", "cs", "go",
                "rb", "php", "sql", "sh", "bat", "cmd", "ps1", "json", "xml", "xsd", "yaml", "yml", "html", "htm",
                "css", "properties", "ini"),
        PDF(AppColors.of("app.FilesIcons.kindPdf"), "pdf"),
        WORD(AppColors.of("app.FilesIcons.kindWord"), "doc", "docx", "odt", "rtf"),
        TABLE(AppColors.of("app.FilesIcons.kindTable"), "xls", "xlsx", "ods", "csv"),
        SLIDES(AppColors.of("app.FilesIcons.kindSlides"), "ppt", "pptx", "odp"),
        MAIL(AppColors.of("app.FilesIcons.kindMail"), "msg", "eml"),
        IMAGE(AppColors.of("app.FilesIcons.kindImage"), "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "tif", "tiff"),
        ARCHIVE(AppColors.of("app.FilesIcons.kindArchive"), "zip", "jar", "7z", "rar", "gz", "tar"),
        TEXT(null, "txt", "log", "md");

        private final Color color;
        private final java.util.Set<String> extensions;

        Kind(Color color, String... extensions) {
            this.color = color;
            this.extensions = java.util.Set.of(extensions);
        }

        static Kind of(String name) {
            int dot = name == null ? -1 : name.lastIndexOf('.');
            String extension = dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
            for (Kind kind : values()) {
                if (kind.extensions.contains(extension)) {
                    return kind;
                }
            }
            return TEXT;
        }
    }

    private static final java.util.Map<Kind, Icon> TYPED = new java.util.EnumMap<>(Kind.class);

    /** Blatt mit farbigem Band unten für die Dateiart; Text ohne Band. */
    static Icon forName(String name) {
        Kind kind = Kind.of(name);
        return TYPED.computeIfAbsent(kind, k -> k.color == null ? file() : new Icon() {
            private final Icon paper = file();

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                paper.paintIcon(c, g, x, y);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(k.color);
                g2.fillRoundRect(x + 1, y + 9, 10, 6, 2, 2);
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
        });
    }

    /** Nach Endung gruppieren: drei Ordnerreiter übereinander. */
    static com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon group() {
        return new com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRect(1, 2, 5, 3);
                g2.drawLine(8, 4, 15, 4);
                g2.drawRect(3, 7, 5, 3);
                g2.drawLine(10, 9, 15, 9);
                g2.drawRect(3, 12, 5, 3);
                g2.drawLine(10, 14, 15, 14);
            }
        };
    }
}
