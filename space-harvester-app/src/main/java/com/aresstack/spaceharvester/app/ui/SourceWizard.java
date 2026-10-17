package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import javax.swing.BorderFactory;
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
 * Quellen-Assistent: legt eine Wissensquelle in der Konfigurationsdatei an oder ändert eine bestehende an ihrer Stelle. Schritte: Art, Adresse, Anmeldung,
 * Zertifikate und Netz, Prüfen und Speichern. Passwort und Token kommen nie in die Datei, dort steht nur der
 * Platzhalter der Umgebungsvariable; ein hier eingegebener Wert gilt nur für den Test und diese Sitzung. Der
 * Verbindungstest läuft mit einem eigenen Harvester in einem leeren Arbeitsordner, bevor etwas gespeichert ist.
 */
final class SourceWizard {

    enum Step {
        KIND("Art der Quelle"), ADDRESS("Adresse"), AUTH("Anmeldung"), SECURITY("Zertifikate und Netz"),
        CHECK("Prüfen und speichern");

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
    final JTextField baseUrl = Comic.field(32);
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
    private final JTextArea preview = Comic.code();
    final JTextArea checkNotes = Comic.note("");
    private final JLabel variableState = Comic.muted(" ");
    private final Form address = new Form();
    private final Form login = new Form();
    private final Form security = new Form();

    final ComposerButton back = Comic.button("Zurück", null);
    final ComposerButton next = Comic.primary("Weiter", null);
    final ComposerButton test = Comic.button("Verbindung testen", "Spaces live laden, bevor gespeichert wird");
    final ComposerButton save = Comic.primary("Speichern", "Quelle in die Konfigurationsdatei schreiben und neu laden");
    final ComposerButton cancel = Comic.button("Abbrechen", null);

    private final SourceDraft.Editable editing;
    private Step step = Step.KIND;
    private String suggestedVariable;
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
        root.setPreferredSize(new Dimension(640, 470));
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
        include.setText("*.md, *.txt, *.pdf, *.docx, *.html");
        kind.addActionListener(e -> updateVisibility());
        auth.addActionListener(e -> updateVisibility());
        certificate.addActionListener(e -> updateVisibility());

        Form kindForm = new Form();
        kindForm.row("Art", kind);
        kindForm.row("ID", id, "Kleinbuchstaben, Ziffern, Bindestrich, z. B. wiki; erscheint in Aufträgen und Exporten");
        kindForm.row("Titel", title, "Anzeigename, optional");

        address.row("Adresse", baseUrl, "z. B. https://confluence.example.org (ohne /rest)");
        address.row("Ordner", row(directory, browse(directory, true)), null);
        address.row("Dateien", include, "Muster, durch Komma getrennt");

        login.row("Anmeldung", auth);
        login.row("Benutzer", username);
        login.row("Umgebungsvariable", variable,
                "Die Datei enthält nur ${NAME}. Den Wert unter Windows einmalig setzen: setx NAME \"…\"");
        login.row("", variableState, null);
        login.row("Wert für diese Sitzung", sessionValue,
                "Optional: für den Test und bis zum Schließen der App; wird nie gespeichert");

        security.row("Client-Zertifikat", certificate, "mTLS zusätzlich zur Anmeldung");
        security.row("Alias", alias, "Name des Zertifikats im Speicher bzw. in der Datei");
        security.row("Fingerabdruck", thumbprint, "optional, SHA-1 oder SHA-256; prüft zusätzlich (kein Geheimnis)");
        security.row("PKCS#12-Datei", row(certificatePath, browse(certificatePath, false)), null);
        security.row("Passwort-Variable", certificatePasswordVariable, "Umgebungsvariable mit dem Zertifikatspasswort");
        security.row("Vertrauen", trust, "Eigene Zertifizierungsstellen stehen unter spring.ssl.bundle");
        security.row("Proxy", Comic.text(proxy()), "Gilt für alle Quellen; in der Datei unter space-harvester.network");

        JPanel check = new JPanel(new BorderLayout(0, 8));
        check.setOpaque(false);
        check.add(checkNotes, BorderLayout.NORTH);
        JComponent scroll = Comic.scroll(preview);
        scroll.setPreferredSize(new Dimension(560, 200));
        check.add(scroll, BorderLayout.CENTER);

