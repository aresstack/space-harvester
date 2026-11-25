package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;
import com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI;

import javax.swing.JScrollPane;
import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * Measure Markdown blocks at their natural height. Cap code blocks at
 * {@value #MAX_CONTENT_HEIGHT} px; display tables at their full content height. Braucht der Inhalt bei der gegebenen Breite einen
 * horizontalen Scrollbalken, wird dessen Höhe eingerechnet, damit er nicht den Ausschnitt verkleinert und einem kurzen
 * Block zusätzlich einen vertikalen Scrollbalken beschert. Die Höhe hängt damit von der Breite ab
 * ({@link WidthAwareHeight}), was {@link MarkdownHeights} beim Layout der Blase berücksichtigt.
 *
 * <p>Beide Balken tragen die schlanken Comic-Balken ({@link ComicScrollBarUI}, ohne Pfeilknöpfe, transparente
 * Schiene); der Daumen nimmt die gedämpfte Schriftfarbe der Blase und beim Ziehen ihre volle Schriftfarbe, damit er
 * auf jeder Blasenfarbe sichtbar bleibt.</p>
 */
class CappedScrollPane extends JScrollPane implements WidthAwareHeight {

    static final int MAX_CONTENT_HEIGHT = 320;

    private final int preferredWidth;
    private final int extraHeight;
    private final HeightPolicy heightPolicy;

    enum HeightPolicy {
        CAPPED, FULL_CONTENT
    }

    /**
     * @param view           der Inhalt (Textfläche oder Tabelle), der seine natürliche Größe meldet
     * @param preferredWidth die Wunschbreite, solange die Umgebung noch keine Breite vorgibt
     * @param extraHeight    Luft um den Inhalt, die zur natürlichen Höhe vor der Kappung hinzukommt
     * @param theme          liefert die Daumenfarben der Comic-Balken
     */
    CappedScrollPane(Component view, int preferredWidth, int extraHeight, MarkdownTheme theme) {
        this(view, preferredWidth, extraHeight, theme, HeightPolicy.CAPPED);
    }

    CappedScrollPane(Component view, int preferredWidth, int extraHeight, MarkdownTheme theme,
                     HeightPolicy heightPolicy) {
        super(view);
        this.heightPolicy = java.util.Objects.requireNonNull(heightPolicy, "heightPolicy");
        this.preferredWidth = preferredWidth;
        this.extraHeight = extraHeight;
        ComicScrollBarUI.install(getVerticalScrollBar(), theme.getMutedForeground(), theme.getForeground());
        ComicScrollBarUI.install(getHorizontalScrollBar(), theme.getMutedForeground(), theme.getForeground());
        setOpaque(false);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // Ab der Kappung scrollt der Inhalt vertikal, statt die restlichen Zeilen abzuschneiden.
        setVerticalScrollBarPolicy(heightPolicy == HeightPolicy.FULL_CONTENT
                ? VERTICAL_SCROLLBAR_NEVER : VERTICAL_SCROLLBAR_AS_NEEDED);
        // Hand the wheel to the chat once this block has nothing left to scroll in that direction.
        NestedScrollWheelRouter.install(this);
    }

    @Override
    public int preferredHeightForWidth(int width) {
        int height = contentHeight();
        if (needsHorizontalBar(width)) {
            height += getHorizontalScrollBar().getPreferredSize().height;
        }
        return height;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            width = getParent().getWidth();
        }
        return new Dimension(preferredWidth, preferredHeightForWidth(width));
    }

    /** Measure the content, header and padding before adding the horizontal scrollbar. */
    private int cappedContentHeight() {
        Component view = getViewport().getView();
        int content = view == null ? 0 : view.getPreferredSize().height;
        JViewport columnHeader = getColumnHeader();
        if (columnHeader != null && columnHeader.getView() != null) {
            content += columnHeader.getView().getPreferredSize().height;
        }
        Insets insets = getInsets();
        int naturalHeight = content + extraHeight;
        int height = heightPolicy == HeightPolicy.FULL_CONTENT ? naturalHeight : Math.min(MAX_CONTENT_HEIGHT, naturalHeight);
        return height + insets.top + insets.bottom;
    }

    /** Braucht der Inhalt mehr Breite, als der Ausschnitt bei {@code width} hat, erscheint der horizontale Balken. */
    private boolean needsHorizontalBar(int width) {
        Component view = getViewport().getView();
        if (view == null || width <= 0) {
            return false;
        }
        Insets insets = getInsets();
        return view.getPreferredSize().width > width - insets.left - insets.right;
    }
}
