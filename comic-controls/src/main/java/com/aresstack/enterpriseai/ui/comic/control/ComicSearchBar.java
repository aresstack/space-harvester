package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * The polished search bar (ported from AskAI's comic-controls, originally MainframeMate's
 * {@code SearchBarPanel}): rounded container, magnifying-glass icon, placeholder text, flat "go"
 * button and a focus highlight. The DEFAULT look is the design language's NAVIGATION role — calm
 * neutral surface, blue border while focused. Subclasses may restyle it through the protected
 * constructor (AskAI's amber find bar and search tag do exactly that).
 *
 * <pre>
 *   +----------------------------------------------+
 *   |  (magnifier)  placeholder...    [▶] [extras] |
 *   +----------------------------------------------+
 * </pre>
 */
public class ComicSearchBar extends JPanel {

    private final Color normalBackground;
    private final Color focusedBackground;
    private final Color normalBorder;
    private final Color focusedBorder;
    private final Color iconColor;
    private final Color placeholderColor;
    private final int arc;
    private final float borderWidth;

    private final JTextField textField;
    private final JButton goButton;
    private final JPanel eastPanel;
    private boolean fieldFocused;

    /** The navigation-blue default bar. */
    public ComicSearchBar(String placeholder, String tooltip) {
        this(placeholder, tooltip, ComicPalette.defaultPalette());
    }

    public ComicSearchBar(String placeholder, String tooltip, ComicPalette palette) {
        this(placeholder, tooltip,
                palette.getSurface(), Color.WHITE,
                new Color(0xBBBBBB), palette.getNavigationBlue(),
                new Color(0x888888), new Color(0xAAAAAA), 10, 1.5f);
    }

    /** The full styling seam for the variants (amber find bar, amber-rimmed search tag). */
    protected ComicSearchBar(String placeholder, String tooltip,
                             Color normalBackground, Color focusedBackground,
                             Color normalBorder, Color focusedBorder,
                             Color iconColor, Color placeholderColor, int arc, float borderWidth) {
        this.normalBackground = normalBackground;
        this.focusedBackground = focusedBackground;
        this.normalBorder = normalBorder;
        this.focusedBorder = focusedBorder;
        this.iconColor = iconColor;
        this.placeholderColor = placeholderColor;
        this.arc = arc;
        this.borderWidth = borderWidth;
        setOpaque(false);
        setLayout(new BorderLayout(0, 0));

        final String placeholderText = placeholder;
        textField = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && placeholderText != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(ComicSearchBar.this.placeholderColor);
                    g2.setFont(getFont().deriveFont(Font.PLAIN));
                    FontMetrics fm = g2.getFontMetrics();
                    int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(placeholderText, getInsets().left, y);
                    g2.dispose();
                }
            }
        };
        textField.setOpaque(false);
        textField.setBorder(new EmptyBorder(2, 4, 2, 4));
        textField.setFont(textField.getFont().deriveFont(12.5f));
        if (tooltip != null) {
            textField.setToolTipText(tooltip);
        }

        goButton = new JButton("▶") {
            @Override
            public Dimension getPreferredSize() {
                int h = textField.getPreferredSize().height;
                int side = Math.max(h, 20);
                return new Dimension(side, side);
            }
        };
        goButton.setFont(goButton.getFont().deriveFont(11f));
        goButton.setMargin(new Insets(0, 0, 0, 0));
        goButton.setFocusable(false);
        goButton.setBorderPainted(false);
        goButton.setContentAreaFilled(false);
        goButton.setOpaque(false);
        goButton.setForeground(iconColor);
        goButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        goButton.setToolTipText("Start search");

        JPanel iconPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintMagnifyingGlass((Graphics2D) g, getWidth(), getHeight(),
                        ComicSearchBar.this.iconColor);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(24, 20);
            }
        };
        iconPanel.setOpaque(false);

        eastPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        eastPanel.setOpaque(false);
        eastPanel.add(goButton);

        add(iconPanel, BorderLayout.WEST);
        add(textField, BorderLayout.CENTER);
        add(eastPanel, BorderLayout.EAST);

        textField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                fieldFocused = true;
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                fieldFocused = false;
                repaint();
            }
        });

        // Click anywhere on the bar → focus the text field.
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                textField.requestFocusInWindow();
            }
        });
    }

    public ComicSearchBar(String placeholder) {
        this(placeholder, null);
    }

    /**
     * THE search bar's natural height — the shared metric other slim bars align to (e.g. the
     * research sky's collapsed status bar), so a later theme/font change keeps them in sync
     * automatically. Computed from a reference bar's preferred size, never hardcoded (no static
     * cache: global mutable state is forbidden here; callers keep the value per instance).
     */
    public static int standardHeight() {
        return new ComicSearchBar("", null).getPreferredSize().height;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int inset = 1;
        RoundRectangle2D bg = new RoundRectangle2D.Float(
                inset, inset, getWidth() - 2 * inset, getHeight() - 2 * inset, arc, arc);
        g2.setColor(fieldFocused ? focusedBackground : normalBackground);
        g2.fill(bg);
        g2.setColor(fieldFocused ? focusedBorder : normalBorder);
        g2.setStroke(new BasicStroke(fieldFocused ? borderWidth + 0.5f : borderWidth));
        g2.draw(bg);
        g2.dispose();
    }

    @Override
    public Insets getInsets() {
        return new Insets(3, 4, 3, 3);
    }

    protected static void paintMagnifyingGlass(Graphics2D g2, int w, int h, Color color) {
        g2 = (Graphics2D) g2.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = w / 2 - 1;
        int cy = h / 2 - 1;
        int r = 5;
        g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        int hx = cx + (int) (r * 0.7);
        int hy = cy + (int) (r * 0.7);
        g2.drawLine(hx, hy, hx + 4, hy + 4);
        g2.dispose();
    }

    /** Register an action triggered on Enter key AND go-button click. */
    public void addSearchAction(ActionListener listener) {
        textField.addActionListener(listener);
        goButton.addActionListener(listener);
    }

    /** The current search text (not trimmed). */
    public String getText() {
        return textField.getText();
    }

    public void setText(String text) {
        textField.setText(text);
    }

    /** The inner text field for custom key listeners, etc. */
    public JTextField getTextField() {
        return textField;
    }

    public void focusField() {
        textField.requestFocusInWindow();
    }

    public void focusAndSelectAll() {
        textField.requestFocusInWindow();
        textField.selectAll();
    }

    /** Add a component right of the go button (toggles, pagination, …). */
    public void addEastComponent(java.awt.Component component) {
        eastPanel.add(component);
    }

    protected JButton getGoButton() {
        return goButton;
    }

    protected JPanel getEastPanel() {
        return eastPanel;
    }

    protected boolean isFieldFocused() {
        return fieldFocused;
    }
}
