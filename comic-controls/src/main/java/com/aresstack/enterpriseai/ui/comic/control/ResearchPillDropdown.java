package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The custom pill dropdown of the research-UI design study — deliberately NOT a {@link
 * javax.swing.JComboBox}: the pill and its popup are painted with Java2D (rounded surface, chevron,
 * dark popup rows) so the study's look survives every LaF. Selection only NOTIFIES the listener;
 * the pill's displayed value follows {@link #setSelectedIndex} — the owner decides whether a
 * selection is actually adopted (e.g. after a state-machine transition was accepted).
 */
public class ResearchPillDropdown extends JComponent {

    /** One popup row; disabled rows render muted and do not react to clicks. */
    public static final class Item {
        final String text;
        /** The REDUCED pill label for compact mode (or null = use {@link #text}); the popup
         *  always shows the full {@link #text} — the map's key/value contract. */
        final String shortText;
        final Icon icon;
        final boolean enabled;
        final String tooltip;

        public Item(String text, Icon icon, boolean enabled, String tooltip) {
            this(text, null, icon, enabled, tooltip);
        }

        public Item(String text, String shortText, Icon icon, boolean enabled, String tooltip) {
            this.text = text;
            this.shortText = shortText;
            this.icon = icon;
            this.enabled = enabled;
            this.tooltip = tooltip;
        }
    }

    public interface SelectionListener {
        void itemSelected(int index);
    }

    private final int fixedHeight;
    private final int radius;
    private final int minWidth;
    private final int paddingLeft;
    private final int paddingRight;
    private Color normalFill = ResearchUiPalette.SECONDARY_SURFACE;
    private Color hoverFill = ResearchUiPalette.SECONDARY_HOVER;
    private Color openFill = ResearchUiPalette.SECONDARY_HOVER;
    private Color foreground = ResearchUiPalette.TEXT_PRIMARY;

    private List<Item> items = Collections.emptyList();
    private int selectedIndex = -1;
    private SelectionListener listener;
    private boolean hovered;
    private JPopupMenu popup;
    /** Painted INSIDE the pill, before the selected item's own icon/text (e.g. the flask glyph). */
    private Icon leadingIcon;

