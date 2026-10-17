package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.ProxyDraft;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.InvalidNetworkConfigurationException;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Quellen-Assistent: legt eine Wissensquelle in der Konfigurationsdatei an oder ändert eine bestehende an ihrer Stelle.
 * Schritte wie im Enterprise AI Client: Quelle (Art und Adresse zuerst, Anzeigename und technische Kennung werden
 * daraus vorgeschlagen), Anmeldung mit optionalem Client-Zertifikat aus dem Windows-Zertifikatsspeicher, Proxy und
 * Netzwerk (gilt für alle Quellen, mit „Proxy auflösen“), Prüfen und Speichern. Ohne eigene Adresse gilt die
 * öffentliche Confluence-Hilfe von Atlassian, damit sich die App sofort ausprobieren lässt. Fehlende Angaben stehen
 * direkt unter dem Feld.
 *
 * <p>Passwort und Token kommen nie in die Datei, dort steht nur der Platzhalter {@code ${NAME}}. Den Wert legt die
 * App im {@link SecretVault} ab (unter Windows mit DPAPI verschlüsselt) und setzt ihn beim Start selbst; eine
 * Umgebungsvariable ist nicht nötig. Der Verbindungstest nutzt genau die gewählten Einstellungen (Quelle, Anmeldung,
 * Proxy) mit einem eigenen Harvester in einem leeren Arbeitsordner, bevor etwas gespeichert ist.
 */
final class SourceWizard {

    enum Step {
        SOURCE("Quelle"), AUTH("Anmeldung"), NETWORK("Proxy und Netzwerk"), CHECK("Prüfen und speichern");

        private final String title;

        Step(String title) {
            this.title = title;
        }
    }

    private static final String CONFLUENCE = "Confluence (Data Center/Server)";
    private static final String FILES = "Lokale Dateien (Ordner)";
    private static final String PAT = "Personal Access Token";
    private static final String BASIC = "Benutzer und Passwort";
    private static final String NONE = "Ohne Anmeldung";
    private static final String NO_CERTIFICATE = "Kein Client-Zertifikat";
    private static final String WINDOWS_MY = "Windows-Zertifikatsspeicher (CurrentUser\\My)";
    private static final String PKCS12 = "PKCS#12-Datei (.p12/.pfx)";
    private static final String JAVA_TRUST = "Zertifikate der Java-Laufzeit";
    /** Öffentliche Confluence-Instanz (Atlassian-Dokumentation), lesbar ohne Anmeldung: Vorgabe zum Ausprobieren. */
    static final String DEFAULT_URL = "https://confluence.atlassian.com";
    static final String DEFAULT_TITLE = "Confluence-Hilfe (Atlassian)";

    private final AppContext context;
    private final ConfigFileEditor editor;
    private final JPanel root = new JPanel(new BorderLayout(0, 10));
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final JLabel heading = new JLabel(" ");
    private final Feedback feedback = new Feedback();

    final JComboBox<String> kind = Comic.combo();
    final JTextField id = Comic.field(24);
    final JTextField title = Comic.field(24);
    final PlaceholderField baseUrl = new PlaceholderField(32, DEFAULT_URL);
    final JTextField directory = Comic.field(32);
    final JTextField include = Comic.field(24);
    final JComboBox<String> auth = Comic.combo();
    final JTextField username = Comic.field(24);
    final JTextField variable = Comic.field(24);
    final JPasswordField sessionValue = new JPasswordField(24);
    final JComboBox<String> certificate = Comic.combo();
    final JTextField thumbprint = Comic.field(32);
    final JTextField alias = Comic.field(24);
    final JTextField certificatePath = Comic.field(32);
    final JTextField certificatePasswordVariable = Comic.field(24);
    final JComboBox<String> trust = Comic.combo();
    final ComposerButton chooseCertificate = Comic.button("Wählen …",
            "Aus dem Windows-Zertifikatsspeicher wählen (nur öffentliche Zertifikate, keine Schlüssel)");
    final JComboBox<String> proxyMode = Comic.combo();
    final JTextField pacUrl = Comic.field(32);
    final JTextField proxyHost = Comic.field(24);
    final JTextField proxyPort = Comic.field(6);
    final JTextField nonProxyHosts = Comic.field(32);
    final ComposerButton resolveProxy = Comic.button("Proxy auflösen",
            "Route zur Adresse mit den gewählten Proxy-Einstellungen bestimmen, ohne etwas zu speichern");
    final JTextArea proxyLog = Comic.code();
    private final JTextArea preview = Comic.code();
    final JTextArea checkNotes = Comic.note("");
    private final Form address = new Form();
    private final Form login = new Form();
    private final Form network = new Form();
    private final ProxySettings currentProxy;

    final ComposerButton back = Comic.button("Zurück", null);
    final ComposerButton next = Comic.primary("Weiter", null);
    final ComposerButton test = Comic.button("Verbindung testen", "Spaces live laden, bevor gespeichert wird");
    final ComposerButton save = Comic.primary("Speichern", "Quelle in die Konfigurationsdatei schreiben und neu laden");
    final ComposerButton cancel = Comic.button("Abbrechen", null);

    private final SourceDraft.Editable editing;
    private Step step = Step.SOURCE;
    private String suggestedVariable;
    private String suggestedId = "";
    private String suggestedTitle = "";
    private boolean authChosen;
    private boolean deriving;
    private Runnable close = () -> { };
    private Consumer<String> saved = ignored -> { };

    SourceWizard(AppContext context) {
        this(context, null);
    }

