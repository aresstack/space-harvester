package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.deck.terminal.TerminalModel;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * „Neue Verbindung“ zum TN3270-Host: sichtbar nur Host und Port, Modell, TLS, Codepage und Keep-Alive unter
 * „Erweitert“. Nichts ist mit Namen vorbelegt; die zuletzt benutzte Verbindung steht beim nächsten Mal wieder da.
 * Verbunden wird erst auf „Verbinden“ (oder Eingabe), nie beim Öffnen der Perspektive.
 */
final class ConnectForm extends JPanel {

    static final String HOST_KEY = "deck.terminal.host";
    static final String PORT_KEY = "deck.terminal.port";
    static final String MODEL_KEY = "deck.terminal.model";
    static final String TLS_KEY = "deck.terminal.tls";
    static final String CODE_PAGE_KEY = "deck.terminal.code-page";
    static final String KEEP_ALIVE_KEY = "deck.terminal.keep-alive";

    final PlaceholderField host = new PlaceholderField(22, "Rechnername oder Adresse");
    final PlaceholderField port = new PlaceholderField(6, String.valueOf(TerminalProfile.DEFAULT_PORT));
    final JComboBox<TerminalModel> model = new JComboBox<>(TerminalModel.values());
    final JCheckBox tls = new JCheckBox("Verschlüsselt (TLS)");
    final PlaceholderField codePage = new PlaceholderField(8, TerminalProfile.DEFAULT_CODE_PAGE);
    final PlaceholderField keepAlive = new PlaceholderField(6, "aus");
    private final JLabel problem = new JLabel(" ");
    private final JPanel advanced = new JPanel(new GridBagLayout());
    private final JLabel advancedToggle = new JLabel();
    private final Consumer<TerminalProfile> connect;

    ConnectForm(Consumer<TerminalProfile> connect) {
        super(new BorderLayout());
        this.connect = connect;
        setOpaque(false);
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        row(fields, 0, "Host", host);
        row(fields, 1, "Port", port);
        advanced.setOpaque(false);
        row(advanced, 0, "Modell", model);
        row(advanced, 1, "", tls);
        row(advanced, 2, "Codepage", codePage);
        row(advanced, 3, "Keep-Alive (s)", keepAlive);
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
        tls.setOpaque(false);
        tls.setFont(Comic.regular(13f));
        // Mit TLS ist 992 der übliche Port; ein eingetragener Port bleibt, wie er ist.
        tls.addActionListener(e -> port.setPlaceholder(String.valueOf(
                tls.isSelected() ? TerminalProfile.DEFAULT_TLS_PORT : TerminalProfile.DEFAULT_PORT)));
        model.setFont(Comic.regular(13f));
        problem.setFont(Comic.regular(12.5f));
        problem.setForeground(Comic.RED);
        var button = Comic.primary("Verbinden", "TN3270-Sitzung zu diesem Host öffnen (Eingabe)");
        button.addActionListener(e -> submit());
        host.addActionListener(e -> submit());
        port.addActionListener(e -> submit());

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        for (JComponent part : new JComponent[] {fields, advancedToggle, advanced, problem, Comic.row(button)}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            body.add(part);
        }
        advancedToggle.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        problem.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        JComponent plate = Comic.plate("Neue Verbindung", Comic.YELLOW, body);
        add(plate, BorderLayout.CENTER);
        recall();
        getAccessibleContext().setAccessibleName("Neue TN3270-Verbindung");
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
        field.setFont(Comic.regular(13f));
        panel.add(field, c);
    }

    private void advanced(boolean shown) {
        advanced.setVisible(shown);
        advancedToggle.setText(shown ? "Erweitert ausblenden" : "Erweitert …");
        revalidate();
        repaint();
    }

    /** Das Profil aus den Feldern; wirft mit lesbarer Meldung, wenn etwas fehlt. */
    TerminalProfile profile() {
        int portNumber = number(port.getText(), tls.isSelected()
                ? TerminalProfile.DEFAULT_TLS_PORT : TerminalProfile.DEFAULT_PORT, "Port");
        int keepAliveSeconds = number(keepAlive.getText(), 0, "Keep-Alive");
        return new TerminalProfile(host.getText(), portNumber, (TerminalModel) model.getSelectedItem(),
                tls.isSelected(), keepAliveSeconds, codePage.getText());
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
        remember(profile);
        connect.accept(profile);
    }

    String problemText() {
        return problem.getText();
    }

    void focusHost() {
        host.requestFocusInWindow();
    }

    private void remember(TerminalProfile profile) {
        AppState state = AppState.shared();
        state.putAll(java.util.Map.of(HOST_KEY, profile.host(), PORT_KEY, port.getText().trim(),
                MODEL_KEY, profile.model().name(), TLS_KEY, String.valueOf(profile.tls()),
                CODE_PAGE_KEY, codePage.getText().trim(), KEEP_ALIVE_KEY, keepAlive.getText().trim()));
    }

    private void recall() {
        AppState state = AppState.shared();
        host.setText(nonNull(state.get(HOST_KEY)));
        port.setText(nonNull(state.get(PORT_KEY)));
        String modelName = state.get(MODEL_KEY);
        for (TerminalModel candidate : TerminalModel.values()) {
            if (candidate.name().equals(modelName)) {
                model.setSelectedItem(candidate);
            }
        }
        tls.setSelected(state.getBoolean(TLS_KEY, false));
        codePage.setText(nonNull(state.get(CODE_PAGE_KEY)));
        keepAlive.setText(nonNull(state.get(KEEP_ALIVE_KEY)));
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
