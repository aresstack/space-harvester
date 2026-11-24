package com.aresstack.spaceharvester.app.ui.deck;

import static com.aresstack.spaceharvester.app.ui.deck.DeckForms.row;

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
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * „Neue Verbindung“ zum NDV-Server (Natural Development Server). Ein Server ist derselbe KeePass-Eintrag wie beim
 * Terminal (Angelo, 22.11.2026): Adresse aus dem URL-Feld, Benutzer und Passwort aus dem Eintrag. Sichtbar sind nur
 * Server und Port (Vorgabe {@value NdvProfile#DEFAULT_PORT} wie in MainframeMate; der Port des Eintrags gilt fürs
 * Terminal); ein Host von Hand und die Standard-Bibliothek stehen unter „Erweitert“. Verbunden wird erst auf „Verbinden“, das Passwort liest die Sitzung im Hintergrund.
 */
final class NdvConnectForm extends JPanel {

    static final String ENTRY_KEY = "deck.ndv.entry";
    /** Standard-Bibliothek, die nach dem Verbinden geöffnet wird (wie {@code ndvDefaultLibrary} in MainframeMate). */
    static final String LIBRARY_KEY = "deck.ndv.defaultLibrary";
    /** Ob über den Proxy der App verbunden wird; Vorgabe nein (direkt wie MainframeMate, Angelo 23.11.). */
    static final String PROXY_KEY = "deck.ndv.useProxy";
    /** Die zuletzt erfolgreich verbundene Verbindung (Eintrag, Host, Port, Bibliothek, Proxy). */
    static final String WORKING_KEY = "deck.ndv.working";
    static final String PROXY_TEXT = "Proxy der App verwenden";

    /** Verbinden mit Profil, Eintragstitel für die Anzeige und der Quelle der Anmeldung. */
    @FunctionalInterface
    interface Connect {
        void open(NdvProfile profile, String account, NdvSessions.LoginSource login);
    }

    final JComboBox<ServerAccount> server = Comic.combo(ConnectForm::accountText);
    final PlaceholderField port = new PlaceholderField(6, String.valueOf(NdvProfile.DEFAULT_PORT));
    final PlaceholderField host = new PlaceholderField(22, "aus dem KeePass-Eintrag");
    final PlaceholderField library = new PlaceholderField(12, "leer = Bibliotheksliste");
    final javax.swing.JCheckBox useProxy = new javax.swing.JCheckBox(PROXY_TEXT);
    private final JLabel problem = new JLabel(" ");
    private final JLabel serverState = Comic.muted(" ");
    private final JPanel advanced = new JPanel(new GridBagLayout());
    private final JLabel advancedToggle = new JLabel();
    private final ServerAccounts accounts;
    private final Connect connect;
    private boolean loaded;
    private ServerAccount shownAccount;

    NdvConnectForm(ServerAccounts accounts, Connect connect) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.connect = connect;
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
        advanced.setOpaque(false);
        row(advanced, 0, "Host", host);
        row(advanced, 1, "Standard-Bibliothek", library);
        String rememberedLibrary = AppState.shared().get(LIBRARY_KEY);
        library.setText(rememberedLibrary == null ? "" : rememberedLibrary);
        library.setToolTipText("Wird nach dem Verbinden geöffnet; leer zeigt die Bibliotheksliste");
        useProxy.setOpaque(false);
        useProxy.setFont(Comic.regular(12.5f));
        useProxy.setSelected(Boolean.parseBoolean(AppState.shared().get(PROXY_KEY)));
        useProxy.setToolTipText("Aus: direkt zum NDV-Server wie in MainframeMate. An: über den Proxy aus Einstellungen › "
                + "Netzwerk (HTTP-Tunnel), etwa wenn der Server nur über den Proxy erreichbar ist");
        row(advanced, 2, "", useProxy);
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
        problem.setFont(Comic.regular(12.5f));
        problem.setForeground(Comic.RED);
        var button = Comic.primary("Verbinden", "Am NDV-Server anmelden, mit Benutzer und Passwort aus KeePass (Eingabe)");
        button.addActionListener(e -> submit());
        host.addActionListener(e -> submit());
        port.addActionListener(e -> submit());
        library.addActionListener(e -> submit());

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        for (JComponent part : new JComponent[] {fields, advancedToggle, advanced, problem, Comic.row(button)}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            body.add(part);
        }
        advancedToggle.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        problem.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        add(Comic.plate("Neue NDV-Verbindung", Comic.YELLOW, body), BorderLayout.CENTER);
        serverChanged();
        getAccessibleContext().setAccessibleName("Neue NDV-Verbindung");
        // Die Liste kommt beim ersten Zeigen (nur lesen, ohne Kopplungsdialog: der steht unter Sicherheit).
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !loaded) {
                load();
            }
        });
    }

    /** Lädt die KeePass-Einträge im Hintergrund; die gemerkte Wahl bleibt gewählt. */
    void load() {
        loaded = true;
        serverState.setText("Lade Einträge aus KeePass …");
        serverState.setForeground(Comic.MUTED);
        Background.run(accounts::accounts, this::show, error -> {
            serverState.setText(ConnectForm.describe(error));
            serverState.setForeground(Comic.RED);
        });
    }

    /** Zeigt die Einträge (für Tests auch direkt). Ohne eigene Wahl gilt die des Terminals: derselbe Host. */
    void show(List<ServerAccount> list) {
        String wanted = selected() != null ? selected().entry() : remembered();
        DefaultComboBoxModel<ServerAccount> items = new DefaultComboBoxModel<>();
        items.addElement(null);
        list.forEach(items::addElement);
        server.setModel(items);
        list.stream().filter(account -> account.entry().equals(wanted)).findFirst().ifPresent(server::setSelectedItem);
        serverState.setText(list.isEmpty() ? "KeePass liefert keine Einträge"
                : list.size() == 1 ? "1 Eintrag aus KeePass" : list.size() + " Einträge aus KeePass");
        serverState.setForeground(Comic.MUTED);
        serverChanged();
    }

    /** Der KeePass-Eintrag, mit dem „Neue Verbindung“ von selbst anmelden würde (für den Sperrschutz). */
    static String rememberedEntry() {
        return working().map(Working::account).orElseGet(NdvConnectForm::remembered);
    }

    private static String remembered() {
        String own = AppState.shared().get(ENTRY_KEY);
        return own == null || own.isBlank() ? AppState.shared().get(ConnectForm.ENTRY_KEY) : own;
    }

    private ServerAccount selected() {
        return server.getSelectedItem() instanceof ServerAccount account ? account : null;
    }

    private void serverChanged() {
        ServerAccount account = selected();
        // Abweichender Host und Port gelten nur für den eben gewählten Server (wie ConnectForm): Wer den Server
        // wechselt, verbindet sich mit dessen Adresse, damit Zugangsdaten nie an eine vergessene Adresse gehen.
        if (account != shownAccount) {
            host.setText("");
            port.setText("");
            shownAccount = account;
        }
        host.setPlaceholder(account == null || account.host().isEmpty() ? "Rechnername oder Adresse"
                : account.host());
    }

    private void advanced(boolean shown) {
        advanced.setVisible(shown);
        advancedToggle.setText(shown ? "Erweitert ausblenden" : "Erweitert …");
        revalidate();
        repaint();
    }

    /** Das Profil aus Server und Feldern; wirft mit lesbarer Meldung, wenn etwas fehlt. */
    NdvProfile profile() {
        ServerAccount account = selected();
        if (account == null) {
            throw new IllegalArgumentException("Server wählen (KeePass-Eintrag mit Benutzer und Passwort)");
        }
        String target = host.getText().isBlank() ? account.host() : host.getText();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Der KeePass-Eintrag „" + account.entry() + "“ hat keine Adresse (URL)");
        }
        if (account.user().isEmpty()) {
            throw new IllegalArgumentException("Der KeePass-Eintrag „" + account.entry() + "“ hat keinen Benutzer");
        }
        return new NdvProfile(target, number(port.getText(), NdvProfile.DEFAULT_PORT), library.getText(),
                useProxy.isSelected());
    }

    private static int number(String text, int fallback) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port muss eine Zahl sein, nicht „" + trimmed + "“");
        }
    }

    void submit() {
        NdvProfile profile;
        try {
            profile = profile();
        } catch (IllegalArgumentException e) {
            problem.setText(e.getMessage());
            return;
        }
        problem.setText(" ");
        ServerAccount account = selected();
        // Nur der Server wird gemerkt; ein abweichender Host oder Port gilt nur für diese Wahl.
        AppState.shared().put(ENTRY_KEY, account.entry());
        AppState.shared().put(LIBRARY_KEY, profile.defaultLibrary());
        AppState.shared().put(PROXY_KEY, String.valueOf(profile.useProxy()));
        String entry = account.entry();
        connect.open(profile, entry, () -> accounts.credentials(entry));
    }

    /** Breite des Terminal-Formulars, einmal gemessen: die Formulare des Deck sehen gleich aus (UX 23.11.). */
    private static int terminalWidth;

    @Override
    public Dimension getPreferredSize() {
        Dimension own = super.getPreferredSize();
        if (isPreferredSizeSet()) {
            return own;
        }
        if (terminalWidth == 0) {
            terminalWidth = new ConnectForm(accounts, (profile, login) -> { }).getPreferredSize().width;
        }
        return new Dimension(Math.max(own.width, terminalWidth), own.height);
    }

    /** Zeigt im Formular, warum das Verbinden misslang. */
    void problem(String reason) {
        problem.setText(reason == null || reason.isBlank() ? " " : reason);
    }

    /** Eine Verbindung, die schon einmal geklappt hat: damit verbindet „Neue Verbindung“ sofort. */
    record Working(NdvProfile profile, String account) {
    }

    /** Merkt nach dem ersten erfolgreichen Verbinden Server, Adresse, Port, Bibliothek und Proxy-Wahl. */
    static void rememberWorking(NdvProfile profile, String account) {
        if (account == null || account.isBlank()) {
            return;
        }
        AppState.shared().put(WORKING_KEY, String.join("\t", account, profile.host(), String.valueOf(profile.port()),
                profile.defaultLibrary() == null ? "" : profile.defaultLibrary(), String.valueOf(profile.useProxy())));
    }

    /**
     * Ohne erfolgreiche Verbindung: der gemerkte Server (sonst der des Terminals) aus den KeePass-Einträgen, mit
     * Standard-Port, gemerkter Bibliothek und Proxy-Wahl; leer, wenn er fehlt oder keine Adresse oder keinen Benutzer
     * hat (dann braucht es das Formular).
     */
    static java.util.Optional<Working> fromRemembered(List<ServerAccount> list) {
        String wanted = remembered();
        if (wanted == null || wanted.isBlank() || list == null) {
            return java.util.Optional.empty();
        }
        return list.stream().filter(account -> account.entry().equals(wanted)).findFirst()
                .filter(account -> account.host() != null && !account.host().isBlank() && !account.user().isEmpty())
                .map(account -> new Working(new NdvProfile(account.host(), NdvProfile.DEFAULT_PORT,
                        AppState.shared().get(LIBRARY_KEY), Boolean.parseBoolean(AppState.shared().get(PROXY_KEY))),
                        account.entry()));
    }

    static java.util.Optional<Working> working() {
        String stored = AppState.shared().get(WORKING_KEY);
        if (stored == null || stored.isBlank()) {
            return java.util.Optional.empty();
        }
        String[] parts = stored.split("\t", -1);
        try {
            return java.util.Optional.of(new Working(new NdvProfile(parts[1], Integer.parseInt(parts[2]), parts[3],
                    Boolean.parseBoolean(parts[4])), parts[0]));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
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

}
