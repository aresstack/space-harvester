package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.JTextPane;
import javax.swing.text.DefaultCaret;
import javax.swing.text.View;
import java.awt.Dimension;
import java.awt.Insets;

/** Hält eine Textfläche in einem vertikalen Swing-Layout auf ihrer natürlichen umbrochenen Höhe (aus askai-java8). */
final class WrappingTextPane extends JTextPane {

    WrappingTextPane() {
        setEditable(false);
        setOpaque(false);
        setBorder(null);
        setFocusable(true);
        // Nur lesen: Einfügen des Texts setzt keine Schreibmarke, die den Leser per scrollRectToVisible ans Ende
        // eines Dokuments rollen würde. Markieren mit der Maus bleibt möglich.
        if (getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public Dimension getPreferredSize() {
        // Bei der Breite messen, die das Layout zugewiesen hat (getWidth); vor dem ersten echten Layout die Breite
        // des Elternteils nehmen. Die Höhe kommt aus der Text-View bei dieser Breite, ohne die Komponente zu
        // verändern, sodass schon ein normaler Layoutdurchlauf die richtige Höhe liefert.
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            width = getParent().getWidth();
        }
        if (width <= 0) {
            return super.getPreferredSize();
        }
        return new Dimension(Math.max(1, width), heightForWidth(width));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * Berechnet die umbrochene Höhe für eine exakte Komponentenbreite, indem die Wurzel-View bei dieser Breite
     * ausgelegt wird. Das ändert nur den gecachten Span der View, nicht die Grenzen der Komponente, und ist daher
     * aus {@code getPreferredSize()} und aus einem Wirt, der vor dem Layout misst, sicher aufrufbar
     * (siehe {@link MarkdownHeights}).
     */
    int heightForWidth(int width) {
        View root = getUI().getRootView(this);
        if (root == null) {
            return super.getPreferredSize().height;
        }
        Insets insets = getInsets();
        float textWidth = Math.max(1f, width - insets.left - insets.right);
        root.setSize(textWidth, Integer.MAX_VALUE);
        int textHeight = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS));
        return textHeight + insets.top + insets.bottom;
    }
}
