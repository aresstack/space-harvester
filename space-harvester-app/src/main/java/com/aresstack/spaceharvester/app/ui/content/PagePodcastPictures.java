package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.controls.ImageViewer;
import com.aresstack.spaceharvester.app.ui.controls.TextWithPicture;
import com.aresstack.spaceharvester.app.ui.controls.ViewerPopup;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Der Podcast einer Seite im Lesetext (Angelo 23.11.2026: „Bilder … einfügen … in der Textseite, die hat ja sowieso
 * keine Bilder“, „markieren wir auch wieder die Stellen im Text, die gerade behandelt werden“):
 *
 * <ul>
 *   <li>Die Bilder der Themen stehen rechts neben dem Text, jedes auf der Höhe der Stelle, von der sein Thema erzählt
 *   (wie bei Wikipedia); der Text bricht links davon um. Ein Klick öffnet das Bild im rahmenlosen Betrachter, der aus
 *   der Bildstelle aufwächst; blättern geht durch alle Bilder der Seite.</li>
 *   <li>Die Stellen des laufenden Themas sind gelb markiert wie die Suche; die erste rollt in Sicht.</li>
 * </ul>
 *
 * Der Text selbst bleibt unverändert: Bilder liegen als Kinder über dem Lesetext, nicht im Dokument, und alle
 * Zeichenpositionen (Akronyme, Navigation) gelten weiter.
 */
final class PagePodcastPictures {

    /** Breite der Bildspalte rechts neben dem Text. */
    static final int COLUMN = 240;
    /** Abstand zwischen Text und Bild und zwischen zwei Bildern. */
    static final int GAP = 12;
    /** Markierung der laufenden Stelle, wie die Podcast-Markierung im Bericht. */
    static final Color MARK = new Color(0xFF, 0xF1, 0x9E);

    /** Ein Bild des Podcasts mit der Stelle im Text, an der sein Thema beginnt. */
    record Placed(int topic, int offset, BufferedImage image, String caption, ImageViewer.Picture viewer) {
        Placed {
            Objects.requireNonNull(image, "image");
            caption = caption == null ? "" : caption;
        }
    }

    private final JTextComponent text;
    private final List<Placed> placed = new ArrayList<>();
    private final List<JLabel> labels = new ArrayList<>();
    private final List<Object> marks = new ArrayList<>();
    private final DefaultHighlighter.DefaultHighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(MARK);
    /** Die grauen Lücken: Text, von dem der ganze Podcast nie erzählt (Angelo 27.11.2026). */
    private final List<Object> gaps = new ArrayList<>();
    private final DefaultHighlighter.DefaultHighlightPainter gapPainter =
            new DefaultHighlighter.DefaultHighlightPainter(Comic.PODCAST_GAP);
    /** Der rechte Innenabstand des Texts ohne Bildspalte; {@code -1}, solange keine Spalte steht. */
    private int baseRight = -1;
    /** Der untere Innenabstand ohne Bilder, die über das Textende hinausragen; {@code -1}, solange keine Spalte steht. */
    private int baseBottom = -1;
    private ViewerPopup cued;
    private Runnable closedByUser = () -> { };