    public ResearchPillDropdown(int fixedHeight, int radius, int minWidth,
                                int paddingLeft, int paddingRight) {
        this.fixedHeight = fixedHeight;
        this.radius = radius;
        this.minWidth = minWidth;
        this.paddingLeft = paddingLeft;
        this.paddingRight = paddingRight;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installKeyboardSupport();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hovered = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                togglePopup();
            }
        });
    }

    public void setFills(Color normal, Color hover, Color open) {
        this.normalFill = normal;
        this.hoverFill = hover;
        this.openFill = open;
        repaint();
    }

    public void setPillForeground(Color color) {
        this.foreground = color;
        repaint();
    }

    /** A glyph that is part of the pill itself (left of the value), or {@code null} for none. */
    public void setLeadingIcon(Icon icon) {
        this.leadingIcon = icon;
        revalidate();
        repaint();
    }

    private boolean compact;

    /**
     * COMPACT mode: the pill shows each item's short label and drops its minimum width, so it
     * stops dominating whatever unfolds beside it. The POPUP keeps the full texts either way.
     */
    public void setCompact(boolean compact) {
        if (this.compact != compact) {
            this.compact = compact;
            revalidate();
            repaint();
        }
    }

    private String pillTextOf(Item item) {
        return compact && item.shortText != null && !item.shortText.isEmpty()
                ? item.shortText : item.text;
    }

    public void setSelectionListener(SelectionListener listener) {
        this.listener = listener;
    }

    public void setItems(List<Item> items) {
        this.items = items == null ? Collections.<Item>emptyList()
                : new ArrayList<Item>(items);
        revalidate();
        repaint();
    }

    /** Show this item's text/icon on the pill (no listener callback — display only). */
    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        revalidate();
        repaint();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /** Programmatic selection that behaves like a popup click: notifies the listener (enabled rows only). */
    public void select(int index) {
        if (listener != null && index >= 0 && index < items.size() && items.get(index).enabled) {
            listener.itemSelected(index);
        }
    }

    /** Keyboard: the next enabled item in {@code step} direction (no wrap-around); nothing when none follows. */
    public void selectRelative(int step) {
        for (int index = selectedIndex + step; index >= 0 && index < items.size(); index += step) {
            if (items.get(index).enabled) {
                select(index);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ keyboard and accessibility

    /**
     * Reachable with Tab (a mouse click leaves the focus where it is): Space/Enter open and close the popup,
     * Escape closes it, Up/Left and Down/Right change the value directly like a combo box.
     */
    private void installKeyboardSupport() {
        setFocusable(true);
        setRequestFocusEnabled(false);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                repaint();
            }
        });
        InputMap input = getInputMap(WHEN_FOCUSED);
        ActionMap actions = getActionMap();
        bind(input, actions, "togglePopup", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                togglePopup();
            }
        }, KeyEvent.VK_SPACE, KeyEvent.VK_ENTER);
        bind(input, actions, "closePopup", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (popup != null && popup.isVisible()) {
                    popup.setVisible(false);
                }
            }
        }, KeyEvent.VK_ESCAPE);
        bind(input, actions, "selectPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                selectRelative(-1);
            }
        }, KeyEvent.VK_UP, KeyEvent.VK_LEFT);
        bind(input, actions, "selectNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                selectRelative(1);
            }
        }, KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT);
    }

    private static void bind(InputMap input, ActionMap actions, String name, AbstractAction action,
                             int... keyCodes) {
        actions.put(name, action);
        for (int keyCode : keyCodes) {
            input.put(KeyStroke.getKeyStroke(keyCode, 0), name);
        }
    }

    /** Assistive technology sees a combo box whose name is the current value. */
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.COMBO_BOX;
                }

                @Override
                public String getAccessibleName() {
                    String name = super.getAccessibleName();
                    if (name != null) {
                        return name;
                    }
                    Item item = selectedItem();
                    return item == null ? null : item.text;
                }
            };
        }
        return accessibleContext;
    }

    private Item selectedItem() {
        return selectedIndex >= 0 && selectedIndex < items.size() ? items.get(selectedIndex) : null;
    }

    // ------------------------------------------------------------------ pill painting

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            boolean open = popup != null && popup.isVisible();
            Color fill = open ? openFill : hovered ? hoverFill : normalFill;
            ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), radius, fill);
            if (isFocusOwner()) {
                ResearchUiPainter.strokeRound(g2, 1, 1, getWidth() - 2, getHeight() - 2, radius - 1, foreground);
            }

            int x = paddingLeft;
            if (leadingIcon != null) {
                int iconY = (getHeight() - leadingIcon.getIconHeight()) / 2;
                leadingIcon.paintIcon(this, g2, x, iconY);
                x += leadingIcon.getIconWidth() + 8;
            }
            Item item = selectedItem();
            g2.setFont(getFont());
            FontMetrics metrics = g2.getFontMetrics();
            if (item != null) {
                if (item.icon != null) {
                    int iconY = (getHeight() - item.icon.getIconHeight()) / 2;
                    item.icon.paintIcon(this, g2, x, iconY);
                    x += item.icon.getIconWidth() + 8;
                }
                g2.setColor(foreground);
                int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(pillTextOf(item), x, textY);
            }
            // The chevron sits ~12px from the right edge (design study).
            ResearchUiPainter.paintChevronDown(g2, getWidth() - paddingRight - 3,
                    getHeight() / 2, 4, foreground);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        Item item = selectedItem();
        int width = paddingLeft + paddingRight + 8 + 9; // chevron zone
        if (leadingIcon != null) {
            width += leadingIcon.getIconWidth() + 8;
        }
        if (item != null) {
            if (item.icon != null) {
                width += item.icon.getIconWidth() + 8;
            }
            width += metrics.stringWidth(pillTextOf(item));
        }
        // Compact drops the minimum width too — the whole point is to stop dominating.
        return new Dimension(compact ? width : Math.max(minWidth, width), fixedHeight);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    // ------------------------------------------------------------------ popup

    private void togglePopup() {
        if (popup != null && popup.isVisible()) {
            popup.setVisible(false);
            return;
        }
        if (items.isEmpty()) {
            return;
        }
        popup = new JPopupMenu();
        popup.setOpaque(true);
        popup.setBackground(ResearchUiPalette.DARK_SURFACE);
        popup.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ResearchUiPalette.BORDER_WINDOW),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        popup.setLayout(new javax.swing.BoxLayout(popup, javax.swing.BoxLayout.Y_AXIS));
        for (int index = 0; index < items.size(); index++) {
            popup.add(new PopupRow(index));
        }
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                repaint(); // leave the "open" fill
            }

            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        popup.show(this, 0, getHeight() + 2);
        repaint();
    }

    /** One custom-painted popup row: rounded hover surface, icon + text, muted when disabled. */
    private final class PopupRow extends JComponent {

        private final int index;
        private boolean rowHovered;

        PopupRow(final int index) {
            this.index = index;
            Item item = items.get(index);
            setToolTipText(item.tooltip);
            if (item.enabled) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent event) {
                        rowHovered = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent event) {
                        rowHovered = false;
                        repaint();
                    }

                    @Override
                    public void mousePressed(MouseEvent event) {
                        popup.setVisible(false);
                        if (listener != null) {
                            listener.itemSelected(index);
                        }
                    }
                });
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                Item item = items.get(index);
                if (rowHovered && item.enabled) {
                    ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), 8,
                            ResearchUiPalette.SECONDARY_HOVER);
                }
                int x = 10;
                if (item.icon != null) {
                    int iconY = (getHeight() - item.icon.getIconHeight()) / 2;
                    item.icon.paintIcon(this, g2, x, iconY);
                    x += item.icon.getIconWidth() + 8;
                }
                g2.setFont(getFont() != null ? getFont() : ResearchPillDropdown.this.getFont());
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(item.enabled
                        ? (index == selectedIndex
                                ? foreground : ResearchUiPalette.TEXT_PRIMARY)
                        : ResearchUiPalette.TEXT_MUTED);
                int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(item.text, x, textY);
                if (index == selectedIndex) {
                    // A quiet marker in front of the current value keeps orientation without a checkbox look.
                    g2.fillOval(2, getHeight() / 2 - 2, 4, 4);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Item item = items.get(index);
            FontMetrics metrics = getFontMetrics(
                    getFont() != null ? getFont() : ResearchPillDropdown.this.getFont());
            int width = 10 + 10 + metrics.stringWidth(item.text)
                    + (item.icon != null ? item.icon.getIconWidth() + 8 : 0);
            return new Dimension(Math.max(width, ResearchPillDropdown.this.getWidth()), 30);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 30);
        }
    }
}
