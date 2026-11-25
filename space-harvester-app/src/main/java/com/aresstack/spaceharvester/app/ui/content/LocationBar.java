package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Bereichsanzeige über der Seitenansicht: Wissensquelle › Space › Vorfahren der Seite, in einer Zeile. Jeder Teil
 * mit Ziel ist ein Link (Angelo, 16.11.2026): Er führt zur Seite bzw. markiert Quelle oder Space im Baum. Reicht der
 * Platz nicht, bleiben der erste Teil und so viele der letzten, wie passen; die Mitte wird wie in Confluence zu „…“,
 * dessen Klick die ausgelassenen Teile als Menü zeigt. Nur auf dem EDT.
 */
final class LocationBar extends JPanel {

    static final String SEPARATOR = " › ";
    static final String ELLIPSIS = "…";
    /** Höchstanteil der Zeile, damit Überschrift und Aktionen daneben Platz behalten. */
    static final double SHARE = 0.45;

    /** Ein Teil des Pfads; {@code action} {@code null}: nur Text. */
    record Part(String text, String tooltip, Runnable action) {
    }

    private List<Part> parts = List.of();
    private final List<JLabel> labels = new ArrayList<>();
    private final List<JLabel> separators = new ArrayList<>();
    private final JLabel ellipsis = label(ELLIPSIS, true);
    private final JLabel ellipsisSeparator = label(SEPARATOR, false);
    /** Die Teile, die das letzte Auslegen hinter „…“ gelegt hat. */
    private List<Part> hidden = List.of();

    LocationBar() {
        super(null);
        setOpaque(false);
        ellipsis.setToolTipText("Ausgelassene Ebenen zeigen");
        ellipsis.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                hiddenMenu().show(ellipsis, 0, ellipsis.getHeight());
            }
        });
    }

    void show(List<Part> path) {
        parts = List.copyOf(path);
        removeAll();
        labels.clear();
        separators.clear();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            JLabel label = label(part.text(), part.action() != null);
            label.setToolTipText(part.tooltip());
            if (part.action() != null) {
                label.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        part.action().run();
                    }
                });
            }
            labels.add(label);
            add(label);
            if (i > 0) {
                JLabel separator = label(SEPARATOR, false);
                separators.add(separator);
                add(separator);
            }
        }
        add(ellipsis);
        add(ellipsisSeparator);
        revalidate();
        repaint();
    }

    /** Der ganze Pfad als Text, auch die ausgelassenen Teile: „Quelle › Space › Vorfahr“. */
    String text() {
        return String.join(SEPARATOR, parts.stream().map(Part::text).toList());
    }

    List<Part> parts() {
        return parts;
    }

    /** Die Teile hinter „…“ nach dem letzten Auslegen, von oben nach unten. */
    List<Part> hidden() {
        return hidden;
    }

    /** Die sichtbaren Texte nach dem letzten Auslegen, mit „…“ für die ausgelassene Mitte. */
    List<String> visibleTexts() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).isVisible()) {
                texts.add(labels.get(i).getText());
            } else if (texts.isEmpty() || !ELLIPSIS.equals(texts.get(texts.size() - 1))) {
                texts.add(ELLIPSIS);
            }
        }
        return texts;
    }

    JPopupMenu hiddenMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (Part part : hidden) {
            JMenuItem item = new JMenuItem(part.text());
            item.setFont(Comic.regular(13f));
            item.setToolTipText(part.tooltip());
            item.setEnabled(part.action() != null);
            if (part.action() != null) {
                item.addActionListener(e -> part.action().run());
            }
            menu.add(item);
        }
        return menu;
    }

    private int fullWidth() {
        int width = 0;
        for (JLabel label : labels) {
            width += label.getPreferredSize().width;
        }
        for (JLabel separator : separators) {
            width += separator.getPreferredSize().width;
        }
        return width;
    }

    private int rowHeight() {
        return ellipsis.getPreferredSize().height;
    }

    @Override
    public Dimension getPreferredSize() {
        int available = getParent() == null ? 0 : getParent().getWidth();
        return preferredSizeForWidth(available, 0);
    }

    /** Preserve the header width share when a leading navigation control uses part of it. */
    Dimension preferredSizeForWidth(int available, int reserved) {
        int full = fullWidth();
        int budget = Math.max(0, (int) (available * SHARE) - reserved);
        return new Dimension(available <= 0 ? full : Math.min(full, budget), rowHeight());
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, rowHeight());
    }

    @Override
    public void doLayout() {
        int available = getWidth();
        int count = labels.size();
        int first = 0;
        // Wie viele Teile am Anfang (einer) und am Ende sichtbar bleiben; ohne Platznot alle.
        int tail = count;
        // Bei Platznot bekommt der erste Teil höchstens die Hälfte dessen, was neben „…“ bleibt, und kürzt sich
        // selbst; sonst drängte ein langer Quellenname „…“ und die letzte Ebene aus der Zeile.
        int firstMax = Integer.MAX_VALUE;
        if (fullWidth() > available && count > 1) {
            int dots = ellipsisSeparator.getPreferredSize().width + ellipsis.getPreferredSize().width;
            firstMax = Math.max(0, (available - dots) / 2);
            int fixed = Math.min(labels.get(0).getPreferredSize().width, firstMax) + dots;
            first = 1;
            tail = 0;
            int used = fixed;
            for (int i = count - 1; i >= 1; i--) {
                int width = labels.get(i).getPreferredSize().width + separators.get(i - 1).getPreferredSize().width;
                if (used + width > available && tail > 0) {
                    break;
                }
                if (used + width > available) {
                    // Nicht einmal der letzte Teil passt ganz: er bleibt und kürzt sich selbst mit „…“.
                    tail = 1;
                    break;
                }
                used += width;
                tail++;
            }
            if (tail == count - 1) {
                // Alles außer dem ersten passt doch (nur „…“ war zu viel): nichts auslassen.
                tail = count;
                first = 0;
            }
        }
        boolean collapsed = tail < count;
        int firstTail = collapsed ? count - tail : 0;
        List<Part> skipped = new ArrayList<>();
        int x = 0;
        int height = getHeight();
        for (int i = 0; i < count; i++) {
            boolean visible = !collapsed || i < first || i >= firstTail;
            JLabel label = labels.get(i);
            JLabel separator = i == 0 ? null : separators.get(i - 1);
            if (!visible) {
                label.setVisible(false);
                if (separator != null) {
                    separator.setVisible(false);
                }
                skipped.add(parts.get(i));
                continue;
            }
            if (collapsed && i == firstTail) {
                x = place(ellipsisSeparator, x, height, Integer.MAX_VALUE);
                x = place(ellipsis, x, height, Integer.MAX_VALUE);
            }
            if (separator != null) {
                separator.setVisible(true);
                x = place(separator, x, height, Integer.MAX_VALUE);
            }
            label.setVisible(true);
            x = place(label, x, height, i == 0 ? firstMax : Math.max(0, available - x));
        }
        ellipsis.setVisible(collapsed);
        ellipsisSeparator.setVisible(collapsed);
        hidden = List.copyOf(skipped);
    }

    private static int place(JLabel label, int x, int height, int maxWidth) {
        Dimension size = label.getPreferredSize();
        int width = Math.min(size.width, maxWidth);
        label.setBounds(x, (height - size.height) / 2, width, size.height);
        return x + width;
    }

    private static JLabel label(String text, boolean link) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.regular(13f));
        label.setForeground(link ? Comic.BLUE : Comic.MUTED);
        if (link) {
            Cursors.set(label, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        return label;
    }
}
