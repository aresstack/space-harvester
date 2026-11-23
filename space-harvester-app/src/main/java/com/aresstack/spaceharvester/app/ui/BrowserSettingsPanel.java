package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.proasteion.application.config.BrowserAccessConfig;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import com.aresstack.spaceharvester.app.chat.browser.BrowserSettings;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Einstellungen › Browser, übernommen aus {@code BrowserTab} des Enterprise AI Client: die Zugriffsregeln der
 * Websuche im Chat je Profil. Jeder Chat nutzt Extern (Vorgabe) oder Intern (Umschalter im Menü am Pfeil der
 * Weltkugel); beide Profile haben dieselben Felder: Modus (standardmäßig blockieren mit Whitelist oder
 * standardmäßig erlauben mit Blacklist), beide Regellisten und die Such-URLs, die der Chat im Systemhinweis bekommt.
 * Jede Änderung (Auswahl oder Verlassen eines Felds) wird sofort gespeichert. Nur auf dem EDT.
 */
public final class BrowserSettingsPanel {

    public static final String HEADING = "Browserprofile";
    static final String BLOCK_LABEL = "Standardmäßig blockieren — nur Websites aus der Whitelist sind erreichbar.";
    static final String ALLOW_LABEL = "Standardmäßig erlauben — nur Websites aus der Blacklist werden blockiert.";
    static final String RULE_HINT = "Eine Regel je Zeile: example.org = genau dieser Host, *.example.org = "
            + "Subdomains; Blacklist gewinnt immer";
    static final String SEARCH_HINT = "Eine Such-URL je Zeile, {query} steht für die Suchbegriffe. "
            + "Der Chat bekommt sie bei eingeschalteter Websuche.";
    static final String NOTE = "Jeder Chat nutzt das Profil Extern oder Intern (Umschalter im Menü am Pfeil der "
            + "Weltkugel, Standard Extern). Bei „Standardmäßig blockieren“ fragt der Chat bei unbekannten Websites "
            + "nach: Einmal erlauben, Immer erlauben, Immer verbieten oder Abbrechen. „Immer“ trägt den Host in das "
            + "aktive Profil ein. Auch das Ziel einer Weiterleitung wird geprüft. Firefox sucht die App am üblichen "
            + "Ort oder unter -Denterpriseai.firefox=….";

    /** Die gleichen Felder eines Profils. */
    final class Profile {

        final BrowserProfile profile;
        final JRadioButton blockByDefault = radio(BLOCK_LABEL);
        final JRadioButton allowByDefault = radio(ALLOW_LABEL);
        final JTextArea whitelist = area();
        final JTextArea blacklist = area();
        final JTextArea searchUrls = area();
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        /** Was zuletzt geladen oder gespeichert wurde. */
        BrowserAccessConfig loaded;

        Profile(BrowserProfile profile) {
            this.profile = profile;
            ButtonGroup group = new ButtonGroup();
            group.add(blockByDefault);
            group.add(allowByDefault);
            blockByDefault.setSelected(true);
            SettingDialogs.Rows rows = new SettingDialogs.Rows();
            rows.add("Modus", modes(), null);
            rows.add("Whitelist", scroll(whitelist, "Whitelist " + profile.label()), RULE_HINT);
            rows.add("Blacklist", scroll(blacklist, "Blacklist " + profile.label()), RULE_HINT);
            rows.add("Such-URLs", scroll(searchUrls, "Such-URLs " + profile.label()), SEARCH_HINT);
            panel.setOpaque(false);
            panel.add(rows, BorderLayout.CENTER);
            blockByDefault.addActionListener(e -> save(this));
            allowByDefault.addActionListener(e -> save(this));
            for (JTextArea area : new JTextArea[] {whitelist, blacklist, searchUrls}) {
                area.addFocusListener(new java.awt.event.FocusAdapter() {
                    @Override
                    public void focusLost(java.awt.event.FocusEvent e) {
                        save(Profile.this);
                    }
                });
            }
        }

        private JPanel modes() {
            JPanel modes = new JPanel();
            modes.setOpaque(false);
            modes.setLayout(new javax.swing.BoxLayout(modes, javax.swing.BoxLayout.Y_AXIS));
            modes.add(blockByDefault);
            modes.add(allowByDefault);
            return modes;
        }

