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
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.RetryPolicy;
import com.aresstack.spaceharvester.deck.ftp.TextTransform;
import com.aresstack.spaceharvester.deck.ftp.TransferOptions;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.JCheckBox;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * „Neue Verbindung“ zum FTP-Host. Ein Server ist ein KeePass-Eintrag, derselbe wie für Terminal und NDV: Adresse aus dem
 * URL-Feld (nur der Rechner; der Port dort gilt dem Terminal), Benutzer und Passwort aus dem Eintrag. Sichtbar ist nur
 * der Server. Unter „Erweitert“ stehen wie in den FTP-Einstellungen von MainframeMate: Host von Hand, Port, Zeichensatz,
 * die drei Zeitlimits (Verbindung, Antworten, Daten), Start-HLQ, die Übertragung (Datei-Typ, Textformat, Struktur,
 * Modus), die Wiederholung lesender Aufträge und die Ermittlung der DD-Namen im Spool. Alles wird gemerkt. Verbunden
 * wird erst auf „Verbinden“; das Passwort liest die Sitzung erst beim Anmelden.
 */
final class FtpConnectForm extends JPanel {

    /** Was „Verbinden“ auslöst: Profil, KeePass-Eintrag. */
    interface Connect {
        void connect(FtpProfile profile, String entry);
    }

    /** Breite des Server-Felds wie im Formular des Terminals. */
    static final int FIELD_WIDTH = 415;
    static final String ENTRY_KEY = "deck.ftp.entry";
    /** Die gemerkte Verbindung hat einmal geklappt: „Neue Verbindung“ verbindet dann ohne Formular. */
    static final String CONFIGURED_KEY = "deck.ftp.configured";
    static final String HOST_KEY = "deck.ftp.host";
    static final String PORT_KEY = "deck.ftp.port";
    static final String ENCODING_KEY = "deck.ftp.encoding";
    static final String TIMEOUT_KEY = "deck.ftp.timeout";
    static final String CONTROL_TIMEOUT_KEY = "deck.ftp.timeout.control";
    static final String DATA_TIMEOUT_KEY = "deck.ftp.timeout.data";
    static final String HLQ_KEY = "deck.ftp.hlq";
    static final String SYSLIB_KEY = "deck.ftp.syslib";
    static final String FILE_TYPE_KEY = "deck.ftp.transfer.type";
    static final String TEXT_FORMAT_KEY = "deck.ftp.transfer.format";
    static final String STRUCTURE_KEY = "deck.ftp.transfer.structure";
    static final String MODE_KEY = "deck.ftp.transfer.mode";
    static final String ATTEMPTS_KEY = "deck.ftp.retry.attempts";
    static final String BACKOFF_KEY = "deck.ftp.retry.backoff";
    static final String EXPONENTIAL_KEY = "deck.ftp.retry.exponential";
    static final String MAX_BACKOFF_KEY = "deck.ftp.retry.max";
    static final String ON_TIMEOUT_KEY = "deck.ftp.retry.timeout";
    static final String ON_CONNECTION_KEY = "deck.ftp.retry.connection";
    static final String CODES_KEY = "deck.ftp.retry.codes";
    static final String SPOOL_MODE_KEY = "deck.ftp.spool.mode";
    static final String SPOOL_CONNECTIONS_KEY = "deck.ftp.spool.connections";
    static final String SPOOL_PROBE_KEY = "deck.ftp.spool.probe";
    static final String PROXY_KEY = "deck.ftp.proxy";
    static final String RECORD_MARKER_KEY = "deck.ftp.transform.line-ending";
    static final String FINAL_NEWLINE_KEY = "deck.ftp.transform.remove-final-newline";
    static final String FILE_END_KEY = "deck.ftp.transform.file-end-marker";
    static final String PADDING_KEY = "deck.ftp.transform.padding";

