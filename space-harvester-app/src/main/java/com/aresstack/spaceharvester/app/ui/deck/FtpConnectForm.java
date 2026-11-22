package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * „Neue Verbindung“ zum FTP-Host. Ein Server ist ein KeePass-Eintrag, derselbe wie für Terminal und NDV: Adresse aus dem
 * URL-Feld (nur der Rechner; der Port dort gilt dem Terminal), Benutzer und Passwort aus dem Eintrag. Sichtbar ist nur
 * der Server; Host von Hand, Port, Zeichensatz und Zeitlimit stehen unter „Erweitert“. Verbunden wird erst auf
 * „Verbinden“; das Passwort liest die Sitzung erst beim Anmelden.
 */
final class FtpConnectForm extends JPanel {

    /** Was „Verbinden“ auslöst: Profil, KeePass-Eintrag. */
    interface Connect {
        void connect(FtpProfile profile, String entry);
    }

    static final String ENTRY_KEY = "deck.ftp.entry";
    static final String HOST_KEY = "deck.ftp.host";
    static final String PORT_KEY = "deck.ftp.port";
    static final String ENCODING_KEY = "deck.ftp.encoding";
    static final String TIMEOUT_KEY = "deck.ftp.timeout";

    final JComboBox<ServerAccount> server = new JComboBox<>();
    final PlaceholderField host = new PlaceholderField(22, "aus dem KeePass-Eintrag");
    final PlaceholderField port = new PlaceholderField(6, String.valueOf(FtpProfile.DEFAULT_PORT));
    final PlaceholderField encoding = new PlaceholderField(10, FtpProfile.DEFAULT_ENCODING);
    final PlaceholderField timeout = new PlaceholderField(6, String.valueOf(FtpProfile.DEFAULT_TIMEOUT_SECONDS));
    private final JLabel problem = new JLabel(" ");
    private final JLabel serverState = Comic.muted(" ");
    private final JPanel advanced = new JPanel(new GridBagLayout());
    private final JLabel advancedToggle = new JLabel();
    private final ServerAccounts accounts;
    private final Connect connect;
    private boolean loaded;

    FtpConnectForm(ServerAccounts accounts, Connect connect) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.connect = connect;
        setOpaque(false);
        server.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                                   int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof ServerAccount account) {
                    setText(account.toString() + (account.user().isEmpty() ? "" : " · " + account.user()));
                } else {
                    setText("KeePass-Eintrag wählen …");
                }
                return this;
            }
        });
        server.addActionListener(e -> serverChanged());
        IconButton reload = new IconButton(Glyph.REFRESH, "Einträge neu aus KeePass laden");
        reload.addActionListener(e -> load());
        InputFrame serverFrame = new InputFrame(server);
        serverFrame.addButton(reload);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        row(fields, 0, "Server", serverFrame);
        row(fields, 1, "", serverState);
        advanced.setOpaque(false);
        row(advanced, 0, "Host", host);
        row(advanced, 1, "Port", port);
        row(advanced, 2, "Zeichensatz", encoding);
        row(advanced, 3, "Zeitlimit (s)", timeout);
        advanced.setVisible(false);
        advancedToggle.setFont(Comic.regular(12.5f));
        advancedToggle.setForeground(Comic.BLUE);
        advancedToggle.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        advancedToggle.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                advanced(!advanced.isVisible());
            }
        });
        advanced(false);
        problem.setFont(Comic.regular(12.5f));
        problem.setForeground(Comic.RED);
        var button = Comic.primary("Verbinden", "FTP-Verbindung zu diesem Server öffnen (Eingabe)");
        button.addActionListener(e -> submit());
        for (PlaceholderField field : new PlaceholderField[] {host, port, encoding, timeout}) {
            field.addActionListener(e -> submit());
        }

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        for (JComponent part : new JComponent[] {fields, advancedToggle, advanced, problem, Comic.row(button)}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            body.add(part);
        }
        advancedToggle.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        problem.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        add(Comic.plate("Neue FTP-Verbindung", Comic.YELLOW, body), BorderLayout.CENTER);
        recall();
        serverChanged();
        getAccessibleContext().setAccessibleName("Neue FTP-Verbindung");
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
            serverState.setText(FtpWorkspace.describe(error));
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
    FtpProfile profile() {
        ServerAccount account = selected();
        if (account == null) {
            throw new IllegalArgumentException("Server wählen (KeePass-Eintrag mit Benutzer und Passwort)");
        }
        String target = host.getText().isBlank() ? account.host() : host.getText();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Der KeePass-Eintrag „" + account.entry() + "“ hat keine Adresse (URL)");
        }
        return new FtpProfile(target, number(port.getText(), FtpProfile.DEFAULT_PORT, "Port"), encoding.getText(),
                number(timeout.getText(), FtpProfile.DEFAULT_TIMEOUT_SECONDS, "Zeitlimit"));
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
        FtpProfile profile;
        try {
            profile = profile();
        } catch (IllegalArgumentException e) {
            problem.setText(e.getMessage());
            return;
        }
        problem.setText(" ");
        remember();
        connect.connect(profile, selected().entry());
    }

    String problemText() {
        return problem.getText();
    }

    void focusServer() {
        server.requestFocusInWindow();
    }

    private void remember() {
        ServerAccount account = selected();
        AppState.shared().putAll(Map.of(ENTRY_KEY, account == null ? "" : account.entry(),
                HOST_KEY, host.getText().trim(), PORT_KEY, port.getText().trim(),
                ENCODING_KEY, encoding.getText().trim(), TIMEOUT_KEY, timeout.getText().trim()));
    }

    private void recall() {
        AppState state = AppState.shared();
        host.setText(nonNull(state.get(HOST_KEY)));
        port.setText(nonNull(state.get(PORT_KEY)));
        encoding.setText(nonNull(state.get(ENCODING_KEY)));
        timeout.setText(nonNull(state.get(TIMEOUT_KEY)));
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