    PagePodcastPictures(JTextComponent text) {
        this.text = Objects.requireNonNull(text, "text");
        text.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                layout();
            }
        });
    }

    /** Wer erfährt, dass der Benutzer ein vom Podcast geöffnetes Bild selbst geschlossen hat. */
    void onClosedByUser(Runnable listener) {
        this.closedByUser = Objects.requireNonNull(listener, "listener");
    }

    /** Stellt diese Bilder neben den Text; leer nimmt Spalte und Bilder weg. */
    void show(List<Placed> pictures) {
        for (JLabel label : labels) {
            text.remove(label);
        }
        labels.clear();
        placed.clear();
        int length = text.getDocument().getLength();
        // Themen ohne Stelle im Text (etwa über Unterseiten) stehen am Ende, damit kein Bild verloren geht
        pictures.stream().map(picture -> picture.offset() >= 0 && picture.offset() <= length ? picture
                        : new Placed(picture.topic(), length, picture.image(), picture.caption(), picture.viewer()))
                .sorted(Comparator.comparingInt(Placed::offset)).forEach(placed::add);
        column(!placed.isEmpty());
        for (int i = 0; i < placed.size(); i++) {
            Placed picture = placed.get(i);
            JLabel label = new JLabel(new ImageIcon(scaled(picture.image())));
            label.setName("page.podcastPicture");
            label.setToolTipText(picture.caption().isBlank() ? "Bild zum Thema: Klick vergrößert"
                    : picture.caption() + " · Klick vergrößert");
            label.setBorder(BorderFactory.createLineBorder(Comic.LINE, 1));
            Cursors.set(label, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            int position = i;
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    open(position, false);
                }
            });
            labels.add(label);
            text.add(label);
        }
        layout();
        text.repaint();
    }

    /** Markiert diese Stellen gelb und rollt die erste in Sicht; leer nimmt die Markierung weg. */
    void mark(List<int[]> spans) {
        for (Object mark : marks) {
            text.getHighlighter().removeHighlight(mark);
        }
        marks.clear();
        int length = text.getDocument().getLength();
        int first = -1;
        for (int[] span : spans) {
            int start = Math.max(0, Math.min(span[0], length));
            int end = Math.max(start, Math.min(span[1], length));
            if (end <= start) {
                continue;
            }
            try {
                marks.add(text.getHighlighter().addHighlight(start, end, painter));
                first = first < 0 ? start : Math.min(first, start);
            } catch (BadLocationException e) {
                // eine Stelle außerhalb des Texts bleibt unmarkiert
            }
        }
        if (first >= 0) {
            Rectangle at = rectangle(first);
            JTextComponentScroll.toTop(text, at);
        }
    }

    /**
     * Hinterlegt hellgrau, was außerhalb dieser Stellen steht, also wovon der ganze Podcast nie erzählt (Angelo
     * 27.11.2026: „damit man sofort sehen kann, was nicht abgedeckt war im Podcast“); leer nimmt das Grau weg. Das Gelb
     * der laufenden Stelle liegt nie auf Grau, denn es markiert nur erzählte Stellen.
     */
    void cover(java.util.Optional<List<int[]>> covered) {
        for (Object gap : gaps) {
            text.getHighlighter().removeHighlight(gap);
        }
        gaps.clear();
        if (covered.isEmpty()) {
            return;
        }
        String all;
        try {
            all = text.getDocument().getText(0, text.getDocument().getLength());
        } catch (BadLocationException e) {
            return;
        }
        for (int[] gap : gaps(all, covered.get())) {
            try {
                gaps.add(text.getHighlighter().addHighlight(gap[0], gap[1], gapPainter));
            } catch (BadLocationException e) {
                // eine Stelle außerhalb des Texts bleibt ohne Grau
            }
        }
    }

    /** Wie viele graue Lücken dastehen. */
    int gaps() {
        return gaps.size();
    }

    /**
     * Die Stellen von {@code all}, die außerhalb aller {@code covered} liegen, als {@code [start, end)} in
     * Textreihenfolge; Leerraum am Rand einer Lücke bleibt weiß, eine Lücke nur aus Leerraum entfällt.
     */
    static List<int[]> gaps(String all, List<int[]> covered) {
        boolean[] told = new boolean[all.length()];
        for (int[] span : covered) {
            int start = Math.max(0, Math.min(span[0], all.length()));
            int end = Math.max(start, Math.min(span[1], all.length()));
            java.util.Arrays.fill(told, start, end, true);
        }
        List<int[]> gaps = new ArrayList<>();
        int i = 0;
        while (i < all.length()) {
            if (told[i]) {
                i++;
                continue;
            }
            int start = i;
            while (i < all.length() && !told[i]) {
                i++;
            }
            int end = i;
            while (start < end && Character.isWhitespace(all.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(all.charAt(end - 1))) {
                end--;
            }
            if (end > start) {
                gaps.add(new int[]{start, end});
            }
        }
        return gaps;
    }

    /** Öffnet das Bild von Thema {@code topic}, wie es der Podcast tut; {@code false} ohne Bild. */
    boolean open(int topic) {
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).topic() == topic) {
                text.scrollRectToVisible(labels.get(i).getBounds());
                return open(i, true);
            }
        }
        return false;
    }

    /** Schließt ein vom Podcast geöffnetes Bild wieder an seine Stelle. */
    void close() {
        ViewerPopup open = cued;
        cued = null;
        if (open != null) {
            open.close();
        }
    }

    private boolean open(int position, boolean byPodcast) {
        if (position < 0 || position >= labels.size() || SwingUtilities.getRootPane(text) == null) {
            return false;
        }
        JLabel label = labels.get(position);
        ViewerPopup popup = ViewerPopup.open(text, new ImageViewer(placed.stream().map(Placed::viewer).toList(),
                        position, Comic.viewerLook(com.aresstack.spaceharvester.app.settings.ViewerBackground.DEFAULT)),
                () -> label.getParent() == text ? label.getBounds() : null);
        if (byPodcast) {
            cued = popup;
            popup.onClosed(() -> {
                if (cued == popup) {
                    cued = null;
                    closedByUser.run();
                }
            });
        }
        return true;
    }

    /** Hält rechts Platz für die Bildspalte frei oder gibt ihn zurück. */
    private void column(boolean shown) {
        Insets insets = text.getInsets();
        if (shown && baseRight < 0) {
            baseRight = insets.right;
            baseBottom = insets.bottom;
            text.setBorder(BorderFactory.createEmptyBorder(insets.top, insets.left, insets.bottom,
                    baseRight + COLUMN + GAP));
        } else if (!shown && baseRight >= 0) {
            text.setBorder(BorderFactory.createEmptyBorder(insets.top, insets.left, baseBottom, baseRight));
            baseRight = -1;
            baseBottom = -1;
        }
        text.revalidate();
    }

    /** Jedes Bild auf der Höhe seiner Stelle, rechts; überlappen würde es nie, es rückt unter das vorige. */
    private void layout() {
        if (labels.isEmpty()) {
            return;
        }
        int x = text.getWidth() - (baseRight < 0 ? 0 : baseRight) - COLUMN;
        int below = 0;
        for (int i = 0; i < labels.size(); i++) {
            JLabel label = labels.get(i);
            java.awt.Dimension size = label.getPreferredSize();
            Rectangle at = rectangle(placed.get(i).offset());
            int y = Math.max(at == null ? below : at.y, below);
            label.setBounds(Math.max(0, x), y, size.width, size.height);
            below = y + size.height + GAP;
        }
        reach(below);
        text.repaint();
    }

    /**
     * Ragen Bilder unter das Textende, wächst der untere Innenabstand so weit, dass die Bildlaufleiste bis zum letzten
     * Bild reicht; die Wunschhöhe des Texts kennt nur das Dokument, nicht die Bilder darüber.
     */
    private void reach(int bottom) {
        if (baseBottom < 0) {
            return;
        }
        Rectangle end = rectangle(text.getDocument().getLength());
        int textBottom = end == null ? 0 : end.y + end.height;
        int wanted = baseBottom + Math.max(0, bottom - textBottom);
        Insets insets = text.getInsets();
        if (insets.bottom != wanted) {
            text.setBorder(BorderFactory.createEmptyBorder(insets.top, insets.left, wanted, insets.right));
            text.revalidate();
        }
    }

    private Rectangle rectangle(int offset) {
        try {
            Rectangle2D view = text.modelToView2D(Math.max(0, Math.min(offset, text.getDocument().getLength())));
            return view == null ? null : view.getBounds();
        } catch (BadLocationException e) {
            return null;
        }
    }

    private static Image scaled(BufferedImage image) {
        int width = Math.min(COLUMN, Math.min(TextWithPicture.MAX_PICTURE_WIDTH, image.getWidth()));
        int height = Math.max(1, Math.round(image.getHeight() * (width / (float) image.getWidth())));
        return image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
    }

    // --- für Tests ----------------------------------------------------------------------------------------------

    /** Die Bildunterschriften der gezeigten Bilder in der Reihenfolge der Seite. */
    List<String> captions() {
        return placed.stream().map(Placed::caption).toList();
    }

    /** Wie viele Stellen markiert sind. */
    int marked() {
        return marks.size();
    }

    /** Die Lage der Bilder im Text. */
    List<Rectangle> bounds() {
        return labels.stream().map(JLabel::getBounds).toList();
    }

    /** Rollt so, dass eine Stelle mit etwas Luft darüber oben steht. */
    private static final class JTextComponentScroll {
        private JTextComponentScroll() {
        }

        static void toTop(JTextComponent text, Rectangle at) {
            if (at == null || !(SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, text)
                    instanceof javax.swing.JViewport port)) {
                return;
            }
            int y = Math.max(0, Math.min(at.y - port.getExtentSize().height / 5,
                    text.getHeight() - port.getExtentSize().height));
            port.setViewPosition(new java.awt.Point(port.getViewPosition().x, y));
        }
    }
}
