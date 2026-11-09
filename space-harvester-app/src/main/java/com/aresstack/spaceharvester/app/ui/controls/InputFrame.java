package com.aresstack.spaceharvester.app.ui.controls;


import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;

/**
 * Eingabefeld mit integrierten Knöpfen. Aufbau und Verhalten (Lupe links, flache Knöpfe rechts im Feld, ✕ erscheint
 * bei Text) stammen aus AskAIs {@code ComicSearchBar}/{@code ComicFindBar}; das Aussehen ist das der übrigen
 * Space-Harvester-Felder ({@code Comic.style}), um ein beliebiges vorhandenes Feld gelegt. Knöpfe, die zu einem Feld gehören
 * (Suchoptionen ▾, Löschen ✕, Modelle laden, Durchsuchen, Auge), sitzen so rechts IM Feld statt daneben.
 *
 * <pre>
 *   +----------------------------------------------+
 *   | [west]  Feld …                 [k1] [k2] … |
 *   +----------------------------------------------+
 * </pre>
 *
 * Das Feld behält Modell, Listener und Tastenbelegung; nur sein eigener Rahmen und Hintergrund entfallen, der
 * Rahmen zeichnet beides. Die Mitte liegt in {@link BorderLayout#CENTER}, damit vorhandener Code das Feld
 * weiterhin dort findet.
 */
public class InputFrame extends JPanel {

    private static final Color ICON = new Color(0x666666);

    private final JComponent field;
    private final JPanel eastPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private boolean focused;

    public InputFrame(JComponent field) {
        this(field, null);
    }

    /** {@code west}: optional ein Symbol links im Feld (etwa die Lupe); {@code null} für keins. */
    public InputFrame(JComponent field, Component west) {
        super(new BorderLayout(0, 0));
        this.field = field;
        // Aussehen wie die übrigen Felder der App (Comic.style): weiß, schlichter abgerundeter Rahmen; nur
        // Verhalten und Aufbau kommen aus AskAI, nicht dessen Stil.
        setBorder(com.aresstack.spaceharvester.app.ui.Comic.fieldBorder());
        setBackground(Color.WHITE);
        setOpaque(true);
        if (field instanceof JTextComponent text) {
            text.setBorder(new EmptyBorder(2, 4, 2, 4));
            text.setOpaque(false);
        }
        eastPanel.setOpaque(false);
        if (west != null) {
            add(west, BorderLayout.WEST);
        }
        add(field, BorderLayout.CENTER);
        add(eastPanel, BorderLayout.EAST);
        FocusAdapter repaintOnFocus = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                updateFocus();
            }

            @Override
            public void focusLost(FocusEvent e) {
                updateFocus();
            }
        };
        field.addFocusListener(repaintOnFocus);
        // Klick auf den Rahmen (nicht auf einen Knopf) setzt den Fokus ins Feld, wie bei ComicSearchBar.
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                field.requestFocusInWindow();
            }
        });
        PropertyChangeListener enabled = e -> repaint();
        field.addPropertyChangeListener("enabled", enabled);
    }

    /** Ein flacher Knopf im Stil des „go“-Knopfs von {@link ComicSearchBar}, für {@link #addButton}. */
    public static JButton flatButton(String text, String tooltip) {
        JButton button = new JButton(text);
        styleFlat(button);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        return button;
    }

    /** Macht einen vorhandenen Knopf flach, damit er im Feld nicht als eigener Kasten erscheint. */
    public static void styleFlat(AbstractButton button) {
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setForeground(ICON);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Hängt einen Knopf (oder eine Anzeige, etwa „2 / 5“) rechts ins Feld; Reihenfolge = Aufrufreihenfolge. */
    public <T extends Component> T addButton(T component) {
        eastPanel.add(component);
        if (component instanceof JComponent c) {
            c.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    updateFocus();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    updateFocus();
                }
            });
        }
        revalidate();
        return component;
    }

    /**
     * Der Löschen-Knopf ✕ aus AskAIs {@code ComicFindBar}: erscheint, sobald Text im Feld steht, leert es und
     * setzt den Fokus zurück ins Feld. Nur für Textfelder; liefert den Knopf (etwa für Tests).
     */
    public JButton addClearButton() {
        JTextComponent text = (JTextComponent) field;
        JButton clear = flatButton("\u2715", "Eingabe löschen");
        clear.setFocusable(false);
        clear.addActionListener(e -> {
            text.setText("");
            text.requestFocusInWindow();
        });
        Runnable sync = () -> {
            boolean show = !text.getText().isEmpty();
            if (clear.isVisible() != show) {
                clear.setVisible(show);
                revalidate();
                repaint();
            }
        };
        text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                sync.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                sync.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                sync.run();
            }
        });
        clear.setVisible(!text.getText().isEmpty());
        addButton(clear);
        return clear;
    }

    /** Suchfeld wie in AskAI: Lupe links, ✕ rechts im Feld, sobald Text steht. */
    public static InputFrame search(JTextComponent field) {
        InputFrame frame = new InputFrame(field, magnifier());
        frame.addClearButton();
        return frame;
    }

    public JComponent field() {
        return field;
    }

    /** Die Lupe aus {@link ComicSearchBar} als Symbol links im Feld. */
    public static JComponent magnifier() {
        JPanel icon = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ICON);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2 - 1;
                int cy = getHeight() / 2 - 1;
                int r = 5;
                g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
                int hx = cx + (int) (r * 0.7);
                int hy = cy + (int) (r * 0.7);
                g2.drawLine(hx, hy, hx + 4, hy + 4);
                g2.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(24, 20);
            }
        };
        icon.setOpaque(false);
        return icon;
    }

    private void updateFocus() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean now = owner != null && (owner == field || javax.swing.SwingUtilities.isDescendingFrom(owner, this));
        if (now != focused) {
            focused = now;
            setBorder(com.aresstack.spaceharvester.app.ui.Comic.fieldBorder());
            repaint();
        }
    }

    boolean isFrameFocused() {
        return focused;
    }

}
