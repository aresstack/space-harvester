package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AiDraft;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.app.settings.TrustStores;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
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
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Formular einer Wissensquelle auf einer Seite, zum Hinzufügen und zum Bearbeiten, in der Wissensquellenverwaltung wie
 * im Einrichtungsassistenten: Quelle (Art und Adresse zuerst, Anzeigename und technische Kennung werden daraus
 * vorgeschlagen), Anmeldung, Client-Zertifikat und Serverzertifikate dieser Quelle sowie die Freigabe für den
 * KI-Dienst. Proxy und Netzwerk gelten für alle Quellen und stehen nicht hier, sondern unter Einstellungen › Netzwerk.
 * Ohne eigene Adresse gilt die öffentliche Confluence-Hilfe von Atlassian. Fehlende Angaben stehen direkt unter dem
 * Feld.
 *
 * <p>Passwort und Token kommen nie in die Datei, dort steht nur der Platzhalter {@code ${NAME}}. Den Wert legt die
 * App im {@link SecretVault} ab (unter Windows mit DPAPI verschlüsselt) und setzt ihn beim Start selbst. „Verbindung
 * testen“ nutzt genau die Eingaben mit einem eigenen Harvester in einem leeren Arbeitsordner, bevor etwas gespeichert
 * ist.
 */
final class SourceForm {

    private static final String CONFLUENCE = "Confluence (Data Center/Server)";
    private static final String FILES = "Lokale Dateien (Ordner)";
    private static final String PAT = "Personal Access Token";
    private static final String BASIC = "Benutzer und Passwort";
    private static final String NONE = "Ohne Anmeldung";
    private static final String NO_CERTIFICATE = "Kein Client-Zertifikat";
    private static final String WINDOWS_MY = "Windows-Zertifikatsspeicher (CurrentUser\\My)";
    private static final String PKCS12 = "PKCS#12-Datei (.p12/.pfx)";
    /** Kein eigenes Bundle: das Vertrauen aller Quellen ({@code space-harvester.network.tls}, #189). */
    static final String NETWORK_TRUST = "Wie für alle Quellen (Einstellungen › Serverzertifikate)";
    /** Öffentliche Confluence-Instanz (Atlassian-Dokumentation), lesbar ohne Anmeldung: Vorgabe zum Ausprobieren. */
    static final String DEFAULT_URL = "https://confluence.atlassian.com";
    static final String DEFAULT_TITLE = "Confluence-Hilfe (Atlassian)";

    private final AppContext context;
    private final ConfigFileEditor editor;
    private final JPanel root = new JPanel(new BorderLayout(0, 10));
    final Feedback feedback = new Feedback();

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
    /** Freigabe für den KI-Dienst ({@code space-harvester.ai.external-processing-sources}); Vorgabe aus. */
    final JCheckBox release = new JCheckBox("Inhalte dieser Quelle dürfen an den KI-Dienst gehen (Embeddings, "
            + "Reranking)");
    /** Was beim Speichern wohin kommt (Datei, Tresor, Sicherung). */
    final JTextArea notes = Comic.note("");
    private final Form form = new Form();
    private final JLabel loginSection = Form.section("Anmeldung");
    private final JLabel certificateSection = Form.section("Client-Zertifikat");
    private final JLabel trustSection = Form.section("Serverzertifikate");

    final ComposerButton test = Comic.button("Verbindung testen", "Spaces live laden, bevor gespeichert wird");
    final ComposerButton save = Comic.primary("Speichern", "Quelle in die Konfigurationsdatei schreiben und neu laden");
    final ComposerButton cancel = Comic.button("Abbrechen", null);

    private final SourceDraft.Editable editing;
    private String suggestedVariable;
    private String suggestedId = "";
    private String suggestedTitle = "";
    private boolean authChosen;
    private boolean deriving;
    private Runnable close = () -> { };
    private Consumer<String> saved = ignored -> { };

    SourceForm(AppContext context) {
        this(context, null);
    }

