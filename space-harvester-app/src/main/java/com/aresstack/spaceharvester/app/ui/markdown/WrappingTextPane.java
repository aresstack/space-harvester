package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.JTextPane;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DefaultCaret;
import javax.swing.text.EditorKit;
import javax.swing.text.Element;
import javax.swing.text.LabelView;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
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

    /**
     * Text bricht immer auf die zugewiesene Breite um (Angelo 26.11.2026: im Chat nichts rechts abschneiden). Swing
     * legt einen Absatz sonst mindestens so breit wie sein längstes Stück ohne Leerzeichen aus (Mindestbreite der
     * Text-View): eine lange URL, ein Pfad, ein Klassenname, Wortketten mit Bindestrich oder ein ganzer Satz mit
     * geschützten Leerzeichen machten die Zeilen breiter als die Fläche, der Rest war abgeschnitten.
     */
    @Override
    protected EditorKit createDefaultEditorKit() {
        return new WrappingEditorKit();
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

    /** Wie {@link StyledEditorKit}, aber Textstücke dürfen schmaler werden als ihr längstes Wort. */
    private static final class WrappingEditorKit extends StyledEditorKit {

        private final ViewFactory factory = new WrappingViewFactory(super.getViewFactory());

        @Override
        public ViewFactory getViewFactory() {
            return factory;
        }
    }

    private static final class WrappingViewFactory implements ViewFactory {

        private final ViewFactory styled;

        WrappingViewFactory(ViewFactory styled) {
            this.styled = styled;
        }

        @Override
        public View create(Element element) {
            if (AbstractDocument.ContentElementName.equals(element.getName())) {
                return new BreakableLabelView(element);
            }
            return styled.create(element);
        }
    }

    /**
     * Ohne Mindestbreite bekommt der Absatz genau die verfügbare Breite. Der Zeilenumbruch bevorzugt weiter
     * Leerzeichen und die Umbruchstellen des Zeilen-{@code BreakIterator}s; nur ein Stück, das allein nicht in die
     * Zeile passt, wird zwischen zwei Zeichen getrennt.
     */
    private static final class BreakableLabelView extends LabelView {

        BreakableLabelView(Element element) {
            super(element);
        }

        @Override
        public float getMinimumSpan(int axis) {
            return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
        }
    }
}
