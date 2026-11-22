package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.PasswordReveal;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.betaview.BetaViewProfile;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSessions;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

/**
 * „Neue Anmeldung“ am BetaView-Server: Adresse und Anmeldung. Mit „KeePass“ kommen Benutzer und Kennwort (und ohne
 * eingetragene Adresse auch diese) aus einem Eintrag der vorhandenen KeePass-Kopplung der App, mit „Manuell“ aus den
 * Feldern. Nichts ist mit Namen vorbelegt; die zuletzt benutzte Adresse, Anmeldeart, der Eintrag bzw. Benutzer stehen
 * beim nächsten Mal wieder da, nie ein Kennwort. Angemeldet wird erst auf „Anmelden“ (oder Eingabe).
 */
final class BetaViewConnectForm extends JPanel {

    static final String ADDRESS_KEY = "deck.betaview.address";
    static final String LOGIN_KEY = "deck.betaview.login";
    static final String ENTRY_KEY = "deck.betaview.keepass-entry";
    static final String USER_KEY = "deck.betaview.user";
    static final String KEEPASS = "KeePass";
    static final String MANUAL = "Manuell";

    final PlaceholderField address = new PlaceholderField(28, "https://server/betaview/");
    final SegmentedToggle login = new SegmentedToggle().segment(KEEPASS).segment(MANUAL);
    final JComboBox<String> entry = Comic.combo();
    final PlaceholderField user = new PlaceholderField(20, "Benutzer");
    final JPasswordField password = new JPasswordField(20);
    private final JLabel keePassHint = Comic.muted(" ");
    private final JLabel loadEntries = new JLabel("Einträge laden");
    private final JLabel problem = new JLabel(" ");
    private final JPanel keePassRows = Prompts.form();
    private final JPanel manualRows = Prompts.form();
    private final ServerAccounts accounts;
    private final BiConsumer<BetaViewProfile, char[]> connect;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton submit;
    private boolean entriesLoaded;

    /**
     * @param accounts die gemeinsamen Host-Zugänge des Command Deck (dieselbe KeePass-Kopplung wie Terminal, FTP, NDV)
     * @param connect  meldet mit Profil und Kennwort an (auf dem EDT); das Kennwort gehört danach dem Empfänger
     */
    BetaViewConnectForm(ServerAccounts accounts, BiConsumer<BetaViewProfile, char[]> connect) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.connect = connect;
        setOpaque(false);
        entry.setEditable(true);
        Comic.style(password);
        PasswordReveal.install(password);