    /** @param editing bestehender Eintrag zum Bearbeiten oder {@code null} für eine neue Quelle */
    SourceForm(AppContext context, SourceDraft.Editable editing) {
        this.context = context;
        this.editing = editing;
        this.editor = new ConfigFileEditor(context.loader());
        root.setOpaque(false);
        root.setPreferredSize(new Dimension(680, 600));
        Comic.style(sessionValue);

        kind.addItem(CONFLUENCE);
        kind.addItem(FILES);
        auth.addItem(PAT);
        auth.addItem(BASIC);
        auth.addItem(NONE);
        certificate.addItem(NO_CERTIFICATE);
        certificate.addItem(WINDOWS_MY);
        certificate.addItem(PKCS12);
        trust.addItem(NETWORK_TRUST);
        AppSettings settings = context.settings();
        if (settings != null) {
            settings.trustStores().keySet().stream().sorted().forEach(trust::addItem);
        }
        include.setText("*.md, *.txt, *.pdf, *.docx, *.html");
        release.setOpaque(false);
        release.setFont(Comic.regular(13f));
        release.setForeground(Comic.INK);
        kind.addActionListener(e -> {
            updateVisibility();
            derive();
        });
        auth.addActionListener(e -> {
            authChosen |= !deriving;
            suggestVariable();
            updateVisibility();
        });
        certificate.addActionListener(e -> updateVisibility());

        form.header(Form.section("Quelle"));
        form.row("Art", kind);
        form.row("Adresse", baseUrl, "Startadresse von Confluence ohne /rest; leer lassen für die Confluence-Hilfe");
        form.row("Ordner", row(directory, browse(directory, true)), null);
        form.row("Dateien", include, "Muster, durch Komma getrennt");
        form.row("Anzeigename", title, "So heißt die Quelle in der App");
        form.row("Technische Kennung", id, "Wird aus der Adresse vorgeschlagen; nur ändern, wenn nötig");
        onEdit(baseUrl, this::derive);
        onEdit(directory, this::derive);
        onEdit(id, this::suggestVariable);

        form.header(loginSection);
        form.row("Anmeldung", auth);
        form.row("Benutzer", username);
        form.row("Token", sessionValue, " ");
        form.row("Platzhalter", variable, "Name in der Datei (${NAME}); die App setzt den Wert selbst");

        form.header(certificateSection);
        form.row("Client-Zertifikat", certificate, "mTLS, zusätzlich zur Anmeldung oder ohne sie");
        form.row("Alias", row(alias, chooseCertificate), "Zertifikat im Speicher bzw. in der Datei");
        form.row("Fingerabdruck", thumbprint, "Optional, SHA-1 oder SHA-256; prüft zusätzlich (kein Geheimnis)");
        form.row("PKCS#12-Datei", row(certificatePath, browse(certificatePath, false)), null);
        form.row("Passwort-Platzhalter", certificatePasswordVariable,
                "Java-Property oder Umgebungsvariable mit dem Zertifikatspasswort");
        chooseCertificate.addActionListener(e -> {
            String chosen = CertificateChooser.choose(root, alias.getText().trim());
            if (chosen != null) {
                alias.setText(chosen);
            }
        });

        form.header(trustSection);
        form.row("Vertrauen", trust, "Wem die App bei Serverzertifikaten dieser Quelle glaubt; eigene Truststores "
                + "unter spring.ssl.bundle");

        form.header(Form.section("KI-Dienst"));
        boolean aiConfigured = settings != null && settings.ai().configured();
        form.row("Freigabe", release, aiConfigured ? "Vorgabe aus; ohne Freigabe keine Embeddings und kein Reranking "
                + "für diese Quelle" : "Wirkt erst, wenn unter Einstellungen › KI-Dienst ein Dienst eingetragen ist");
        sessionValue.getDocument().addDocumentListener(changes(this::renderNotes));

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setOpaque(false);
        content.add(form, BorderLayout.NORTH);
        content.add(notes, BorderLayout.CENTER);
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(test);
        buttons.add(save);
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(feedback, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(Comic.scroll(content), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        test.addActionListener(e -> test(() -> { }));
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> close.run());
        if (editing != null) {
            prefill(editing.draft());
        }
        updateVisibility();
        derive();
        suggestVariable();
        renderNotes();
    }

    /** Öffnet das Formular modal für eine neue Quelle; {@code saved} erhält nach Speichern und Neu laden ihre ID. */
    static void open(Component parent, AppContext context, Consumer<String> saved) {
        open(parent, context, null, saved);
    }

    /** Wie {@link #open(Component, AppContext, Consumer)}, aber zum Bearbeiten eines bestehenden Eintrags. */
    static void open(Component parent, AppContext context, SourceDraft.Editable editing, Consumer<String> saved) {
        SourceForm form = new SourceForm(context, editing);
        String heading = editing == null ? "Wissensquelle hinzufügen" : "Wissensquelle bearbeiten";
        JDialog dialog = Dialogs.dialog(parent, heading);
        form.saved = saved;
        form.close = dialog::dispose;
        Dialogs.frame(dialog, heading, form.component(), dialog::dispose);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                form.clearSecret();
            }
        });
        Dialogs.present(dialog, new Dimension(680, 680));
    }

    JComponent component() {
        return root;
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
                !confluence || NETWORK_TRUST.equals(bundle) ? null : bundle,
                editing == null ? null : editing.draft().extraOptions());
    }

    /** Die eingetragene Adresse; leer heißt die öffentliche Confluence-Hilfe. */
    private String address() {
        return baseUrl.getText().isBlank() ? DEFAULT_URL : baseUrl.getText();
    }

    /**
     * Prüft alle Eingaben und schreibt jeden Hinweis unter sein Feld; was keinem Feld gehört, steht in der Meldung.
     *
     * @return ob die Quelle so gespeichert werden kann
     */
    boolean validateInput() {
        form.clearErrors();
        List<String> problems = problems();
        if (problems.isEmpty()) {
            return true;
        }
        JComponent first = null;
        List<String> unplaced = new ArrayList<>();
        for (String problem : problems) {
            JComponent field = field(problem);
            if (form.has(field)) {
                form.error(field, shortly(problem));
                first = first == null ? field : first;
            } else {
                unplaced.add(problem);
            }
        }
        if (!unplaced.isEmpty()) {
            feedback.warning("Bitte prüfen", String.join(" ", unplaced));
        } else {
            feedback.clear();
        }
        if (first != null) {
            first.requestFocusInWindow();
        }
        return false;
    }

    /** Testet die Verbindung mit den Eingaben, ohne etwas zu speichern. */
    void test(Runnable done) {
        if (!validateInput()) {
            done.run();
            return;
        }
        SourceDraft source = draft();
        ConfigFileEditor.Draft draft;
        try {
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
                check -> {
                    test.setEnabled(true);
                    if (check.successful()) {
                        feedback.success(check.message());
                    } else {
                        feedback.warning("Verbindung fehlgeschlagen", check.message());
                    }
                    done.run();
                },
                error -> {
                    test.setEnabled(true);
                    feedback.problem(error);
                    done.run();
                });
    }

    /** Prüft, schreibt die Quelle (und eine geänderte KI-Freigabe) in die Datei und lädt die Konfiguration neu. */
    void save() {
        if (!validateInput() || !Dialogs.restartAllowed(root, context)) {
            return;
        }
        SourceDraft source = draft();
        char[] value = sessionValue.getPassword();
        feedback.working("Wird gespeichert …");
        save.setEnabled(false);
        AppRuntime runtime = context.runtime();
        AppRuntime.SessionSecrets secrets = sessionSecrets(source);
        AppSettings before = context.settings();
        List<SourceSettingsConfig> configured = before == null ? List.of()
                : before.sources().stream().map(AppSettings.SourceEntry::config).toList();
        AiDraft released = releaseChanged(before, source.id()) ? withRelease(before, source.id(), release.isSelected())
                : null;
        Background.run(() -> {
            if (runtime != null) {
                // Prüfung des Core (#188), u. a. dasselbe Ziel unter neuer ID; vor jedem Schreiben, damit bei einem
                // Problem nichts landet. Ohne Geheimnis der Sitzung prüft erst der Neustart.
                SourceSettingsConfig config = null;
                try {
                    config = AppRuntime.config(write(source).settings(), source.id(), secrets);
                } catch (IllegalStateException missingSecret) {
                    // siehe oben
                }
                List<String> problems = config == null ? List.of()
                        : runtime.workspace().sourceProblems(config, editing != null, configured);
                if (!problems.isEmpty()) {
                    throw new IllegalArgumentException(String.join("\n", problems));
                }
            }
            if (released != null) {
                // Vor der Quelle: Scheitert die Quelle danach, schreibt ein zweiter Versuch denselben Block erneut.
                AiSettingsPanel.store(context, released, new char[0]);
            }
            ConfigFileEditor.Draft written = write(source);
            SourceSaving.store(context, editor, editing, source, written, value);
            if (editing != null && runtime != null) {
                // Zeigt die Quelle jetzt auf ein anderes Ziel, entfernt der Core ihre bisherigen Daten (#188).
                try {
                    runtime.workspace().changeSource(AppRuntime.config(written.settings(), source.id(), secrets));
                } catch (RuntimeException e) {
                    // Ohne gültige Einstellungen bleibt es beim Neuladen; dessen Prüfung nennt das Problem.
                }
            }
            return source.id();
        }, savedId -> {
            SourceSaving.remember(context, editing, source, value);
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

    /** Ob die Freigabe vom Stand der Datei abweicht. */
    private boolean releaseChanged(AppSettings settings, String sourceId) {
        boolean saved = settings != null && settings.ai().externalProcessingSources().contains(sourceId);
        return saved != release.isSelected();
    }

    /** Die KI-Einstellungen der Datei mit dieser Quelle in den Freigaben oder ohne sie. */
    static AiDraft withRelease(AppSettings settings, String sourceId, boolean released) {
        AiDraft current = settings == null ? AiDraft.EMPTY : AiDraft.of(settings.ai());
        List<String> ids = new ArrayList<>(current.externalProcessingSources());
        ids.remove(sourceId);
        if (released) {
            ids.add(sourceId);
        }
        return current.withService(current.baseUrl(), ids);
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

    /** Der neue Dateitext mit der Quelle; der Proxy für alle Quellen bleibt, wie er ist. */
    private ConfigFileEditor.Draft write(SourceDraft source) throws IOException {
        return editing == null ? editor.draft(context.configFile(), source)
                : editor.replace(context.configFile(), source);
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
        AppSettings settings = context.settings();
        if (TrustStores.followsNetwork(source.trustBundle(), settings == null ? Map.of() : settings.trustStores())) {
            trust.setSelectedItem(NETWORK_TRUST);
        } else {
            boolean listed = false;
            for (int i = 0; i < trust.getItemCount(); i++) {
                listed |= source.trustBundle().equals(trust.getItemAt(i));
            }
            if (!listed) {
                trust.addItem(source.trustBundle());
            }
            trust.setSelectedItem(source.trustBundle());
        }
        release.setSelected(settings != null && settings.ai().externalProcessingSources().contains(source.id()));
    }

    void clearSecret() {
        sessionValue.setText("");
    }

    /** Ob für Token bzw. Passwort schon ein Wert da ist: eingegeben, in der Sitzung, im Tresor oder als Property. */
    private boolean secretAvailable(SourceDraft source) {
        return SourceSaving.secretAvailable(context, editing, source, sessionValue.getPassword().length > 0);
    }

    AppRuntime.SessionSecrets sessionSecrets(SourceDraft source) {
        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        // „Verbindung testen“ geht über denselben Proxy, also mit dessen Passwort.
        secrets.proxyPassword(context.secrets().proxyPassword());
        String sourceId = source.id();
        char[] value = sessionValue.getPassword();
        if (value.length > 0 && sourceId != null) {
            secrets.put(sourceId, SourceSaving.login(source), value);
        } else if (sourceId != null && context.secrets().has(sourceId, SourceSaving.login(source))
                && !SourceSaving.loginChanged(editing, source)) {
            return context.secrets();
        }
        return secrets;
    }

    private List<String> problems() {
        SourceDraft source = draft();
        List<String> problems = new ArrayList<>(source.problems());
        if (source.confluence() && !secretAvailable(source)) {
            problems.add(source.authType() == SourceSettingsConfig.AuthType.PAT ? "Das Token fehlt."
                    : "Das Passwort fehlt.");
        }
        if (editing == null && source.id() != null && context.settings() != null
                && context.settings().sources().stream().anyMatch(s -> s.id().equals(source.id()))) {
            problems.add("Eine Quelle mit der ID " + source.id() + " gibt es schon.");
        }
        return problems;
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

    /** Schlägt den Platzhalter aus Kennung und Anmeldeart vor, solange niemand ihn geändert hat. */
    private void suggestVariable() {
        if (editing != null && !variable.getText().isBlank() && !variable.getText().equals(suggestedVariable)) {
            return;
        }
        SourceSettingsConfig.AuthType authType = BASIC.equals(auth.getSelectedItem())
                ? SourceSettingsConfig.AuthType.BASIC : SourceSettingsConfig.AuthType.PAT;
        String suggestion = id.getText().isBlank() ? "" : SourceDraft.suggestedVariable(id.getText(), authType);
        if (variable.getText().isBlank() || variable.getText().equals(suggestedVariable)) {
            variable.setText(suggestion);
            suggestedVariable = suggestion;
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
        field.getDocument().addDocumentListener(changes(action));
    }

    private static javax.swing.event.DocumentListener changes(Runnable action) {
        return new javax.swing.event.DocumentListener() {
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
        };
    }

    /** Was beim Speichern wohin kommt: Datei, Sicherung, Tresor, Zertifikatspasswort. */
    private void renderNotes() {
        SourceDraft source = draft();
        StringBuilder text = new StringBuilder(editing == null ? "Speichern trägt die Quelle in " : "Speichern ersetzt ")
                .append(editing == null ? "" : "den Eintrag der Quelle " + source.id() + " in ")
                .append(context.configFile().toAbsolutePath()).append(" unter space-harvester.sources ein; der Rest "
                        + "der Datei bleibt unverändert");
        // Dieselbe Entscheidung wie beim Speichern: Steht irgendwo in der Datei ein Geheimnis im Klartext, entsteht
        // keine .bak (eine vorhandene wird gelöscht).
        if (!dropsBackup()) {
            text.append(", die vorige Fassung bleibt als .bak erhalten.");
        } else {
            text.append(". Weil die Konfigurationsdatei ein Passwort oder Token im Klartext enthält, entsteht "
                    + "KEINE .bak-Sicherung, und eine vorhandene wird gelöscht.");
            if (!editing.plaintextSecrets().isEmpty()) {
                text.append(" In diesem Eintrag betrifft das ").append(String.join(" und ",
                        editing.plaintextSecrets())).append("; dort steht künftig nur der Platzhalter.");
            }
        }
        if (source.secretVariable() != null) {
            String what = source.secretName();
            text.append("\n").append(Character.toUpperCase(what.charAt(0))).append(what.substring(1))
                    .append(" steht nie in der Datei, sondern wird ").append(context.vault().describe())
                    .append(" gespeichert; die App setzt es beim Start selbst.");
        }
        if (source.certificatePasswordVariable() != null && System.getenv(source.certificatePasswordVariable()) == null
                && System.getProperty(source.certificatePasswordVariable()) == null) {
            text.append("\nDas Zertifikatspasswort kommt aus der Java-Property bzw. Umgebungsvariable ")
                    .append(source.certificatePasswordVariable()).append("; sie ist noch nicht gesetzt.");
        }
        notes.setText(text.toString());
    }

    private void updateVisibility() {
        boolean confluence = CONFLUENCE.equals(kind.getSelectedItem());
        form.visible(baseUrl, confluence);
        form.visible(directory, !confluence);
        form.visible(include, !confluence);
        loginSection.setVisible(confluence);
        certificateSection.setVisible(confluence);
        trustSection.setVisible(confluence);
        form.visible(auth, confluence);
        boolean basic = BASIC.equals(auth.getSelectedItem());
        boolean anonymous = NONE.equals(auth.getSelectedItem());
        form.visible(username, confluence && basic);
        form.visible(variable, confluence && !anonymous);
        form.visible(sessionValue, confluence && !anonymous);
        form.label(sessionValue, basic ? "Passwort" : "Token");
        form.hint(sessionValue, secretHint());
        form.visible(certificate, confluence);
        boolean windows = confluence && WINDOWS_MY.equals(certificate.getSelectedItem());
        boolean pkcs12 = confluence && PKCS12.equals(certificate.getSelectedItem());
        form.visible(thumbprint, windows || pkcs12);
        form.visible(alias, windows || pkcs12);
        chooseCertificate.setVisible(windows);
        form.visible(certificatePath, pkcs12);
        form.visible(certificatePasswordVariable, pkcs12);
        form.visible(trust, confluence);
        if (pkcs12 && certificatePasswordVariable.getText().isBlank()) {
            String base = id.getText().isBlank() ? "CONFLUENCE" : SourceDraft.suggestedVariable(id.getText(),
                    SourceSettingsConfig.AuthType.PAT).replaceAll("_TOKEN$", "");
            certificatePasswordVariable.setText(base + "_CLIENT_PASSWORD");
        }
        renderNotes();
        root.revalidate();
        root.repaint();
    }

    /** Wo Token bzw. Passwort landet; beim Bearbeiten, ob schon eins gespeichert ist. */
    private String secretHint() {
        String stored = editing != null && (context.vault().has(editing.draft().id(), SourceSaving.login(editing.draft()))
                || context.secrets().has(editing.draft().id(), SourceSaving.login(editing.draft())))
                ? "Schon hinterlegt; leer lassen zum Behalten. " : "";
        return stored + (context.vault().available() ? "Wird verschlüsselt für dein Windows-Konto gespeichert, nie in "
                + "der Datei" : "Gilt bis zum Schließen der App, nie in der Datei");
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

    /** Der Hinweis unter einem Feld, solange dort ein Fehler steht; sonst {@code null}. */
    String error(JComponent field) {
        return form.error(field);
    }

    /**
     * Formular mit Abschnittsüberschriften und je Zeile Beschriftung, Feld und Hinweiszeile; Zeilen lassen sich
     * ausblenden. Ein Fehler ersetzt den Hinweis in derselben Zeile (rot), so bleibt die Höhe gleich; Tippen im Feld
     * nimmt ihn wieder weg.
     */
    private static final class Form extends JPanel {

        private final List<Row> rows = new ArrayList<>();
        private int y;

        private record Row(JComponent key, JLabel label, JComponent field, JLabel hint, String text) {
        }

        Form() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        static JLabel section(String text) {
            JLabel label = new JLabel(text);
            label.setFont(Comic.semiBold(14f));
            label.setForeground(Comic.INK);
            return label;
        }

        void header(JLabel label) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = y++;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(y == 1 ? 0 : 14, 0, 2, 0);
            add(label, c);
        }

        void row(String label, JComponent field) {
            row(label, field, null);
        }

        void row(String text, JComponent field, String hint) {
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
            y += 2;
            JComponent key = field instanceof JPanel panel && panel.getComponentCount() > 0
                    && panel.getComponent(0) instanceof JComponent inner ? inner : field;
            rows.add(new Row(key, label, field, hintLabel, hint == null ? " " : hint));
            if (key instanceof JTextField textField) {
                onEdit(textField, () -> clear(key));
            }
        }

        boolean has(JComponent field) {
            return field != null && rows.stream().anyMatch(row -> row.key() == field && row.field().isVisible());
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
                    rows.set(i, new Row(row.key(), row.label(), row.field(), row.hint(), text));
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
}