        pages.setOpaque(false);
        pages.add(kindForm, Step.KIND.name());
        pages.add(address, Step.ADDRESS.name());
        pages.add(login, Step.AUTH.name());
        pages.add(security, Step.SECURITY.name());
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
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(feedback, BorderLayout.CENTER);
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
        show(Step.KIND);
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
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(Comic.SURFACE);
        content.setBorder(BorderFactory.createCompoundBorder(ComicBorder.windowBorder(Comic.PALETTE, 2),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));
        JLabel label = new JLabel(heading);
        label.setFont(Comic.semiBold(16f));
        label.setForeground(Comic.INK);
        content.add(label, BorderLayout.NORTH);
        content.add(wizard.component(), BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                wizard.clearSecret();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
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
                title.getText(), confluence ? baseUrl.getText() : null, confluence ? null : directory.getText(),
                confluence ? null : include.getText(), authType,
                authType == SourceSettingsConfig.AuthType.BASIC ? username.getText() : null,
                authType == SourceSettingsConfig.AuthType.NONE ? null : variable.getText(), certificateType,
                pkcs12 ? certificatePath.getText() : null, pkcs12 ? certificatePasswordVariable.getText() : null,
                certificateType == null ? null : alias.getText(),
                certificateType == null ? null : thumbprint.getText(),
                !confluence || JAVA_TRUST.equals(bundle) ? null : bundle,
                editing == null ? null : editing.draft().extraOptions());
    }

    /** Nächster Schritt, wenn die Eingaben dieses Schritts vollständig sind. */
    boolean next() {
        List<String> problems = problems(step);
        if (!problems.isEmpty()) {
            feedback.warning("Bitte ergänzen", String.join("\n", problems));
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
            show(steps.get(index - 1));
        }
    }

