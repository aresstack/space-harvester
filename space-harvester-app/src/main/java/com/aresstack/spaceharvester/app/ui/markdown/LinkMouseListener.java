package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyledDocument;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Öffnet Links aus dem StyledDocument, weil JTextPane-Hyperlink-Ereignisse ein HTML-Dokument voraussetzen. */
final class LinkMouseListener extends MouseAdapter {

    static final String HREF_ATTRIBUTE = "markdown.href";

    private final JTextPane pane;
    private final DesktopLinkOpener linkOpener;

    LinkMouseListener(JTextPane pane, DesktopLinkOpener linkOpener) {
        this.pane = pane;
        this.linkOpener = linkOpener;
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        pane.setCursor(findLink(event) == null
                ? Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public void mouseClicked(MouseEvent event) {
        // A click that ends a drag selection selects text; it never opens the link under it (issue #117).
        if (event.getButton() != MouseEvent.BUTTON1 || pane.getSelectionStart() != pane.getSelectionEnd()) {
            return;
        }
        String link = findLink(event);
        if (link != null) {
            linkOpener.open(link);
        }
    }

    private String findLink(MouseEvent event) {
        return linkAt(pane, event.getPoint());
    }

    /** Liefert den Link unter einem Punkt der Textfläche (in deren Koordinaten) oder {@code null}. */
    static String linkAt(JTextPane pane, Point point) {
        int position = pane.viewToModel(point);
        if (position < 0) {
            return null;
        }
        return hrefAt(pane.getStyledDocument(), position);
    }

    /**
     * Wie {@link #linkAt}, aber nur, wenn der Punkt tatsächlich auf einem Zeichen des Links liegt: in einer
     * Tabellenzelle bleibt rechts neben einem kurzen Link viel Leerraum, der kein Klickziel sein soll.
     */
    static String linkOnGlyphAt(JTextPane pane, Point point) {
        int position = pane.viewToModel(point);
        if (position < 0) {
            return null;
        }
        int length = pane.getDocument().getLength();
        for (int offset = Math.max(0, position - 1); offset <= Math.min(position, length - 1); offset++) {
            try {
                Rectangle start = pane.modelToView(offset);
                Rectangle end = pane.modelToView(offset + 1);
                if (start == null || end == null || end.y != start.y) {
                    continue;
                }
                if (point.x >= start.x && point.x <= end.x
                        && point.y >= start.y && point.y < start.y + start.height) {
                    return hrefAt(pane.getStyledDocument(), offset);
                }
            } catch (BadLocationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String hrefAt(StyledDocument document, int position) {
        Element element = document.getCharacterElement(position);
        AttributeSet attributes = element.getAttributes();
        Object value = attributes.getAttribute(HREF_ATTRIBUTE);
        return value == null ? null : String.valueOf(value);
    }
}