        JPanel top = Prompts.form();
        Prompts.row(top, 0, "Adresse", address);
        Prompts.row(top, 1, "Anmeldung", login);
        Prompts.row(keePassRows, 0, "KeePass-Eintrag", entry);
        JPanel hintRow = new JPanel(new BorderLayout(8, 0));
        hintRow.setOpaque(false);
        hintRow.add(keePassHint, BorderLayout.CENTER);
        loadEntries.setFont(Comic.regular(12.5f));
        loadEntries.setForeground(Comic.BLUE);
        loadEntries.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        loadEntries.setToolTipText("Einträge neu aus KeePass lesen");
        loadEntries.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                loadEntries();
            }
        });
        hintRow.add(loadEntries, BorderLayout.EAST);
        Prompts.row(keePassRows, 1, "", hintRow);
        Prompts.row(manualRows, 0, "Benutzer", user);
        Prompts.row(manualRows, 1, "Kennwort", password);
        problem.setFont(Comic.regular(12.5f));
        problem.setForeground(Comic.RED);
        problem.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        submit = Comic.primary("Anmelden", "Am BetaView-Server anmelden (Eingabe)");
        submit.addActionListener(e -> submit());
        address.addActionListener(e -> submit());
        user.addActionListener(e -> submit());
        password.addActionListener(e -> submit());
        login.onSelect(choice -> {
            updateLogin();
            if (KEEPASS.equals(choice)) {
                loadEntries();
            }
        });

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        for (JComponent part : new JComponent[] {top, keePassRows, manualRows, problem, Comic.row(submit)}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            body.add(part);
        }
        add(Comic.plate("Neue Anmeldung", Comic.YELLOW, body), BorderLayout.CENTER);
        recall();
        updateLogin();
        // Die Einträge erst lesen, wenn das Formular zum ersten Mal zu sehen ist – nie beim Start der App.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !entriesLoaded && KEEPASS.equals(login.selected())) {
                entriesLoaded = true;
                loadEntries();
            }
        });
        getAccessibleContext().setAccessibleName("Neue BetaView-Anmeldung");
    }

    private void updateLogin() {
        boolean keePass = KEEPASS.equals(login.selected());
        keePassRows.setVisible(keePass);
        manualRows.setVisible(!keePass);
        revalidate();
        repaint();
    }

    /**
     * Liest die Einträge aus den gemeinsamen Host-Zugängen (stiller KeePass-Zugang wie Terminal, FTP und NDV; ohne
     * Kopplung steht der Grund mit dem Weg zu den Einstellungen im Hinweis).
     */
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
            keePassHint.setText(titles.size() + (titles.size() == 1 ? " Eintrag" : " Einträge")
                    + " · gemeinsam mit Terminal, FTP und NDV");
        }, error -> keePassHint.setText(BetaViewSessions.describe(error)));
    }

    String entryTitle() {
        Object typed = entry.isEditable() ? entry.getEditor().getItem() : entry.getSelectedItem();
        return typed == null ? "" : typed.toString().strip();
    }

    /** Prüft die Felder, holt bei KeePass Benutzer und Kennwort im Hintergrund und meldet dann an. */
    void submit() {
        problem.setText(" ");
        String typedAddress = address.getText().strip();
        if (KEEPASS.equals(login.selected())) {
            String title = entryTitle();
            if (title.isEmpty()) {
                problem.setText("KeePass-Eintrag fehlt");
                return;
            }
            if (!typedAddress.isEmpty() && !validAddress(typedAddress)) {
                return;
            }
            submit.setEnabled(false);
            keePassHint.setText("Lese den Eintrag aus KeePass …");
            Background.run(() -> accounts.credentials(title), read -> {
                submit.setEnabled(true);
                keePassHint.setText(" ");
                try (read) {
                    String where = typedAddress.isEmpty() ? read.account().location() : typedAddress;
                    if (where.isEmpty()) {
                        problem.setText("Adresse fehlt (auch im KeePass-Eintrag steht keine)");
                        return;
                    }
                    start(where, read.user(), read.copyPassword(), Map.of(ENTRY_KEY, title));
                }
            }, error -> {
                submit.setEnabled(true);
                keePassHint.setText(" ");
                problem.setText("KeePass: " + BetaViewSessions.describe(error));
            });
            return;
        }
        if (!validAddress(typedAddress)) {
            return;
        }
        char[] secret = password.getPassword();
        if (secret.length == 0) {
            problem.setText("Kennwort fehlt");
            return;
        }
        password.setText("");
        start(typedAddress, user.getText(), secret, Map.of(USER_KEY, user.getText().strip()));
    }

    private boolean validAddress(String text) {
        try {
            BetaViewProfile.normalise(text);
            return true;
        } catch (IllegalArgumentException e) {
            problem.setText(e.getMessage());
            return false;
        }
    }

    private void start(String where, String userName, char[] secret, Map<String, String> remember) {
        BetaViewProfile profile;
        try {
            URI base = BetaViewProfile.normalise(where);
            profile = new BetaViewProfile(base, userName);
        } catch (IllegalArgumentException e) {
            Arrays.fill(secret, '\0');
            problem.setText(e.getMessage());
            return;
        }
        java.util.Map<String, String> state = new java.util.HashMap<>(remember);
        state.put(ADDRESS_KEY, address.getText().strip());
        state.put(LOGIN_KEY, login.selected());
        AppState.shared().putAll(state);
        connect.accept(profile, secret);
    }

    String problemText() {
        return problem.getText();
    }

    void focusFirst() {
        (address.getText().isEmpty() ? address : KEEPASS.equals(login.selected()) ? entry : password)
                .requestFocusInWindow();
    }

    private void recall() {
        AppState state = AppState.shared();
        address.setText(nonNull(state.get(ADDRESS_KEY)));
        String mode = state.get(LOGIN_KEY);
        login.select(MANUAL.equals(mode) ? MANUAL : KEEPASS);
        String title = nonNull(state.get(ENTRY_KEY));
        if (!title.isEmpty()) {
            entry.addItem(title);
            entry.setSelectedItem(title);
        }
        user.setText(nonNull(state.get(USER_KEY)));
    }

    /** Für Tests: Titel ins Auswahlfeld legen, ohne KeePass. */
    void entries(List<String> titles) {
        entry.removeAllItems();
        titles.forEach(entry::addItem);
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