    /** @param editing bestehender Eintrag zum Bearbeiten oder {@code null} für eine neue Quelle */
    SourceWizard(AppContext context, SourceDraft.Editable editing) {
        this.context = context;
        this.editing = editing;
        this.editor = new ConfigFileEditor(context.loader());
        root.setOpaque(false);
        root.setPreferredSize(new Dimension(700, 540));
        Comic.style(sessionValue);

        kind.addItem(CONFLUENCE);
        kind.addItem(FILES);
        auth.addItem(PAT);
        auth.addItem(BASIC);
        auth.addItem(NONE);
        certificate.addItem(NO_CERTIFICATE);
        certificate.addItem(WINDOWS_MY);
        certificate.addItem(PKCS12);
        trust.addItem(JAVA_TRUST);
        AppSettings settings = context.settings();
        if (settings != null) {
            settings.trustStores().keySet().stream().sorted().forEach(trust::addItem);
        }
        for (ProxySettings.Mode mode : PROXY_MODES) {
            proxyMode.addItem(proxyLabel(mode));
        }
        currentProxy = settings != null ? settings.proxy() : context.loader().defaultProxy();
        ProxyDraft proxyDraft = ProxyDraft.of(currentProxy);
        proxyMode.setSelectedItem(proxyLabel(proxyDraft.mode()));
        pacUrl.setText(proxyDraft.pacUrl() == null ? "" : proxyDraft.pacUrl());
        proxyHost.setText(proxyDraft.proxyHost() == null ? "" : proxyDraft.proxyHost());
        proxyPort.setText(proxyDraft.proxyPort() == null ? "" : proxyDraft.proxyPort());
        nonProxyHosts.setText(proxyDraft.nonProxyHosts() == null ? "" : proxyDraft.nonProxyHosts());
        proxyMode.addActionListener(e -> updateVisibility());
        proxyLog.setRows(6);
        include.setText("*.md, *.txt, *.pdf, *.docx, *.html");
        kind.addActionListener(e -> {
            updateVisibility();
            derive();
        });
        auth.addActionListener(e -> {
            authChosen |= !deriving;
            updateVisibility();
        });
        certificate.addActionListener(e -> updateVisibility());

        address.row("Art", kind);
        address.row("Adresse", baseUrl, "Startadresse von Confluence ohne /rest; leer lassen für die Confluence-Hilfe");
        address.row("Ordner", row(directory, browse(directory, true)), null);
        address.row("Dateien", include, "Muster, durch Komma getrennt");
        address.row("Anzeigename", title, "So heißt die Quelle in der App");
        address.row("Technische Kennung", id, "Wird aus der Adresse vorgeschlagen; nur ändern, wenn nötig");
        onEdit(baseUrl, this::derive);
        onEdit(directory, this::derive);

        login.row("Anmeldung", auth);
        login.row("Benutzer", username);
        login.row("Token", sessionValue, " ");
        login.row("Platzhalter", variable, "Name in der Datei (${NAME}); die App setzt den Wert selbst");
        login.row("Client-Zertifikat", certificate, "mTLS, zusätzlich zur Anmeldung oder ohne sie");
        login.row("Alias", row(alias, chooseCertificate), "Zertifikat im Speicher bzw. in der Datei");
        login.row("Fingerabdruck", thumbprint, "Optional, SHA-1 oder SHA-256; prüft zusätzlich (kein Geheimnis)");
        login.row("PKCS#12-Datei", row(certificatePath, browse(certificatePath, false)), null);
        login.row("Passwort-Platzhalter", certificatePasswordVariable,
                "Java-Property oder Umgebungsvariable mit dem Zertifikatspasswort");
        chooseCertificate.addActionListener(e -> {
            String chosen = CertificateChooser.choose(root, alias.getText().trim());
            if (chosen != null) {
                alias.setText(chosen);
            }
        });

        network.row("", Comic.text("Diese Einstellungen gelten für alle Quellen (space-harvester.network.proxy)."),
                null);
        network.row("Proxy", proxyMode, "Standard unter Windows: PAC-Adresse der Interneteinstellungen per PowerShell");
        network.row("PAC-Adresse", pacUrl, "z. B. http://proxy.example.org/proxy.pac");
        network.row("Proxy-Host", proxyHost, null);
        network.row("Proxy-Port", proxyPort, "1 bis 65535, z. B. 8080");
        network.row("Ohne Proxy", nonProxyHosts, "Optional: Hosts, die direkt gehen, z. B. localhost, *.intern");
        network.row("Vertrauen", trust, "Eigene Zertifizierungsstellen stehen unter spring.ssl.bundle");
        JPanel diagnose = new JPanel(new BorderLayout(0, 6));
        diagnose.setOpaque(false);
        JPanel diagnoseButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        diagnoseButtons.setOpaque(false);
        diagnoseButtons.add(resolveProxy);
        diagnose.add(diagnoseButtons, BorderLayout.NORTH);
        JComponent log = Comic.scroll(proxyLog);
        log.setPreferredSize(new Dimension(480, 110));
        diagnose.add(log, BorderLayout.CENTER);
        network.row("Diagnose", diagnose, null);
        resolveProxy.addActionListener(e -> resolveProxy(() -> { }));

        JPanel check = new JPanel(new BorderLayout(0, 8));
        check.setOpaque(false);
        JPanel checkTop = new JPanel(new BorderLayout(0, 6));
        checkTop.setOpaque(false);
        checkTop.add(checkNotes, BorderLayout.NORTH);
        // Ergebnis von Test und Speichern nur hier: Die Formularschritte behalten so ihre Höhe.
        checkTop.add(feedback, BorderLayout.CENTER);
        check.add(checkTop, BorderLayout.NORTH);
        JComponent scroll = Comic.scroll(preview);
        scroll.setPreferredSize(new Dimension(560, 200));
        check.add(scroll, BorderLayout.CENTER);

        pages.setOpaque(false);
        pages.add(address, Step.SOURCE.name());
        pages.add(login, Step.AUTH.name());
        pages.add(network, Step.NETWORK.name());
        pages.add(check, Step.CHECK.name());

        heading.setFont(Comic.semiBold(13f));
        heading.setForeground(Comic.MUTED);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(back);
        buttons.add(test);
        buttons.add(next);
        buttons.add(save);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(buttons, BorderLayout.SOUTH);

        root.add(heading, BorderLayout.NORTH);
        root.add(pages, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        back.addActionListener(e -> back());
        next.addActionListener(e -> next());
        test.addActionListener(e -> test(() -> { }));
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> close.run());
        if (editing != null) {
            prefill(editing.draft());
        }
        updateVisibility();
        derive();
        show(Step.SOURCE);
    }

