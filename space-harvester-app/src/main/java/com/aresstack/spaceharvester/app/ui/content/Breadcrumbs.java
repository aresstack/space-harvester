package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Der Pfad einer Seite unter ihrem Titel (#177): Space mit Namen, dann die Vorfahren von oben nach unten; jeder Vorfahr
 * öffnet per Klick seine Seite ({@code onOpen}). Leer, solange die Lage unbekannt ist oder die Quelle keinen Seitenbaum
 * hat. Nur auf dem EDT.
 */
final class Breadcrumbs extends JPanel {

    static final String SEPARATOR = " › ";

    private Consumer<String> onOpen = pageId -> { };
    private final List<String> parts = new ArrayList<>();

    Breadcrumbs() {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setOpaque(false);
    }

    void onOpen(Consumer<String> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    void clear() {
        parts.clear();
        removeAll();
        setVisible(false);
        revalidate();
        repaint();
    }

    void show(PageNavigation.Position position) {
        clear();
        if (position == null) {
            return;
        }
        String space = position.spaceName() == null || position.spaceName().isBlank() ? position.spaceKey()
                : position.spaceName() + " (" + position.spaceKey() + ")";
        add(part(space, null));
        for (PageNode ancestor : position.ancestors()) {
            add(separator());
            add(part(title(ancestor), ancestor.pageId()));
        }
        setVisible(true);
        revalidate();
        repaint();
    }

    /** Der Pfad als Text, zum Beispiel für Tests: „Space (KEY) › Vorfahr › Vorfahr“. */
    String text() {
        return String.join(SEPARATOR, parts);
    }

    private JLabel part(String text, String pageId) {
        parts.add(text);
        JLabel label = Comic.muted(text);
        if (pageId != null) {
            label.setForeground(Comic.BLUE);
            label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            label.setToolTipText("Seite " + pageId + " öffnen");
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onOpen.accept(pageId);
                }
            });
        }
        return label;
    }

    private static JLabel separator() {
        return Comic.muted(SEPARATOR);
    }

    static String title(PageNode node) {
        return node.title() == null || node.title().isBlank() ? "Seite " + node.pageId() : node.title();
    }
}
