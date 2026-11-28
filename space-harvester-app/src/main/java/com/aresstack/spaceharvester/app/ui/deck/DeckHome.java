package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

/**
 * Die Startseite des Command Deck (Angelo, 23.11.2026): Beim Wechsel ins Deck ist sie gewählt, damit nicht sofort eine
 * Verbindung aufgebaut wird. Sie zeigt je Bereich eine Kachel mit dem Stand (verbunden oder nicht, gemerkter
 * KeePass-Eintrag, Anmeldesperre) und verbindet selbst nichts. Ein Klick auf eine Kachel wechselt in den Bereich, wie
 * das Icon oben.
 *
 * <p>Der Stand wird beim Einblenden gelesen; solange die Seite zu sehen ist, ändert er sich nur durch Sitzungen, die
 * hier nicht entstehen (kein Abfragen).
 */
final class DeckHome extends JPanel {

    /**
     * Eine Kachel.
     *
     * @param area   der Bereich, in den ein Klick wechselt
     * @param status Zeilen zum Stand, beim Einblenden gelesen
     */
    record Tile(DeckArea area, Supplier<List<String>> status) {
        Tile {
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(status, "status");
        }
    }

    private final List<Tile> tiles;
    private final List<JLabel> statusLabels = new ArrayList<>();
    private final List<JComponent> cards = new ArrayList<>();

    DeckHome(List<Tile> tiles, Consumer<DeckArea> open) {
        super(new BorderLayout());
        this.tiles = List.copyOf(tiles);
        Objects.requireNonNull(open, "open");
        setBackground(Comic.SURFACE);
        getAccessibleContext().setAccessibleName(DeckArea.HOME.title());

        JPanel column = new JPanel(new BorderLayout(0, 16));
        column.setOpaque(false);
        JLabel title = new JLabel("Command Deck", SwingConstants.LEFT);
        title.setFont(Comic.semiBold(20f));
        title.setForeground(Comic.INK);
        JLabel hint = new JLabel("Wähle einen Bereich. Verbunden wird erst, wenn du ihn öffnest.");
        hint.setFont(Comic.regular(13f));
        hint.setForeground(Comic.MUTED);
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(hint, BorderLayout.CENTER);
        column.add(head, BorderLayout.NORTH);

        GridLayout flow = new GridLayout(0, MAX_COLUMNS, 12, 12);
        JPanel grid = new JPanel(flow);
        grid.setOpaque(false);
        for (Tile tile : this.tiles) {
            JComponent card = tile(tile, open);
            cards.add(card);
            grid.add(card);
        }
        column.add(grid, BorderLayout.CENTER);

        // Schmales Fenster oder breiter Chat: weniger Spalten, und notfalls rollen statt abschneiden.
        JPanel centred = new JPanel(new GridBagLayout());
        centred.setBackground(Comic.SURFACE);
        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(24, 24, 24, 24);
        centred.add(column, at);
        javax.swing.JScrollPane scroll = Comic.comicBars(new javax.swing.JScrollPane(centred,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Comic.SURFACE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int columns = columnsFor(scroll.getViewport().getWidth(), cards.isEmpty() ? 0
                        : cards.get(0).getPreferredSize().width);
                if (columns != flow.getColumns()) {
                    flow.setColumns(columns);
                    grid.revalidate();
                }
            }
        });
        add(scroll, BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refresh();
            }
        });
        refresh();
    }

    static final int MAX_COLUMNS = 3;

    /** Wie viele Kacheln nebeneinander passen (1 … 3), bei {@code width} Platz und Kacheln von {@code tileWidth}. */
    static int columnsFor(int width, int tileWidth) {
        if (tileWidth <= 0) {
            return MAX_COLUMNS;
        }
        int room = width - 48 + 12; // Rand links und rechts, ein Abstand weniger als Kacheln
        return Math.max(1, Math.min(MAX_COLUMNS, room / (tileWidth + 12)));
    }

    /** Spalten des Kachelrasters (für Tests). */
    int columns() {
        return ((GridLayout) cards.get(0).getParent().getLayout()).getColumns();
    }

    /** Liest den Stand jeder Kachel neu. */
    void refresh() {
        for (int i = 0; i < tiles.size(); i++) {
            List<String> lines = tiles.get(i).status().get();
            StringBuilder html = new StringBuilder("<html>");
            for (int line = 0; line < lines.size(); line++) {
                if (line > 0) {
                    html.append("<br>");
                }
                html.append(escape(lines.get(line)));
            }
            statusLabels.get(i).setText(html.append("</html>").toString());
            statusLabels.get(i).getAccessibleContext().setAccessibleDescription(String.join(", ", lines));
        }
    }

    /** Der Stand einer Kachel als Text (für Tests). */
    String statusOf(DeckArea area) {
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).area() == area) {
                return String.join("\n", tiles.get(i).status().get());
            }
        }
        return "";
    }

    /** Die Kachel eines Bereichs (für Tests). */
    JComponent tileOf(DeckArea area) {
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).area() == area) {
                return cards.get(i);
            }
        }
        throw new IllegalArgumentException(area.name());
    }

    private JComponent tile(Tile tile, Consumer<DeckArea> open) {
        JPanel card = new JPanel(new BorderLayout(10, 4));
        card.setBackground(Comic.SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.MUTED, 1, true),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        com.aresstack.spaceharvester.app.ui.controls.Cursors.set(card, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.setFocusable(true);
        card.setToolTipText(tile.area().title() + " öffnen");
        card.getAccessibleContext().setAccessibleName(tile.area().title());

        JLabel name = new JLabel(tile.area().title(), tile.area().icon(), SwingConstants.LEFT);
        name.setFont(Comic.semiBold(15f));
        name.setForeground(Comic.INK);
        name.setIconTextGap(8);
        // Feste Breite, damit lange Beschreibungen umbrechen statt abgeschnitten zu werden.
        JLabel what = new JLabel("<html><body style='width:220px'>" + escape(tile.area().hint()) + "</body></html>");
        what.setFont(Comic.regular(12f));
        what.setForeground(Comic.MUTED);
        JLabel status = new JLabel();
        status.setFont(Comic.regular(12f));
        status.setForeground(Comic.INK);
        statusLabels.add(status);

        JPanel text = new JPanel(new BorderLayout(0, 6));
        text.setOpaque(false);
        text.add(what, BorderLayout.NORTH);
        text.add(status, BorderLayout.CENTER);
        card.add(name, BorderLayout.NORTH);
        card.add(text, BorderLayout.CENTER);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                open.accept(tile.area());
            }
        });
        card.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        card.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "open");
        card.getActionMap().put("open", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                open.accept(tile.area());
            }
        });
        com.aresstack.spaceharvester.app.ui.controls.KeyboardFocusRing.install(card);
        return card;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