    final JComboBox<ServerAccount> server = Comic.combo(ConnectForm::accountText);
    final PlaceholderField host = new PlaceholderField(22, "aus dem KeePass-Eintrag");
    final PlaceholderField port = new PlaceholderField(6, String.valueOf(FtpProfile.DEFAULT_PORT));
    final PlaceholderField encoding = new PlaceholderField(10, FtpProfile.DEFAULT_ENCODING);
    final PlaceholderField timeout = new PlaceholderField(6, String.valueOf(FtpProfile.DEFAULT_TIMEOUT_SECONDS));
    final PlaceholderField controlTimeout = new PlaceholderField(6, "wie oben");
    final PlaceholderField dataTimeout = new PlaceholderField(6, "wie oben");
    final PlaceholderField startHlq = new PlaceholderField(12, "Anmeldename");
    final PlaceholderField syslib = new PlaceholderField(22, "HLQ.COPYLIB, HLQ.CPY");
    final JCheckBox useProxy = Comic.checkBox("Proxy verwenden");
    final JComboBox<TransferOptions.FileType> fileType = combo(TransferOptions.FileType.values(),
            TransferOptions.FileType::label);
    final JComboBox<TransferOptions.TextFormat> textFormat = combo(TransferOptions.TextFormat.values(),
            TransferOptions.TextFormat::label);
    final JComboBox<TransferOptions.Structure> structure = combo(TransferOptions.Structure.values(),
            TransferOptions.Structure::label);
    final JComboBox<TransferOptions.TransferMode> transferMode = combo(java.util.Arrays.stream(
            TransferOptions.TransferMode.values()).filter(TransferOptions.TransferMode::supported)
            .toArray(TransferOptions.TransferMode[]::new), TransferOptions.TransferMode::label);
    final PlaceholderField attempts = new PlaceholderField(4, String.valueOf(RetryPolicy.DEFAULT.maxAttempts()));
    final PlaceholderField backoff = new PlaceholderField(6, "0");
    final JCheckBox exponential = Comic.checkBox("Wartezeit jedes Mal verdoppeln");
    final PlaceholderField maxBackoff = new PlaceholderField(6, "unbegrenzt");
    final JCheckBox onTimeout = Comic.checkBox("bei Zeitüberschreitung");
    final JCheckBox onConnection = Comic.checkBox("bei Verbindungsabbruch");
    final PlaceholderField codes = new PlaceholderField(14, "z.B. 421, 425, 426");
    final JComboBox<SpoolNaming.Mode> spoolMode = combo(SpoolNaming.Mode.values(), SpoolNaming.Mode::label);
    final PlaceholderField spoolConnections = new PlaceholderField(4, "1");
    final JCheckBox spoolProbe = Comic.checkBox("DD-Namen danach einzeln nachladen");
    // Datenumwandlung wie MainframeMate (Einstellungen → Datenumwandlung), editierbar mit denselben Vorschlägen.
    final JComboBox<String> recordMarker = hexCombo(TextTransform.RECORD_MARKER_PRESETS);
    final JCheckBox removeFinalNewline = Comic.checkBox("Letzten Zeilenumbruch ausblenden");
    final JComboBox<String> fileEndMarker = hexCombo(TextTransform.FILE_END_PRESETS);
    final JComboBox<String> padding = hexCombo(TextTransform.PADDING_PRESETS);
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
        server.addActionListener(e -> serverChanged());
        IconButton reload = new IconButton(Glyph.REFRESH, "Einträge neu aus KeePass laden");
        reload.addActionListener(e -> load());
        InputFrame serverFrame = new InputFrame(server);
        serverFrame.addButton(reload);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        row(fields, 0, "Server", serverFrame);
        // So breit wie im Formular des Terminals, damit die Formulare des Command Deck gleich aussehen und der Eintrag
        // lesbar ist.
        serverFrame.setPreferredSize(new Dimension(FIELD_WIDTH, serverFrame.getPreferredSize().height));
        row(fields, 1, "", serverState);
        advanced.setOpaque(false);
        // Zwei Spalten, damit „Verbinden“ auch bei kleinem Fenster sichtbar bleibt: links Verbindung und Übertragung,
        // rechts Wiederholung und Spool.
        JPanel left = new JPanel(new GridBagLayout());
        JPanel right = new JPanel(new GridBagLayout());
        left.setOpaque(false);
        right.setOpaque(false);
        row(left, 0, "Host", host);
        row(left, 1, "Port", port);
        row(left, 2, "Zeichensatz", encoding);
        row(left, 3, "Zeitlimit (s)", timeout);
        row(left, 4, "Antworten (s)", controlTimeout);
        row(left, 5, "Daten (s)", dataTimeout);
        row(left, 6, "Start-HLQ", startHlq);
        row(left, 7, "", useProxy);
        section(left, 8, "Übertragung");
        row(left, 9, "Datei-Typ", fileType);
        row(left, 10, "Textformat", textFormat);
        row(left, 11, "Struktur", structure);
        row(left, 12, "Modus", transferMode);
        section(left, 13, "COBOL");
        row(left, 14, "SYSLIB", syslib);
        syslib.setToolTipText("Absolute Copybook-Datasets, durch Komma getrennt, in Suchreihenfolge");
        section(right, 0, "Wiederholung lesender Aufträge");
        row(right, 1, "Versuche", attempts);
        row(right, 2, "Wartezeit (ms)", backoff);
        row(right, 3, "", exponential);
        row(right, 4, "Höchstens (ms)", maxBackoff);
        row(right, 5, "Wiederholen", onTimeout);
        row(right, 6, "", onConnection);
        row(right, 7, "Antwortcodes", codes);
        section(right, 8, "Spool (JES)");
        row(right, 9, "DD-Namen", spoolMode);
        row(right, 10, "Verbindungen", spoolConnections);
        row(right, 11, "", spoolProbe);
        section(right, 12, "Datenumwandlung");
        row(right, 13, "Zeilenumbruch", recordMarker);
        row(right, 14, "", removeFinalNewline);
        row(right, 15, "Datei-Ende", fileEndMarker);
        row(right, 16, "Padding Byte", padding);
        GridBagConstraints column = new GridBagConstraints();
        column.anchor = GridBagConstraints.NORTHWEST;
        column.fill = GridBagConstraints.HORIZONTAL;
        column.weightx = 1;
        column.insets = new Insets(0, 0, 0, 20);
        advanced.add(left, column);
        column.gridx = 1;
        column.insets = new Insets(0, 0, 0, 0);
        advanced.add(right, column);
        timeout.setToolTipText("Zeitlimit für den Verbindungsaufbau in Sekunden (0 = keins)");
        controlTimeout.setToolTipText("Wie lange auf eine Antwort des Hosts gewartet wird; leer = wie das Zeitlimit");
        dataTimeout.setToolTipText("Wie lange eine Datenübertragung stocken darf; leer = wie das Zeitlimit");
        startHlq.setToolTipText("Erster Qualifier nach dem Anmelden auf MVS; leer = Anmeldename");
        fileType.setToolTipText("TYPE: ASCII (A), EBCDIC (E), Binär (I) oder Lokal (L)");
        textFormat.setToolTipText("Formatangabe zu TYPE A/E: Standard (N), Telnet (T), Steuerzeichen (C)");
        structure.setToolTipText("STRU: Automatisch = Satzstruktur auf MVS, sonst Datei");
        transferMode.setToolTipText("MODE: Stream (S). Block und Komprimiert bietet MainframeMate an, Commons Net kann "
                + "sie aber nicht rahmen – die Daten kämen verfälscht an");
        attempts.setToolTipText("Wie oft ein lesender Auftrag höchstens versucht wird (1–10); Schreiben nie doppelt");
        backoff.setToolTipText("Pause vor dem nächsten Versuch in Millisekunden");
        maxBackoff.setToolTipText("Längste Pause beim Verdoppeln; leer = unbegrenzt");
        codes.setToolTipText("FTP-Antwortcodes, bei denen wiederholt wird, durch Komma getrennt");
        spoolMode.setToolTipText("Schnell: Spool-Liste des Hosts; Einzeln prüfen: Dateien nacheinander abfragen; "
                + "Aus: gesamten Output laden");
        spoolConnections.setToolTipText("Parallele Verbindungen beim Nachladen der DD-Namen (1–10)");
        onTimeout.setOpaque(false);
        onConnection.setOpaque(false);
        exponential.setOpaque(false);
        spoolProbe.setOpaque(false);
        removeFinalNewline.setOpaque(false);
        recordMarker.setToolTipText("Zeilenumbruch des Servers (Hex): Satzende bei Satzstruktur, Standard "
                + TextTransform.DEFAULT_RECORD_MARKER + "; leer = keins");
        removeFinalNewline.setToolTipText("Den abschließenden Umbruch nach der Umwandlung entfernen (Standard an)");
        fileEndMarker.setToolTipText("Datei-Ende-Kennung (Hex), beim Lesen entfernt und beim Schreiben angehängt, "
                + "Standard " + TextTransform.DEFAULT_FILE_END_MARKER + "; leer = keine");
        padding.setToolTipText("Füllbyte (Hex), das beim Lesen komplett entfernt wird, Standard "
                + TextTransform.DEFAULT_PADDING + "; leer = keins");
        useProxy.setOpaque(false);
        useProxy.setToolTipText("An (Vorgabe): Proxy-Einstellungen von Java/System wie bisher; aus: immer direkt zum "
                + "Host, auch an einem SOCKS-Proxy vorbei");
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
        var button = Comic.primary("Verbinden", "FTP-Verbindung zu diesem Server öffnen (Eingabe)");
        button.addActionListener(e -> submit());
        for (PlaceholderField field : new PlaceholderField[] {host, port, encoding, timeout, controlTimeout,
                dataTimeout, startHlq, attempts, backoff, maxBackoff, codes, spoolConnections}) {
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

    private static void section(JPanel panel, int y, String title) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.gridwidth = 2;
        c.insets = new Insets(10, 0, 2, 0);
        c.anchor = GridBagConstraints.WEST;
        panel.add(Comic.caption(title), c);
    }

