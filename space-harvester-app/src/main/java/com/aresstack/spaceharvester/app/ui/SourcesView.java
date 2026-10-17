package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.app.settings.TrustStores;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SortOrder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.function.Consumer;
import java.util.Arrays;

/**
 * Wissensquellen: links je Quelle eine Karte mit Verbindungszeichen und Symbolknöpfen (Space synchronisieren,
 * Einstellungen, entfernen, mehr), rechts die gewählte Quelle mit Textreitern: Spaces als Hauptinhalt, Einstellungen
 * (Geheimnisse nur als „gesetzt“) und die technischen Fähigkeiten. „Verbunden“ erscheint nur nach einer erfolgreichen
 * Prüfung.
 */
public final class SourcesView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final List<AppSettings.SourceEntry> sources = new ArrayList<>();
    private final Map<String, SourceCard> cards = new LinkedHashMap<>();
    private final CardsPanel cardsPanel = new CardsPanel();
    private String selected;
    private final Feedback feedback = new Feedback();
    private final JPanel properties = new JPanel(new GridBagLayout());
    private final Map<String, EditableProperty> rows = new LinkedHashMap<>();
    private AppSettings.SourceEntry shown;
    /** Die Quelle, deren Spaces die Tabelle gerade zeigt. */
    private String spacesOf;
    private final Facts capabilities = new Facts();
    private final PillTabs tabs = new PillTabs();
    private final SpacesModel spacesModel = new SpacesModel();
    private final JTable spaces = Comic.table(new JTable(spacesModel));
    private final TableRowSorter<SpacesModel> sorter = new TableRowSorter<>(spacesModel);
    private final JTextField filter = Comic.field(14);
    private final JLabel spacesHint = Comic.muted(" ");
    private final ComposerButton add = Comic.primary("Quelle hinzufügen …",
            "Assistent: Art, Adresse, Anmeldung, Zertifikate, Verbindungstest; schreibt die Konfigurationsdatei");
    private final JLabel title = new JLabel(" ");
    private final JLabel address = Comic.muted(" ");

    static final String SPACES = "Spaces";
    static final String SETTINGS = "Einstellungen";
    static final String CAPABILITIES = "Fähigkeiten";

    public SourcesView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBackground(Comic.SURFACE);
        left.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 8));
        left.add(Comic.caption("Wissensquellen"), BorderLayout.NORTH);
        JScrollPane cardsScroll = Comic.scroll(cardsPanel);
        cardsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        cardsScroll.setBorder(BorderFactory.createEmptyBorder());
        cardsScroll.getViewport().setBackground(Comic.SURFACE);
        left.add(cardsScroll, BorderLayout.CENTER);
        add.addActionListener(e -> SourceWizard.open(root, context, this::select));
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(add, BorderLayout.NORTH);
        south.add(Comic.note("Quellen stehen in der Konfigurationsdatei (Einstellungen). Token und Passwörter legt "
                + "der Assistent verschlüsselt ab."), BorderLayout.SOUTH);
        left.add(south, BorderLayout.SOUTH);

        title.setFont(Comic.semiBold(18f));
        title.setForeground(Comic.INK);
        spaces.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        spaces.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        spaces.setToolTipText("Doppelklick oder Enter öffnet den Space unter Inhalte");
        spaces.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && spaces.rowAtPoint(e.getPoint()) >= 0) {
                    openSelectedSpace();
                }
            }
        });
        spaces.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openSpace");
        spaces.getActionMap().put("openSpace", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSelectedSpace();
            }
        });
        filter.setToolTipText("Spaces nach Name oder Schlüssel filtern");
        filter.getAccessibleContext().setAccessibleName("Spaces filtern");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        JPanel spacesTools = new JPanel(new BorderLayout(6, 0));
        spacesTools.setOpaque(false);
        spacesTools.add(Comic.muted("Filter"), BorderLayout.WEST);
        spacesTools.add(filter, BorderLayout.CENTER);
        JPanel spacesTop = new JPanel(new BorderLayout(0, 6));
        spacesTop.setOpaque(false);
        spacesTop.add(spacesTools, BorderLayout.NORTH);
        spacesTop.add(spacesHint, BorderLayout.SOUTH);
        JPanel spacesPanel = new JPanel(new BorderLayout(0, 6));
        spacesPanel.setOpaque(false);
        spacesPanel.add(spacesTop, BorderLayout.NORTH);
        JScrollPane table = Comic.scroll(spaces);
        table.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        table.setPreferredSize(new Dimension(400, 220));
        spacesPanel.add(table, BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        header.add(title, BorderLayout.NORTH);
        header.add(address, BorderLayout.CENTER);
        header.add(feedback, BorderLayout.SOUTH);

        properties.setOpaque(false);
        JPanel settings = new JPanel(new BorderLayout());
        settings.setOpaque(false);
        settings.add(properties, BorderLayout.NORTH);
        JPanel capabilityPanel = new JPanel(new BorderLayout());
        capabilityPanel.setOpaque(false);
        capabilityPanel.add(capabilities, BorderLayout.NORTH);
        // Textreiter statt Trenner: je Reiter die ganze Höhe, die Einstellungen scrollen bei Bedarf.
        tabs.tab(SPACES, spacesPanel)
                .tab(SETTINGS, flush(Comic.column(settings)))
                .tab(CAPABILITIES, flush(Comic.column(capabilityPanel)));
        JPanel tabsFrame = new JPanel(new BorderLayout());
        tabsFrame.setOpaque(false);
        tabsFrame.setBorder(BorderFactory.createEmptyBorder(0, 14, 12, 14));
        tabsFrame.add(tabs, BorderLayout.CENTER);

        JPanel detail = new JPanel(new BorderLayout());
        detail.setBackground(Comic.SURFACE);
        detail.add(header, BorderLayout.NORTH);
        detail.add(tabsFrame, BorderLayout.CENTER);

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
        if (sourceId == null || !cards.containsKey(sourceId)) {
            return;
        }
        if (!sourceId.equals(selected)) {
            selected = sourceId;
            filter.setText("");
        }
        cards.values().forEach(SourceCard::update);
        cards.get(sourceId).scrollRectToVisible(new Rectangle(cards.get(sourceId).getSize()));
        showSelected();
    }

    private void refresh() {
        AppSettings settings = context.settings();
        List<AppSettings.SourceEntry> entries = settings == null ? List.of() : settings.sources();
        if (!entries.equals(sources)) {
            sources.clear();
            sources.addAll(entries);
            cards.clear();
            cardsPanel.removeAll();
            for (AppSettings.SourceEntry entry : entries) {
                SourceCard card = new SourceCard(entry);
                cards.put(entry.id(), card);
                cardsPanel.add(card);
            }
            cardsPanel.revalidate();
            cardsPanel.repaint();
        }
        if (selected == null || !cards.containsKey(selected)) {
            selected = sources.isEmpty() ? null : sources.get(0).id();
        }
        cards.values().forEach(SourceCard::update);
        showSelected();
    }

    private AppSettings.SourceEntry selectedEntry() {
        SourceCard card = selected == null ? null : cards.get(selected);
        return card == null ? null : card.entry;
    }

    private void showSelected() {
        AppSettings.SourceEntry entry = selectedEntry();
        capabilities.reset();
        if (entry == null) {
            showProperties(null);
            title.setText(sources.isEmpty() ? "Noch keine Wissensquelle eingerichtet" : " ");
            address.setText(" ");
            spacesModel.set(List.of());
            spacesHint.setText(sources.isEmpty()
                    ? "„Quelle hinzufügen …“ führt durch die Einrichtung; alternativ die Konfigurationsdatei bearbeiten."
                    : " ");
            feedback.clear();
            return;
        }
        SourceSettingsConfig config = entry.config();
        title.setText(config.title() == null ? config.id() : config.title() + "  (" + config.id() + ")");
        address.setText(Texts.sourceType(config.type()) + (config.baseUrl() == null ? ""
                : " · " + config.baseUrl()));
        showProperties(entry);

        AppRuntime runtime = context.runtime();
        Optional<HarvestSource> source = runtime == null ? Optional.empty() : runtime.workspace().sources().stream()
                .filter(s -> s.id().equals(entry.id())).findFirst();
        if (source.isPresent()) {
            for (SourceCapability capability : SourceCapability.values()) {
                boolean has = source.get().capabilities().contains(capability);
                capabilities.add(Texts.capability(capability), has ? "ja" : "nein", has ? Comic.INK : Comic.MUTED);
            }
        } else {
            capabilities.add("Stand", entry.secretMissing() && !context.secrets().has(entry)
                    ? "wartet auf Zugangsdaten" : "nicht gestartet");
        }

        AppContext.Connection connection = context.connection(entry.id());
        // Neu setzen löscht die Tabellenauswahl; derselbe Space derselben Quelle bleibt gewählt, damit Kreispfeil und
        // Export ihn nach dem Klick auf die Karte noch sehen.
        String keep = entry.id().equals(spacesOf) ? selectedSpace().map(Partition::key).orElse(null) : null;
        spacesOf = entry.id();
        spacesModel.set(connection.spaces());
        reselect(keep);
        // Erfolg zeigt das Zeichen an der Karte; hier erscheinen nur laufende Prüfungen und Probleme.
        if (context.runtime() != null && context.runtime().waitingForSecret().contains(entry.id())) {
            spacesHint.setText("Die Quelle wartet auf Zugangsdaten; an der Karte unter ⋯ eingeben.");
            feedback.clear();
        } else {
            switch (connection.state()) {
                case NOT_CHECKED -> {
                    spacesHint.setText("Nicht geprüft. „Verbindung prüfen“ unter ⋯ an der Karte lädt die Spaces live.");
                    feedback.clear();
                }
                case CHECKING -> {
                    spacesHint.setText("Spaces werden live geladen …");
                    feedback.clear();
                }
                case CONNECTED -> {
                    spacesHint.setText(Texts.count(connection.spaces().size(), "Space", "Spaces")
                            + " sichtbar · live geprüft " + Texts.time(connection.checkedAt())
                            + " · Doppelklick öffnet, der Kreispfeil an der Karte synchronisiert den gewählten");
                    feedback.clear();
                }
                case FAILED -> {
                    spacesHint.setText("Prüfung fehlgeschlagen " + Texts.time(connection.checkedAt()));
                    feedback.problem(connection.error());
                }
            }
        }
        applyFilter();
    }

    /**
     * Die Einstellungen der Quelle als Zeilen mit Stift. Eine laufende Bearbeitung bleibt stehen, solange dieselbe
     * Quelle gezeigt wird; Verbindungsmeldungen bauen die Zeilen sonst neu.
     */
    private void showProperties(AppSettings.SourceEntry entry) {
        if (entry != null && entry.equals(shown) && rows.values().stream().anyMatch(EditableProperty::editing)) {
            return;
        }
        shown = entry;
        rows.clear();
        properties.removeAll();
        if (entry != null) {
            SourceSettingsConfig config = entry.config();
            boolean confluence = SourceDraft.CONFLUENCE.equals(config.type());
            row(EditableProperty.view("Typ", Texts.sourceType(config.type()), Comic.INK));
            row(EditableProperty.text("Anzeigename", config.title(), config.title(), "Anzeigenamen ändern",
                    (value, ok, failed) -> saveField(entry, d -> with(d, value, d.baseUrl(), d.directory()), ok,
                            failed)));
            if (confluence || config.baseUrl() != null) {
                String url = config.baseUrl() == null ? "" : config.baseUrl().toString();
                row(EditableProperty.text("Adresse", url, url, "Adresse der Quelle ändern",
                        (value, ok, failed) -> saveField(entry, d -> with(d, d.title(), value, d.directory()), ok,
                                failed)));
            }
            config.options().forEach((key, value) -> {
                if ("directory".equals(key)) {
                    row(EditableProperty.text("Ordner", value, value, "Ordner der Quelle ändern",
                            (typed, ok, failed) -> saveField(entry, d -> with(d, d.title(), d.baseUrl(), typed), ok,
                                    failed)));
                } else {
                    row(EditableProperty.view("Option " + key, value, Comic.INK));
                }
            });
            if (confluence) {
                row(EditableProperty.action("Anmeldung", Texts.auth(config.auth()), Comic.INK,
                        "Anmeldeart, Benutzer und Token bzw. Passwort ändern",
                        () -> setting(entry, SettingDialogs::auth)));
            } else {
                row(EditableProperty.view("Anmeldung", Texts.auth(config.auth()), Comic.INK));
            }
            if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
                boolean session = context.secrets().has(entry);
                boolean token = config.auth().type() == SourceSettingsConfig.AuthType.PAT;
                row(EditableProperty.action(token ? "Token" : "Passwort",
                        context.vault().has(entry.id(), AppRuntime.SessionSecrets.login(entry))
                                ? "verschlüsselt gespeichert"
                                : session ? "für diese Sitzung eingegeben"
                                : !entry.secretMissing() ? "gesetzt (Property/Umgebung)"
                                : "fehlt",
                        entry.secretMissing() && !session ? Comic.RED : Comic.INK,
                        (token ? "Token" : "Passwort") + " neu eingeben", () -> storeSecret(entry)));
            }
            if (confluence) {
                SourceSettingsConfig.ClientCertificate certificate = config.clientCertificate();
                row(EditableProperty.action("Client-Zertifikat", !certificate.enabled() ? "aus"
                                : (certificate.type() == SourceSettingsConfig.CertificateStoreType.WINDOWS_MY
                                ? "Windows-Zertifikatsspeicher (CurrentUser\\My)"
                                : "PKCS#12-Datei " + certificate.path())
                                + (certificate.alias() == null ? "" : ", Alias " + certificate.alias())
                                + (certificate.thumbprint() == null ? ""
                                : ", Fingerabdruck " + certificate.thumbprint()),
                        Comic.INK, "Client-Zertifikat wählen",
                        () -> setting(entry, SettingDialogs::certificate)));
                row(EditableProperty.action(SettingDialogs.TRUST_TITLE,
                        SettingDialogs.trustLabel(config.trustBundle()), Comic.INK,
                        "Festlegen, welchen Zertifizierungsstellen die App bei Serverzertifikaten glaubt",
                        () -> setting(entry, SettingDialogs::trust)));
                row(EditableProperty.action("Proxy", SourceWizard.proxyLabel(context.settings().proxy().mode())
                                + " · gilt für alle Quellen", Comic.INK, "Proxy ändern (gilt für alle Quellen)",
                        () -> SettingDialogs.proxy(root, context,
                                config.baseUrl() == null ? null : config.baseUrl().toString(),
                                () -> select(entry.id())).open()));
            }
        }
        properties.revalidate();
        properties.repaint();
    }

    private void row(EditableProperty property) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = rows.size();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        // Eine Bearbeitung je Quelle: Eine neue schließt die offene, und solange eine Zeile speichert, beginnt keine
        // zweite; so überschreiben sich zwei Änderungen derselben Datei nicht.
        property.coordinate(() -> rows.values().stream().anyMatch(EditableProperty::saving),
                () -> rows.values().stream().filter(other -> other != property).forEach(EditableProperty::cancel));
        rows.put(property.name(), property);
        properties.add(property, c);
    }

    /**
     * Ändert ein Feld der Quelle in der Konfigurationsdatei (kommentarschonend, mit .bak) und lädt neu. Enthält der
     * Eintrag ein Geheimnis im Klartext, bleibt die Datei unverändert: Das Speichern ersetzte es durch einen
     * Platzhalter; das übernimmt der Assistent mit Rückfrage.
     */
    private void saveField(AppSettings.SourceEntry entry, UnaryOperator<SourceDraft> change, Runnable ok,
                           Consumer<Throwable> failed) {
        if (!Dialogs.restartAllowed(root, context)) {
            failed.accept(new IllegalStateException("Nicht übernommen: Es laufen noch Aufträge."));
            return;
        }
        ConfigFileEditor editor = new ConfigFileEditor(context.loader());
        Background.run(() -> {
            SourceDraft.Editable editable = editor.editable(context.configFile(), entry.id());
            if (!editable.plaintextSecrets().isEmpty()) {
                throw new IllegalStateException("Der Eintrag enthält ein Geheimnis im Klartext. Bitte über ⋯ › "
                        + "„Assistent erneut öffnen“ an der Karte bearbeiten; der Assistent legt es sicher ab.");
            }
            boolean backup = !editor.fileHasPlaintextSecret(context.configFile());
            ConfigFileEditor.Draft draft = editor.replace(context.configFile(), change.apply(editable.draft()));
            editor.save(context.configFile(), draft, backup);
            return entry.id();
        }, id -> {
            ok.run();
            context.reload(() -> select(id));
        }, failed);
    }

    private static SourceDraft with(SourceDraft d, String title, String baseUrl, String directory) {
        return new SourceDraft(d.id(), d.type(), title, baseUrl, directory, d.include(), d.authType(), d.username(),
                d.secretVariable(), d.certificateType(), d.certificatePath(), d.certificatePasswordVariable(),
                d.certificateAlias(), d.thumbprint(), d.trustBundle(), d.extraOptions());
    }

    /**
     * Token oder Passwort neu eingeben: verschlüsselt im Tresor (Windows), sonst nur für die Sitzung. Die Datei bleibt
     * unverändert; der Wert erscheint nirgends.
     */
    private void storeSecret(AppSettings.SourceEntry entry) {
        boolean token = entry.config().auth().type() == SourceSettingsConfig.AuthType.PAT;
        SecretVault vault = context.vault();
        boolean persist = vault.available();
        char[] value = Dialogs.askSecret(root, (token ? "Token" : "Passwort") + " für " + entry.id(),
                (token ? "Personal Access Token" : "Passwort für " + entry.config().auth().username())
                        + " der Quelle " + entry.id() + ". " + (persist
                        ? "Wird " + vault.describe() + " abgelegt, nie in der Konfigurationsdatei."
                        : "Gilt nur für diese Sitzung; auf diesem System gibt es keinen Tresor."),
                persist ? "Speichern" : "Übernehmen");
        if (value == null || value.length == 0) {
            return;
        }
        if (!Dialogs.restartAllowed(root, context)) {
            Arrays.fill(value, '\0');
            return;
        }
        String login = AppRuntime.SessionSecrets.login(entry);
        feedback.working("Zugangsdaten werden übernommen …");
        Background.execute(() -> {
            if (persist) {
                vault.store(entry.id(), login, Arrays.copyOf(value, value.length));
            }
        }, () -> {
            context.secrets().put(entry.id(), login, value);
            context.reload(() -> feedback.success(persist ? "Zugangsdaten verschlüsselt gespeichert"
                    : "Zugangsdaten für diese Sitzung übernommen"));
        }, error -> {
            Arrays.fill(value, '\0');
            feedback.problem(error);
        });
    }

    private void applyFilter() {
        String text = filter.getText().trim().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(text.isEmpty() ? null : new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends SpacesModel, ? extends Integer> row) {
                Partition partition = spacesModel.rows.get(row.getIdentifier());
                return partition.key().toLowerCase(Locale.ROOT).contains(text)
                        || partition.name().toLowerCase(Locale.ROOT).contains(text);
            }
        });
    }

    private void check(AppSettings.SourceEntry entry) {
        context.checkConnection(entry.id(), () -> { });
    }

    private void askSecret(AppSettings.SourceEntry entry) {
        boolean token = entry.config().auth().type() == SourceSettingsConfig.AuthType.PAT;
        char[] value = Dialogs.askSecret(root, (token ? "Token" : "Passwort") + " für " + entry.id(),
                (token ? "Personal Access Token" : "Passwort für " + entry.config().auth().username())
                        + " der Quelle " + entry.id() + ". Gilt nur für diese Sitzung und wird nicht gespeichert.");
        if (value == null || value.length == 0) {
            return;
        }
        if (!Dialogs.restartAllowed(root, context)) {
            Arrays.fill(value, '\0');
            return;
        }
        context.secrets().put(entry.id(), AppRuntime.SessionSecrets.login(entry), value);
        // Die Eingabe geht einer Property oder Variable vor und gilt sofort (Neustart der Quellen).
        feedback.working(entry.secretMissing() ? "Quelle wird mit den neuen Zugangsdaten gestartet …"
                : "Die Eingabe ersetzt für diese Sitzung den Wert aus Property/Umgebung; Quellen starten neu …");
        context.reload(() -> { });
    }

    private void edit(AppSettings.SourceEntry entry) {
        edit(entry, null);
    }

    /** Ein Einstellungsdialog der Quelle, z. B. „Anmeldung“; liest den Eintrag frisch aus der Datei. */
    private void setting(AppSettings.SourceEntry entry, SettingDialog dialog) {
        SourceDraft.Editable editable;
        try {
            editable = new ConfigFileEditor(context.loader()).editable(context.configFile(), entry.id());
        } catch (IOException | RuntimeException e) {
            feedback.problem(e);
            return;
        }
        dialog.create(root, context, editable, this::select).open();
    }

    /** Erzeugt einen der {@link SettingDialogs} zu einer Quelle. */
    interface SettingDialog {
        SettingDialogs.Shell create(java.awt.Component parent, AppContext context, SourceDraft.Editable editing,
                                    Consumer<String> saved);
    }

    private void edit(AppSettings.SourceEntry entry, SourceWizard.Step step) {
        String id = entry.id();
        ConfigFileEditor editor = new ConfigFileEditor(context.loader());
        SourceDraft.Editable editable;
        try {
            editable = editor.editable(context.configFile(), id);
        } catch (IOException | RuntimeException e) {
            feedback.problem(e);
            return;
        }
        SourceWizard.open(root, context, editable, step, this::select);
    }

    private void remove(AppSettings.SourceEntry entry) {
        String id = entry.id();
        ConfigFileEditor editor = new ConfigFileEditor(context.loader());
        boolean plaintext;
        try {
            plaintext = editor.fileHasPlaintextSecret(context.configFile());
        } catch (IOException | RuntimeException e) {
            feedback.problem(e);
            return;
        }
        // Mit Klartext-Geheimnis irgendwo in der Datei entsteht keine .bak (und eine alte wird gelöscht), sonst läge
        // das Geheimnis dort weiter; das muss der Bediener vor dem Bestätigen wissen.
        String backup = plaintext
                ? "Weil die Konfigurationsdatei ein Passwort oder Token im Klartext enthält, wird KEINE Sicherung (.bak) "
                        + "angelegt "
                        + "und eine vorhandene gelöscht; das Entfernen lässt sich nicht rückgängig machen."
                : "Die vorige Fassung bleibt als .bak erhalten.";
        if (!Dialogs.confirm(root, "Quelle entfernen", "Die Quelle " + id + " wird aus der Konfigurationsdatei "
                + "entfernt. " + backup + " Die Quellen starten danach neu. Der lokale Bestand im "
                + "Datenordner bleibt liegen, ist ohne die Quelle aber nicht mehr erreichbar.", "Entfernen")
                || !Dialogs.restartAllowed(root, context)) {
            return;
        }
        feedback.working("Quelle wird entfernt …");
        Background.execute(() -> {
            // Gehört zur Entfernung: Ein späterer Eintrag mit derselben ID erbt sonst das gespeicherte Geheimnis.
            // Erst der Tresor (verschlüsselt gemerkt), dann die Datei; scheitert die Datei, kommt er zurück.
            SecretVault vault = context.vault();
            String stored = vault.raw(id);
            vault.remove(id);
            try {
                editor.save(context.configFile(), editor.remove(context.configFile(), id), !plaintext);
            } catch (IOException | RuntimeException e) {
                if (stored != null) {
                    try {
                        vault.restore(id, stored);
                    } catch (IOException | RuntimeException restore) {
                        e.addSuppressed(restore);
                    }
                }
                throw e;
            }
        }, () -> {
            context.secrets().remove(id);
            context.reload(() -> feedback.success("Quelle " + id + " entfernt"));
        }, feedback::problem);
    }

    /**
     * Gleicht den gewählten Space vollständig mit der Quelle ab. Ohne Auswahl startet nichts, auch nicht der erste
     * Space der Liste: Die Ansicht zeigt die Spaces und bittet um eine Auswahl.
     */
    private void syncSelected() {
        tabs.select(SPACES);
        Optional<Partition> space = selectedSpace();
        if (space.isEmpty()) {
            feedback.info("Bitte zuerst einen Space auswählen",
                    "Der Kreispfeil gleicht den in der Liste gewählten Space vollständig mit der Quelle ab.");
            spaces.requestFocusInWindow();
            return;
        }
        String spaceKey = space.get().key();
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

    private void reselect(String key) {
        if (key == null) {
            return;
        }
        for (int i = 0; i < spacesModel.rows.size(); i++) {
            if (spacesModel.rows.get(i).key().equals(key)) {
                int view = spaces.convertRowIndexToView(i);
                if (view >= 0) {
                    spaces.setRowSelectionInterval(view, view);
                }
                return;
            }
        }
    }

    private void openSelectedSpace() {
        selectedSpace().ifPresent(space -> context.navigator().openSpace(selectedId(), space.key()));
    }

    /** Für Tests: zeigt einen Reiter (Spaces, Einstellungen, Fähigkeiten). */
    String selectedTab() {
        return tabs.selected();
    }

    private String selectedId() {
        AppSettings.SourceEntry entry = selectedEntry();
        return entry == null ? null : entry.id();
    }

    private Optional<Partition> selectedSpace() {
        int row = spaces.getSelectedRow();
        return row < 0 ? Optional.empty() : Optional.of(spacesModel.rows.get(spaces.convertRowIndexToModel(row)));
    }

    /** Die Karten untereinander, so breit wie der Sichtbereich: lange Namen brechen um, nichts scrollt waagerecht. */
    private static final class CardsPanel extends JPanel implements Scrollable {

        private static final int GAP = 8;

        CardsPanel() {
            super(null);
            setBackground(Comic.SURFACE);
        }

        /** Höhen aus der tatsächlichen Breite: umbrechende Namen kennen ihre Höhe erst mit der Breite. */
        @Override
        public void doLayout() {
            int y = 0;
            for (Component c : getComponents()) {
                int h = ((SourceCard) c).heightFor(getWidth());
                c.setBounds(0, y, getWidth(), h);
                y += h + GAP;
            }
            if (y != lastHeight) {
                lastHeight = y;
                revalidate();
            }
        }

        private int lastHeight = -1;

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : getParent() != null && getParent().getWidth() > 0
                    ? getParent().getWidth() : 220;
            int y = 0;
            for (Component c : getComponents()) {
                y += ((SourceCard) c).heightFor(width) + GAP;
            }
            return new Dimension(width, y);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 48);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }

    /**
     * Eine Quelle als Karte: Verbindungszeichen, Name (bricht um), Art und Ort, Zustand und die Aktionen als
     * Symbolknöpfe mit Tooltip. Ein Klick auf die Karte wählt die Quelle; Tab erreicht die Knöpfe.
     */
    private final class SourceCard extends JPanel {

        private final AppSettings.SourceEntry entry;
        private final StatusMark mark = new StatusMark();
        private final JTextArea name = Comic.note("");
        private final JTextArea where = Comic.note("");
        private final JTextArea state = Comic.note("");
        private final IconButton sync = new IconButton(Glyph.REFRESH,
                "Gewählten Space vollständig mit der Quelle abgleichen");
        private final IconButton edit = new IconButton(Glyph.PENCIL, "Einstellungen der Quelle öffnen");
        private final IconButton remove = new IconButton(Glyph.TRASH,
                "Quelle entfernen (fragt nach; der lokale Bestand bleibt im Datenordner)");
        private final IconButton more = new IconButton(Glyph.MORE, "Weitere Aktionen");

        private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));

        SourceCard(AppSettings.SourceEntry entry) {
            super(null);
            this.entry = entry;
            setOpaque(true);
            SourceSettingsConfig config = entry.config();
            name.setText(config.title() == null ? config.id() : config.title());
            name.setFont(Comic.semiBold(13.5f));
            name.setForeground(Comic.INK);
            where.setText(Texts.sourceType(config.type()) + " · " + where(config));
            where.setForeground(Comic.MUTED);
            where.setFont(Comic.regular(12f));
            state.setFont(Comic.regular(12f));

            add(mark);
            add(name);
            add(where);
            add(state);
            actions.setOpaque(false);
            actions.add(sync);
            actions.add(edit);
            actions.add(remove);
            actions.add(more);
            add(actions);

            sync.addActionListener(e -> {
                select(entry.id());
                syncSelected();
            });
            edit.addActionListener(e -> {
                select(entry.id());
                tabs.select(SETTINGS);
            });
            remove.addActionListener(e -> {
                select(entry.id());
                SourcesView.this.remove(entry);
            });
            more.addActionListener(e -> {
                select(entry.id());
                menu().show(more, 0, more.getHeight());
            });
            MouseAdapter choose = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    select(entry.id());
                }
            };
            for (Component c : List.of(this, name, where, state, mark, actions)) {
                c.addMouseListener(choose);
            }
            getAccessibleContext().setAccessibleName(name.getText());
        }

        private JPopupMenu menu() {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem wizard = new JMenuItem("Assistent erneut öffnen …");
            wizard.addActionListener(e -> SourcesView.this.edit(entry));
            menu.add(wizard);
            boolean running = context.runtime() != null
                    && !context.runtime().waitingForSecret().contains(entry.id());
            JMenuItem check = new JMenuItem("Verbindung prüfen");
            check.setToolTipText("Meldet sich an und lädt die Spaces live");
            check.setEnabled(running && context.connection(entry.id()).state() != AppContext.Connection.State.CHECKING);
            check.addActionListener(e -> SourcesView.this.check(entry));
            menu.add(check);
            JMenuItem export = new JMenuItem("Exportieren …");
            export.setToolTipText("Export vorbereiten, mit dem gewählten Space");
            export.setEnabled(running);
            export.addActionListener(e -> context.navigator().prepareExport(entry.id(),
                    selectedSpace().map(Partition::key).orElse(null)));
            menu.add(export);
            if (entry.config().auth().type() != SourceSettingsConfig.AuthType.NONE) {
                JMenuItem secret = new JMenuItem("Zugangsdaten für diese Sitzung eingeben …");
                secret.setToolTipText("Passwort oder Token nur für diese Sitzung; es wird nicht gespeichert");
                secret.addActionListener(e -> askSecret(entry));
                menu.add(secret);
            }
            return menu;
        }

        /** Übernimmt Auswahl und Verbindungsstand; die Karte selbst bleibt dieselbe. */
        void update() {
            boolean chosen = entry.id().equals(selected);
            setBackground(chosen ? Comic.YELLOW : Comic.SURFACE);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(chosen ? Comic.INK : Comic.LINE, chosen ? 2 : 1, true),
                    BorderFactory.createEmptyBorder(chosen ? 7 : 8, chosen ? 7 : 8, chosen ? 5 : 6, chosen ? 7 : 8)));
            AppContext.Connection connection = context.connection(entry.id());
            boolean waiting = context.runtime() != null && context.runtime().waitingForSecret().contains(entry.id());
            String text;
            Color color;
            StatusMark.State mode;
            if (waiting) {
                text = "Zugangsdaten fehlen";
                color = Comic.RED;
                mode = StatusMark.State.FAILED;
            } else {
                switch (connection.state()) {
                    case CONNECTED -> {
                        text = "Verbunden · " + Texts.count(connection.spaces().size(), "Space", "Spaces");
                        color = Comic.INK;
                        mode = StatusMark.State.CONNECTED;
                    }
                    case FAILED -> {
                        text = "Fehler: " + Problems.describe(connection.error()).title();
                        color = Comic.RED;
                        mode = StatusMark.State.FAILED;
                    }
                    case CHECKING -> {
                        text = "Wird geprüft …";
                        color = Comic.BLUE;
                        mode = StatusMark.State.CHECKING;
                    }
                    default -> {
                        text = "Nicht geprüft";
                        color = Comic.MUTED;
                        mode = StatusMark.State.UNCHECKED;
                    }
                }
            }
            state.setText(text);
            state.setForeground(color);
            mark.state(mode, text);
            sync.setEnabled(!waiting && context.runtime() != null);
            revalidate();
            repaint();
        }

        private static final int MARK = 18;
        private static final int GAP = 8;

        /** Höhe der Karte bei dieser Breite; die Texte brechen in der Spalte neben dem Zeichen um. */
        int heightFor(int width) {
            return place(width, false);
        }

        @Override
        public void doLayout() {
            place(getWidth(), true);
        }

        private int place(int width, boolean apply) {
            Insets in = getInsets();
            int x = in.left + MARK + GAP;
            int column = Math.max(60, width - x - in.right);
            int y = in.top;
            for (JTextArea area : List.of(name, where, state)) {
                area.setSize(column, Short.MAX_VALUE);
                int h = area.getPreferredSize().height;
                if (apply) {
                    area.setBounds(x, y, column, h);
                }
                y += h;
            }
            if (apply) {
                mark.setBounds(in.left, in.top, MARK, MARK);
            }
            y += 4;
            int h = actions.getPreferredSize().height;
            if (apply) {
                actions.setBounds(x - 2, y, column + 2, h);
            }
            return y + h + in.bottom;
        }

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : 220;
            return new Dimension(width, heightFor(width));
        }
    }

    /** Eine Spalte ohne eigenen Rand, bündig mit der Space-Tabelle im Nachbarreiter. */
    private static JScrollPane flush(JScrollPane column) {
        ((JComponent) column.getViewport().getView()).setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        column.setBorder(BorderFactory.createEmptyBorder());
        return column;
    }

    /** Nur der letzte Ordnername; der volle Pfad steht rechts unter Einstellungen. */
    private static String folder(String directory) {
        String trimmed = directory.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    private static String where(SourceSettingsConfig config) {
        return config.baseUrl() == null ? folder(config.options().getOrDefault("directory", ""))
                : config.baseUrl().getHost() != null ? config.baseUrl().getHost()
                : config.baseUrl().getRawAuthority() != null ? config.baseUrl().getRawAuthority()
                : config.baseUrl().toString();
    }

    private static final class SpacesModel extends AbstractTableModel {

        private List<Partition> rows = new ArrayList<>();

        void set(List<Partition> partitions) {
            rows = new ArrayList<>(partitions);
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
            return column == 0 ? "Name" : "Schlüssel";
        }

        @Override
        public Object getValueAt(int row, int column) {
            Partition partition = rows.get(row);
            return column == 0 ? partition.name() : partition.key();
        }
    }
}
