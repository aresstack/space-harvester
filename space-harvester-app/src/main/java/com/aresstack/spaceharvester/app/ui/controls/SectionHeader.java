package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Aufklappbare Überschrift einer Gruppe, etwa je Quellentyp über den Quellenkarten: Winkel (▸ zu, ▾ offen, gezeichnet
 * als {@link Glyph}), Titel und rechts gedämpft die Anzahl. Ein Klick auf die Zeile, Leertaste oder Enter klappt um;
 * was darunter steht, zeigt oder versteckt der Aufrufer. Im Stil der App, nicht das Standard-Swing-Aussehen.
 */
public class SectionHeader extends JPanel {

    private static final int HEIGHT = 26;

    private final JLabel chevron = new JLabel(Glyph.COLLAPSE);
    private final JLabel title = new JLabel();
    private final JLabel count = new JLabel();
    private final Runnable onToggle;
    private boolean open = true;

    /**
     * @param text     Titel der Gruppe
     * @param open     ob sie anfangs aufgeklappt ist
     * @param onToggle wird nach jedem Umklappen gerufen; {@link #isOpen()} sagt den neuen Zustand
     */
    public SectionHeader(String text, boolean open, Runnable onToggle) {
        super(new BorderLayout(6, 0));
        this.onToggle = onToggle;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        chevron.setForeground(Comic.MUTED);
        title.setText(text);
        title.setFont(Comic.semiBold(12.5f));
        title.setForeground(Comic.INK);
        count.setFont(Comic.regular(12f));
        count.setForeground(Comic.MUTED);
        add(chevron, BorderLayout.WEST);
        add(title, BorderLayout.CENTER);
        add(count, BorderLayout.EAST);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 2, 6));
        open(open);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                toggle();
            }
        });
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggle");
        getActionMap().put("toggle", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                toggle();
            }
        });
        getAccessibleContext().setAccessibleName(text);
    }

    /** Klappt um und meldet es. */
    public void toggle() {
        open(!open);
        if (onToggle != null) {
            onToggle.run();
        }
    }

    /** Setzt den Zustand, ohne zu melden. */
    public void open(boolean open) {
        this.open = open;
        chevron.setIcon(open ? Glyph.COLLAPSE : Glyph.EXPAND);
        setToolTipText(open ? "Zuklappen" : "Aufklappen");
        repaint();
    }

    public boolean isOpen() {
        return open;
    }

    /** Die Anzahl rechts; 0 blendet sie aus. */
    public void count(int value) {
        count.setText(value > 0 ? String.valueOf(value) : "");
    }

    public String title() {
        return title.getText();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, Math.max(HEIGHT, d.height));
    }
}
