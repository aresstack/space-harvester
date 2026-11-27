package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * Überschriftsbereich einer Ansicht wie die Zeile über dem Lesebereich: gleiche Höhe, feine Linie darunter, links der
 * Titel, rechtsbündig Symbolknöpfe (etwa Wiederholen). Ein Klick auf die Zeile wählt sie aus, gelb hinterlegt wie die
 * Überschrift „Wissensquellen“ im Explorer, und meldet das ({@link #onSelect}); {@link #selected(boolean) false} hebt
 * die Markierung wieder auf, etwa wenn darunter ein Eintrag gewählt wird (Angelo, 23.11.2026). Nur auf dem EDT.
 */
public final class SelectableHeading extends HeadBar {

    private final JLabel title = new JLabel(" ");
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    private boolean selected;
    private Runnable onSelect = () -> { };

    public SelectableHeading(String text) {
        // Dieselbe Kopfleiste wie über dem Lesebereich und den Seitenleisten (HeadBar).
        super(new BorderLayout(12, 0), 12, 12);
        setBackground(Comic.YELLOW);
        title.setFont(Comic.semiBold(15f));
        title.setForeground(Comic.INK);
        title(text);
        add(title, BorderLayout.CENTER);
        tools.setOpaque(false);
        add(tools, BorderLayout.EAST);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                select();
            }
        };
        addMouseListener(click);
        title.addMouseListener(click);
    }

    /** Ändert den Titel; der volle Wortlaut steht auch im Tooltip, falls die Zeile ihn kürzt. */
    public void title(String text) {
        String shown = text == null || text.isBlank() ? " " : text;
        title.setText(shown);
        title.setToolTipText(shown.isBlank() ? null : shown);
    }

    public String titleText() {
        return title.getText();
    }

    /** Place the view-specific navigation directly before the title without changing the title selection. */
    public void leading(javax.swing.JComponent component) {
        java.awt.Component previous = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.WEST);
        if (previous != null) {
            remove(previous);
        }
        if (component != null) {
            add(component, BorderLayout.WEST);
        }
        revalidate();
        repaint();
    }

    /** Ein Symbolknopf rechtsbündig in der Zeile. */
    public IconButton tool(Glyph glyph, String tooltip, Runnable action) {
        IconButton button = new IconButton(glyph, tooltip);
        button.addActionListener(e -> action.run());
        tools.add(button);
        return button;
    }

    /** Wird nach einem Klick auf die Zeile gerufen (nicht bei einem Klick auf einen Symbolknopf). */
    public void onSelect(Runnable listener) {
        onSelect = Objects.requireNonNull(listener, "listener");
    }

    /** Wählt die Zeile aus, wie ein Klick. */
    public void select() {
        selected(true);
        onSelect.run();
    }

    public boolean selected() {
        return selected;
    }

    /** Setzt oder hebt die gelbe Markierung auf, ohne {@link #onSelect} zu rufen. */
    public void selected(boolean on) {
        if (selected == on) {
            return;
        }
        selected = on;
        setOpaque(on);
        repaint();
    }
}
