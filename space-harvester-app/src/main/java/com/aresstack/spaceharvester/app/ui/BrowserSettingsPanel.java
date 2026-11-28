package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.proasteion.application.config.BrowserAccessConfig;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import com.aresstack.spaceharvester.app.chat.browser.BrowserSettings;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
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
 * standardmäßig erlauben mit Blacklist), beide Regellisten und die Suchmaschinen des Profils (Tabelle aus Ask AI;
 * Intern nur Suchseiten des Hauses). {@code browser_search} sucht damit in Tabs des Browsers des Chats (Angelo 28.11.2026:
 * ein Browser); nur Google läuft in einem eigenen Suchbrowser.
 * Jede Änderung (Auswahl oder Verlassen eines Felds) wird sofort gespeichert. Nur auf dem EDT.
 */
public final class BrowserSettingsPanel {

    public static final String HEADING = "Browserprofile";
    static final String BLOCK_LABEL = "Standardmäßig blockieren — nur Websites aus der Whitelist sind erreichbar.";
    static final String ALLOW_LABEL = "Standardmäßig erlauben — nur Websites aus der Blacklist werden blockiert.";
    static final String RULE_HINT = "Eine Regel je Zeile: example.org = genau dieser Host, *.example.org = "
            + "Subdomains; Blacklist gewinnt immer";
    static final String ENGINES_HINT = "Der Chat sucht damit in Tabs seines Browsers, die danach wieder schließen; "
            + "Priorität 0 zuerst, Zeilen gleicher Priorität zusammen.";
    static final String INTERNAL_ENGINES_HINT = "Nur Suchseiten des Hauses (etwa das Unternehmensportal), keine "
            + "öffentlichen Suchmaschinen. Weiß man nicht, wo etwas steht: mehrere mit gleicher Priorität.";
    static final String SEARCH_WINDOW_LABEL = "Google-Suchbrowser sichtbar";
    static final String SEARCH_WINDOW_HINT = "Nur Google sucht in einem eigenen Browser: zeigt dessen Fenster (etwa um "
            + "eine Sicherheitsabfrage zu lösen). Gilt ab seinem nächsten Start.";
    static final String NOTE = "Jeder Chat nutzt das Profil Extern oder Intern (Umschalter im Menü am Pfeil der "
            + "Weltkugel, Standard Extern). Bei „Standardmäßig blockieren“ fragt der Chat bei unbekannten Websites "
            + "nach: Einmal erlauben, Immer erlauben, Immer verbieten oder Abbrechen. „Immer“ trägt den Host in das "
            + "aktive Profil ein. Auch das Ziel einer Weiterleitung wird geprüft.";

    /** Überschrift der Browserwahl. */
    public static final String ENGINE_HEADING = "Browser des Chats";
    static final String CHANNEL_HINT = "Firefox, Edge und Chrome nutzen den installierten Browser mit Proxy und "
            + "Zertifikaten des Hauses. Camoufox muss installiert sein; der Start lädt nichts herunter. Gilt ab dem "
            + "nächsten Browser-Start.";

    /** Der Browser des Chats (ADR 0046). */
    final JComboBox<String> channel = Comic.combo(value -> BrowserSettings.CHANNELS.getOrDefault(
            String.valueOf(value), String.valueOf(value)));
    private final JPanel engine = new JPanel(new BorderLayout(0, 6));
    /** Ob der Suchbrowser ein Fenster zeigt. */
    final javax.swing.JCheckBox searchWindow = Comic.checkBox(SEARCH_WINDOW_LABEL);
    private Runnable channelChanged = () -> { };

    /** Die gleichen Felder eines Profils. */
    final class Profile {

        final BrowserProfile profile;
        final JRadioButton blockByDefault = radio(BLOCK_LABEL);
        final JRadioButton allowByDefault = radio(ALLOW_LABEL);
        final JTextArea whitelist = area();
        final JTextArea blacklist = area();
        final SearchEngineTable engines;
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        /** Was zuletzt geladen oder gespeichert wurde. */
        BrowserAccessConfig loaded;

        Profile(BrowserProfile profile) {
            this.profile = profile;
            boolean internalProfile = profile == BrowserProfile.INTERNAL;
            engines = new SearchEngineTable("Suchmaschinen " + profile.label(), internalProfile);
            ButtonGroup group = new ButtonGroup();
            group.add(blockByDefault);
            group.add(allowByDefault);
            blockByDefault.setSelected(true);
            SettingDialogs.Rows rows = new SettingDialogs.Rows();
            rows.add("Modus", modes(), null);
            rows.add("Whitelist", scroll(whitelist, "Whitelist " + profile.label()), RULE_HINT);
            rows.add("Blacklist", scroll(blacklist, "Blacklist " + profile.label()), RULE_HINT);
            rows.add("Suchmaschinen", engines, internalProfile ? INTERNAL_ENGINES_HINT : ENGINES_HINT);
            panel.setOpaque(false);
            panel.add(rows, BorderLayout.CENTER);
            blockByDefault.addActionListener(e -> save(this));
            allowByDefault.addActionListener(e -> save(this));
            engines.onChange(selection -> {
                if (!loading) {
                    settings.saveEngines(profile, selection);
                }
            });
            for (JTextArea area : new JTextArea[] {whitelist, blacklist}) {
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
                engines.set(settings.engines(profile));
            } finally {
                loading = false;
            }
            loaded = config();
        }

