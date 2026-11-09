package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Ein Eintrag der Einstellungs-Navigation im linken Drawer, übernommen aus den Zeilen der Einstellungen im AskAI-Client
 * ({@code ChatHistoryRow} in {@code aresstack/askai}): gerundet, in Ruhe durchsichtig, beim Darüberfahren ein sehr
 * helles Blau, gewählt das helle Blau mit dem 3-px-Akzent links. Einzeilig, ohne Menü und Zeitangabe.
 */
final class SettingsNavRow extends JComponent {

    private static final Color HOVER_WASH = ResearchUiPainter.mix(ResearchUiPalette.ACCENT_BLUE, Comic.SURFACE, 0.94f);
    private static final Color SELECTED_WASH =
            ResearchUiPainter.mix(ResearchUiPalette.ACCENT_BLUE, Comic.SURFACE, 0.88f);
    /** Einzeilig: niedriger als die zweizeiligen Chat-Zeilen. */
    static final int HEIGHT = 34;

    private final String title;
    private boolean selected;
    private boolean hovered;

    SettingsNavRow(String title, Runnable select) {
        this.title = title;
        setOpaque(false);
        setFocusable(true);
        setToolTipText(title);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
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
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    select.run();
                }
            }
        };
        addMouseListener(mouse);
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                repaint();
            }
        });
        addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                if (event.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER
                        || event.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
                    select.run();
                }
            }
        });
    }

    String title() {
        return title;
    }

    boolean selected() {
        return selected;
    }

    void setSelected(boolean selected) {
        if (this.selected != selected) {
            this.selected = selected;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            int radius = ResearchUiMetrics.CHAT_ROW_RADIUS;
            if (selected) {
                ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), radius, SELECTED_WASH);
                g2.setColor(ResearchUiPalette.ACCENT_BLUE);
                g2.fillRoundRect(0, 6, ResearchUiMetrics.CHAT_ROW_ACCENT_WIDTH, getHeight() - 12, 2, 2);
            } else if (hovered) {
                ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), radius, HOVER_WASH);
            }
            int textX = ResearchUiMetrics.CHAT_ROW_PADDING_H + 4;
            g2.setFont(selected ? ResearchUiTypography.semiBold(13f) : ResearchUiTypography.regular(13f));
            FontMetrics metrics = g2.getFontMetrics();
            g2.setColor(Comic.INK);
            g2.drawString(ellipsize(title, metrics, getWidth() - textX - ResearchUiMetrics.CHAT_ROW_PADDING_H), textX,
                    (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
            if (isFocusOwner()) {
                ResearchUiPainter.strokeRound(g2, 1, 1, getWidth() - 2, getHeight() - 2, radius,
                        ResearchUiPalette.ACCENT_BLUE);
            }
        } finally {
            g2.dispose();
        }
    }

    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth || text.isEmpty()) {
            return text;
        }
        String ellipsis = "…";
        int budget = maxWidth - metrics.stringWidth(ellipsis);
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > budget) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PAGE_TAB;
                }

                @Override
                public String getAccessibleName() {
                    return title;
                }
            };
        }
        return accessibleContext;
    }
}
