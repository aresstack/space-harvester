package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Verstecktes Extra am Suchfeld (Angelo, 28.11.2026): Gedrückthalten der Maus auf dem Feld wie am Zurück-Knopf
 * ({@link HoldGesture}) oder Pfeil nach unten klappt die letzten Suchbegriffe ({@link RecentTerms}) unter dem Feld
 * auf. Pfeil auf/ab wählt, Enter oder Klick sucht den Begriff, Esc schließt, Shift+Entf löscht den gewählten
 * Begriff wie in klassischen Windows-Dropdowns. Der Fokus bleibt im Feld; die Tasten wirken nur bei offener Liste,
 * sonst tun sie, was sie vorher taten.
 */
public final class RecentTermsPopup {

    private final JTextField field;
    private final JComponent anchor;
    private final RecentTerms terms;
    private final Consumer<String> choose;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    final JList<String> list = new JList<>(model);
    final JPopupMenu popup = new JPopupMenu();
    /** Zeigt das Popup unter der Leiste; Tests ohne Bildschirm setzen hier etwas anderes ein. */
    java.util.function.BooleanSupplier display = this::display;
    private boolean open;

    private RecentTermsPopup(JTextField field, JComponent anchor, RecentTerms terms, Consumer<String> choose) {
        this.field = field;
        this.anchor = anchor;
        this.terms = terms;
        this.choose = choose;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setFont(field.getFont());
        list.setBackground(Comic.SURFACE);
        list.getAccessibleContext().setAccessibleName("Letzte Suchbegriffe");
        list.setToolTipText("Letzte Suchbegriffe – Enter sucht, Shift+Entf löscht den gewählten");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                    list.setSelectedIndex(index);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                        pick(model.get(index));
                    }
                }
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(Comic.LINE));
        popup.add(list);
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                open = false;
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                open = false;
            }
        });
    }

    /**
     * Hängt die Liste an das Feld: Gedrückthalten öffnet sie, Pfeil auf/ab, Enter, Esc und Shift+Entf wirken bei
     * offener Liste. Pfeil nach unten bei geschlossener Liste tut weiter, was er vorher tat; zum Öffnen per Tastatur
     * ruft die Ansicht {@link #show(boolean)} aus ihrer eigenen Belegung auf.
     *
     * @param anchor die Leiste, unter der die Liste aufgeht (meist der Rahmen um das Feld)
     * @param choose sucht den gewählten Begriff, nachdem er im Feld steht
     */
    public static RecentTermsPopup install(JTextField field, JComponent anchor, RecentTerms terms,
                                           Consumer<String> choose) {
        RecentTermsPopup popup = new RecentTermsPopup(Objects.requireNonNull(field, "field"),
                Objects.requireNonNull(anchor, "anchor"), Objects.requireNonNull(terms, "terms"),
                Objects.requireNonNull(choose, "choose"));
        HoldGesture.install(field, () -> popup.show(false));
        popup.bind("DOWN", () -> popup.move(1));
        popup.bind("UP", () -> popup.move(-1));
        popup.bind("ENTER", popup::pickSelected);
        popup.bind("ESCAPE", popup::hide);
        popup.bind("shift DELETE", popup::removeSelected);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                popup.hide();
            }
        });
        return popup;
    }

    /**
     * Klappt die Liste auf, wenn es Begriffe gibt.
     *
     * @param selectFirst per Tastatur: der neueste Begriff ist gleich gewählt
     * @return ob die Liste aufging
     */
    public boolean show(boolean selectFirst) {
        List<String> current = terms.terms();
        if (current.isEmpty()) {
            return false;
        }
        model.clear();
        current.forEach(model::addElement);
        if (selectFirst) {
            list.setSelectedIndex(0);
        } else {
            list.clearSelection();
        }
        resize();
        open = display.getAsBoolean();
        return open;
    }

    public boolean isVisible() {
        return open;
    }

    public void hide() {
        open = false;
        popup.setVisible(false);
    }

    private boolean display() {
        if (!anchor.isShowing()) {
            return false;
        }
        popup.show(anchor, 0, anchor.getHeight());
        return true;
    }

    private void resize() {
        list.setVisibleRowCount(model.getSize());
        Dimension size = list.getPreferredSize();
        popup.setPopupSize(new Dimension(Math.max(anchor.getWidth(), size.width + 2), size.height + 2));
    }

    private void move(int step) {
        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int index = list.getSelectedIndex();
        int next = index < 0 ? (step > 0 ? 0 : size - 1) : Math.floorMod(index + step, size);
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private void pickSelected() {
        String selected = list.getSelectedValue();
        if (selected == null) {
            // Nichts gewählt: Enter sucht wie sonst den Text im Feld.
            hide();
            field.postActionEvent();
            return;
        }
        pick(selected);
    }

    private void pick(String term) {
        hide();
        field.setText(term);
        choose.accept(term);
    }

    private void removeSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) {
            return;
        }
        terms.remove(model.get(index));
        model.remove(index);
        if (model.isEmpty()) {
            hide();
            return;
        }
        int next = Math.min(index, model.getSize() - 1);
        list.setSelectedIndex(next);
        resize();
        if (popup.isVisible()) {
            popup.pack();
        }
    }

    /** Belegt eine Taste nur bei offener Liste; sonst läuft die bisherige Belegung des Felds. */
    private void bind(String key, Runnable whenOpen) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key);
        InputMap keys = field.getInputMap(JComponent.WHEN_FOCUSED);
        Object previousKey = keys.get(stroke);
        String name = "recentTerms." + key;
        keys.put(stroke, name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isVisible()) {
                    whenOpen.run();
                    return;
                }
                Action previous = previousKey == null ? null : field.getActionMap().get(previousKey);
                if (previous != null && previous.isEnabled()) {
                    previous.actionPerformed(e);
                }
            }
        });
    }
}
