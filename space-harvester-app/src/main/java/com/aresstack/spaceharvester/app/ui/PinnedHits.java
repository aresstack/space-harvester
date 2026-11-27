package com.aresstack.spaceharvester.app.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Abgesetzter Bereich über den Suchtreffern (Angelo, 23.11.2026): oben eine kleine Überschrift („Seite zur Adresse“
 * bzw. „Titeltreffer“), darunter je Seite eine Zeile mit Titel und Ort. Ein Klick wählt die Zeile gelb und zeigt die
 * Seite im Baum, ein Doppelklick öffnet sie, wie bei den Treffern darunter. Ohne Seiten ausgeblendet. Nur auf dem EDT.
 */
final class PinnedHits extends JPanel {

    static final String ADDRESS = "Seite zur Adresse";
    static final String TITLES = "Titeltreffer";
    static final String TICKET = "Vorgang zum Schlüssel";
    static final String PAGE_ID = "Seite zur Nummer";

    private final JLabel caption = Comic.muted(" ");
    private final List<JLabel> rows = new ArrayList<>();
    private List<SearchView.Hit> hits = List.of();
    private int selected = -1;
    private Consumer<SearchView.Hit> onSelect = hit -> { };
    private Consumer<SearchView.Hit> onOpen = hit -> { };

    PinnedHits() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.LINE, 1, true),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        caption.setFont(Comic.semiBold(12f));
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        setVisible(false);
    }

    void onSelect(Consumer<SearchView.Hit> listener) {
        onSelect = Objects.requireNonNull(listener, "listener");
    }

    void onOpen(Consumer<SearchView.Hit> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    void clear() {
        show(TITLES, List.of());
    }

    /** Zeigt {@code pages} unter {@code title}; leer blendet den Bereich aus. */
    void show(String title, List<SearchView.Hit> pages) {
        hits = List.copyOf(pages);
        selected = -1;
        removeAll();
        rows.clear();
        caption.setText(title);
        add(caption);
        for (int i = 0; i < hits.size(); i++) {
            SearchView.Hit hit = hits.get(i);
            JLabel row = new JLabel(hit.title() + (hit.spaceKey() == null || hit.spaceKey().isBlank() ? ""
                    : "  ·  " + hit.spaceKey()));
            row.setFont(Comic.semiBold(14f));
            row.setForeground(Comic.INK);
            row.setBackground(Comic.YELLOW);
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            com.aresstack.spaceharvester.app.ui.controls.Cursors.set(row, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.setToolTipText("Klick zeigt die Seite im Baum, Doppelklick öffnet sie");
            int index = i;
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    select(index);
                    if (e.getClickCount() == 2) {
                        onOpen.accept(hits.get(index));
                    }
                }
            });
            rows.add(row);
            add(row);
        }
        setVisible(!hits.isEmpty());
        revalidate();
        repaint();
    }

    /** Wählt die Zeile {@code index} gelb und meldet ihre Seite. */
    void select(int index) {
        mark(index);
        onSelect.accept(hits.get(index));
    }

    void clearSelection() {
        mark(-1);
    }

    private void mark(int index) {
        selected = index;
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setOpaque(i == index);
            rows.get(i).repaint();
        }
    }

    // ---- für Tests

    List<SearchView.Hit> hits() {
        return hits;
    }

    String caption() {
        return caption.getText();
    }

    int selectedIndex() {
        return selected;
    }
}
