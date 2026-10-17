package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Wissensquellen: die konfigurierten Quellen mit geprüftem Verbindungsstand, ihre Einstellungen (Geheimnisse nur als
 * „gesetzt“), Fähigkeiten und Spaces. „Verbunden“ erscheint nur nach einer erfolgreichen Prüfung.
 */
public final class SourcesView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final DefaultListModel<AppSettings.SourceEntry> sources = new DefaultListModel<>();
    private final JList<AppSettings.SourceEntry> list = new JList<>(sources);
    private final Feedback feedback = new Feedback();
    private final Facts facts = new Facts();
    private final Facts capabilities = new Facts();
    private final SpacesModel spacesModel = new SpacesModel();
    private final JTable spaces = Comic.table(new JTable(spacesModel));
    private final JLabel spacesHint = Comic.muted(" ");
    private final ComposerButton check = Comic.primary("Verbindung prüfen", "Lädt die Spaces live aus der Quelle");
    private final ComposerButton secret = Comic.button("Zugangsdaten eingeben …",
            "Passwort oder Token nur für diese Sitzung eingeben; es wird nicht gespeichert");
    private final ComposerButton open = Comic.button("Inhalte öffnen", "Seiten dieses Space ansehen");
    private final ComposerButton sync = Comic.button("Synchronisieren", "Space vollständig mit der Quelle abgleichen");
    private final ComposerButton export = Comic.button("Exportieren …", "Export dieses Space vorbereiten");
    private final JLabel title = new JLabel(" ");

    public SourcesView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);

        list.setCellRenderer(this::renderSource);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Comic.SURFACE);
        list.setFixedCellHeight(-1);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBackground(Comic.SURFACE);
        left.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 8));
        left.add(Comic.caption("Wissensquellen"), BorderLayout.NORTH);
        left.add(Comic.scroll(list), BorderLayout.CENTER);
        left.add(Comic.note("Quellen stehen in der Konfigurationsdatei (Einstellungen). Zugangsdaten kommen aus "
                + "Umgebungsvariablen oder werden hier für die Sitzung eingegeben."), BorderLayout.SOUTH);

        title.setFont(Comic.semiBold(18f));
        title.setForeground(Comic.INK);
        check.addActionListener(e -> checkSelected());
        secret.addActionListener(e -> askSecret());
        open.addActionListener(e -> selectedSpace().ifPresent(space ->
                context.navigator().openSpace(selectedId(), space.key())));
        sync.addActionListener(e -> selectedSpace().ifPresent(space -> startSync(space.key())));
        export.addActionListener(e -> selectedSpace().ifPresent(space ->
                context.navigator().prepareExport(selectedId(), space.key())));
        spaces.getSelectionModel().addListSelectionListener(e -> updateButtons());
        spaces.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    open.doClick();
                }
            }
        });

        JPanel spacesPanel = new JPanel(new BorderLayout(0, 6));
        spacesPanel.setOpaque(false);
        JComponent table = Comic.scroll(spaces);
        table.setPreferredSize(new java.awt.Dimension(400, 260));
        spacesPanel.add(spacesHint, BorderLayout.NORTH);
        spacesPanel.add(table, BorderLayout.CENTER);
        spacesPanel.add(Comic.row(open, sync, export), BorderLayout.SOUTH);

        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        header.add(Comic.row(check, secret), BorderLayout.CENTER);
        header.add(feedback, BorderLayout.SOUTH);

        JPanel detail = new JPanel(new BorderLayout());
        detail.setBackground(Comic.SURFACE);
        detail.add(Comic.column(header, Comic.plate("Einstellungen", Comic.PETROL, facts),
                Comic.plate("Fähigkeiten", Comic.BLUE, capabilities),
                Comic.plate("Spaces", Comic.ORANGE, spacesPanel)), BorderLayout.CENTER);

        root.add(new ComicSplitPane(left, detail, 240, 460), BorderLayout.CENTER);
        context.onChange(ignored -> refresh());
        refresh();
    }

    @Override
    public JComponent component() {
        return root;
    }

    /** Wählt eine Quelle aus (z. B. von der Übersicht). */
    public void select(String sourceId) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id().equals(sourceId)) {
                list.setSelectedIndex(i);
                return;
            }
        }
    }

    private void refresh() {
        String selected = selectedId();
        AppSettings settings = context.settings();
        List<AppSettings.SourceEntry> entries = settings == null ? List.of() : settings.sources();
        boolean same = entries.size() == sources.size();
        for (int i = 0; same && i < entries.size(); i++) {
            same = entries.get(i).equals(sources.get(i));
        }
        if (!same) {
            sources.clear();
            entries.forEach(sources::addElement);
        }
        if (selected != null) {
            select(selected);
        }
        if (list.getSelectedIndex() < 0 && !sources.isEmpty()) {
            list.setSelectedIndex(0);
        }
        list.repaint();
        showSelected();
    }

    private void showSelected() {
        AppSettings.SourceEntry entry = list.getSelectedValue();
        facts.reset();
        capabilities.reset();
        if (entry == null) {
            title.setText(sources.isEmpty() ? "Noch keine Wissensquelle eingerichtet" : " ");
            spacesModel.set(List.of());
            spacesHint.setText(sources.isEmpty()
                    ? "Eine Quelle in der Konfigurationsdatei eintragen (Einstellungen › Konfigurationsdatei öffnen)."
                    : " ");
            updateButtons();
            return;
        }
        SourceSettingsConfig config = entry.config();
        title.setText(config.title() == null ? config.id() : config.title() + "  (" + config.id() + ")");
        facts.add("Typ", Texts.sourceType(config.type()));
        if (config.baseUrl() != null) {
            facts.add("Adresse", config.baseUrl().toString());
        }
        config.options().forEach((key, value) -> facts.add("Option " + key, value));
        facts.add("Anmeldung", Texts.auth(config.auth()));
        if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
            boolean session = context.secrets().has(entry.id());
            facts.add(config.auth().type() == SourceSettingsConfig.AuthType.PAT ? "Token" : "Passwort",
                    !entry.secretMissing() ? "gesetzt (Konfiguration/Umgebung)"
                            : session ? "für diese Sitzung eingegeben"
                            : "fehlt" + (entry.secretVariable() == null ? "" : " (Umgebungsvariable "
                            + entry.secretVariable() + ")"),
                    entry.secretMissing() && !session ? Comic.RED : Comic.INK);
        }
        SourceSettingsConfig.ClientCertificate certificate = config.clientCertificate();
        facts.add("Client-Zertifikat", !certificate.enabled() ? "aus"
                : (certificate.type() == SourceSettingsConfig.CertificateStoreType.WINDOWS_MY
                ? "Windows-Zertifikatsspeicher (CurrentUser\\My)" : "PKCS#12-Datei " + certificate.path())
                + (certificate.alias() == null ? "" : ", Alias " + certificate.alias())
                + (certificate.thumbprint() == null ? "" : ", Fingerabdruck " + certificate.thumbprint()));
        facts.add("Vertrauen", config.trustBundle() == null ? "Zertifikate der Java-Laufzeit"
                : "SSL-Bundle " + config.trustBundle());
        facts.add("Proxy", context.settings().proxy().mode().name().toLowerCase().replace('_', '-'));

        AppRuntime runtime = context.runtime();
        Optional<HarvestSource> source = runtime == null ? Optional.empty() : runtime.workspace().sources().stream()
                .filter(s -> s.id().equals(entry.id())).findFirst();
        if (source.isPresent()) {
            for (SourceCapability capability : SourceCapability.values()) {
                boolean has = source.get().capabilities().contains(capability);
                capabilities.add(Texts.capability(capability), has ? "ja" : "nein", has ? Comic.INK : Comic.MUTED);
            }
        } else {
            capabilities.add("Stand", entry.secretMissing() && !context.secrets().has(entry.id())
                    ? "wartet auf Zugangsdaten" : "nicht gestartet");
        }

        AppContext.Connection connection = context.connection(entry.id());
        spacesModel.set(connection.spaces());
        switch (connection.state()) {
            case NOT_CHECKED -> {
                spacesHint.setText("Nicht geprüft. „Verbindung prüfen“ lädt die Spaces live.");
                feedback.clear();
            }
            case CHECKING -> {
                spacesHint.setText(" ");
                feedback.working("Verbindung wird geprüft …");
            }
            case CONNECTED -> {
                spacesHint.setText(Texts.count(connection.spaces().size(), "Space", "Spaces") + " sichtbar, geprüft "
                        + Texts.time(connection.checkedAt()));
                feedback.success("Verbunden");
            }
            case FAILED -> {
                spacesHint.setText("Prüfung fehlgeschlagen " + Texts.time(connection.checkedAt()));
                feedback.problem(connection.error());
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        AppSettings.SourceEntry entry = list.getSelectedValue();
        boolean running = entry != null && context.runtime() != null
                && !context.runtime().waitingForSecret().contains(entry.id());
        check.setEnabled(running && context.connection(entry.id()).state() != AppContext.Connection.State.CHECKING);
        secret.setVisible(entry != null && entry.config().auth().type() != SourceSettingsConfig.AuthType.NONE);
        boolean space = running && spaces.getSelectedRow() >= 0;
        open.setEnabled(space);
        sync.setEnabled(space);
        export.setEnabled(space);
    }

    private void checkSelected() {
        String id = selectedId();
        if (id != null) {
            context.checkConnection(id, () -> { });
        }
    }

    private void askSecret() {
        AppSettings.SourceEntry entry = list.getSelectedValue();
        if (entry == null) {
            return;
        }
        boolean token = entry.config().auth().type() == SourceSettingsConfig.AuthType.PAT;
        char[] value = Dialogs.askSecret(root, (token ? "Token" : "Passwort") + " für " + entry.id(),
                (token ? "Personal Access Token" : "Passwort für " + entry.config().auth().username())
                        + " der Quelle " + entry.id() + ". Gilt nur für diese Sitzung und wird nicht gespeichert.");
        if (value == null || value.length == 0) {
            return;
        }
        context.secrets().put(entry.id(), value);
        if (!entry.secretMissing()) {
            feedback.info("Eingabe gemerkt", "Die Konfiguration liefert bereits ein Geheimnis; es bleibt in Gebrauch.");
            return;
        }
        feedback.working("Quelle wird mit den neuen Zugangsdaten gestartet …");
        context.reload(() -> { });
    }

    private void startSync(String spaceKey) {
        String id = selectedId();
        AppRuntime runtime = context.runtime();
        if (id == null || runtime == null) {
            return;
        }
        feedback.working("Abgleich wird gestartet …");
        Background.run(() -> runtime.workspace().startSync(id, spaceKey), status -> {
            feedback.success("Abgleich von " + spaceKey + " gestartet");
            context.navigator().showJob(status.id());
        }, feedback::problem);
    }

    private String selectedId() {
        AppSettings.SourceEntry entry = list.getSelectedValue();
        return entry == null ? null : entry.id();
    }

    private Optional<Partition> selectedSpace() {
        int row = spaces.getSelectedRow();
        return row < 0 ? Optional.empty() : Optional.of(spacesModel.rows.get(spaces.convertRowIndexToModel(row)));
    }

    private Component renderSource(JList<? extends AppSettings.SourceEntry> jlist, AppSettings.SourceEntry entry,
                                   int index, boolean selected, boolean focus) {
        JPanel row = new JPanel(new GridLayout(3, 1, 0, 0));
        row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        row.setBackground(selected ? Comic.YELLOW : Comic.SURFACE);
        SourceSettingsConfig config = entry.config();
        JLabel name = new JLabel(config.title() == null ? config.id() : config.title());
        name.setFont(Comic.semiBold(13.5f));
        name.setForeground(Comic.INK);
        String where = config.baseUrl() != null ? config.baseUrl().getHost()
                : config.options().getOrDefault("directory", "");
        JLabel kind = Comic.muted(Texts.sourceType(config.type()) + " · " + where);
        AppContext.Connection connection = context.connection(entry.id());
        String state;
        Color color;
        if (context.runtime() != null && context.runtime().waitingForSecret().contains(entry.id())) {
            state = "Zugangsdaten fehlen";
            color = Comic.RED;
        } else {
            switch (connection.state()) {
                case CONNECTED -> {
                    state = "Verbunden · " + Texts.count(connection.spaces().size(), "Space", "Spaces");
                    color = Comic.GREEN;
                }
                case FAILED -> {
                    state = "Fehler: " + Problems.describe(connection.error()).title();
                    color = Comic.RED;
                }
                case CHECKING -> {
                    state = "Wird geprüft …";
                    color = Comic.BLUE;
                }
                default -> {
                    state = "Nicht geprüft";
                    color = Comic.MUTED;
                }
            }
        }
        JLabel status = Comic.muted(state);
        status.setForeground(color);
        row.add(name);
        row.add(kind);
        row.add(status);
        return row;
    }

    private static final class SpacesModel extends AbstractTableModel {

        private List<Partition> rows = new ArrayList<>();

        void set(List<Partition> partitions) {
            rows = partitions.stream().sorted((a, b) -> a.key().compareToIgnoreCase(b.key()))
                    .collect(Collectors.toList());
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Key" : "Name";
        }

        @Override
        public Object getValueAt(int row, int column) {
            Partition partition = rows.get(row);
            return column == 0 ? partition.key() : partition.name();
        }
    }
}
