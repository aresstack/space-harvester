package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.terminal.TerminalLogin;
import com.aresstack.spaceharvester.deck.terminal.TerminalModel;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * „Neue Verbindung“ zum TN3270-Host. Ein Server ist ein KeePass-Eintrag (Angelo, 22.11.2026): Adresse aus dem
 * URL-Feld, Benutzer und Passwort aus dem Eintrag, gemeinsam für Terminal, FTP und NDV. Sichtbar sind nur Server,
 * Port und „Automatisch anmelden“; Host von Hand, Modell, TLS, Codepage, Keep-Alive und Startkommando stehen unter
 * „Erweitert“. Verbunden wird erst auf „Verbinden“; das Passwort liest die Sitzung erst danach im Hintergrund.
 */
final class ConnectForm extends JPanel {

    static final String ENTRY_KEY = "deck.terminal.entry";
    static final String MODEL_KEY = "deck.terminal.model";
    /** Neuer Schlüssel: die alte Vorgabe „ohne TLS“ war falsch (MainframeMate: Port 992 mit TLS) und bleibt nicht hängen. */
    static final String TLS_KEY = "deck.terminal.use-tls";
    static final String CODE_PAGE_KEY = "deck.terminal.code-page";
    static final String KEEP_ALIVE_KEY = "deck.terminal.keep-alive";
    static final String AUTO_LOGIN_KEY = "deck.terminal.auto-login";
    /** Neuer Schlüssel: Vorgabe „a“ wie tn3270AutoCommandText in MainframeMate (das alte leere Feld bleibt nicht hängen). */
    static final String COMMAND_KEY = "deck.terminal.start-command";
    /** Startkommando nach der Anmeldung, wie in MainframeMate voreingestellt. */
    static final String DEFAULT_COMMAND = "a";

    final JComboBox<ServerAccount> server = Comic.combo(ConnectForm::accountText);
    final PlaceholderField host = new PlaceholderField(22, "aus dem KeePass-Eintrag");
    final PlaceholderField port = new PlaceholderField(6, String.valueOf(TerminalProfile.DEFAULT_PORT));
    final JCheckBox autoLogin = Comic.checkBox("Automatisch anmelden (Benutzer und Passwort aus KeePass)");
    final JComboBox<TerminalModel> model = Comic.combo();
    final JCheckBox tls = Comic.checkBox("Verschlüsselt (TLS)");
    final PlaceholderField codePage = new PlaceholderField(8, TerminalProfile.DEFAULT_CODE_PAGE);
    final PlaceholderField keepAlive = new PlaceholderField(6, "aus");
    final PlaceholderField command = new PlaceholderField(22, "nach der Anmeldung eingeben, z. B. ein Menü");
    private final JLabel problem = new JLabel(" ");
    private final JLabel serverState = Comic.muted(" ");
    private final JPanel advanced = new JPanel(new GridBagLayout());
    private final JLabel advancedToggle = new JLabel();
    private final ServerAccounts accounts;
    private ServerAccount shownAccount;
    private final java.util.function.Consumer<Request> connect;
    private boolean loaded;
    /** Terminal-Lesezeichen (MainframeMate): Ziel, KeePass-Eintrag, Makro. Nur sichtbar, wenn es welche gibt. */
    private final TerminalBookmarks bookmarks;
    final JComboBox<TerminalBookmarks.Bookmark> bookmark = Comic.combo();
    private final JPanel bookmarkRow = new JPanel(new BorderLayout(6, 0));

    /**
     * Was „Verbinden“ (oder ein Lesezeichen) öffnet: Ziel, Anmeldung, dazu woher sie kam (für ein späteres
     * Lesezeichen) und das abzuspielende Makro.
     *
     * @param entry   KeePass-Eintrag der Anmeldung, leer ohne automatische Anmeldung
     * @param command Startkommando nach der Anmeldung
     */
    record Request(TerminalProfile profile, TerminalSessions.LoginSource login, String entry, String command,
                   List<com.aresstack.spaceharvester.deck.terminal.TerminalMacro.Step> steps) {
    }