        void load(BrowserAccessConfig config) {
            loading = true;
            try {
                if (config.allowByDefault()) {
                    allowByDefault.setSelected(true);
                } else {
                    blockByDefault.setSelected(true);
                }
                whitelist.setText(lines(config.allow()));
                blacklist.setText(lines(config.block()));
                searchUrls.setText(lines(config.searchUrls()));
            } finally {
                loading = false;
            }
            loaded = config();
        }

        BrowserAccessConfig config() {
            return new BrowserAccessConfig(allowByDefault.isSelected(), rules(whitelist.getText()),
                    rules(blacklist.getText()), urls(searchUrls.getText()), null);
        }
    }

    private final BrowserSettings settings;
    final Profile external;
    final Profile internal;
    private final JTextArea note = Comic.note(NOTE);
    private boolean loading;

    public BrowserSettingsPanel() {
        this(BrowserSettings.shared());
    }

    BrowserSettingsPanel(BrowserSettings settings) {
        this.settings = settings;
        external = new Profile(BrowserProfile.EXTERNAL);
        internal = new Profile(BrowserProfile.INTERNAL);
        load();
    }

    /** Liest die gespeicherten Profile neu (etwa nach „Immer erlauben“ im Chat). */
    public void load() {
        BrowserAccessConfig config = settings.load();
        external.load(config.profile(BrowserProfile.EXTERNAL));
        internal.load(config.profile(BrowserProfile.INTERNAL));
    }

    /**
     * Speichert ein Profil, aber nur, wenn der Benutzer hier etwas geändert hat: Ein bloßes Verlassen eines Feldes
     * überschreibt so nie Regeln, die der Chat inzwischen gemerkt hat („Immer erlauben“/„Immer verbieten“).
     */
    private void save(Profile profile) {
        if (loading) {
            return;
        }
        BrowserAccessConfig edited = profile.config();
        if (same(edited, profile.loaded)) {
            return;
        }
        settings.save(profile.profile, edited);
        profile.loaded = edited;
    }

    private static boolean same(BrowserAccessConfig a, BrowserAccessConfig b) {
        return b != null && a.allowByDefault() == b.allowByDefault() && a.allow().equals(b.allow())
                && a.block().equals(b.block()) && a.searchUrls().equals(b.searchUrls());
    }

    /** Der Hinweis über beiden Profilen. */
    public JComponent note() {
        return note;
    }

    public JComponent external() {
        return external.panel;
    }

    public JComponent internal() {
        return internal.panel;
    }

    private static JRadioButton radio(String label) {
        JRadioButton button = new JRadioButton(label);
        button.setOpaque(false);
        button.setForeground(Comic.TEXT);
        button.setFont(Comic.regular(12.5f));
        button.setFocusPainted(false);
        return button;
    }

    private static JTextArea area() {
        JTextArea area = new JTextArea(5, 40);
        area.setLineWrap(false);
        area.setFont(Comic.regular(12.5f));
        return area;
    }

    private static JScrollPane scroll(JTextArea area, String name) {
        area.getAccessibleContext().setAccessibleName(name);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(Comic.MUTED));
        return scroll;
    }

    private static String lines(List<String> values) {
        return String.join("\n", values);
    }

    /** Eine Regel je Zeile; auch Kommas trennen, Leeres und Doppeltes entfällt. */
    static List<String> rules(String text) {
        List<String> rules = new ArrayList<>();
        if (text != null) {
            for (String part : text.split("[,\\r\\n]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !rules.contains(trimmed)) {
                    rules.add(trimmed);
                }
            }
        }
        return rules;
    }

    /** Eine Such-URL je Zeile (URLs dürfen Kommas enthalten); Leeres und Doppeltes entfällt. */
    static List<String> urls(String text) {
        List<String> urls = new ArrayList<>();
        if (text != null) {
            for (String part : text.split("\\s+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !urls.contains(trimmed)) {
                    urls.add(trimmed);
                }
            }
        }
        return urls;
    }
}
