package com.aresstack.spaceharvester.app.ui.deck;

import static com.aresstack.spaceharvester.app.ui.deck.DeckForms.row;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.eakte.EakteProfile;
import com.aresstack.spaceharvester.deck.eakte.EakteSessions;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * „Neue Verbindung“ zur E-Akte. Benutzer und Kennwort kommen aus einem EIGENEN KeePass-Eintrag (Angelo, 23.11.2026:
 * andere Zugangsdaten als die des Mainframes); anders als BetaView fällt die E-Akte nie auf den Eintrag von Terminal,
 * FTP oder NDV zurück. Sichtbar ist nur der Eintrag; Adressen, Anmeldedienste und Proxy stehen unter „Erweitert“ und
 * sind mit den Werten aus dem Testprogramm vorbelegt. Gemerkt werden Eintrag und Erweitert, nie ein Kennwort.
 */
final class EakteConnectForm extends JPanel {

    static final String ENTRY_KEY = "deck.eakte.keepass-entry";
    static final String START_KEY = "deck.eakte.start";
    static final String USE_PROXY_KEY = "deck.eakte.use-proxy";

    final JComboBox<String> entry = Comic.combo();
    /**
     * Die eine E-Akte-Adresse, die man sonst im Browser öffnet: Einstieg der Anmeldung. WebDAV-Wurzel und erlaubte
     * Anmeldedienste legt der Adapter fest ({@link EakteProfile#fromAddress}); sie sind nicht einstellbar.
     */
    final PlaceholderField start = new PlaceholderField(30, "Adresse, die du sonst im Browser öffnest");
    /** Nur für den Nachbau in Tests: andere Anmeldedienste als die festen. */
    Set<String> loginHostsForTests;
    final JCheckBox useProxy = Comic.checkBox("Proxy der App verwenden");
    private final JLabel keePassHint = Comic.muted(" ");
    private final JLabel problem = new JLabel(" ");
    private final JPanel advanced = new JPanel(new GridBagLayout());
    private final JButton disclosure = Comic.disclosure("Erweitert", "Proxy");
    private final ServerAccounts accounts;
    private final BiConsumer<EakteProfile, char[]> connect;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton submit;
    private boolean entriesLoaded;
    private Consumer<String> direct;
    private String attemptEntry = "";

    /** @param connect meldet mit Profil und Kennwort an (auf dem EDT); das Kennwort gehört danach dem Empfänger */
    EakteConnectForm(ServerAccounts accounts, BiConsumer<EakteProfile, char[]> connect) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.connect = connect;
        setOpaque(false);
        entry.setEditable(true);

