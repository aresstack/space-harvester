package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;

/**
 * Umbrechender Text ohne Rahmen, der seine Spalte nie breiter macht: Er nimmt die Breite, die er bekommt, und ist
 * dann genau so hoch wie sein Text (Hinweise unter Formularfeldern, Meldungen).
 *
 * <p>Die Höhe misst er selbst für eine genannte Breite ({@link #heightFor}), an einem eigenen, nie angezeigten
 * Textfeld. Die Wunschhöhe eines {@link JTextArea} hängt sonst davon ab, mit welcher Breite es zuletzt umbrochen hat:
 * Ein noch unsichtbarer Behälter wird mit Breite 0 ausgelegt, der Text darin bekommt eine negative Breite und bricht
 * danach je Zeichen um. Eine kurze Meldung war so beim ersten Einblenden Hunderte Pixel hoch.
 */
public final class WrappedText extends JTextArea {

    /** Breite, mit der ein noch nicht ausgelegter Text seine Höhe misst (übliche Spalte eines Formulars). */
    public static final int FALLBACK_WIDTH = 400;
    /** Wunschbreite: schmal, damit der Text seine Spalte nie aufdrückt. */
    private static final int NARROW = 40;
    /** Schmaler wird nicht gemessen: Darunter passt kein Wort mehr in eine Zeile. */
    private static final int MINIMUM_WIDTH = 16;

    private JTextArea ruler;
    private String measuredText;
    private Font measuredFont;
    private int measuredWidth = -1;
    private int measuredHeight;
    private int naturalWidth;
    /** Die Höhe, die der Behälter zuletzt als Wunschhöhe bekommen hat. */
    private int reported = -1;
    private int checkedWidth = -1;

    public WrappedText(String text) {
        super(text);
        setEditable(false);
        setFocusable(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        // Neuer Text setzt die Schreibmarke ans Ende; ohne das scrollte ein Scrollbereich darum dorthin mit.
        if (getCaret() instanceof DefaultCaret caret) {
            caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
    }

    /** Die Höhe des Texts, wenn er auf {@code width} Pixel (mit Rand) umbricht. */
    public int heightFor(int width) {
        Insets insets = getInsets();
        measure(Math.max(MINIMUM_WIDTH, width - insets.left - insets.right));
        return measuredHeight + insets.top + insets.bottom;
    }

    private void measure(int inner) {
        String text = getText();
        Font font = getFont();
        if (inner == measuredWidth && text.equals(measuredText) && font.equals(measuredFont)) {
            return;
        }
        if (ruler == null) {
            ruler = new JTextArea();
            ruler.setLineWrap(true);
            ruler.setWrapStyleWord(true);
            ruler.setBorder(BorderFactory.createEmptyBorder());
        }
        ruler.setFont(font);
        ruler.setTabSize(getTabSize());
        ruler.setText(text);
        // Breite UND Höhe gesetzt: nur dann bricht das Textfeld für genau diese Breite um.
        ruler.setSize(inner, Short.MAX_VALUE);
        measuredHeight = ruler.getPreferredSize().height;
        FontMetrics metrics = getFontMetrics(font);
        int natural = 1;
        for (String paragraph : text.split("\n", -1)) {
            natural = Math.max(natural, metrics.stringWidth(paragraph) + 1);
            if (natural >= NARROW) {
                break;
            }
        }
        naturalWidth = natural;
        measuredText = text;
        measuredFont = font;
        measuredWidth = inner;
    }

    @Override
    public Dimension getPreferredSize() {
        // Noch ohne Breite (vor dem ersten Auslegen, beim pack() eines Fensters oder in einem unsichtbaren
        // Behälter): die Höhe für eine übliche Spaltenbreite, damit von Anfang an Platz für den Umbruch ist.
        reported = heightFor(getWidth() > 0 ? getWidth() : FALLBACK_WIDTH);
        Insets insets = getInsets();
        return new Dimension(Math.min(naturalWidth, NARROW) + insets.left + insets.right, reported);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        // Mit der echten Breite bricht der Text anders um als bei der Schätzung; braucht er eine andere Höhe, als der
        // Behälter erfahren hat, legt der neu aus. Je Breite nur einmal, sonst liefe das im Kreis.
        if (width > 0 && width != checkedWidth) {
            checkedWidth = width;
            if (heightFor(width) != reported) {
                SwingUtilities.invokeLater(this::revalidate);
            }
        }
    }
}
