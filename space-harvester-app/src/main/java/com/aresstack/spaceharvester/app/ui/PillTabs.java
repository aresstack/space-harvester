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

    private final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
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
        add(bar, BorderLayout.NORTH);
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

    public ComposerToggleButton button(String name) {
        return buttons.get(name);
    }
}