    private static <E> JComboBox<E> combo(E[] values, Function<E, String> label) {
        JComboBox<E> box = Comic.combo();
        for (E value : values) {
            box.addItem(value);
        }
        box.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            @SuppressWarnings("unchecked")
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                                   int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value != null) {
                    setText(label.apply((E) value));
                }
                return this;
            }
        });
        return box;
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
        show(list, selected() == null ? AppState.shared().get(ENTRY_KEY) : selected().entry());
    }

    private void show(List<ServerAccount> list, String wanted) {
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

    void advanced(boolean shown) {
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
        int connectSeconds = number(timeout.getText(), FtpProfile.DEFAULT_TIMEOUT_SECONDS, "Zeitlimit");
        TransferOptions transfer = new TransferOptions((TransferOptions.FileType) fileType.getSelectedItem(),
                (TransferOptions.TextFormat) textFormat.getSelectedItem(),
                (TransferOptions.Structure) structure.getSelectedItem(),
                (TransferOptions.TransferMode) transferMode.getSelectedItem(), transform());
        RetryPolicy retry = new RetryPolicy(number(attempts.getText(), RetryPolicy.DEFAULT.maxAttempts(), "Versuche"),
                number(backoff.getText(), 0, "Wartezeit"), exponential.isSelected(),
                number(maxBackoff.getText(), 0, "Höchste Wartezeit"), onTimeout.isSelected(),
                onConnection.isSelected(), RetryPolicy.parseCodes(codes.getText()));
        SpoolNaming naming = new SpoolNaming((SpoolNaming.Mode) spoolMode.getSelectedItem(),
                number(spoolConnections.getText(), 1, "Verbindungen"), spoolProbe.isSelected());
        return new FtpProfile(target, number(port.getText(), FtpProfile.DEFAULT_PORT, "Port"), encoding.getText(),
                connectSeconds, number(controlTimeout.getText(), connectSeconds, "Zeitlimit Antworten"),
                number(dataTimeout.getText(), connectSeconds, "Zeitlimit Daten"), transfer, retry, startHlq.getText(),
                naming, useProxy.isSelected()).withSyslib(java.util.Arrays.asList(syslib.getText().split(",")));
    }

    /** Die Datenumwandlung aus den Feldern (leer = aus, wie in MainframeMate). */
    TextTransform transform() {
        return new TextTransform(text(recordMarker), text(fileEndMarker), text(padding),
                removeFinalNewline.isSelected());
    }

    private static String text(JComboBox<String> box) {
        Object value = box.isEditable() && box.getEditor() != null && box.getEditor().getItem() != null
                ? box.getEditor().getItem() : box.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private static JComboBox<String> hexCombo(java.util.List<String> presets) {
        JComboBox<String> box = Comic.combo();
        presets.forEach(box::addItem);
        box.setEditable(true);
        return box;
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

    /** Verbindet mit Server und Feldern; {@code false} mit lesbarem Grund in {@link #problemText()}. */
    boolean submit() {
        FtpProfile profile;
        try {
            profile = profile();
        } catch (IllegalArgumentException e) {
            problem(e.getMessage());
            return false;
        }
        problem.setText(" ");
        remember();
        connect.connect(profile, selected().entry());
        return true;
    }

    /** Hat die gemerkte Verbindung schon einmal geklappt (und ist ein Eintrag gemerkt)? */
    static boolean isConfigured() {
        AppState state = AppState.shared();
        return state.getBoolean(CONFIGURED_KEY, false) && !nonNull(state.get(ENTRY_KEY)).isBlank();
    }

    /** Der gemerkte KeePass-Eintrag, leer wenn keiner. */
    static String rememberedEntry() {
        return nonNull(AppState.shared().get(ENTRY_KEY));
    }

    /** Die gemerkte Verbindung hat geklappt. */
    static void configured() {
        AppState.shared().put(CONFIGURED_KEY, "true");
    }

    /**
     * „Neue Verbindung“ ohne Formular: gemerkte Werte und gemerkter KeePass-Eintrag (Liste im Hintergrund geladen).
     * Scheitert schon das, meldet {@code failed} den Grund; das Formular zeigt ihn dann.
     */
    void connectDirectly(java.util.function.Consumer<String> failed) {
        recall();
        loaded = true;
        String wanted = AppState.shared().get(ENTRY_KEY);
        serverState.setText("Lade Einträge aus KeePass …");
        serverState.setForeground(Comic.MUTED);
        Background.run(accounts::accounts, list -> {
            show(list, wanted);
            if (selected() == null) {
                problem("Der gemerkte KeePass-Eintrag „" + nonNull(wanted) + "“ fehlt");
                failed.accept(problemText());
            } else if (!submit()) {
                failed.accept(problemText());
            }
        }, error -> {
            serverState.setText(FtpWorkspace.describe(error));
            serverState.setForeground(Comic.RED);
            problem("KeePass nicht lesbar: " + FtpWorkspace.describe(error));
            failed.accept(problemText());
        });
    }

    /** Zeigt, warum es nicht geklappt hat (auch für eine gescheiterte Verbindung). */
    void problem(String text) {
        problem.setText(text == null || text.isBlank() ? " " : text);
    }

    String problemText() {
        return problem.getText();
    }

    void focusServer() {
        server.requestFocusInWindow();
    }

    private void remember() {
        ServerAccount account = selected();
        Map<String, String> values = new HashMap<>();
        values.put(ENTRY_KEY, account == null ? "" : account.entry());
        values.put(HOST_KEY, host.getText().trim());
        values.put(PORT_KEY, port.getText().trim());
        values.put(ENCODING_KEY, encoding.getText().trim());
        values.put(TIMEOUT_KEY, timeout.getText().trim());
        values.put(CONTROL_TIMEOUT_KEY, controlTimeout.getText().trim());
        values.put(DATA_TIMEOUT_KEY, dataTimeout.getText().trim());
        values.put(HLQ_KEY, startHlq.getText().trim());
        values.put(SYSLIB_KEY, syslib.getText().trim());
        values.put(FILE_TYPE_KEY, ((Enum<?>) fileType.getSelectedItem()).name());
        values.put(TEXT_FORMAT_KEY, ((Enum<?>) textFormat.getSelectedItem()).name());
        values.put(STRUCTURE_KEY, ((Enum<?>) structure.getSelectedItem()).name());
        values.put(MODE_KEY, ((Enum<?>) transferMode.getSelectedItem()).name());
        values.put(ATTEMPTS_KEY, attempts.getText().trim());
        values.put(BACKOFF_KEY, backoff.getText().trim());
        values.put(EXPONENTIAL_KEY, String.valueOf(exponential.isSelected()));
        values.put(MAX_BACKOFF_KEY, maxBackoff.getText().trim());
        values.put(ON_TIMEOUT_KEY, String.valueOf(onTimeout.isSelected()));
        values.put(ON_CONNECTION_KEY, String.valueOf(onConnection.isSelected()));
        values.put(CODES_KEY, codes.getText().trim());
        values.put(SPOOL_MODE_KEY, ((Enum<?>) spoolMode.getSelectedItem()).name());
        values.put(SPOOL_CONNECTIONS_KEY, spoolConnections.getText().trim());
        values.put(SPOOL_PROBE_KEY, String.valueOf(spoolProbe.isSelected()));
        values.put(PROXY_KEY, String.valueOf(useProxy.isSelected()));
        TextTransform transform = transform();
        values.put(RECORD_MARKER_KEY, transform.recordMarker());
        values.put(FINAL_NEWLINE_KEY, String.valueOf(transform.removeFinalNewline()));
        values.put(FILE_END_KEY, transform.fileEndMarker());
        values.put(PADDING_KEY, transform.padding());
        AppState.shared().putAll(values);
    }

    private void recall() {
        AppState state = AppState.shared();
        host.setText(nonNull(state.get(HOST_KEY)));
        port.setText(nonNull(state.get(PORT_KEY)));
        encoding.setText(nonNull(state.get(ENCODING_KEY)));
        timeout.setText(nonNull(state.get(TIMEOUT_KEY)));
        controlTimeout.setText(nonNull(state.get(CONTROL_TIMEOUT_KEY)));
        dataTimeout.setText(nonNull(state.get(DATA_TIMEOUT_KEY)));
        startHlq.setText(nonNull(state.get(HLQ_KEY)));
        syslib.setText(nonNull(state.get(SYSLIB_KEY)));
        select(fileType, TransferOptions.FileType.class, state.get(FILE_TYPE_KEY), TransferOptions.DEFAULT.fileType());
        select(textFormat, TransferOptions.TextFormat.class, state.get(TEXT_FORMAT_KEY),
                TransferOptions.DEFAULT.textFormat());
        select(structure, TransferOptions.Structure.class, state.get(STRUCTURE_KEY),
                TransferOptions.DEFAULT.structure());
        select(transferMode, TransferOptions.TransferMode.class, state.get(MODE_KEY),
                TransferOptions.DEFAULT.transferMode());
        attempts.setText(nonNull(state.get(ATTEMPTS_KEY)));
        backoff.setText(nonNull(state.get(BACKOFF_KEY)));
        exponential.setSelected(flag(state.get(EXPONENTIAL_KEY), RetryPolicy.DEFAULT.exponential()));
        maxBackoff.setText(nonNull(state.get(MAX_BACKOFF_KEY)));
        onTimeout.setSelected(flag(state.get(ON_TIMEOUT_KEY), RetryPolicy.DEFAULT.onTimeout()));
        onConnection.setSelected(flag(state.get(ON_CONNECTION_KEY), RetryPolicy.DEFAULT.onConnectionError()));
        codes.setText(nonNull(state.get(CODES_KEY)));
        select(spoolMode, SpoolNaming.Mode.class, state.get(SPOOL_MODE_KEY), SpoolNaming.DEFAULT.mode());
        spoolConnections.setText(nonNull(state.get(SPOOL_CONNECTIONS_KEY)));
        spoolProbe.setSelected(flag(state.get(SPOOL_PROBE_KEY), SpoolNaming.DEFAULT.backgroundProbe()));
        useProxy.setSelected(flag(state.get(PROXY_KEY), true));
        // Nie gespeichert = Standardwerte von MainframeMate; gespeichert leer = bewusst aus.
        recordMarker.setSelectedItem(orDefault(state.get(RECORD_MARKER_KEY), TextTransform.DEFAULT_RECORD_MARKER));
        removeFinalNewline.setSelected(flag(state.get(FINAL_NEWLINE_KEY), TextTransform.DEFAULT_REMOVE_FINAL_NEWLINE));
        fileEndMarker.setSelectedItem(orDefault(state.get(FILE_END_KEY), TextTransform.DEFAULT_FILE_END_MARKER));
        padding.setSelectedItem(orDefault(state.get(PADDING_KEY), TextTransform.DEFAULT_PADDING));
    }

    private static String orDefault(String stored, String fallback) {
        return stored == null ? fallback : stored;
    }

    private static <E extends Enum<E>> void select(JComboBox<E> box, Class<E> type, String name, E fallback) {
        E chosen = fallback;
        if (name != null && !name.isBlank()) {
            try {
                chosen = Enum.valueOf(type, name);
            } catch (IllegalArgumentException unknown) {
                chosen = fallback;
            }
        }
        box.setSelectedItem(chosen);
    }

    private static boolean flag(String text, boolean fallback) {
        return text == null || text.isBlank() ? fallback : Boolean.parseBoolean(text);
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