        BrowserAccessConfig config() {
            // Die alten Such-URLs bleiben, wie sie sind: BrowserSettings übernimmt sie in die Suchmaschinen.
            return new BrowserAccessConfig(allowByDefault.isSelected(), rules(whitelist.getText()),
                    rules(blacklist.getText()), loaded == null ? List.of() : loaded.searchUrls(), null);
        }
    }

    /** Überschrift der gemerkten Antworten auf ändernde Aktionen. */
    public static final String CHANGES_HEADING = "Ändernde Aktionen";
    static final String CHANGES_HINT = "Formular absenden, Speichern, Senden …: auf diesen Websites ohne Rückfrage "
            + "bzw. nie. „Immer erlauben“ und „Immer verbieten“ der Rückfrage im Chat tragen den Host hier ein.";

    /** Die gemerkten Antworten auf ändernde Aktionen ({@code browser_act}), für beide Profile. */
    final JTextArea changesAllowed = area();
    final JTextArea changesBlocked = area();
    private final JPanel changes = new JPanel(new BorderLayout(0, 6));
    private List<String> loadedAllowed = List.of();
    private List<String> loadedBlocked = List.of();

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
        for (String key : BrowserSettings.CHANNELS.keySet()) {
            channel.addItem(key);
        }
        channel.getAccessibleContext().setAccessibleName(ENGINE_HEADING);
        SettingDialogs.Rows engineRows = new SettingDialogs.Rows();
        engineRows.add("Browser", channel, CHANNEL_HINT);
        searchWindow.getAccessibleContext().setAccessibleName(SEARCH_WINDOW_LABEL);
        engineRows.add("Suche", searchWindow, SEARCH_WINDOW_HINT);
        searchWindow.addActionListener(e -> {
            if (!loading) {
                settings.saveSearchWindowShown(searchWindow.isSelected());
            }
        });
        engine.setOpaque(false);
        engine.add(engineRows, BorderLayout.CENTER);
        channel.addActionListener(e -> saveChannel());
        external = new Profile(BrowserProfile.EXTERNAL);
        internal = new Profile(BrowserProfile.INTERNAL);
        SettingDialogs.Rows rows = new SettingDialogs.Rows();
        rows.add("Immer erlauben", scroll(changesAllowed, "Ändernde Aktionen immer erlauben"), CHANGES_HINT);
        rows.add("Immer verbieten", scroll(changesBlocked, "Ändernde Aktionen immer verbieten"), RULE_HINT);
        changes.setOpaque(false);
        changes.add(rows, BorderLayout.CENTER);
        for (JTextArea area : new JTextArea[] {changesAllowed, changesBlocked}) {
            area.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    saveChanges();
                }
            });
        }
        load();
    }

    /** Liest die gespeicherten Profile neu (etwa nach „Immer erlauben“ im Chat). */
    public void load() {
        BrowserAccessConfig config = settings.load();
        external.load(config.profile(BrowserProfile.EXTERNAL));
        internal.load(config.profile(BrowserProfile.INTERNAL));
        var remembered = settings.changes();
        loading = true;
        try {
            channel.setSelectedItem(settings.channel());
            searchWindow.setSelected(settings.searchWindowShown());
            changesAllowed.setText(lines(remembered.allow()));
            changesBlocked.setText(lines(remembered.block()));
        } finally {
            loading = false;
        }
        loadedAllowed = rules(changesAllowed.getText());
        loadedBlocked = rules(changesBlocked.getText());
    }

    /** Wie {@link #save(Profile)}: nur echte Änderungen überschreiben, was der Chat inzwischen gemerkt hat. */
    private void saveChanges() {
        if (loading) {
            return;
        }
        List<String> allowed = rules(changesAllowed.getText());
        List<String> blocked = rules(changesBlocked.getText());
        if (allowed.equals(loadedAllowed) && blocked.equals(loadedBlocked)) {
            return;
        }
        settings.saveChanges(allowed, blocked);
        loadedAllowed = allowed;
        loadedBlocked = blocked;
    }

    /** Speichert die Browserwahl, wenn der Benutzer sie geändert hat, und meldet sie (neuer Start mit dem neuen). */
    private void saveChannel() {
        Object chosen = channel.getSelectedItem();
        if (loading || chosen == null || chosen.equals(settings.channel())) {
            return;
        }
        settings.saveChannel(chosen.toString());
        channelChanged.run();
    }

    /** Was nach einer anderen Browserwahl geschieht (die App startet die Chat-Browser neu). */
    public void onChannelChanged(Runnable action) {
        channelChanged = action == null ? () -> { } : action;
    }

    /** Die Wahl des Browsers. */
    public JComponent engine() {
        return engine;
    }

    /** Die gemerkten Antworten auf ändernde Aktionen. */
    public JComponent changes() {
        return changes;
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
        JScrollPane scroll = Comic.comicBars(new JScrollPane(area));
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
}
