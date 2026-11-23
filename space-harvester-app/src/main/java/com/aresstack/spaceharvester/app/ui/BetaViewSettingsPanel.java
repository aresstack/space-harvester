package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewDefaults;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Einstellungen › BetaView: Platte „Verbindung“ (Proxy der App ja/nein, Vorgabe nein wie MainframeMate) und Platte
 * „Standard-Suchfilter“ wie in MainframeMate: Favorite ID, Locale, Extension, Form,
 * Tage zurück. Eine Änderung (Enter oder Verlassen des Felds) wird sofort gespeichert und gilt für die nächste
 * Suchmaske; „Vorgaben“ setzt die Werte von MainframeMate. Nur auf dem EDT.
 */
public final class BetaViewSettingsPanel {

    public static final String HEADING = "Standard-Suchfilter";
    public static final String CONNECTION_HEADING = "Verbindung";
    static final String USE_PROXY_TEXT = "Proxy der App verwenden";

    /** Wie MainframeMate geht BetaView als Vorgabe direkt zum Server, ohne den Proxy der App. */
    final JCheckBox useProxy = Comic.checkBox(USE_PROXY_TEXT);
    private final JPanel connection = new JPanel(new BorderLayout(0, 6));

    final JTextField favorite = Comic.field(20);
    final JTextField locale = Comic.field(10);
    final JTextField extension = Comic.field(20);
    final JTextField form = Comic.field(10);
    final JTextField daysBack = Comic.field(6);
    private final Feedback feedback = new Feedback();
    private final JPanel component = new JPanel(new BorderLayout(0, 8));
    private BetaViewDefaults shown;
    private boolean showing;

    public BetaViewSettingsPanel() {
        SettingDialogs.Rows rows = new SettingDialogs.Rows();
        rows.add("Favorite ID", favorite, "Favorit (Auswahlmaske) des Servers, etwa aus select.action?favoriteID=…. "
                + "Vorgabe A158.");
        rows.add("Locale", locale, "Sprache der Oberfläche des Servers. Vorgabe de.");
        rows.add("Extension", extension, "Vorbelegung des Felds Extension, etwa BK18BANK* oder WV00BANK. Vorgabe *.");
        rows.add("Form", form, "Vorbelegung des Felds Form, etwa APZF. Vorgabe APZF.");
        rows.add("Tage zurück", daysBack, "Zahl des Zeitraums „Individuell“, von 1 bis 9999. Vorgabe 60.");
        for (JTextField field : new JTextField[] {favorite, locale, extension, form, daysBack}) {
            field.addActionListener(e -> save());
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    save();
                }
            });
        }
        ComposerButton reset = Comic.button("Vorgaben", "Die Vorgaben von MainframeMate setzen: A158, de, *, APZF, 60");
        reset.addActionListener(e -> {
            render(BetaViewDefaults.MAINFRAMEMATE);
            save();
        });
        component.setOpaque(false);
        component.add(rows, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(reset);
        south.add(buttons, BorderLayout.NORTH);
        south.add(feedback, BorderLayout.CENTER);
        component.add(south, BorderLayout.SOUTH);
        useProxy.setOpaque(false);
        useProxy.setToolTipText("<html>Aus (Vorgabe, wie MainframeMate): BetaView verbindet sich direkt mit dem Server."
                + "<br>An: über den Proxy aus Einstellungen › Netzwerk. Gilt ab der nächsten Verbindung.</html>");
        useProxy.addActionListener(e -> {
            com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewAccess.useProxy(useProxy.isSelected());
            feedback.success(useProxy.isSelected() ? "BetaView verbindet sich ab der nächsten Verbindung über den "
                    + "Proxy der App" : "BetaView verbindet sich ab der nächsten Verbindung direkt (ohne Proxy)");
        });
        connection.setOpaque(false);
        connection.add(useProxy, BorderLayout.NORTH);
        render();
    }

    public JComponent component() {
        return component;
    }

    /** Platte „Verbindung“: der Proxy-Schalter. */
    public JComponent connection() {
        return connection;
    }

    /** Zeigt die gespeicherten Werte. */
    public void render() {
        shown = BetaViewDefaults.load();
        render(shown);
        useProxy.setSelected(com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewAccess.useProxy());
    }

    private void render(BetaViewDefaults defaults) {
        showing = true;
        try {
            favorite.setText(defaults.favoriteId());
            locale.setText(defaults.locale());
            extension.setText(defaults.extension());
            form.setText(defaults.form());
            daysBack.setText(String.valueOf(defaults.daysBack()));
        } finally {
            showing = false;
        }
    }

    void save() {
        if (showing) {
            return;
        }
        String days = daysBack.getText().strip();
        int number;
        try {
            number = days.isEmpty() ? 0 : Integer.parseInt(days);
        } catch (NumberFormatException e) {
            feedback.problem(new IllegalArgumentException("Tage zurück: „" + days + "“ ist keine ganze Zahl; nicht "
                    + "gespeichert"));
            return;
        }
        if (number < 0 || number > 9999) {
            feedback.problem(new IllegalArgumentException("Tage zurück: " + number + " liegt nicht zwischen 1 und "
                    + "9999; nicht gespeichert"));
            return;
        }
        BetaViewDefaults chosen = new BetaViewDefaults(favorite.getText(), locale.getText(), extension.getText(),
                form.getText(), number);
        if (chosen.equals(shown)) {
            return;
        }
        chosen.save();
        shown = chosen;
        render(chosen);
        feedback.success(HEADING + " gespeichert – gilt für die nächste Suchmaske");
    }
}
