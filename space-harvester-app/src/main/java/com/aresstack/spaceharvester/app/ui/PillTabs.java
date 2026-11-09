package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Reiter als Pillen wie die Kategorien der Client-Einstellungen; der aktive Reiter ist dunkel gefüllt. */
public final class PillTabs extends JPanel {

    /** Umbrechend: In einer schmalen Reiterzeile (Aktionen rechts, Explorer offen) bleiben alle Pillen sichtbar. */
    private final JPanel bar = new JPanel(new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel row = new JPanel(new BorderLayout(8, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final ButtonGroup group = new ButtonGroup();
    private final Map<String, ComposerToggleButton> buttons = new LinkedHashMap<>();
    private Consumer<String> listener = name -> { };

    public PillTabs() {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        bar.setOpaque(false);
        deck.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        row.setOpaque(false);
        actions.setOpaque(false);
        row.add(bar, BorderLayout.CENTER);
        row.add(actions, BorderLayout.EAST);
        add(row, BorderLayout.NORTH);
        add(deck, BorderLayout.CENTER);
    }

    public PillTabs tab(String name, JComponent content) {
        ComposerToggleButton button = new ComposerToggleButton(null, name, null);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        button.addActionListener(e -> select(name));
        group.add(button);
        buttons.put(name, button);
        bar.add(button);
        deck.add(content, name);
        if (buttons.size() == 1) {
            button.setSelected(true);
        }
        return this;
    }

    /** Aktionen rechts in der Reiterzeile, auf derselben Höhe wie die Pillen; sie gelten für alle Reiter. */
    public PillTabs actions(JComponent... buttons) {
        for (JComponent button : buttons) {
            actions.add(button);
        }
        return this;
    }

    public void select(String name) {
        ComposerToggleButton button = buttons.get(name);
        if (button != null) {
            button.setSelected(true);
            cards.show(deck, name);
            listener.accept(name);
        }
    }

    public String selected() {
        for (Map.Entry<String, ComposerToggleButton> entry : buttons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void onSelect(Consumer<String> listener) {
        this.listener = listener;
    }

    /** Namen der Reiter in der Reihenfolge der Leiste. */
    public java.util.List<String> names() {
        return java.util.List.copyOf(buttons.keySet());
    }

    public ComposerToggleButton button(String name) {
        return buttons.get(name);
    }
}