    /** Beschriftung eines KeePass-Zugangs in den Auswahllisten des Command Deck: Eintrag und Benutzer. */
    static String accountText(Object value) {
        return value instanceof ServerAccount account
                ? account.toString() + (account.user().isEmpty() ? "" : " · " + account.user())
                : "KeePass-Eintrag wählen …";
    }

    ConnectForm(ServerAccounts accounts, BiConsumer<TerminalProfile, TerminalSessions.LoginSource> connect) {
        this(accounts, new TerminalBookmarks(AppState.shared()),
                request -> connect.accept(request.profile(), request.login()));
    }

    ConnectForm(ServerAccounts accounts, TerminalBookmarks bookmarks, java.util.function.Consumer<Request> connect) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.connect = connect;
        this.bookmarks = bookmarks;
        setOpaque(false);
        server.addActionListener(e -> serverChanged());
        IconButton reload = new IconButton(Glyph.REFRESH, "Einträge neu aus KeePass laden");
        reload.addActionListener(e -> load());
        InputFrame serverFrame = new InputFrame(server);
        serverFrame.addButton(reload);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        row(fields, 0, "Server", serverFrame);
        row(fields, 1, "", serverState);
        row(fields, 2, "Port", port);
        // TLS ist eine Entscheidung (wie in MainframeMate sichtbar): mit TLS gilt Port 992, ohne Port 23.
        row(fields, 3, "", tls);
        row(fields, 4, "", autoLogin);
        advanced.setOpaque(false);
        row(advanced, 0, "Host", host);
        row(advanced, 1, "Startkommando", command);
        row(advanced, 2, "Modell", new InputFrame(model));
        row(advanced, 3, "Codepage", codePage);
        row(advanced, 4, "Keep-Alive (s)", keepAlive);
        advanced.setVisible(false);
        advancedToggle.setFont(Comic.regular(12.5f));
        advancedToggle.setForeground(Comic.BLUE);
        Cursors.set(advancedToggle, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        advancedToggle.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                advanced(!advanced.isVisible());
            }
        });
        advanced(false);
        // Mit TLS ist 992 der übliche Port; ein eingetragener Port bleibt, wie er ist.
        tls.addActionListener(e -> serverChanged());
        model.setModel(new DefaultComboBoxModel<>(TerminalModel.values()));
        problem.setFont(Comic.regular(12.5f));
        problem.setForeground(Comic.RED);
        var button = Comic.primary("Verbinden", "TN3270-Sitzung zu diesem Server öffnen (Eingabe)");
        button.addActionListener(e -> submit());
        host.addActionListener(e -> submit());
        port.addActionListener(e -> submit());

        // Lesezeichen: wählen und öffnen (verbindet, meldet an, spielt das Makro ab) oder entfernen.
        var openBookmark = Comic.button("Öffnen", "Lesezeichen öffnen: verbinden, anmelden, Makro abspielen");
        openBookmark.addActionListener(e -> openBookmark());
        IconButton dropBookmark = new IconButton(Glyph.TRASH, "Lesezeichen entfernen");
        dropBookmark.addActionListener(e -> dropBookmark());
        InputFrame bookmarkFrame = new InputFrame(bookmark);
        bookmarkFrame.addButton(dropBookmark);
        JLabel bookmarkLabel = Comic.text("Lesezeichen");
        bookmarkLabel.setPreferredSize(new Dimension(110, bookmarkLabel.getPreferredSize().height));
        bookmarkRow.setOpaque(false);
        bookmarkRow.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        bookmarkRow.add(bookmarkLabel, BorderLayout.WEST);
        bookmarkRow.add(bookmarkFrame, BorderLayout.CENTER);
        bookmarkRow.add(openBookmark, BorderLayout.EAST);
        bookmarks.observe(() -> {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                showBookmarks();
            } else {
                javax.swing.SwingUtilities.invokeLater(this::showBookmarks);
            }
        });
        showBookmarks();

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        for (JComponent part : new JComponent[] {fields, advancedToggle, advanced, problem, Comic.row(button),
                bookmarkRow}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            body.add(part);
        }
        advancedToggle.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        problem.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        add(Comic.plate("Neue Verbindung", Comic.YELLOW, body), BorderLayout.CENTER);
        recall();
        serverChanged();
        getAccessibleContext().setAccessibleName("Neue TN3270-Verbindung");
        // Die Liste kommt beim ersten Zeigen (nur lesen, ohne Kopplungsdialog: der steht unter Sicherheit).
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !loaded) {
                load();
            }
        });
    }

    private static void row(JPanel panel, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(3, 0, 3, 10);
        c.anchor = GridBagConstraints.WEST;
        JLabel text = Comic.text(label);
        text.setPreferredSize(new Dimension(110, text.getPreferredSize().height));
        panel.add(text, c);
        c.gridx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        if (!(field instanceof JLabel)) {
            field.setFont(Comic.regular(13f));
        }
        panel.add(field, c);
    }

    /** Lädt die KeePass-Einträge im Hintergrund; die gemerkte Wahl bleibt gewählt. */
    void load() {
        loaded = true;
        serverState.setText("Lade Einträge aus KeePass …");
        serverState.setForeground(Comic.MUTED);
        Background.run(accounts::accounts, this::show, error -> {
            serverState.setText(describe(error));
            serverState.setForeground(Comic.RED);
        });
    }

    /** Zeigt die Einträge (für Tests auch direkt). */
    void show(List<ServerAccount> list) {
        String wanted = selected() == null ? AppState.shared().get(ENTRY_KEY) : selected().entry();
        DefaultComboBoxModel<ServerAccount> items = new DefaultComboBoxModel<>();
        items.addElement(null);
        list.forEach(items::addElement);
        server.setModel(items);
        list.stream().filter(account -> account.entry().equals(wanted)).findFirst().ifPresent(server::setSelectedItem);
        serverState.setText(list.isEmpty() ? "KeePass liefert keine Einträge" : list.size() + " Einträge aus KeePass");
        serverState.setForeground(Comic.MUTED);
        serverChanged();
    }

    private ServerAccount selected() {
        return server.getSelectedItem() instanceof ServerAccount account ? account : null;
    }

    private void serverChanged() {
        ServerAccount account = selected();
        // Eine abweichende Adresse (und ein abweichender Port) gilt nur für die eben gewählte Verbindung: Wer den Server wechselt, verbindet sich
        // mit dessen Adresse, damit Zugangsdaten nie an eine vergessene, verdeckte Adresse gehen.
        if (account != shownAccount) {
            host.setText("");
            port.setText("");
            shownAccount = account;
        }
        host.setPlaceholder(account == null || account.host().isEmpty() ? "Rechnername oder Adresse"
                : account.host());
        int fallback = account != null && account.port().isPresent() ? account.port().getAsInt()
                : tls.isSelected() ? TerminalProfile.DEFAULT_TLS_PORT : TerminalProfile.DEFAULT_PORT;
        port.setPlaceholder(String.valueOf(fallback));
        autoLogin.setEnabled(account != null);
    }

    private void advanced(boolean shown) {
        advanced.setVisible(shown);
        advancedToggle.setText(shown ? "Erweitert ausblenden" : "Erweitert …");
        revalidate();
        repaint();
    }

    /** Das Profil aus Server und Feldern; wirft mit lesbarer Meldung, wenn etwas fehlt. */
    TerminalProfile profile() {
        ServerAccount account = selected();
        String target = host.getText().isBlank() && account != null ? account.host() : host.getText();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(account == null ? "Server wählen (KeePass-Eintrag)"
                    : "Der KeePass-Eintrag „" + account.entry() + "“ hat keine Adresse (URL)");
        }
        int fallback = account != null && account.port().isPresent() ? account.port().getAsInt()
                : tls.isSelected() ? TerminalProfile.DEFAULT_TLS_PORT : TerminalProfile.DEFAULT_PORT;
        int portNumber = number(port.getText(), fallback, "Port");
        int keepAliveSeconds = number(keepAlive.getText(), 0, "Keep-Alive");
        return new TerminalProfile(target, portNumber, (TerminalModel) model.getSelectedItem(), tls.isSelected(),
                keepAliveSeconds, codePage.getText());
    }

    /** Die Anmeldung aus dem gewählten Eintrag, gelesen erst im Hintergrund nach dem Verbinden; sonst keine. */
    TerminalSessions.LoginSource login() {
        ServerAccount account = selected();
        if (account == null || !autoLogin.isSelected()) {
            return null;
        }
        return loginFor(account.entry(), command.getText());
    }

    /** Anmeldung über einen KeePass-Eintrag; das Passwort wird erst beim Aufruf gelesen und danach gelöscht. */
    TerminalSessions.LoginSource loginFor(String entry, String startCommand) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        return () -> {
            try (ServerCredentials credentials = accounts.credentials(entry)) {
                char[] password = credentials.copyPassword();
                try {
                    return new TerminalLogin(credentials.user(), password, startCommand);
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        };
    }

    private static int number(String text, int fallback, String name) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " muss eine Zahl sein, nicht „" + trimmed + "“");
        }
    }

    void submit() {
        TerminalProfile profile;
        try {
            profile = profile();
        } catch (IllegalArgumentException e) {
            problem.setText(e.getMessage());
            return;
        }
        problem.setText(" ");
        remember();
        TerminalSessions.LoginSource login = login();
        connect.accept(new Request(profile, login, login == null ? "" : selected().entry(), command.getText().trim(),
                List.of()));
    }

    private void showBookmarks() {
        Object wanted = bookmark.getSelectedItem();
        List<TerminalBookmarks.Bookmark> list = bookmarks.all();
        DefaultComboBoxModel<TerminalBookmarks.Bookmark> items = new DefaultComboBoxModel<>();
        list.forEach(items::addElement);
        bookmark.setModel(items);
        list.stream().filter(candidate -> wanted instanceof TerminalBookmarks.Bookmark old
                && old.name().equals(candidate.name())).findFirst().ifPresent(bookmark::setSelectedItem);
        bookmarkRow.setVisible(!list.isEmpty());
        revalidate();
        repaint();
    }

    /** Öffnet das gewählte Lesezeichen wie in MainframeMate: verbinden, anmelden, Makro abspielen. */
    void openBookmark() {
        if (!(bookmark.getSelectedItem() instanceof TerminalBookmarks.Bookmark chosen)) {
            return;
        }
        problem.setText(" ");
        connect.accept(new Request(chosen.profile(), loginFor(chosen.entry(), chosen.command()), chosen.entry(),
                chosen.command(), chosen.steps()));
    }

    private void dropBookmark() {
        if (bookmark.getSelectedItem() instanceof TerminalBookmarks.Bookmark chosen
                && com.aresstack.spaceharvester.app.ui.Dialogs.confirm(this, "Lesezeichen entfernen",
                "Lesezeichen „" + chosen.name() + "“ wirklich entfernen?")) {
            bookmarks.remove(chosen.name());
        }
    }

    boolean bookmarksShown() {
        return bookmarkRow.isVisible();
    }

    String problemText() {
        return problem.getText();
    }

    String serverStateText() {
        return serverState.getText();
    }

    void focusServer() {
        server.requestFocusInWindow();
    }

    private void remember() {
        ServerAccount account = selected();
        AppState.shared().putAll(Map.of(ENTRY_KEY, account == null ? "" : account.entry(),
                MODEL_KEY, ((TerminalModel) model.getSelectedItem()).name(), TLS_KEY, String.valueOf(tls.isSelected()),
                CODE_PAGE_KEY, codePage.getText().trim(), KEEP_ALIVE_KEY, keepAlive.getText().trim(),
                AUTO_LOGIN_KEY, String.valueOf(autoLogin.isSelected()), COMMAND_KEY, command.getText().trim()));
    }

    private void recall() {
        AppState state = AppState.shared();
        String modelName = state.get(MODEL_KEY);
        for (TerminalModel candidate : TerminalModel.values()) {
            if (candidate.name().equals(modelName)) {
                model.setSelectedItem(candidate);
            }
        }
        tls.setSelected(state.getBoolean(TLS_KEY, true));
        codePage.setText(nonNull(state.get(CODE_PAGE_KEY)));
        keepAlive.setText(nonNull(state.get(KEEP_ALIVE_KEY)));
        autoLogin.setSelected(state.getBoolean(AUTO_LOGIN_KEY, true));
        String startCommand = state.get(COMMAND_KEY);
        command.setText(startCommand == null ? DEFAULT_COMMAND : startCommand);
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }

    /** Ein lesbarer Grund ohne Stacktrace. */
    static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