        JPanel top = new JPanel(new GridBagLayout());
        top.setOpaque(false);
        row(top, 0, "KeePass-Eintrag", entry);
        row(top, 1, "", keePassHint);
        row(top, 2, "E-Akte-Adresse", start);
        advanced.setOpaque(false);
        row(advanced, 0, "", useProxy);
        advanced.setVisible(false);
        disclosure.addActionListener(e -> {
            boolean open = !Comic.disclosed(disclosure);
            Comic.disclose(disclosure, open);
            advanced.setVisible(open);
            revalidate();
        });
        problem.setFont(Comic.regular(12.5f));
        problem.setForeground(Comic.RED);
        problem.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        submit = Comic.primary("Verbinden", "An der E-Akte anmelden (Eingabe)");
        submit.addActionListener(e -> submit());
        entry.getEditor().addActionListener(e -> submit());
        start.addActionListener(e -> submit());

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        for (JComponent part : new JComponent[] {top, disclosure, advanced, problem, Comic.row(submit)}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            body.add(part);
        }
        add(Comic.plate("Neue Verbindung zur E-Akte", Comic.YELLOW, body), BorderLayout.CENTER);
        recall();
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !entriesLoaded) {
                entriesLoaded = true;
                loadEntries();
            }
        });
        getAccessibleContext().setAccessibleName("Neue Verbindung zur E-Akte");
    }

    /** Ob ohne Formular verbunden werden darf: Es gibt einen eigenen gemerkten KeePass-Eintrag der E-Akte. */
    static boolean isConfigured() {
        String start = AppState.shared().get(START_KEY);
        return !savedEntry().isEmpty() && start != null && !start.isBlank();
    }

    /** Der eigene KeePass-Eintrag der E-Akte; nie der von Terminal, FTP oder NDV. */
    static String savedEntry() {
        String saved = AppState.shared().get(ENTRY_KEY);
        return saved == null ? "" : saved.strip();
    }

    /** Ob die E-Akte den Proxy der App nimmt (Vorgabe ja, wie BetaView). */
    static boolean proxyWanted() {
        return AppState.shared().getBoolean(USE_PROXY_KEY, true);
    }

    void loadEntries() {
        String keep = entryTitle();
        keePassHint.setText("Lese KeePass …");
        Background.run(accounts::accounts, found -> {
            List<String> titles = found.stream().map(ServerAccount::entry).toList();
            entry.removeAllItems();
            titles.forEach(entry::addItem);
            if (!keep.isEmpty() && !titles.contains(keep)) {
                entry.addItem(keep);
            }
            entry.setSelectedItem(keep.isEmpty() ? null : keep);
            keePassHint.setText("Eigener Eintrag der E-Akte, nicht der des Mainframes");
        }, error -> keePassHint.setText(EakteSessions.describe(error)));
    }

    String entryTitle() {
        Object typed = entry.getEditor().getItem();
        return typed == null ? "" : typed.toString().strip();
    }

    /** Prüft die Felder, liest Benutzer und Kennwort aus KeePass im Hintergrund und verbindet dann. */
    void submit() {
        problem.setText(" ");
        String title = entryTitle();
        if (title.isEmpty()) {
            failed("KeePass-Eintrag fehlt");
            return;
        }
        EakteProfile draft;
        try {
            draft = profile("pruefung");
        } catch (IllegalArgumentException e) {
            failed(e.getMessage());
            return;
        }
        submit.setEnabled(false);
        keePassHint.setText("Lese den Eintrag aus KeePass …");
        Background.run(() -> accounts.credentials(title), read -> {
            submit.setEnabled(true);
            keePassHint.setText(" ");
            char[] secret = read.copyPassword();
            try (read) {
                EakteProfile profile = new EakteProfile(draft.webdav(), draft.start(), read.user(),
                        draft.loginHosts());
                remember(title);
                direct = null;
                attemptEntry = title;
                connect.accept(profile, secret);
            } catch (IllegalArgumentException e) {
                Arrays.fill(secret, '\0');
                failed("KeePass-Eintrag „" + title + "“: " + e.getMessage());
            }
        }, error -> {
            submit.setEnabled(true);
            keePassHint.setText(" ");
            failed("KeePass: " + EakteSessions.describe(error));
        });
    }

    /** Verbinden ohne Formular mit dem gemerkten Eintrag; scheitert schon das Lesen, meldet {@code failed} den Grund. */
    void connectDirectly(Consumer<String> failed) {
        recall();
        direct = failed;
        submit();
    }

    /** Zeigt den Grund; eine direkte Verbindung erfährt ihn, damit das Formular erscheint. */
    void failed(String reason) {
        problem.setText(reason);
        Consumer<String> waiting = direct;
        direct = null;
        if (waiting != null) {
            waiting.accept(reason);
        }
    }

    /** Zeigt, warum nicht automatisch verbunden wurde (gesperrter Eintrag). */
    void notice(String text) {
        problem.setText(text);
    }

    String problemText() {
        return problem.getText();
    }

    /** Der KeePass-Eintrag der zuletzt gestarteten Anmeldung; gilt nur für die nächste geöffnete Sitzung. */
    String takeAttemptEntry() {
        String taken = attemptEntry;
        attemptEntry = "";
        return taken;
    }

    void focusFirst() {
        entry.requestFocusInWindow();
    }

    private EakteProfile profile(String user) {
        return loginHostsForTests == null ? EakteProfile.fromAddress(start.getText(), user)
                : EakteProfile.fromAddress(start.getText(), user, loginHostsForTests);
    }

    private void remember(String title) {
        AppState.shared().putAll(Map.of(ENTRY_KEY, title, START_KEY, start.getText().strip()));
        AppState.shared().put(USE_PROXY_KEY, useProxy.isSelected());
    }

    private void recall() {
        AppState state = AppState.shared();
        String title = savedEntry();
        if (!title.isEmpty() && !title.equals(entryTitle())) {
            entry.addItem(title);
            entry.setSelectedItem(title);
        }
        start.setText(value(state.get(START_KEY), ""));
        useProxy.setSelected(proxyWanted());
    }

    private static String value(String stored, String fallback) {
        return stored == null ? fallback : stored;
    }

    /** Für Tests: Titel ins Auswahlfeld legen, ohne KeePass. */
    void entries(List<String> titles) {
        entry.removeAllItems();
        titles.forEach(entry::addItem);
    }
}
