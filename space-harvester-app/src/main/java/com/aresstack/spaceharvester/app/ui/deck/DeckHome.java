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
        super(new GridBagLayout());
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

        JPanel grid = new JPanel(new GridLayout(0, 3, 12, 12));
        grid.setOpaque(false);
        for (Tile tile : this.tiles) {
            JComponent card = tile(tile, open);
            cards.add(card);
            grid.add(card);
        }
        column.add(grid, BorderLayout.CENTER);

        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(24, 24, 24, 24);
        add(column, at);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refresh();
            }
        });
        refresh();
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
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