    /** Öffnet den Assistenten modal; {@code saved} erhält nach Speichern und Neu laden die ID der neuen Quelle. */
    static void open(Component parent, AppContext context, Consumer<String> saved) {
        open(parent, context, null, saved);
    }

    /** Wie {@link #open(Component, AppContext, Consumer)}, aber zum Bearbeiten eines bestehenden Eintrags. */
    static void open(Component parent, AppContext context, SourceDraft.Editable editing, Consumer<String> saved) {
        SourceWizard wizard = new SourceWizard(context, editing);
        String heading = editing == null ? "Wissensquelle hinzufügen" : "Wissensquelle bearbeiten";
        JDialog dialog = Dialogs.dialog(parent, heading);
        wizard.saved = saved;
        wizard.close = dialog::dispose;
        Dialogs.frame(dialog, heading, wizard.component(), dialog::dispose);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                wizard.clearSecret();
            }
        });
        Dialogs.present(dialog, new Dimension(660, 600));
    }

    JComponent component() {
        return root;
    }

    Step step() {
        return step;
    }

    void onSaved(Consumer<String> saved) {
        this.saved = saved;
    }

    /** Die Eingaben als Eintrag für die Datei. */
    SourceDraft draft() {
        boolean confluence = CONFLUENCE.equals(kind.getSelectedItem());
        SourceSettingsConfig.AuthType authType = !confluence ? SourceSettingsConfig.AuthType.NONE
                : PAT.equals(auth.getSelectedItem()) ? SourceSettingsConfig.AuthType.PAT
                : BASIC.equals(auth.getSelectedItem()) ? SourceSettingsConfig.AuthType.BASIC
                : SourceSettingsConfig.AuthType.NONE;
        SourceSettingsConfig.CertificateStoreType certificateType = !confluence ? null
                : WINDOWS_MY.equals(certificate.getSelectedItem()) ? SourceSettingsConfig.CertificateStoreType.WINDOWS_MY
                : PKCS12.equals(certificate.getSelectedItem()) ? SourceSettingsConfig.CertificateStoreType.PKCS12
                : null;
        boolean pkcs12 = certificateType == SourceSettingsConfig.CertificateStoreType.PKCS12;
        String bundle = String.valueOf(trust.getSelectedItem());
        return new SourceDraft(id.getText(), confluence ? SourceDraft.CONFLUENCE : SourceDraft.FILES,
                title.getText(), confluence ? address() : null, confluence ? null : directory.getText(),
                confluence ? null : include.getText(), authType,
                authType == SourceSettingsConfig.AuthType.BASIC ? username.getText() : null,
                authType == SourceSettingsConfig.AuthType.NONE ? null : variable.getText(), certificateType,
                pkcs12 ? certificatePath.getText() : null, pkcs12 ? certificatePasswordVariable.getText() : null,
                certificateType == null ? null : alias.getText(),
                certificateType == null ? null : thumbprint.getText(),
                !confluence || JAVA_TRUST.equals(bundle) ? null : bundle,
                editing == null ? null : editing.draft().extraOptions());
    }

    /** Die eingetragene Adresse; leer heißt die öffentliche Confluence-Hilfe. */
    private String address() {
        return baseUrl.getText().isBlank() ? DEFAULT_URL : baseUrl.getText();
    }

    /** Nächster Schritt, wenn die Eingaben dieses Schritts vollständig sind; sonst steht der Hinweis am Feld. */
    boolean next() {
        Form form = form(step);
        if (form != null) {
            form.clearErrors();
        }
        List<String> problems = problems(step);
        if (!problems.isEmpty()) {
            JComponent first = null;
            for (String problem : problems) {
                JComponent field = field(problem);
                JComponent target = form != null && form.has(field) ? field : form == null ? null : form.first();
                if (target != null) {
                    form.error(target, shortly(problem));
                    first = first == null ? target : first;
                }
            }
            if (first != null) {
                first.requestFocusInWindow();
            }
            return false;
        }
        feedback.clear();
        List<Step> steps = steps();
        int index = steps.indexOf(step);
        if (index + 1 < steps.size()) {
            show(steps.get(index + 1));
        }
        return true;
    }

    void back() {
        List<Step> steps = steps();
        int index = steps.indexOf(step);
        if (index > 0) {
            feedback.clear();
            Form form = form(steps.get(index - 1));
            if (form != null) {
                form.clearErrors();
            }
            show(steps.get(index - 1));
        }
    }

    /** Testet die Verbindung mit den Eingaben, ohne etwas zu speichern. */
    void test(Runnable done) {
        SourceDraft source = draft();
        ConfigFileEditor.Draft draft;
        try {
            // Genau die gewählten Einstellungen, auch der noch nicht gespeicherte Proxy.
            draft = write(source);
        } catch (IOException | RuntimeException e) {
            feedback.problem(e);
            done.run();
            return;
        }
        AppRuntime.SessionSecrets secrets = sessionSecrets(source);
        feedback.working("Verbindung wird geprüft …");
        test.setEnabled(false);
        Background.run(() -> AppRuntime.probe(draft.settings(), source.id(), secrets, context.version(),
                        Files.createTempDirectory("space-harvester-probe")),
                spaces -> {
                    test.setEnabled(true);
                    String keys = spaces.stream().map(Partition::key).limit(8).collect(Collectors.joining(", "));
                    feedback.success("Verbunden: " + Texts.count(spaces.size(), "Space", "Spaces") + " sichtbar"
                            + (keys.isEmpty() ? "" : " (" + keys + (spaces.size() > 8 ? ", …" : "") + ")"));
                    done.run();
                },
                error -> {
                    test.setEnabled(true);
                    feedback.problem(error);
                    done.run();
                });
    }

    /** Schreibt die Quelle in die Datei und lädt die Konfiguration neu. */
    void save() {
        SourceDraft source = draft();
        char[] value = sessionValue.getPassword();
        feedback.working("Wird gespeichert …");
        save.setEnabled(false);
        Background.run(() -> {
            // Ob die bisherige Fassung ein Geheimnis enthält, entscheidet der Rohtext der ganzen Datei.
            boolean backup = editing == null || !editor.fileHasPlaintextSecret(context.configFile());
            ConfigFileEditor.Draft draft = write(source);
            SecretVault vault = context.vault();
            boolean typed = value.length > 0 && source.authType() != SourceSettingsConfig.AuthType.NONE;
            if (typed && vault.available()) {
                // Erst der Tresor, dann die Datei: scheitert die Datei, kommt der alte Tresorwert zurück, sodass
                // ein zweiter Versuch auf demselben Stand beginnt.
                char[] previous = vault.load(source.id());
                vault.store(source.id(), Arrays.copyOf(value, value.length));
                try {
                    editor.save(context.configFile(), draft, backup);
                } catch (IOException | RuntimeException e) {
                    restore(vault, source.id(), previous, e);
                    throw e;
                }
                if (previous != null) {
                    Arrays.fill(previous, '\0');
                }
            } else {
                editor.save(context.configFile(), draft, backup);
                if (!typed && (loginChanged(source) || source.authType() == SourceSettingsConfig.AuthType.NONE)) {
                    vault.remove(source.id());
                }
            }
            return source.id();
        }, savedId -> {
            if (value.length > 0 && source.authType() != SourceSettingsConfig.AuthType.NONE) {
                context.secrets().put(savedId, value);
            } else if (loginChanged(source)) {
                // Ein Token taugt nicht als Passwort (und umgekehrt): Mit der Anmeldeart verfällt der Sitzungswert.
                context.secrets().remove(savedId);
            }
            Arrays.fill(value, '\0');
            clearSecret();
            close.run();
            context.reload(() -> saved.accept(savedId));
        }, error -> {
            Arrays.fill(value, '\0');
            save.setEnabled(true);
            feedback.problem(error);
        });
    }

    private static void restore(SecretVault vault, String sourceId, char[] previous, Exception failure) {
        try {
            if (previous == null) {
                vault.remove(sourceId);
            } else {
                vault.store(sourceId, previous);
            }
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

        private boolean dropsBackup() {
        if (editing == null) {
            return false;
        }
        try {
            return editor.fileHasPlaintextSecret(context.configFile());
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** Der neue Dateitext: die Quelle und, falls geändert, der Proxy für alle Quellen. */
    private ConfigFileEditor.Draft write(SourceDraft source) throws IOException {
        ConfigFileEditor.Draft draft = editing == null ? editor.draft(context.configFile(), source)
                : editor.replace(context.configFile(), source);
        return proxyChanged() ? editor.withProxy(context.configFile(), draft, proxyDraft()) : draft;
    }

    /** Die Proxy-Einstellungen aus Schritt 3; Fristen, die der Assistent nicht zeigt, bleiben wie bisher. */
    ProxyDraft proxyDraft() {
        ProxySettings.Mode mode = PROXY_MODES.get(Math.max(0, proxyMode.getSelectedIndex()));
        return new ProxyDraft(mode, pacUrl.getText(), proxyHost.getText(), proxyPort.getText(),
                nonProxyHosts.getText(), currentProxy.resolveTimeout(), currentProxy.cacheTtl(),
                currentProxy.failureTtl());
    }

    /** Ob Schritt 3 vom bisherigen Proxy abweicht (nur für Confluence, Dateien brauchen kein Netz). */
    boolean proxyChanged() {
        if (!CONFLUENCE.equals(kind.getSelectedItem())) {
            return false;
        }
        ProxyDraft draft = proxyDraft();
        return !draft.problems().isEmpty() || !draft.settings().equals(currentProxy);
    }

    /** „Proxy auflösen“: die Route zur Adresse mit den gewählten Einstellungen, ohne etwas zu speichern. */
    void resolveProxy(Runnable done) {
        ProxyDraft draft = proxyDraft();
        if (!draft.problems().isEmpty()) {
            proxyLog.setText(String.join("\n", draft.problems()));
            done.run();
            return;
        }
        java.net.URI target;
        try {
            target = java.net.URI.create(address().trim());
        } catch (IllegalArgumentException e) {
            proxyLog.setText("Die Adresse aus Schritt 1 ist ungültig: " + e.getMessage());
            done.run();
            return;
        }
        proxyLog.setText("Route zu " + target + " wird bestimmt …");
        resolveProxy.setEnabled(false);
        Background.run(() -> ProxyRoutes.from(draft.settings()).diagnose(target), lines -> {
            resolveProxy.setEnabled(true);
            proxyLog.setText(String.join("\n", lines));
            proxyLog.setCaretPosition(0);
            done.run();
        }, error -> {
            resolveProxy.setEnabled(true);
            proxyLog.setText("Der Proxy lässt sich so nicht verwenden: " + (error instanceof
                    InvalidNetworkConfigurationException invalid ? String.join("; ", invalid.problems())
                    : error.getMessage()));
            done.run();
        });
    }

    private void prefill(SourceDraft source) {
        boolean confluence = source.confluence();
        kind.setSelectedItem(confluence ? CONFLUENCE : FILES);
        kind.setEnabled(false);
        id.setText(source.id());
        id.setEditable(false);
        title.setText(source.title() == null ? "" : source.title());
        baseUrl.setText(source.baseUrl() == null ? "" : source.baseUrl());
        directory.setText(source.directory() == null ? "" : source.directory());
        include.setText(source.include() == null ? "" : source.include());
        auth.setSelectedItem(switch (source.authType()) {
            case PAT -> PAT;
            case BASIC -> BASIC;
            case NONE -> NONE;
        });
        username.setText(source.username() == null ? "" : source.username());
        variable.setText(source.secretVariable() == null ? "" : source.secretVariable());
        certificate.setSelectedItem(source.certificateType() == null ? NO_CERTIFICATE
                : source.certificateType() == SourceSettingsConfig.CertificateStoreType.WINDOWS_MY ? WINDOWS_MY : PKCS12);
        thumbprint.setText(source.thumbprint() == null ? "" : source.thumbprint());
        alias.setText(source.certificateAlias() == null ? "" : source.certificateAlias());
        certificatePath.setText(source.certificatePath() == null ? "" : source.certificatePath());
        certificatePasswordVariable.setText(source.certificatePasswordVariable() == null ? ""
                : source.certificatePasswordVariable());
        if (source.trustBundle() != null) {
            boolean listed = false;
            for (int i = 0; i < trust.getItemCount(); i++) {
                listed |= source.trustBundle().equals(trust.getItemAt(i));
            }
            if (!listed) {
                trust.addItem(source.trustBundle());
            }
            trust.setSelectedItem(source.trustBundle());
        }
    }

    void clearSecret() {
        sessionValue.setText("");
    }

    /** Ob für Token bzw. Passwort schon ein Wert da ist: eingegeben, in der Sitzung, im Tresor oder als Property. */
    private boolean secretAvailable(SourceDraft source) {
        if (source.authType() == SourceSettingsConfig.AuthType.NONE || sessionValue.getPassword().length > 0) {
            return true;
        }
        String sourceId = source.id();
        if (sourceId != null && !loginChanged(source)
                && (context.secrets().has(sourceId) || context.vault().has(sourceId))) {
            return true;
        }
        String name = source.secretVariable();
        return name != null && (System.getProperty(name) != null || System.getenv(name) != null);
    }

    AppRuntime.SessionSecrets sessionSecrets(SourceDraft source) {
        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        String sourceId = source.id();
        char[] value = sessionValue.getPassword();
        if (value.length > 0 && sourceId != null) {
            secrets.put(sourceId, value);
        } else if (sourceId != null && context.secrets().has(sourceId) && !loginChanged(source)) {
            return context.secrets();
        }
        return secrets;
    }

    /** Ob Anmeldeart oder Variable beim Bearbeiten wechseln; dann passt ein Sitzungswert nicht mehr. */
    private boolean loginChanged(SourceDraft source) {
        return editing != null && (editing.draft().authType() != source.authType()
                || !Objects.equals(editing.draft().secretVariable(), source.secretVariable()));
    }

    private List<Step> steps() {
        return CONFLUENCE.equals(kind.getSelectedItem())
                ? List.of(Step.values())
                : List.of(Step.SOURCE, Step.CHECK);
    }

    private List<String> problems(Step current) {
        SourceDraft source = draft();
        List<String> all = source.problems();
        List<String> mine = new ArrayList<>();
        for (String problem : all) {
            Form form = form(current);
            boolean other = !address.has(field(problem)) && !login.has(field(problem)) && !network.has(field(problem));
            if (current == Step.CHECK || (form != null && form.has(field(problem)))
                    || (current == Step.AUTH && other)) {
                mine.add(problem);
            }
        }
        if ((current == Step.AUTH || current == Step.CHECK) && source.confluence() && !secretAvailable(source)) {
            mine.add(source.authType() == SourceSettingsConfig.AuthType.PAT ? "Das Token fehlt." : "Das Passwort fehlt.");
        }
        if ((current == Step.NETWORK || current == Step.CHECK) && source.confluence()) {
            mine.addAll(proxyDraft().problems());
        }
        if (current == Step.SOURCE && editing == null && source.id() != null && context.settings() != null
                && context.settings().sources().stream().anyMatch(s -> s.id().equals(source.id()))) {
            mine.add("Eine Quelle mit der ID " + source.id() + " gibt es schon.");
        }
        return mine;
    }

    private Form form(Step target) {
        return switch (target) {
            case SOURCE -> address;
            case AUTH -> login;
            case NETWORK -> network;
            case CHECK -> null;
        };
    }

    /** Das Feld, an dem ein Problem aus {@link SourceDraft#problems()} steht. */
    private JComponent field(String problem) {
        if (problem.startsWith("Die ID") || problem.startsWith("Eine Quelle mit der ID")) {
            return id;
        } else if (problem.startsWith("Die Art")) {
            return kind;
        } else if (problem.startsWith("Die Adresse")) {
            return baseUrl;
        } else if (problem.startsWith("Der Ordner")) {
            return directory;
        } else if (problem.startsWith("Der Benutzername")) {
            return username;
        } else if (problem.startsWith("Die Umgebungsvariable für das Zertifikatspasswort")) {
            return certificatePasswordVariable;
        } else if (problem.startsWith("Die Umgebungsvariable für")) {
            return variable;
        } else if (problem.startsWith("Die PKCS#12-Datei")) {
            return certificatePath;
        } else if (problem.startsWith("Der Alias")) {
            return alias;
        } else if (problem.startsWith("Das Token") || problem.startsWith("Das Passwort")) {
            return sessionValue;
        } else if (problem.startsWith("Die PAC-Adresse")) {
            return pacUrl;
        } else if (problem.startsWith("Der Proxy-Host")) {
            return proxyHost;
        } else if (problem.startsWith("Der Proxy-Port")) {
            return proxyPort;
        }
        return null;
    }

    /** Kurzer Hinweis für die Zeile unter dem Feld; die Zeile hat nur Platz für einen Satz. */
    private static String shortly(String problem) {
        if (problem.startsWith("Die ID fehlt")) {
            return "Bitte eine Kennung eintragen, z. B. wiki.";
        } else if (problem.startsWith("Die ID darf")) {
            return "Nur Kleinbuchstaben, Ziffern und Bindestrich (höchstens 63 Zeichen).";
        } else if (problem.startsWith("Eine Quelle mit der ID")) {
            return "Diese Kennung ist schon vergeben.";
        } else if (problem.startsWith("Die Adresse muss")) {
            return "Die Adresse beginnt mit https://.";
        } else if (problem.startsWith("Die Adresse darf")) {
            return "Die Adresse darf keinen Platzhalter ${…} enthalten.";
        } else if (problem.startsWith("Der Ordner")) {
            return "Bitte einen Ordner wählen.";
        } else if (problem.startsWith("Der Benutzername")) {
            return "Bitte den Benutzernamen eintragen.";
        } else if (problem.startsWith("Die Umgebungsvariable")) {
            return "Name aus Großbuchstaben, Ziffern und Unterstrich, z. B. WIKI_TOKEN.";
        } else if (problem.startsWith("Die PKCS#12-Datei")) {
            return "Bitte die PKCS#12-Datei wählen.";
        } else if (problem.startsWith("Der Alias")) {
            return "Bitte den Alias wählen oder eintragen.";
        } else if (problem.startsWith("Das Token")) {
            return "Bitte das Token eingeben.";
        } else if (problem.startsWith("Das Passwort")) {
            return "Bitte das Passwort eingeben.";
        }
        return problem;
    }

    /**
     * Schlägt Anzeigename und technische Kennung aus Adresse oder Ordner vor, solange niemand sie geändert hat; bei
     * der öffentlichen Confluence-Hilfe zusätzlich „Ohne Anmeldung“. Beim Bearbeiten bleibt alles, wie es ist.
     */
    private void derive() {
        if (editing != null || deriving) {
            return;
        }
        deriving = true;
        try {
            boolean confluence = CONFLUENCE.equals(kind.getSelectedItem());
            String proposedTitle = confluence ? titleFor(address()) : folderName(directory.getText());
            String proposedId = unique(confluence ? idForUrl(address()) : slug(folderName(directory.getText())));
            if (title.getText().isBlank() || title.getText().equals(suggestedTitle)) {
                title.setText(proposedTitle);
                suggestedTitle = proposedTitle;
            }
            if (id.getText().isBlank() || id.getText().equals(suggestedId)) {
                id.setText(proposedId);
                suggestedId = proposedId;
            }
            if (confluence && !authChosen) {
                auth.setSelectedItem(DEFAULT_URL.equals(normalized(address())) ? NONE : PAT);
            }
        } finally {
            deriving = false;
        }
    }

    private static String normalized(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String host(String url) {
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static String titleFor(String url) {
        return DEFAULT_URL.equals(normalized(url)) ? DEFAULT_TITLE : host(url);
    }

    /** {@code https://confluence.example.org} wird zu {@code confluence-example}: Rechnername ohne Endung. */
    static String idForUrl(String url) {
        String host = host(url);
        if (host.matches("[0-9.]+")) {
            return slug(host);
        }
        int dot = host.lastIndexOf('.');
        return slug(dot > 0 ? host.substring(0, dot) : host);
    }

    private static String folderName(String path) {
        String trimmed = path == null ? "" : path.trim().replaceAll("[\\\\/]+$", "");
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return trimmed.substring(cut + 1);
    }

    private static String slug(String text) {
        String slug = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace("ß", "ss").replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 63 ? slug.substring(0, 63).replaceAll("-+$", "") : slug;
    }

    /** Hängt -2, -3 … an, wenn es die Kennung in der Datei schon gibt. */
    private String unique(String base) {
        if (base.isEmpty() || context.settings() == null) {
            return base;
        }
        java.util.Set<String> taken = context.settings().sources().stream().map(AppSettings.SourceEntry::id)
                .collect(Collectors.toSet());
        String candidate = base;
        for (int n = 2; taken.contains(candidate); n++) {
            String suffix = "-" + n;
            candidate = (base.length() + suffix.length() > 63 ? base.substring(0, 63 - suffix.length()) : base) + suffix;
        }
        return candidate;
    }

    private static void onEdit(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }
        });
    }

    private void show(Step target) {
        if (target == Step.AUTH) {
            SourceSettingsConfig.AuthType authType = draft().authType();
            String suggestion = SourceDraft.suggestedVariable(id.getText(),
                    authType == SourceSettingsConfig.AuthType.NONE ? SourceSettingsConfig.AuthType.PAT : authType);
            if (variable.getText().isBlank() || variable.getText().equals(suggestedVariable)) {
                variable.setText(suggestion);
                suggestedVariable = suggestion;
            }
        }
        if (target == Step.CHECK) {
            renderCheck();
        }
        step = target;
        List<Step> steps = steps();
        heading.setText("Schritt " + (steps.indexOf(target) + 1) + " von " + steps.size() + " · " + target.title);
        cards.show(pages, target.name());
        boolean last = target == Step.CHECK;
        back.setEnabled(steps.indexOf(target) > 0);
        next.setVisible(!last);
        test.setVisible(last);
        save.setVisible(last);
        save.setEnabled(true);
        root.revalidate();
        root.repaint();
    }

    private void renderCheck() {
        SourceDraft source = draft();
        preview.setText(source.yaml("") + (proxyChanged() && proxyDraft().problems().isEmpty()
                ? "\n# space-harvester.network (gilt für alle Quellen)\n" + proxyDraft().yaml("") : ""));
        preview.setCaretPosition(0);
        StringBuilder notes = new StringBuilder(editing == null ? "Dieser Eintrag kommt in " : "Dieser Eintrag ersetzt ")
                .append(editing == null ? "" : "den bisherigen der Quelle " + source.id() + " in ")
                .append(context.configFile().toAbsolutePath()).append(" unter space-harvester.sources; der Rest der "
                        + "Datei bleibt unverändert");
        // Dieselbe Entscheidung wie beim Speichern: Steht irgendwo in der Datei ein Geheimnis im Klartext, entsteht
        // keine .bak (eine vorhandene wird gelöscht).
        if (!dropsBackup()) {
            notes.append(", die vorige Fassung bleibt als .bak erhalten.");
        } else {
            notes.append(". Weil die Konfigurationsdatei ein Passwort oder Token im Klartext enthält, entsteht "
                    + "KEINE .bak-Sicherung, und eine vorhandene wird gelöscht.");
            if (!editing.plaintextSecrets().isEmpty()) {
                notes.append(" In diesem Eintrag betrifft das ").append(String.join(" und ",
                        editing.plaintextSecrets())).append("; dort steht künftig nur der Platzhalter. Den Wert im "
                        + "Schritt Anmeldung eingeben; die App legt ihn verschlüsselt ab.");
            }
        }
        if (source.secretVariable() != null && sessionValue.getPassword().length > 0) {
            String what = source.secretName();
            notes.append("\n\n").append(Character.toUpperCase(what.charAt(0))).append(what.substring(1))
                    .append(" steht nicht in der Datei, sondern wird ")
                    .append(context.vault().describe()).append(" gespeichert; die App setzt es beim Start selbst.");
        }
        if (source.certificatePasswordVariable() != null && System.getenv(source.certificatePasswordVariable()) == null
                && System.getProperty(source.certificatePasswordVariable()) == null) {
            notes.append("\n\nDas Zertifikatspasswort kommt aus der Java-Property bzw. Umgebungsvariable ")
                    .append(source.certificatePasswordVariable()).append("; sie ist noch nicht gesetzt.");
        }
        if (proxyChanged()) {
            notes.append("\n\nDer Proxy ändert sich für ALLE Quellen: ").append(proxyLabel(proxyDraft().mode()))
                    .append(" (space-harvester.network.proxy).");
        }
        checkNotes.setText(notes.toString());
    }

    private void updateVisibility() {
        boolean confluence = CONFLUENCE.equals(kind.getSelectedItem());
        address.visible(baseUrl, confluence);
        address.visible(directory, !confluence);
        address.visible(include, !confluence);
        boolean basic = BASIC.equals(auth.getSelectedItem());
        boolean anonymous = NONE.equals(auth.getSelectedItem());
        login.visible(username, basic);
        login.visible(variable, !anonymous);
        login.visible(sessionValue, !anonymous);
        login.label(sessionValue, basic ? "Passwort" : "Token");
        login.hint(sessionValue, secretHint());
        boolean windows = WINDOWS_MY.equals(certificate.getSelectedItem());
        boolean pkcs12 = PKCS12.equals(certificate.getSelectedItem());
        login.visible(thumbprint, windows || pkcs12);
        login.visible(alias, windows || pkcs12);
        chooseCertificate.setVisible(windows);
        login.visible(certificatePath, pkcs12);
        login.visible(certificatePasswordVariable, pkcs12);
        ProxySettings.Mode mode = PROXY_MODES.get(Math.max(0, proxyMode.getSelectedIndex()));
        network.visible(pacUrl, mode == ProxySettings.Mode.PAC_URL_MANUAL);
        network.visible(proxyHost, mode == ProxySettings.Mode.MANUAL_PROXY);
        network.visible(proxyPort, mode == ProxySettings.Mode.MANUAL_PROXY);
        network.visible(nonProxyHosts, mode != ProxySettings.Mode.DISABLED);
        if (pkcs12 && certificatePasswordVariable.getText().isBlank()) {
            String base = id.getText().isBlank() ? "CONFLUENCE" : SourceDraft.suggestedVariable(id.getText(),
                    SourceSettingsConfig.AuthType.PAT).replaceAll("_TOKEN$", "");
            certificatePasswordVariable.setText(base + "_CLIENT_PASSWORD");
        }
        root.revalidate();
        root.repaint();
    }

    /** Wo Token bzw. Passwort landet; beim Bearbeiten, ob schon eins gespeichert ist. */
    private String secretHint() {
        String stored = editing != null && (context.vault().has(editing.draft().id())
                || context.secrets().has(editing.draft().id())) ? "Schon hinterlegt; leer lassen zum Behalten. " : "";
        return stored + (context.vault().available() ? "Wird verschlüsselt für dein Windows-Konto gespeichert, nie in "
                + "der Datei" : "Gilt bis zum Schließen der App, nie in der Datei");
    }

    /** Die wählbaren Modi des Cores in dieser Reihenfolge; die Vorgabe unter Windows zuerst. */
    static final List<ProxySettings.Mode> PROXY_MODES = List.of(ProxySettings.Mode.PAC_URL_POWERSHELL,
            ProxySettings.Mode.PAC_URL_WINDOWS_SETTINGS, ProxySettings.Mode.PAC_URL_WSCRIPT,
            ProxySettings.Mode.WINDOWS_STATIC_PROXY, ProxySettings.Mode.PAC_URL_MANUAL,
            ProxySettings.Mode.MANUAL_PROXY, ProxySettings.Mode.DISABLED);

    static String proxyLabel(ProxySettings.Mode mode) {
        String text = switch (mode) {
            case PAC_URL_POWERSHELL -> "PAC aus den Windows-Einstellungen per PowerShell";
            case PAC_URL_WINDOWS_SETTINGS -> "PAC aus den Windows-Einstellungen per reg.exe";
            case PAC_URL_WSCRIPT -> "PAC aus den Windows-Einstellungen per VBScript";
            case WINDOWS_STATIC_PROXY -> "Fester Proxy aus den Windows-Einstellungen";
            case PAC_URL_MANUAL -> "PAC-Datei von fester Adresse";
            case MANUAL_PROXY -> "Fester Proxy (Host und Port)";
            case DISABLED -> "Ohne Proxy (direkt)";
        };
        return text + " · " + ProxyDraft.name(mode);
    }


    private JPanel row(JTextField field, ComposerButton button) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        row.add(button, BorderLayout.EAST);
        return row;
    }

    private ComposerButton browse(JTextField target, boolean directories) {
        ComposerButton button = Comic.button("Durchsuchen …", null);
        button.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(target.getText().isBlank() ? null : target.getText());
            chooser.setFileSelectionMode(directories ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().toString());
            }
        });
        return button;
    }

    /**
     * Formular mit Beschriftung, Feld und Hinweiszeile je Zeile; Zeilen lassen sich ausblenden. Ein Fehler ersetzt
     * den Hinweis in derselben Zeile (rot), so bleibt die Höhe gleich; Tippen im Feld nimmt ihn wieder weg.
     */
    private static final class Form extends JPanel {

        private final List<Row> rows = new ArrayList<>();
        private final JPanel filler = new JPanel();

        private record Row(JComponent key, JLabel label, JComponent field, JLabel hint, String text) {
        }

        Form() {
            super(new GridBagLayout());
            setOpaque(false);
            filler.setOpaque(false);
        }

        void row(String label, JComponent field) {
            row(label, field, null);
        }

        void row(String text, JComponent field, String hint) {
            int y = rows.size() * 2;
            JLabel label = new JLabel(text);
            label.setFont(Comic.semiBold(12.5f));
            label.setForeground(Comic.TEXT);
            GridBagConstraints left = new GridBagConstraints();
            left.gridx = 0;
            left.gridy = y;
            left.anchor = GridBagConstraints.WEST;
            left.insets = new Insets(4, 0, 0, 14);
            add(label, left);
            GridBagConstraints right = new GridBagConstraints();
            right.gridx = 1;
            right.gridy = y;
            right.weightx = 1;
            right.fill = GridBagConstraints.HORIZONTAL;
            right.insets = new Insets(4, 0, 0, 0);
            add(field, right);
            // Die Hinweiszeile ist immer da (notfalls leer), damit ein Fehler Platz hat, ohne etwas zu verschieben.
            JLabel hintLabel = Comic.muted(hint == null ? " " : hint);
            GridBagConstraints below = new GridBagConstraints();
            below.gridx = 1;
            below.gridy = y + 1;
            below.anchor = GridBagConstraints.WEST;
            below.fill = GridBagConstraints.HORIZONTAL;
            add(hintLabel, below);
            JComponent key = field instanceof JPanel panel && panel.getComponentCount() > 0
                    && panel.getComponent(0) instanceof JComponent inner ? inner : field;
            Row row = new Row(key, label, field, hintLabel, hint == null ? " " : hint);
            rows.add(row);
            if (key instanceof JTextField textField) {
                onEdit(textField, () -> clear(key));
            }
            // Ein dehnbarer Rest unter der letzten Zeile hält das Formular oben.
            GridBagConstraints rest = new GridBagConstraints();
            rest.gridx = 0;
            rest.gridy = 1000;
            rest.weighty = 1;
            remove(filler);
            add(filler, rest);
        }

        boolean has(JComponent field) {
            return field != null && rows.stream().anyMatch(row -> row.key() == field && row.field().isVisible());
        }

        JComponent first() {
            return rows.stream().filter(row -> row.field().isVisible()).map(Row::key).findFirst().orElse(null);
        }

        void error(JComponent field, String message) {
            for (Row row : rows) {
                if (row.key() == field) {
                    row.hint().setText(message);
                    row.hint().setToolTipText(message);
                    row.hint().setForeground(Comic.RED);
                    row.label().setForeground(Comic.RED);
                }
            }
        }

        void clearErrors() {
            rows.forEach(this::clear);
        }

        String error(JComponent field) {
            return rows.stream().filter(row -> row.key() == field && row.hint().getForeground() == Comic.RED)
                    .map(row -> row.hint().getText()).findFirst().orElse(null);
        }

        private void clear(JComponent key) {
            rows.stream().filter(row -> row.key() == key).forEach(this::clear);
        }

        private void clear(Row row) {
            row.hint().setText(row.text());
            row.hint().setToolTipText(null);
            row.hint().setForeground(Comic.MUTED);
            row.label().setForeground(Comic.TEXT);
        }

        void label(JComponent field, String text) {
            rows.stream().filter(row -> row.key() == field).forEach(row -> row.label().setText(text));
        }

        /** Ersetzt den grauen Hinweis einer Zeile (auch den, zu dem ein Fehler zurückkehrt). */
        void hint(JComponent field, String text) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.key() == field) {
                    boolean error = row.hint().getForeground() == Comic.RED;
                    Row changed = new Row(row.key(), row.label(), row.field(), row.hint(), text);
                    rows.set(i, changed);
                    if (!error) {
                        row.hint().setText(text);
                    }
                }
            }
        }

        void visible(JComponent field, boolean visible) {
            for (Row row : rows) {
                if (row.key() == field || row.field() == field) {
                    row.label().setVisible(visible);
                    row.field().setVisible(visible);
                    row.hint().setVisible(visible);
                }
            }
        }
    }

    /** Der Hinweis unter einem Feld, solange dort ein Fehler steht; sonst {@code null}. */
    String error(JComponent field) {
        for (Form form : List.of(address, login, network)) {
            String error = form.error(field);
            if (error != null) {
                return error;
            }
        }
        return null;
    }
}