    /** Testet die Verbindung mit den Eingaben, ohne etwas zu speichern. */
    void test(Runnable done) {
        SourceDraft source = draft();
        ConfigFileEditor.Draft draft;
        try {
            draft = write(source);
        } catch (IOException | RuntimeException e) {
            feedback.problem(e);
            done.run();
            return;
        }
        AppRuntime.SessionSecrets secrets = sessionSecrets(source.id());
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
            editor.save(context.configFile(), write(source), backup);
            return source.id();
        }, savedId -> {
            if (value.length > 0 && source.authType() != SourceSettingsConfig.AuthType.NONE) {
                context.secrets().put(savedId, value);
            } else if (editing != null && (editing.draft().authType() != source.authType()
                    || !Objects.equals(editing.draft().secretVariable(), source.secretVariable()))) {
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

    private AppRuntime.SessionSecrets sessionSecrets(String sourceId) {
        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        char[] value = sessionValue.getPassword();
        if (value.length > 0 && sourceId != null) {
            secrets.put(sourceId, value);
        } else if (sourceId != null && context.secrets().has(sourceId)) {
            return context.secrets();
        }
        return secrets;
    }

    private List<Step> steps() {
        return CONFLUENCE.equals(kind.getSelectedItem())
                ? List.of(Step.values())
                : List.of(Step.KIND, Step.ADDRESS, Step.CHECK);
    }

    private List<String> problems(Step current) {
        SourceDraft source = draft();
        List<String> all = source.problems();
        List<String> mine = new ArrayList<>();
        for (String problem : all) {
            boolean kindStep = problem.startsWith("Die ID") || problem.startsWith("Die Art");
            boolean addressStep = problem.startsWith("Die Adresse") || problem.startsWith("Der Ordner");
            boolean authStep = problem.startsWith("Der Benutzername") || problem.startsWith("Die Umgebungsvariable für");
            boolean securityStep = !kindStep && !addressStep && !authStep;
            if ((current == Step.KIND && kindStep) || (current == Step.ADDRESS && addressStep)
                    || (current == Step.AUTH && authStep) || (current == Step.SECURITY && securityStep)
                    || current == Step.CHECK) {
                mine.add(problem);
            }
        }
        if (current == Step.KIND && editing == null && source.id() != null && context.settings() != null
                && context.settings().sources().stream().anyMatch(s -> s.id().equals(source.id()))) {
            mine.add("Eine Quelle mit der ID " + source.id() + " gibt es schon.");
        }
        return mine;
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
        preview.setText(source.yaml(""));
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
                        editing.plaintextSecrets())).append("; dort steht künftig nur der Platzhalter. Den Wert als "
                        + "Umgebungsvariable setzen oder hier für diese Sitzung eingeben.");
            }
        }
        if (source.secretVariable() != null && System.getenv(source.secretVariable()) == null
                && System.getProperty(source.secretVariable()) == null) {
            notes.append("\n\nDie Umgebungsvariable ").append(source.secretVariable()).append(" ist nicht gesetzt. ")
                    .append(sessionValue.getPassword().length > 0
                            ? "Der eingegebene Wert gilt für den Test und diese Sitzung. "
                            : "Ohne Wert fragt die App beim Start danach. ")
                    .append("Dauerhaft: setx ").append(source.secretVariable()).append(" \"…\" und die App neu starten.");
        }
        if (source.certificatePasswordVariable() != null && System.getenv(source.certificatePasswordVariable()) == null
                && System.getProperty(source.certificatePasswordVariable()) == null) {
            notes.append("\n\nDas Zertifikatspasswort kommt aus der Umgebungsvariable ")
                    .append(source.certificatePasswordVariable()).append("; sie ist noch nicht gesetzt.");
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
        login.visible(variableState, !anonymous);
        login.visible(sessionValue, !anonymous);
        variableState.setText(anonymous ? " " : "Fehlt die Variable, fragt die App beim Start nach dem Wert.");
        boolean windows = WINDOWS_MY.equals(certificate.getSelectedItem());
        boolean pkcs12 = PKCS12.equals(certificate.getSelectedItem());
        security.visible(thumbprint, windows || pkcs12);
        security.visible(alias, windows || pkcs12);
        security.visible(certificatePath, pkcs12);
        security.visible(certificatePasswordVariable, pkcs12);
        if (pkcs12 && certificatePasswordVariable.getText().isBlank()) {
            String base = id.getText().isBlank() ? "CONFLUENCE" : SourceDraft.suggestedVariable(id.getText(),
                    SourceSettingsConfig.AuthType.PAT).replaceAll("_TOKEN$", "");
            certificatePasswordVariable.setText(base + "_CLIENT_PASSWORD");
        }
        root.revalidate();
        root.repaint();
    }

    private String proxy() {
        AppSettings settings = context.settings();
        return settings == null ? "aus" : settings.proxy().mode().name().toLowerCase(Locale.ROOT).replace('_', '-');
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

    /** Formular mit Beschriftung, Feld und Hinweis je Zeile; Zeilen lassen sich ausblenden. */
    private static final class Form extends JPanel {

        private final List<Row> rows = new ArrayList<>();
        private final JPanel filler = new JPanel();

        private record Row(JComponent key, JLabel label, JComponent field, JLabel hint) {
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
            left.insets = new Insets(6, 0, 0, 14);
            add(label, left);
            GridBagConstraints right = new GridBagConstraints();
            right.gridx = 1;
            right.gridy = y;
            right.weightx = 1;
            right.fill = GridBagConstraints.HORIZONTAL;
            right.insets = new Insets(6, 0, 0, 0);
            add(field, right);
            JLabel hintLabel = Comic.muted(hint == null ? "" : hint);
            GridBagConstraints below = new GridBagConstraints();
            below.gridx = 1;
            below.gridy = y + 1;
            below.anchor = GridBagConstraints.WEST;
            add(hintLabel, below);
            hintLabel.setVisible(hint != null);
            JComponent key = field instanceof JPanel panel && panel.getComponentCount() > 0
                    && panel.getComponent(0) instanceof JComponent inner ? inner : field;
            rows.add(new Row(key, label, field, hint == null ? null : hintLabel));
            // Ein dehnbarer Rest unter der letzten Zeile hält das Formular oben.
            GridBagConstraints rest = new GridBagConstraints();
            rest.gridx = 0;
            rest.gridy = 1000;
            rest.weighty = 1;
            remove(filler);
            add(filler, rest);
        }

        void visible(JComponent field, boolean visible) {
            for (Row row : rows) {
                if (row.key() == field || row.field() == field) {
                    row.label().setVisible(visible);
                    row.field().setVisible(visible);
                    if (row.hint() != null) {
                        row.hint().setVisible(visible);
                    }
                }
            }
        }
    }
}
