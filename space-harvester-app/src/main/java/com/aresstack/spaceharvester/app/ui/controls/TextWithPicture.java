package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * Ein Absatz mit Bild oben rechts daneben (UX-Manager, 22.11.2026, für die Bilder des Podcasts im Bericht): links der
 * Text, rechts oben das Bild mit einem Drittel der Spaltenbreite, höchstens {@link #MAX_PICTURE_WIDTH} Pixel, darunter
 * klein und blass die Bildunterschrift in höchstens zwei Zeilen. Ist der Text länger als das Bild hoch, läuft er
 * darunter in voller Breite weiter. Unter {@link #NARROW} Pixel Spaltenbreite steht das Bild in voller Breite über dem
 * Text. Ohne Bild ist es ein gewöhnlicher umbrechender Absatz. Ein Klick auf das Bild meldet {@link #onOpen}.
 */
public final class TextWithPicture extends JPanel {

    /** Breitestes Bild neben dem Text. */
    public static final int MAX_PICTURE_WIDTH = 280;
    /** Schmaler steht das Bild über dem Text. */
    public static final int NARROW = 560;
    /** Abstand zwischen Text und Bild. */
    static final int GAP = 12;
    static final String OPEN_TIP = "Vergrößern";
    /** Abstand zwischen Text und Fuß. */
    static final int FOOTER_GAP = 2;

    private final String text;
    private final WrappedText beside;
    private final WrappedText below;
    private final JLabel picture = new JLabel();
    private final WrappedText caption = Comic.wrapped("", Comic.MUTED, 12f);
    /**
     * Misst Text, unsichtbar im eigenen Baum: So gelten dieselben Schriftmaße wie für den angezeigten Text, auch bei
     * Bildschirmskalierung (ein Messfeld ohne Eltern bricht dort anders um, und die letzte Zeile fehlte).
     */
    private final javax.swing.JTextArea ruler = new javax.swing.JTextArea();
    private BufferedImage image;
    private JComponent footer;
    /** Aus welchem Bild das gezeigte verkleinert ist (nur neu skalieren, wenn sich etwas ändert). */
    private BufferedImage scaledFrom;
    private Runnable onOpen = () -> { };
    private int laidOutWidth = -1;
    private int splitWidth = -1;
    private int splitHeight = -1;
    private int splitAt;
    /** Hinterlegung des ganzen Absatzes (gelb, solange der Podcast davon erzählt), {@code null} ohne. */
    private Color mark;
    private final List<Runnable> unmark = new java.util.ArrayList<>();

    public TextWithPicture(String text, Color color, float size) {
        super(null);
        this.text = text == null ? "" : text;
        this.beside = Comic.wrapped(this.text, color, size);
        this.below = Comic.wrapped("", color, size);
        ruler.setLineWrap(true);
        ruler.setWrapStyleWord(true);
        ruler.setEditable(false);
        ruler.setBorder(BorderFactory.createEmptyBorder());
        ruler.setVisible(false);
        // Neuer Text setzt die Schreibmarke ans Ende und rollt sie in Sicht, auch unsichtbar: jedes Messen auf dem EDT
        // zog den Bericht sonst zum zuletzt gemessenen Absatz, weg von der Stelle, die der Podcast gerade markiert
        if (ruler.getCaret() instanceof javax.swing.text.DefaultCaret caret) {
            caret.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        }
        setOpaque(false);
        picture.setBorder(BorderFactory.createLineBorder(Comic.INK, 1));
        Cursors.set(picture, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        picture.setToolTipText(OPEN_TIP);
        picture.getAccessibleContext().setAccessibleName(OPEN_TIP);
        picture.setName("text-with-picture.picture");
        picture.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                onOpen.run();
            }
        });
        caption.setName("text-with-picture.caption");
        add(beside);
        add(below);
        add(picture);
        add(caption);
        add(ruler);
        showPicture(false);
    }

    /** Setzt das Bild daneben samt Unterschrift; {@code null} nimmt es weg. */
    public void picture(BufferedImage image, String caption) {
        String strippedCaption = caption == null ? "" : caption.strip();
        if (image == this.image && (image == null || strippedCaption.equals(this.caption.getText()))) {
            return;
        }
        this.image = image;
        this.caption.setText(caption == null ? "" : caption.strip());
        showPicture(image != null);
        laidOutWidth = -1;
        splitWidth = -1;
        revalidate();
        repaint();
    }

    /**
     * Was unter dem Text steht (etwa die Seitenverweise einer Meldung): direkt unter dem Text, auch neben dem Bild,
     * solange der Text daneben passt; {@code null} nimmt ihn weg.
     */
    public void footer(JComponent footer) {
        if (this.footer != null) {
            remove(this.footer);
        }
        this.footer = footer;
        if (footer != null) {
            add(footer);
        }
        revalidate();
        repaint();
    }

    /** Der Fuß unter dem Text, {@code null} ohne. */
    public JComponent footer() {
        return footer;
    }

    /** Was ein Klick auf das Bild tut. */
    public void onOpen(Runnable onOpen) {
        this.onOpen = Objects.requireNonNull(onOpen, "onOpen");
    }

    public boolean hasPicture() {
        return image != null;
    }

    /** Wo das Bild steht, in den eigenen Koordinaten (für das Aufwachsen des Betrachters). */
    public Rectangle pictureBounds() {
        return picture.getBounds();
    }

    /** Das Bild als Komponente (für Koordinaten und Tests). */
    public JComponent pictureComponent() {
        return picture;
    }

    /** Die sichtbaren Textflächen (zum Markieren). */
    public List<WrappedText> texts() {
        return below.isVisible() ? List.of(beside, below) : List.of(beside);
    }

    /** Der ganze Text. */
    public String text() {
        return text;
    }

    /** Die Bildunterschrift, leer ohne. */
    public String caption() {
        return image == null ? "" : caption.getText();
    }

    /**
     * Hinterlegt den ganzen Text in dieser Farbe (auch über den Umbruch neben und unter dem Bild hinweg und nach jedem
     * neuen Umbruch); {@code null} nimmt die Hinterlegung weg.
     */
    public void highlight(Color color) {
        mark = color;
        remark();
    }

    /** Ob der Text gerade hinterlegt ist. */
    public boolean highlighted() {
        return mark != null;
    }

    /** Die Farbe der Hinterlegung, {@code null} ohne. */
    public Color highlightColor() {
        return mark;
    }

    private void remark() {
        unmark.forEach(Runnable::run);
        unmark.clear();
        if (mark == null) {
            return;
        }
        for (WrappedText part : texts()) {
            int length = part.getDocument().getLength();
            if (length == 0) {
                continue;
            }
            try {
                Object tag = part.getHighlighter().addHighlight(0, length,
                        new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(mark));
                unmark.add(() -> part.getHighlighter().removeHighlight(tag));
            } catch (javax.swing.text.BadLocationException e) {
                // ein leerer Text braucht keine Hinterlegung
            }
        }
        repaint();
    }

    private static boolean setText(WrappedText part, String text) {
        if (text.equals(part.getText())) {
            return false;
        }
        part.setText(text);
        return true;
    }

    private void showPicture(boolean show) {
        picture.setVisible(show);
        caption.setVisible(show && !caption.getText().isEmpty());
        if (!show) {
            setText(beside, text);
            setText(below, "");
            below.setVisible(false);
            remark();
        }
    }

    /** Schmale Wunschbreite wie {@link WrappedText}: der Absatz drückt seine Spalte nie auf. */
    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : WrappedText.FALLBACK_WIDTH;
        Insets in = getInsets();
        return new Dimension(40 + in.left + in.right, arrange(width, false));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public void doLayout() {
        int height = arrange(getWidth(), true);
        if (getWidth() != laidOutWidth) {
            laidOutWidth = getWidth();
            if (height != getHeight()) {
                javax.swing.SwingUtilities.invokeLater(this::revalidate);
            }
        }
    }

    /** Ordnet alles für {@code width} an (oder misst nur) und gibt die Gesamthöhe zurück. */
    private int arrange(int width, boolean apply) {
        Insets in = getInsets();
        int w = Math.max(1, width - in.left - in.right);
        if (image == null) {
            int h = measure(text, beside, w);
            int fh = footerHeight(w);
            if (apply) {
                beside.setBounds(in.left, in.top, w, h);
                placeFooter(in.left, in.top + h + FOOTER_GAP, w, fh);
            }
            return in.top + h + (fh > 0 ? FOOTER_GAP + fh : 0) + in.bottom;
        }
        boolean narrow = w < NARROW;
        int pw = narrow ? w : Math.min(MAX_PICTURE_WIDTH, w / 3);
        int ph = Math.max(1, Math.round((float) image.getHeight() * pw / Math.max(1, image.getWidth())));
        int ch = caption.getText().isEmpty() ? 0 : Math.min(measure(caption.getText(), caption, pw), twoLines());
        int block = ph + (ch > 0 ? 4 + ch : 0);
        if (narrow) {
            int th = measure(text, beside, w);
            int fh = footerHeight(w);
            if (apply) {
                place(in.left, in.top, pw, ph, ch);
                boolean changed = setText(beside, text) | below.isVisible();
                below.setVisible(false);
                beside.setBounds(in.left, in.top + block + 8, w, th);
                placeFooter(in.left, in.top + block + 8 + th + FOOTER_GAP, w, fh);
                if (changed) {
                    remark();
                }
            }
            return in.top + block + 8 + th + (fh > 0 ? FOOTER_GAP + fh : 0) + in.bottom;
        }
        int tw = Math.max(1, w - pw - GAP);
        int cut = split(tw, block);
        String top = text.substring(0, cut).strip();
        String rest = text.substring(cut).strip();
        int topHeight = measure(top, beside, tw);
        int restHeight = measure(rest, beside, w);
        // Passt der ganze Text neben das Bild, steht der Fuß (die Seitenverweise) direkt darunter in der linken
        // Spalte, nicht erst unter dem Bild (UX-Manager 22.11.2026); sonst am Ende in voller Breite.
        boolean footBeside = rest.isEmpty();
        int fh = footerHeight(footBeside ? tw : w);
        int left = topHeight + (footBeside && fh > 0 ? FOOTER_GAP + fh : 0);
        int besideHeight = Math.max(block, left);
        int total = besideHeight + (rest.isEmpty() ? 0 : 4 + restHeight)
                + (!footBeside && fh > 0 ? FOOTER_GAP + fh : 0);
        if (apply) {
            place(in.left + w - pw, in.top, pw, ph, ch);
            boolean changed = setText(beside, top) | setText(below, rest) | below.isVisible() == rest.isEmpty();
            beside.setBounds(in.left, in.top, tw, topHeight);
            below.setVisible(!rest.isEmpty());
            below.setBounds(in.left, in.top + besideHeight + 4, w, restHeight);
            if (footBeside) {
                placeFooter(in.left, in.top + topHeight + FOOTER_GAP, tw, fh);
            } else {
                placeFooter(in.left, in.top + besideHeight + 4 + restHeight + FOOTER_GAP, w, fh);
            }
            if (changed) {
                remark();
            }
        }
        return in.top + total + in.bottom;
    }

    /** Wie hoch der Fuß auf {@code width} Pixel ist; 0 ohne. */
    private int footerHeight(int width) {
        if (footer == null || !footer.isVisible()) {
            return 0;
        }
        if (footer.getWidth() != width) {
            footer.setSize(width, Math.max(1, footer.getHeight()));
        }
        return footer.getPreferredSize().height;
    }

    private void placeFooter(int x, int y, int width, int height) {
        if (footer != null) {
            footer.setBounds(x, y, width, height);
        }
    }

    private void place(int x, int y, int pw, int ph, int ch) {
        if (picture.getIcon() == null || picture.getWidth() != pw || picture.getHeight() != ph
                || scaledFrom != image) {
            scaledFrom = image;
            picture.setIcon(new ImageIcon(image.getScaledInstance(Math.max(1, pw - 2), Math.max(1, ph - 2),
                    Image.SCALE_SMOOTH)));
        }
        picture.setBounds(x, y, pw, ph);
        caption.setBounds(x, y + ph + 4, pw, ch);
    }

    /**
     * Wie viel vom Text neben das Bild passt: die längste Folge ganzer Wörter, die auf {@code width} umbrochen nicht
     * höher als {@code height} ist (mindestens das erste Wort, damit neben dem Bild nie nur Leere steht).
     */
    private int split(int width, int height) {
        if (width == splitWidth && height == splitHeight) {
            return splitAt;
        }
        splitWidth = width;
        splitHeight = height;
        splitAt = measureSplit(width, height);
        return splitAt;
    }

    private int measureSplit(int width, int height) {
        if (measure(text, beside, width) <= height) {
            return text.length();
        }
        int[] ends = wordEnds(text);
        int lo = 1;
        int hi = ends.length - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (measure(text.substring(0, ends[mid]), beside, width) <= height) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ends.length == 0 ? text.length() : ends[best];
    }

    /** Wo die Wörter enden (Index nach dem letzten Zeichen), samt dem ersten Wort an Stelle 0. */
    static int[] wordEnds(String text) {
        List<Integer> ends = new java.util.ArrayList<>();
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || Character.isWhitespace(text.charAt(i))
                    && !Character.isWhitespace(text.charAt(i - 1))) {
                ends.add(i);
            }
        }
        return ends.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Wie hoch {@code value} in der Schrift von {@code like} auf {@code width} Pixel umbrochen ist; 0 ohne Text. */
    private int measure(String value, WrappedText like, int width) {
        if (value.isEmpty()) {
            return 0;
        }
        Insets in = like.getInsets();
        if (!like.getFont().equals(ruler.getFont())) {
            ruler.setFont(like.getFont());
        }
        ruler.setText(value);
        // Breite UND Höhe gesetzt: nur dann bricht das Textfeld für genau diese Breite um (wie WrappedText).
        ruler.setSize(Math.max(16, width - in.left - in.right), Short.MAX_VALUE);
        return ruler.getPreferredSize().height + in.top + in.bottom;
    }

    private int twoLines() {
        Font font = caption.getFont();
        Insets in = caption.getInsets();
        return 2 * caption.getFontMetrics(font).getHeight() + in.top + in.bottom;
    }
}
