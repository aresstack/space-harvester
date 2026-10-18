package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.ProxyDraft;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.app.settings.TrustStores;
import com.aresstack.spaceharvester.application.configuration.RefreshStrategy;
import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.network.InvalidNetworkConfigurationException;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Kleine Dialoge für je eine Einstellung einer Quelle, geöffnet vom Stift an der Einstellung: Anmeldung,
 * Client-Zertifikat, Serverzertifikate prüfen und Proxy. Sie zeigen nur das Wesentliche; gespeichert wird wie im
 * Quellenformular ({@link SourceSaving}, {@link ConfigFileEditor}, Tresor), geprüft mit denselben Regeln
 * ({@link SourceDraft#problems()}, {@link ProxyDraft#problems()}). Das {@link SourceForm} bleibt für neue Quellen und
 * „Bearbeiten …“.
 *
 * <p>Übernehmen startet die Quellen neu. Laufen Aufträge, fragt {@link Dialogs#restartAllowed} vorher nach.
 */
final class SettingDialogs {

    static final String TRUST_TITLE = "Serverzertifikate prüfen";
    static final String TRUST_NETWORK = "Wie für alle Quellen";
    static final String TRUST_NOTE = "Damit prüft die App, ob sie wirklich mit dem richtigen Server spricht (HTTPS). "
            + "Das ist unabhängig vom Client-Zertifikat, mit dem sich die App beim Server ausweist.";

    private static final String PAT = "Personal Access Token";
    private static final String BASIC = "Benutzer und Passwort";
    private static final String NONE = "Ohne Anmeldung";
    private static final String NO_CERTIFICATE = "Kein Client-Zertifikat";
    private static final String WINDOWS_MY = "Windows-Zertifikatsspeicher";
    private static final String FILE = "Datei (PKCS#12, .p12/.pfx)";

    private SettingDialogs() {
    }

    /** Beschriftung der Einstellung „Serverzertifikate prüfen“ für ein SSL-Bundle der Quelle. */
    static String trustLabel(String bundle, AppSettings settings) {
        return TrustStores.followsNetwork(bundle, settings == null ? Map.of() : settings.trustStores())
                ? TRUST_NETWORK : "Konfiguriertes Bundle „" + bundle + "“";
    }

    // ------------------------------------------------------------------ Anmeldung

    /** Anmeldeart, Benutzer und Token bzw. Passwort (verschlüsselt im Tresor, nie in der Datei). */
    static Shell auth(Component parent, AppContext context, SourceDraft.Editable editing, Consumer<String> saved) {
        SourceDraft current = editing.draft();
        JComboBox<String> auth = Comic.combo();
        auth.addItem(PAT);
        auth.addItem(BASIC);
        auth.addItem(NONE);
        auth.setSelectedItem(switch (current.authType()) {
            case PAT -> PAT;
            case BASIC -> BASIC;
            case NONE -> NONE;
        });
        JTextField username = Comic.field(24);
        username.setText(current.username() == null ? "" : current.username());
        JPasswordField secret = new JPasswordField(24);
        Comic.style(secret);
        Rows rows = new Rows();
        rows.add("Anmeldung", auth, null);
        rows.add("Benutzer", username, null);
        rows.add("Token", secret, null);
        Supplier<AuthType> type = () -> PAT.equals(auth.getSelectedItem()) ? AuthType.PAT
                : BASIC.equals(auth.getSelectedItem()) ? AuthType.BASIC : AuthType.NONE;
        Supplier<SourceDraft> draft = () -> {
            AuthType chosen = type.get();
            String variable = chosen == AuthType.NONE ? null : variable(current, chosen);
            return copy(current, chosen, chosen == AuthType.BASIC ? username.getText() : null, variable,
                    current.certificateType(), current.certificatePath(), current.certificatePasswordVariable(),
                    current.certificateAlias(), current.thumbprint(), current.trustBundle());
        };
        Runnable visibility = () -> {
            AuthType chosen = type.get();
            rows.visible(username, chosen == AuthType.BASIC);
            rows.visible(secret, chosen != AuthType.NONE);
            rows.label(secret, chosen == AuthType.BASIC ? "Passwort" : "Token");
            SourceDraft source = draft.get();
            boolean stored = !SourceSaving.loginChanged(editing, source)
                    && (context.vault().has(source.id(), SourceSaving.login(source))
                    || context.secrets().has(source.id(), SourceSaving.login(source)));
            rows.hint(secret, (stored ? "Schon hinterlegt; leer lassen zum Behalten. " : "")
                    + (context.vault().available() ? "Wird verschlüsselt für dein Windows-Konto gespeichert, nie in "
                    + "der Datei." : "Gilt bis zum Schließen der App, nie in der Datei."));
            rows.revalidate();
        };
        auth.addActionListener(e -> visibility.run());
        visibility.run();
        Shell shell = new Shell(parent, "Anmeldung · " + title(current), rows);
        shell.onSave(() -> {
            SourceDraft source = draft.get();
            List<String> problems = new ArrayList<>(source.problems());
            if (!SourceSaving.secretAvailable(context, editing, source, secret.getPassword().length > 0)) {
                problems.add(source.authType() == AuthType.PAT ? "Das Token fehlt." : "Das Passwort fehlt.");
            }
            return problems.isEmpty() ? new Change(source, secret.getPassword()) : Change.rejected(problems);
        }, context, editing, saved);
        shell.onClose(() -> secret.setText(""));
        return shell;
    }

    /** Die Variable in der Datei: bleibt, außer sie war der Vorschlag der bisherigen Anmeldeart. */
    private static String variable(SourceDraft current, AuthType chosen) {
        String existing = current.secretVariable();
        if (existing == null || current.authType() != chosen
                && existing.equals(SourceDraft.suggestedVariable(current.id(), current.authType()))) {
            return SourceDraft.suggestedVariable(current.id(), chosen);
        }
        return existing;
    }

    // ------------------------------------------------------------------ Client-Zertifikat

    /**
     * Oben die Herkunft (keins, Windows-Zertifikatsspeicher, Datei), darunter die Auswahl. Aus dem Windows-Speicher
     * kommen Alias und Fingerabdruck aus dem gewählten Zertifikat; getippt wird nichts.
     */
    static Shell certificate(Component parent, AppContext context, SourceDraft.Editable editing,
                             Consumer<String> saved) {
        return certificate(parent, context, editing, saved, CertificateChooser.load());
    }

    static Shell certificate(Component parent, AppContext context, SourceDraft.Editable editing,
                             Consumer<String> saved, List<CertificateChooser.Entry> store) {
        SourceDraft current = editing.draft();
        JComboBox<String> origin = Comic.combo();
        origin.addItem(NO_CERTIFICATE);
        origin.addItem(WINDOWS_MY);
        origin.addItem(FILE);
        origin.setSelectedItem(current.certificateType() == null ? NO_CERTIFICATE
                : current.certificateType() == CertificateStoreType.WINDOWS_MY ? WINDOWS_MY : FILE);
        boolean windowsBefore = current.certificateType() == CertificateStoreType.WINDOWS_MY;
        CertificateChooser.Picker picker = new CertificateChooser.Picker(
                windowsBefore ? current.certificateAlias() : null, store);
        JLabel fingerprint = Comic.muted(" ");
        JLabel kept = Comic.muted(" ");
        JTextField path = Comic.field(32);
        path.setText(current.certificatePath() == null ? "" : current.certificatePath());
        JTextField alias = Comic.field(24);
        alias.setText(!windowsBefore && current.certificateAlias() != null ? current.certificateAlias() : "");
        JTextField password = Comic.field(24);
        password.setText(current.certificatePasswordVariable() == null
                ? SourceDraft.suggestedVariable(current.id(), AuthType.PAT).replaceAll("_TOKEN$", "")
                + "_CLIENT_PASSWORD" : current.certificatePasswordVariable());

        // Windows: Auswahl und daraus der Fingerabdruck. Ein eingetragenes Zertifikat, das dieser Rechner nicht
        // kennt (z. B. außerhalb von Windows), bleibt stehen, solange nichts anderes gewählt wird.
        JPanel windows = new JPanel(new BorderLayout(0, 6));
        windows.setOpaque(false);
        windows.add(picker.component(), BorderLayout.CENTER);
        JPanel facts = new JPanel(new BorderLayout(0, 2));
        facts.setOpaque(false);
        facts.add(fingerprint, BorderLayout.NORTH);
        facts.add(kept, BorderLayout.SOUTH);
        windows.add(facts, BorderLayout.SOUTH);
        Runnable showFingerprint = () -> {
            CertificateChooser.Entry chosen = picker.selected();
            fingerprint.setText(chosen == null ? "Kein Zertifikat gewählt."
                    : "Fingerabdruck (SHA-1): " + chosen.sha1() + " · gültig bis "
                    + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY)
                    .format(chosen.validUntil()));
            boolean unknown = windowsBefore && current.certificateAlias() != null && chosen == null
                    && store.stream().noneMatch(entry -> entry.alias().equals(current.certificateAlias()));
            kept.setText(unknown ? "Eingetragen ist " + current.certificateAlias() + (current.thumbprint() == null
                    ? "" : " (" + Texts.shortHash(current.thumbprint()) + ")")
                    + "; im Speicher dieses Rechners nicht gefunden, bleibt ohne neue Auswahl erhalten." : " ");
        };
        picker.onSelect(showFingerprint);
        showFingerprint.run();

        Rows file = new Rows();
        file.add("Datei", row(path, browse(path)), null);
        file.add("Alias", alias, "Name des Zertifikats in der Datei");
        file.add("Passwort-Platzhalter", password, "Java-Property oder Umgebungsvariable mit dem Dateipasswort");

        // Nur der Teil zur gewählten Herkunft steht im Dialog; das Fenster passt seine Größe beim Wechsel an.
        JPanel detail = new JPanel(new BorderLayout());
        detail.setOpaque(false);
        Map<String, JComponent> parts = Map.of(
                NO_CERTIFICATE, Comic.muted("Die App meldet sich ohne Client-Zertifikat (kein mTLS)."),
                WINDOWS_MY, windows, FILE, file);
        Runnable switchOrigin = () -> {
            detail.removeAll();
            detail.add(parts.get(String.valueOf(origin.getSelectedItem())), BorderLayout.NORTH);
            detail.revalidate();
            detail.repaint();
            java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(detail);
            if (window != null) {
                window.pack();
            }
        };
        origin.addActionListener(e -> switchOrigin.run());
        switchOrigin.run();

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        Rows top = new Rows();
        top.add("Client-Zertifikat", origin, "mTLS: zusätzlich zur Anmeldung oder ohne sie");
        body.add(top, BorderLayout.NORTH);
        body.add(detail, BorderLayout.CENTER);

        Shell shell = new Shell(parent, "Client-Zertifikat · " + title(current), body);
        shell.onSave(() -> {
            Object chosenOrigin = origin.getSelectedItem();
            SourceDraft source;
            if (WINDOWS_MY.equals(chosenOrigin)) {
                CertificateChooser.Entry chosen = picker.selected();
                if (chosen == null && !(windowsBefore && current.certificateAlias() != null)) {
                    return Change.rejected(List.of(picker.empty()
                            ? "Im Windows-Zertifikatsspeicher dieses Kontos gibt es kein Zertifikat mit Schlüssel."
                            : "Bitte ein Zertifikat aus der Liste wählen."));
                }
                String chosenAlias = chosen == null ? current.certificateAlias() : chosen.alias();
                String thumbprint = chosen == null ? current.thumbprint() : chosen.sha1();
                source = copy(current, current.authType(), current.username(), current.secretVariable(),
                        CertificateStoreType.WINDOWS_MY, null, null, chosenAlias, thumbprint, current.trustBundle());
            } else if (FILE.equals(chosenOrigin)) {
                boolean same = current.certificateType() == CertificateStoreType.PKCS12
                        && Objects.equals(current.certificatePath(), path.getText().trim())
                        && Objects.equals(current.certificateAlias(), alias.getText().trim());
                source = copy(current, current.authType(), current.username(), current.secretVariable(),
                        CertificateStoreType.PKCS12, path.getText(), password.getText(), alias.getText(),
                        same ? current.thumbprint() : null, current.trustBundle());
            } else {
                source = copy(current, current.authType(), current.username(), current.secretVariable(),
                        null, null, null, null, null, current.trustBundle());
            }
            List<String> problems = source.problems();
            return problems.isEmpty() ? new Change(source, new char[0]) : Change.rejected(problems);
        }, context, editing, saved);
        return shell;
    }

    // ------------------------------------------------------------------ Serverzertifikate prüfen

    /**
     * Wem die App bei Serverzertifikaten dieser Quelle glaubt: wie alle Quellen ({@code space-harvester.network.tls},
     * #189) oder ein konfiguriertes Bundle. Die bisherige Einstellung bleibt gewählt; das frühere Bundle „windows“
     * gilt als „wie alle Quellen“.
     */
    static Shell trust(Component parent, AppContext context, SourceDraft.Editable editing, Consumer<String> saved) {
        SourceDraft current = editing.draft();
        AppSettings settings = context.settings();
        Map<String, AppSettings.TrustStoreSettings> stores = settings == null ? Map.of() : settings.trustStores();
        String selected = TrustStores.followsNetwork(current.trustBundle(), stores) ? null : current.trustBundle();
        List<String> bundles = new ArrayList<>();
        bundles.add(null);
        stores.keySet().stream().sorted().forEach(bundles::add);
        if (!bundles.contains(selected)) {
            bundles.add(selected);
        }
        Map<JRadioButton, String> choices = new LinkedHashMap<>();
        ButtonGroup group = new ButtonGroup();
        JPanel options = new JPanel();
        options.setOpaque(false);
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        for (String bundle : bundles) {
            JRadioButton button = new JRadioButton(trustLabel(bundle, settings) + (bundle == null
                    ? " · " + (settings == null ? context.loader().defaultTls() : settings.tls()).describe()
                    + " (Einstellungen › Serverzertifikate)" : " · Truststore aus spring.ssl.bundle"));
            button.setOpaque(false);
            button.setFont(Comic.regular(13f));
            button.setSelected(Objects.equals(bundle, selected));
            group.add(button);
            options.add(button);
            choices.put(button, bundle);
        }
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(wrapped(TRUST_NOTE), BorderLayout.NORTH);
        body.add(options, BorderLayout.CENTER);
        Shell shell = new Shell(parent, TRUST_TITLE + " · " + title(current), body);
        shell.onSave(() -> {
            // Kein Stream: „wie alle Quellen“ ist null, und findFirst verträgt kein null.
            String bundle = selected;
            for (Map.Entry<JRadioButton, String> entry : choices.entrySet()) {
                if (entry.getKey().isSelected()) {
                    bundle = entry.getValue();
                }
            }
            SourceDraft source = copy(current, current.authType(), current.username(), current.secretVariable(),
                    current.certificateType(), current.certificatePath(), current.certificatePasswordVariable(),
                    current.certificateAlias(), current.thumbprint(), bundle);
            return new Change(source, new char[0]);
        }, context, editing, saved);
        return shell;
    }

    static final String CACHE_TITLE = "Cache und Aufbewahrung";
    static final String CACHE_MAX_AGE = "Frist";
    static final String REFRESH_TITLE = "Aktualisierung gespeicherter Seiten";
    static final String ALWAYS_CHECK = "Immer Version prüfen";
    static final String TIMED_WITHOUT_CHECK = "Zeitbasiert ohne Versionsprüfung";
    static final String TIMED_WITH_CHECK = "Zeitbasiert mit Versionsprüfung";
    static final List<String> REFRESH_STRATEGIES = List.of(ALWAYS_CHECK, TIMED_WITHOUT_CHECK, TIMED_WITH_CHECK);

    /**
     * Die Strategie zu {@code CacheFreshness(maxAge, versionCheck)}: Frist 0 heißt immer Version prüfen (ohne Frist
     * und ohne Versionsprüfung gibt es nicht, der Core prüft dann ebenfalls bei jeder Anzeige).
     */
    static String refreshStrategy(AppSettings.Cache cache) {
        if (cache.maxAge().isZero()) {
            return ALWAYS_CHECK;
        }
        return cache.versionCheck() ? TIMED_WITH_CHECK : TIMED_WITHOUT_CHECK;
    }
    static final String WITHHELD_RETENTION = "Gesperrte Seiten entfernen nach";
    static final String TLS_TITLE = "Serverzertifikate für alle Quellen";

    /**
     * Vertrauen für alle Quellen und den KI-Dienst ohne eigenes Bundle ({@code space-harvester.network.tls}, #189),
     * wie {@code network.tls} im Enterprise AI Client: drei Schalter und eine CA-Datei. Geprüft und angewandt vom Core
     * ({@code TrustPolicy}); die App liest keinen Zertifikatspeicher selbst.
     */
    static Shell tls(Component parent, AppContext context, Runnable saved) {
        TrustSettings current = context.settings() != null ? context.settings().tls() : context.loader().defaultTls();
        JCheckBox jvm = checkbox("Zertifizierungsstellen der Java-Laufzeit", current.useJvmDefault());
        JCheckBox root = checkbox("Windows-Stammzertifikate (wie im Browser)", current.useWindowsRoot());
        JCheckBox stores = checkbox("Windows-Speicher Root und CA samt Zwischenzertifikaten",
                current.useWindowsCaStores());
        JTextField file = Comic.field(32);
        file.setText(current.caCertificatesFile() == null ? "" : current.caCertificatesFile().toString());
        JPanel switches = new JPanel();
        switches.setOpaque(false);
        switches.setLayout(new BoxLayout(switches, BoxLayout.Y_AXIS));
        switches.add(jvm);
        switches.add(root);
        switches.add(stores);
        Rows rows = new Rows();
        rows.add("Vertrauen", switches, "Ein Zertifikat gilt, sobald eine eingeschaltete Quelle es akzeptiert. "
                + "Die Windows-Speicher gibt es nur unter Windows.");
        rows.add("CA-Datei", row(file, browse(file)), "PEM oder DER, z. B. die CA eines TLS-prüfenden Firmen-Proxys; "
                + "leer ohne");
        Supplier<TrustSettings> draft = () -> new TrustSettings(jvm.isSelected(), root.isSelected(),
                stores.isSelected(), file.getText().isBlank() ? null : java.nio.file.Path.of(file.getText().strip()));
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(wrapped(TRUST_NOTE + " Diese Einstellung gilt für alle Quellen ohne eigenes Bundle und den "
                + "KI-Dienst (space-harvester.network.tls)."), BorderLayout.NORTH);
        body.add(rows, BorderLayout.CENTER);
        Shell shell = new Shell(parent, TLS_TITLE, body);
        shell.onSaveFile(() -> {
            try {
                return TrustPolicy.problems(draft.get());
            } catch (java.nio.file.InvalidPathException e) {
                return List.of("Der Pfad der CA-Datei ist ungültig.");
            }
        }, () -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(context.configFile())
                    ? Files.readString(context.configFile(), StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withTls(context.configFile(),
                    new ConfigFileEditor.Draft(text, null), draft.get());
            editor.save(context.configFile(), written, !editor.fileHasPlaintextSecret(context.configFile()));
        }, () -> {
            try {
                return draft.get().equals(current);
            } catch (java.nio.file.InvalidPathException e) {
                return false;
            }
        }, context, saved);
        return shell;
    }

    /**
     * Cache und Aufbewahrung für alle Quellen, mit denselben Schlüsseln wie der Server: Zeitfenster
     * ({@code space-harvester.cache.max-age}), in dem eine unveränderte Seite ohne Rückfrage bei der Quelle gilt,
     * Versionsprüfung ({@code version-check}) und die Frist, nach der gesperrte Seiten endgültig aus dem Bestand gehen
     * ({@code space-harvester.sync.withheld-retention}, ADR 0014). Die Rechte prüft der Core immer.
     */
    static Shell cache(Component parent, AppContext context, Runnable saved) {
        AppSettings.Cache current = context.settings() != null ? context.settings().cache() : AppSettings.Cache.DEFAULT;
        JTextField maxAge = Comic.field(8);
        maxAge.setText(ConfigFileEditor.duration(current.maxAge()));
        JComboBox<String> refresh = Comic.combo();
        for (String strategy : REFRESH_STRATEGIES) {
            refresh.addItem(strategy);
        }
        refresh.setSelectedItem(refreshStrategy(current));
        if (current.maxAge().isZero()) {
            maxAge.setText("1d");
        }
        Runnable toggle = () -> maxAge.setEnabled(!ALWAYS_CHECK.equals(refresh.getSelectedItem()));
        refresh.addActionListener(e -> toggle.run());
        toggle.run();
        JTextField retention = Comic.field(6);
        // Ganze Tage als Zahl, sonst wie in der Datei (z. B. 12h): Kürzere Fristen gehen so nicht verloren.
        java.time.Duration kept = current.withheldRetention();
        retention.setText(kept.equals(java.time.Duration.ofDays(kept.toDays())) ? String.valueOf(kept.toDays())
                : ConfigFileEditor.duration(kept));
        Rows rows = new Rows();
        rows.add(REFRESH_TITLE, refresh, "Immer Version prüfen: jede Anzeige fragt die Version bei der Quelle ab und "
                + "lädt nur Geänderte neu. Zeitbasiert: innerhalb der Frist gilt die gespeicherte Seite ohne "
                + "Rückfrage, danach ohne Versionsprüfung neu laden bzw. erst die Version vergleichen");
        rows.add(CACHE_MAX_AGE, maxAge, "Frist der zeitbasierten Strategien, z. B. 30m, 6h oder 1d; höchstens 365 Tage");
        rows.add(WITHHELD_RETENTION, retention, "Tage, die eine Seite ohne Leserecht gesperrt im Bestand bleibt, "
                + "bevor ein Abgleich sie endgültig entfernt, oder eine Dauer wie 12h; 0 sofort");
        Supplier<AppSettings.Cache> draft = () -> {
            Object chosen = refresh.getSelectedItem();
            boolean always = ALWAYS_CHECK.equals(chosen);
            java.time.Duration age = always ? java.time.Duration.ZERO : duration(maxAge.getText());
            return new AppSettings.Cache(age, always || TIMED_WITH_CHECK.equals(chosen), days(retention.getText()));
        };
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(wrapped("Gilt für alle Quellen. Leserechte prüft der Core bei jedem Abruf, auch im Zeitfenster."),
                BorderLayout.NORTH);
        body.add(rows, BorderLayout.CENTER);
        Shell shell = new Shell(parent, CACHE_TITLE, body);
        shell.onSaveFile(() -> {
            List<String> problems = new ArrayList<>();
            java.time.Duration age = duration(maxAge.getText());
            if (!ALWAYS_CHECK.equals(refresh.getSelectedItem()) && (age == null || age.isZero()
                    || age.compareTo(java.time.Duration.ofDays(365)) > 0)) {
                problems.add("Die Frist ist eine Dauer wie 30m, 6h oder 1d, mehr als 0 und höchstens 365 Tage.");
            }
            if (days(retention.getText()) == null) {
                problems.add("Die Frist für gesperrte Seiten ist eine Zahl von Tagen ab 0 oder eine Dauer wie 12h.");
            }
            return problems;
        }, () -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(context.configFile())
                    ? Files.readString(context.configFile(), StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withCache(context.configFile(),
                    new ConfigFileEditor.Draft(text, null), draft.get());
            editor.save(context.configFile(), written, !editor.fileHasPlaintextSecret(context.configFile()));
        }, () -> current.equals(draft.get()), context, saved);
        return shell;
    }

    static final String SYNC_RULES_TITLE = "Synchronisierung dieser Quelle";
    static final String AS_GLOBAL = "Wie global";
    static final String ON = "An";
    static final String OFF = "Aus";
    static final String CATALOG_ONLY = "Nur katalogisieren";
    static final String SYNC_CACHE = "Cache synchronisieren";
    static final String FULL = "Vollständig synchronisieren";

    /** Beschriftung der Betriebsart. */
    static String modeLabel(SyncMode mode) {
        return switch (mode) {
            case CATALOG_ONLY -> CATALOG_ONLY;
            case SYNC_CACHE -> SYNC_CACHE;
            case FULL -> FULL;
        };
    }

    /** Beschriftung der Aktualisierungsstrategie, wie unter Einstellungen › Cache. */
    static String strategyLabel(RefreshStrategy strategy) {
        return switch (strategy) {
            case ALWAYS_CHECK_VERSION -> ALWAYS_CHECK;
            case TIME_BASED -> TIMED_WITHOUT_CHECK;
            case TIME_BASED_WITH_VERSION_CHECK -> TIMED_WITH_CHECK;
        };
    }

    /**
     * Regeln einer Quelle ({@code space-harvester.sync.sources.<Quelle>}, {@code SourceSyncOverride}) in drei Bereichen:
     * Erkennung, Aktualisierung, Bereinigung. Jedes Feld kann „wie global“ bleiben (leer bzw. {@link #AS_GLOBAL}); alle
     * Änderungsquellen sind global aus, neue Seiten übernimmt die Suche immer.
     */
    static Shell syncRules(Component parent, AppContext context, String sourceId, Runnable saved) {
        AppSettings settings = context.settings();
        SourceSyncOverride current = settings == null ? SourceSyncOverride.NONE : settings.syncRule(sourceId);
        JTextField changeSearch = Comic.field(8);
        changeSearch.setText(current.changeSearchInterval() == null ? ""
                : ConfigFileEditor.duration(current.changeSearchInterval()));
        JTextField inventory = Comic.field(8);
        inventory.setText(current.inventoryInterval() == null ? ""
                : ConfigFileEditor.duration(current.inventoryInterval()));
        JComboBox<String> mode = choice(current.mode() == null ? AS_GLOBAL : modeLabel(current.mode()),
                AS_GLOBAL, CATALOG_ONLY, SYNC_CACHE, FULL);
        JComboBox<String> catchUp = tri(current.catchUpOnStart());
        JComboBox<String> beforeSearch = tri(current.beforeSearch());
        JComboBox<String> events = tri(current.events());
        JComboBox<String> refresh = choice(current.refreshStrategy() == null ? AS_GLOBAL
                : strategyLabel(current.refreshStrategy()), AS_GLOBAL, ALWAYS_CHECK, TIMED_WITHOUT_CHECK,
                TIMED_WITH_CHECK);
        JTextField maxAge = Comic.field(8);
        maxAge.setText(current.maxAge() == null ? "" : ConfigFileEditor.duration(current.maxAge()));
        JTextField retention = Comic.field(8);
        retention.setText(current.withheldRetention() == null ? "" : ConfigFileEditor.duration(current.withheldRetention()));

        Rows rows = new Rows();
        rows.add("Erkennung", Comic.muted("Wie erfährt die App von neuen und geänderten Seiten?"), " ");
        rows.add("Automatische Änderungssuche", changeSearch, "CQL-Abfrage ohne Administratorrechte, z. B. 1h; leer: "
                + "wie global (aus)");
        rows.add("Regelmäßige Bestandsinventur", inventory, "Nur Seitenmetadaten vergleichen, z. B. 1d; leer: wie "
                + "global (aus)");
        rows.add("Betriebsart", mode, "Was Zeitplan und Nachholen laden: nur den Katalog, dazu gecachte Inhalte "
                + "aktuell halten, oder alle Inhalte");
        rows.add("Nachholen beim Start", catchUp, "Änderungen seit dem letzten Lauf beim Start der App abfragen");
        rows.add("Abgleich vor jeder Suche", beforeSearch, "Vor jeder Suche die Änderungen dieser Quelle abfragen");
        rows.add("Webhooks", events, "Nur im Server; Confluence-Administratoren richten sie dort ein");
        rows.add("Aktualisierung", Comic.muted("Wann prüft die App gespeicherte Seiten?"), " ");
        rows.add(REFRESH_TITLE, refresh, "Wie global: wie unter Einstellungen › Cache");
        rows.add(CACHE_MAX_AGE, maxAge, "Frist der zeitbasierten Strategien, z. B. 6h; leer: wie global");
        rows.add("Bereinigung", Comic.muted("Was geschieht mit Seiten, die nicht mehr gefunden werden?"), " ");
        rows.add(WITHHELD_RETENTION, retention, "Seiten ohne Leserecht verschwinden sofort aus der Suche; so lange "
                + "bleiben die Rohdaten, z. B. 30d; leer: wie global");
        Supplier<SourceSyncOverride> draft = () -> new SourceSyncOverride(optional(changeSearch.getText()),
                optional(inventory.getText()), triValue(events), triValue(catchUp), triValue(beforeSearch),
                switch ((String) mode.getSelectedItem()) {
                    case CATALOG_ONLY -> SyncMode.CATALOG_ONLY;
                    case SYNC_CACHE -> SyncMode.SYNC_CACHE;
                    case FULL -> SyncMode.FULL;
                    default -> null;
                },
                switch ((String) refresh.getSelectedItem()) {
                    case ALWAYS_CHECK -> RefreshStrategy.ALWAYS_CHECK_VERSION;
                    case TIMED_WITHOUT_CHECK -> RefreshStrategy.TIME_BASED;
                    case TIMED_WITH_CHECK -> RefreshStrategy.TIME_BASED_WITH_VERSION_CHECK;
                    default -> null;
                },
                optional(maxAge.getText()), optional(retention.getText()));
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(wrapped("Gilt nur für diese Quelle; leere Felder und „Wie global“ nehmen die gemeinsamen Werte. Neue "
                + "Seiten aus der Suche kommen immer in den Cache. Ein vollständiger Abgleich von Hand ist der "
                + "Kreispfeil am Space."), BorderLayout.NORTH);
        body.add(rows, BorderLayout.CENTER);
        Shell shell = new Shell(parent, SYNC_RULES_TITLE, body);
        shell.onSaveFile(() -> {
            List<String> problems = new ArrayList<>();
            for (JTextField field : List.of(changeSearch, inventory)) {
                java.time.Duration value = optional(field.getText());
                if (!field.getText().isBlank() && (value == null || value.compareTo(SyncSettings.MIN_INTERVAL) < 0)) {
                    problems.add("Abstände sind Dauern wie 30m, 6h oder 1d, mindestens eine Minute.");
                    break;
                }
            }
            if (!maxAge.getText().isBlank() && optional(maxAge.getText()) == null
                    || !retention.getText().isBlank() && optional(retention.getText()) == null) {
                problems.add("Fristen sind Dauern wie 6h oder 30d.");
            }
            String chosen = (String) refresh.getSelectedItem();
            AppSettings.Cache global = context.settings() != null ? context.settings().cache() : AppSettings.Cache.DEFAULT;
            if ((TIMED_WITHOUT_CHECK.equals(chosen) || TIMED_WITH_CHECK.equals(chosen)) && maxAge.getText().isBlank()
                    && global.maxAge().isZero()) {
                problems.add("Die zeitbasierte Aktualisierung braucht eine Frist.");
            }
            return problems;
        }, () -> {
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(context.configFile())
                    ? Files.readString(context.configFile(), StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withSyncRule(context.configFile(),
                    new ConfigFileEditor.Draft(text, null), sourceId, draft.get());
            editor.save(context.configFile(), written, !editor.fileHasPlaintextSecret(context.configFile()));
        }, () -> current.equals(draft.get()), context, saved);
        return shell;
    }

    private static JComboBox<String> choice(String selected, String... items) {
        JComboBox<String> box = Comic.combo();
        for (String item : items) {
            box.addItem(item);
        }
        box.setSelectedItem(selected);
        return box;
    }

    private static JComboBox<String> tri(Boolean value) {
        return choice(value == null ? AS_GLOBAL : value ? ON : OFF, AS_GLOBAL, ON, OFF);
    }

    private static Boolean triValue(JComboBox<String> box) {
        Object chosen = box.getSelectedItem();
        return ON.equals(chosen) ? Boolean.TRUE : OFF.equals(chosen) ? Boolean.FALSE : null;
    }

    /** Leer heißt „wie global“; sonst eine Dauer wie in {@link #duration}. */
    private static java.time.Duration optional(String text) {
        return text == null || text.isBlank() ? null : duration(text);
    }

    /** Eine Dauer wie 0, 45s, 30m, 6h oder 1d; {@code null}, wenn sie so nicht lesbar ist. */
    static java.time.Duration duration(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,6})\\s*([smhd]?)")
                .matcher(text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "m" -> java.time.Duration.ofMinutes(amount);
            case "h" -> java.time.Duration.ofHours(amount);
            case "d" -> java.time.Duration.ofDays(amount);
            default -> java.time.Duration.ofSeconds(amount);
        };
    }

    /** Eine Zahl sind Tage; sonst eine Dauer wie 12h oder 36h. */
    static java.time.Duration days(String text) {
        String value = text == null ? "" : text.strip();
        return value.matches("\\d{1,5}") ? java.time.Duration.ofDays(Long.parseLong(value)) : duration(value);
    }

    private static JCheckBox checkbox(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setFont(Comic.regular(13f));
        box.setForeground(Comic.INK);
        return box;
    }

    // ------------------------------------------------------------------ Proxy

    /**
     * Proxy für alle Quellen ({@code space-harvester.network.proxy}): Modus, Felder, „Proxy auflösen“ und die
     * Ausnahmen unter „Erweitert“. Geöffnet von einer Quelle aus wird zu ihrer Adresse aufgelöst, sonst zur ersten
     * Confluence-Quelle.
     */
    static Shell proxy(Component parent, AppContext context, String target, Runnable saved) {
        ProxySettings currentProxy = context.settings() != null ? context.settings().proxy()
                : context.loader().defaultProxy();
        ProxyDraft current = ProxyDraft.of(currentProxy);
        JComboBox<String> mode = Comic.combo();
        PROXY_MODES.forEach(m -> mode.addItem(proxyLabel(m)));
        mode.setSelectedItem(proxyLabel(current.mode()));
        JTextField pacUrl = Comic.field(32);
        pacUrl.setText(current.pacUrl() == null ? "" : current.pacUrl());
        JTextField host = Comic.field(24);
        host.setText(current.proxyHost() == null ? "" : current.proxyHost());
        JTextField port = Comic.field(6);
        port.setText(current.proxyPort() == null ? "" : current.proxyPort());
        JTextField address = Comic.field(32);
        address.setText(target != null ? target : defaultTarget(context));
        JTextField user = Comic.field(24);
        user.setText(current.proxyUser() == null ? "" : current.proxyUser());
        JPasswordField password = new JPasswordField(24);
        Comic.style(password);
        JTextArea script = Comic.code();
        script.setEditable(true);
        script.setRows(5);
        script.setText(current.pacDiscoveryScript() == null ? "" : current.pacDiscoveryScript());
        HostListEditor exceptions = new HostListEditor();
        exceptions.hosts(current.nonProxyHosts());
        ComposerButton advanced = Comic.button(Glyph.EXPAND, "Erweitert", "Proxy-Ausnahmen anzeigen");
        boolean[] open = {false};
        ComposerButton resolve = Comic.button("Proxy auflösen",
                "Route zur Adresse mit diesen Einstellungen bestimmen, ohne etwas zu speichern");
        JTextArea log = Comic.code();
        log.setRows(5);
        Supplier<ProxySettings.Mode> chosen = () -> PROXY_MODES.get(Math.max(0, mode.getSelectedIndex()));
        Supplier<ProxyDraft> draft = () -> new ProxyDraft(chosen.get(), pacUrl.getText(), host.getText(),
                port.getText(), exceptions.hosts(), currentProxy.resolveTimeout(), currentProxy.cacheTtl(),
                currentProxy.failureTtl(), user.getText(), script.getText());

        Rows rows = new Rows();
        rows.add("Proxy", mode, "Standard unter Windows: PAC-Adresse der Interneteinstellungen per PowerShell");
        rows.add("PAC-Adresse", pacUrl, "z. B. http://proxy.example.org/proxy.pac");
        rows.add("Proxy-Host", host, null);
        rows.add("Proxy-Port", port, "1 bis 65535, z. B. 8080");
        JPanel advancedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        advancedRow.setOpaque(false);
        advancedRow.add(advanced);
        rows.add("", advancedRow, null);
        rows.add("Proxy-Benutzer", user, "Nur wenn der Proxy eine Anmeldung verlangt (BASIC); sonst leer lassen");
        rows.add("Proxy-Passwort", password, null);
        rows.add("Proxy-Ausnahmen", exceptions, "Diese Hosts werden direkt und nicht über den Proxy angesprochen.");
        JComponent scriptScroll = Comic.scroll(script);
        scriptScroll.setPreferredSize(new Dimension(480, 100));
        rows.add("PAC-Skript", scriptScroll, "Eigenes Skript, das die PAC-Adresse ausgibt; leer für das eingebaute");
        rows.add("Testadresse", row(address, resolve), "Nur für „Proxy auflösen“; wird nicht gespeichert");
        JComponent logScroll = Comic.scroll(log);
        logScroll.setPreferredSize(new Dimension(480, 100));
        rows.add("Diagnose", logScroll, null);
        Runnable visibility = () -> {
            ProxySettings.Mode m = chosen.get();
            rows.visible(pacUrl, m == ProxySettings.Mode.PAC_URL_MANUAL);
            rows.visible(host, m == ProxySettings.Mode.MANUAL_PROXY);
            rows.visible(port, m == ProxySettings.Mode.MANUAL_PROXY);
            rows.visible(advancedRow, m != ProxySettings.Mode.DISABLED);
            rows.visible(exceptions, open[0] && m != ProxySettings.Mode.DISABLED);
            rows.visible(scriptScroll, open[0] && ProxyDraft.scriptable(m));
            rows.label(scriptScroll, m == ProxySettings.Mode.PAC_URL_WSCRIPT ? "VBScript" : "PowerShell-Skript");
            rows.visible(user, m != ProxySettings.Mode.DISABLED);
            String name = user.getText().strip();
            rows.visible(password, m != ProxySettings.Mode.DISABLED && !name.isEmpty());
            rows.hint(password, context.proxyPasswordAvailable(name)
                    ? "Gespeichert; leer lassen, um es zu behalten" : context.proxyVault().available()
                    ? "Wird verschlüsselt im Tresor abgelegt, nie in der Datei" : "Gilt bis zum Schließen der App");
            int count = exceptions.count();
            advanced.setIcon(open[0] ? Glyph.COLLAPSE : Glyph.EXPAND);
            advanced.setText("Erweitert" + (count == 0 ? ""
                    : " (" + Texts.count(count, "Proxy-Ausnahme", "Proxy-Ausnahmen") + ")"));
            rows.revalidate();
            rows.repaint();
        };
        mode.addActionListener(e -> visibility.run());
        user.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                visibility.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                visibility.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                visibility.run();
            }
        });
        exceptions.onChange(visibility);
        advanced.addActionListener(e -> {
            open[0] = !open[0];
            visibility.run();
        });
        visibility.run();
        resolve.addActionListener(e -> {
            ProxyDraft proxy = draft.get();
            if (!proxy.problems().isEmpty()) {
                log.setText(String.join("\n", proxy.problems()));
                return;
            }
            URI uri;
            try {
                uri = URI.create(address.getText().trim());
            } catch (IllegalArgumentException invalid) {
                log.setText("Die Testadresse ist ungültig: " + invalid.getMessage());
                return;
            }
            log.setText("Route zu " + uri + " wird bestimmt …");
            resolve.setEnabled(false);
            Background.run(() -> ProxyRoutes.from(proxy.settings()).diagnose(uri), lines -> {
                resolve.setEnabled(true);
                log.setText(String.join("\n", lines));
                log.setCaretPosition(0);
            }, error -> {
                resolve.setEnabled(true);
                log.setText("Der Proxy lässt sich so nicht verwenden: " + (error instanceof
                        InvalidNetworkConfigurationException invalid ? String.join("; ", invalid.problems())
                        : error.getMessage()));
            });
        });

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(Comic.note("Diese Einstellungen gelten für alle Quellen (space-harvester.network.proxy)."),
                BorderLayout.NORTH);
        body.add(rows, BorderLayout.CENTER);
        Shell shell = new Shell(parent, "Proxy für alle Quellen", body);
        shell.onSaveFile(() -> draft.get().problems(), () -> {
            ProxyDraft proxy = draft.get();
            ConfigFileEditor editor = new ConfigFileEditor(context.loader());
            String text = Files.isRegularFile(context.configFile())
                    ? Files.readString(context.configFile(), StandardCharsets.UTF_8) : "";
            ConfigFileEditor.Draft written = editor.withProxy(context.configFile(),
                    new ConfigFileEditor.Draft(text, null), proxy);
            storeProxy(context, editor, written, proxy.proxyUser(), password.getPassword());
        }, () -> draft.get().settings().equals(currentProxy) && password.getPassword().length == 0, context, saved);
        return shell;
    }

    /**
     * Schreibt den Proxy in die Datei und das Passwort in den Tresor (ohne Tresor nur in die Sitzung). Scheitert die
     * Datei, kommt der alte Tresoreintrag zurück. Ohne Benutzer fällt ein gespeichertes Passwort weg.
     */
    static void storeProxy(AppContext context, ConfigFileEditor editor, ConfigFileEditor.Draft written,
                           String username, char[] password) throws IOException {
        boolean backup = !editor.fileHasPlaintextSecret(context.configFile());
        SecretVault vault = context.proxyVault();
        try {
            if (username == null) {
                editor.save(context.configFile(), written, backup);
                if (vault.available()) {
                    vault.remove(AppContext.PROXY_KEY);
                }
                context.sessionProxyPassword(null, null);
                return;
            }
            if (password.length == 0) {
                editor.save(context.configFile(), written, backup);
                return;
            }
            if (!vault.available()) {
                editor.save(context.configFile(), written, backup);
                context.sessionProxyPassword(username, password.clone());
                return;
            }
            String stored = vault.raw(AppContext.PROXY_KEY);
            vault.store(AppContext.PROXY_KEY, username, password.clone());
            try {
                editor.save(context.configFile(), written, backup);
            } catch (IOException | RuntimeException e) {
                try {
                    if (stored == null) {
                        vault.remove(AppContext.PROXY_KEY);
                    } else {
                        vault.restore(AppContext.PROXY_KEY, stored);
                    }
                } catch (IOException | RuntimeException restore) {
                    e.addSuppressed(restore);
                }
                throw e;
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Adresse der ersten Confluence-Quelle, sonst die öffentliche Confluence-Hilfe. */
    private static String defaultTarget(AppContext context) {
        if (context.settings() != null) {
            for (AppSettings.SourceEntry entry : context.settings().sources()) {
                if (entry.config().baseUrl() != null) {
                    return entry.config().baseUrl().toString();
                }
            }
        }
        return SourceForm.DEFAULT_URL;
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

    // ------------------------------------------------------------------ Gemeinsames

    /** Die Dialoge zur ersten Confluence-Quelle als Bilder (Sichtprüfung ohne Bildschirm). */
    static void screenshots(AppContext context, java.nio.file.Path directory) {
        if (context.settings() == null) {
            return;
        }
        context.settings().sources().stream().filter(entry -> entry.config().baseUrl() != null).findFirst()
                .ifPresent(entry -> {
                    SourceDraft.Editable editable;
                    try {
                        editable = new ConfigFileEditor(context.loader()).editable(context.configFile(), entry.id());
                    } catch (IOException | RuntimeException e) {
                        return;
                    }
                    Map<String, Shell> shells = new LinkedHashMap<>();
                    shells.put("anmeldung", auth(null, context, editable, id -> { }));
                    shells.put("client-zertifikat", certificate(null, context, editable, id -> { }, List.of()));
                    Shell windows = certificate(null, context, editable, id -> { }, List.of(
                            new CertificateChooser.Entry("angelo", "CN=Angelo Beispiel, O=Firma",
                                    new java.util.Date(4102444800000L), "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:"
                                    + "89:AB:CD:EF:01", true)));
                    select(windows.body(), WINDOWS_MY);
                    shells.put("client-zertifikat-windows", windows);
                    shells.put("serverzertifikate", trust(null, context, editable, id -> { }));
                    shells.put("serverzertifikate-alle", tls(null, context, () -> { }));
                    shells.put("proxy", proxy(null, context, entry.config().baseUrl().toString(), () -> { }));
                    int n = 1;
                    for (Map.Entry<String, Shell> shell : shells.entrySet()) {
                        JPanel surface = shell.getValue().surface();
                        Dimension size = surface.getPreferredSize();
                        surface.setSize(Math.max(560, size.width), size.height);
                        Screenshots.write(surface, directory.resolve("dialog-" + n++ + "-" + shell.getKey() + ".png"));
                    }
                });
    }

    private static boolean select(java.awt.Container container, String item) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComboBox<?> combo) {
                combo.setSelectedItem(item);
                return true;
            }
            if (child instanceof java.awt.Container nested && select(nested, item)) {
                return true;
            }
        }
        return false;
    }

    /** Ein Hinweis, der in fester Breite umbricht und so auch vor dem ersten Layout seine Höhe kennt. */
    private static JLabel wrapped(String text) {
        JLabel label = new JLabel("<html><div style='width:470px'>" + text + "</div></html>");
        label.setFont(Comic.regular(12.5f));
        label.setForeground(Comic.TEXT);
        return label;
    }

    private static String title(SourceDraft source) {
        return source.title() == null || source.title().isBlank() ? source.id() : source.title();
    }

    private static SourceDraft copy(SourceDraft d, AuthType authType, String username, String variable,
                                    CertificateStoreType certificateType, String certificatePath,
                                    String certificatePasswordVariable, String alias, String thumbprint,
                                    String trustBundle) {
        return new SourceDraft(d.id(), d.type(), d.title(), d.baseUrl(), d.directory(), d.include(), authType,
                username, variable, certificateType, certificatePath, certificatePasswordVariable, alias, thumbprint,
                trustBundle, d.extraOptions());
    }

    private static JPanel row(JTextField field, ComposerButton button) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        row.add(button, BorderLayout.EAST);
        return row;
    }

    private static ComposerButton browse(JTextField target) {
        ComposerButton button = Comic.button("Durchsuchen …", null);
        button.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(target.getText().isBlank() ? null : target.getText());
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(button) == JFileChooser.APPROVE_OPTION) {
                target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().toString());
            }
        });
        return button;
    }

    /** Ergebnis von „Speichern“: die geänderte Quelle oder, was fehlt. */
    record Change(SourceDraft source, char[] secret, List<String> problems) {

        Change(SourceDraft source, char[] secret) {
            this(source, secret, List.of());
        }

        static Change rejected(List<String> problems) {
            return new Change(null, new char[0], List.copyOf(problems));
        }
    }

    /** Schreibt den Dateitext im Hintergrund. */
    interface FileChange {
        void write() throws IOException;
    }

    /**
     * Rahmen eines Einstellungsdialogs: Inhalt, Rückmeldung, Abbrechen und Speichern. Ohne Bildschirm prüfbar
     * ({@link #body()}, {@link #save()}); {@link #open()} zeigt ihn modal.
     */
    static final class Shell {

        private final Component parent;
        private final String title;
        private final JPanel body = new JPanel(new BorderLayout(0, 10));
        final Feedback feedback = new Feedback();
        final ComposerButton save = Comic.primary("Speichern", "Übernehmen und die Quellen neu starten");
        final ComposerButton cancel = Comic.button("Abbrechen", null);
        private Runnable action = () -> { };
        private Runnable close = () -> { };
        private Runnable onClose = () -> { };

        Shell(Component parent, String title, JComponent content) {
            this.parent = parent;
            this.title = title;
            body.setOpaque(false);
            body.add(content, BorderLayout.CENTER);
            body.add(feedback, BorderLayout.SOUTH);
            save.addActionListener(e -> action.run());
            cancel.addActionListener(e -> close.run());
        }

        String title() {
            return title;
        }

        JComponent body() {
            return body;
        }

        void save() {
            action.run();
        }

        void onClose(Runnable listener) {
            this.onClose = listener;
        }

        /** Speichern einer Quelle wie im Assistenten: Datei, Tresor, Sitzung, dann Neu laden. */
        void onSave(Supplier<Change> change, AppContext context, SourceDraft.Editable editing,
                    Consumer<String> saved) {
            action = () -> {
                Change result = change.get();
                if (result.source() == null) {
                    feedback.warning("Bitte prüfen", String.join(" ", result.problems()));
                    return;
                }
                SourceDraft source = result.source();
                char[] value = result.secret();
                if (source.equals(editing.draft()) && value.length == 0) {
                    finish();
                    return;
                }
                if (!Dialogs.restartAllowed(body, context)) {
                    Arrays.fill(value, '\0');
                    return;
                }
                ConfigFileEditor editor = new ConfigFileEditor(context.loader());
                feedback.working("Wird gespeichert …");
                save.setEnabled(false);
                Background.run(() -> {
                    if (!editor.editable(context.configFile(), source.id()).plaintextSecrets().isEmpty()) {
                        throw new IllegalStateException("Der Eintrag enthält ein Geheimnis im Klartext. Bitte über "
                                + "⋯ › „Bearbeiten …“ an der Karte ändern; das Formular legt es sicher ab.");
                    }
                    SourceSaving.store(context, editor, editing, source,
                            editor.replace(context.configFile(), source), value);
                    return source.id();
                }, id -> {
                    SourceSaving.remember(context, editing, source, value);
                    Arrays.fill(value, '\0');
                    finish();
                    context.reload(() -> saved.accept(id));
                }, error -> {
                    Arrays.fill(value, '\0');
                    save.setEnabled(true);
                    feedback.problem(error);
                });
            };
        }

        /**
         * Speichern einer Einstellung außerhalb der Quelle (Proxy): erst prüfen, dann schreiben; {@code unchanged}
         * schließt ohne Neustart.
         */
        void onSaveFile(Supplier<List<String>> problems, FileChange change, Supplier<Boolean> unchanged,
                        AppContext context, Runnable saved) {
            action = () -> {
                List<String> missing = problems.get();
                if (!missing.isEmpty()) {
                    feedback.warning("Bitte prüfen", String.join(" ", missing));
                    return;
                }
                if (unchanged.get()) {
                    finish();
                    return;
                }
                if (!Dialogs.restartAllowed(body, context)) {
                    return;
                }
                feedback.working("Wird gespeichert …");
                save.setEnabled(false);
                Background.execute(change::write, () -> {
                    finish();
                    context.reload(saved);
                }, error -> {
                    save.setEnabled(true);
                    feedback.problem(error);
                });
            };
        }

        private void finish() {
            onClose.run();
            close.run();
        }

        /** Inhalt mit Knöpfen, wie er im Fenster steht. */
        private JPanel stack() {
            JPanel stack = new JPanel(new BorderLayout(0, 12));
            stack.setOpaque(false);
            stack.add(body, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(cancel);
            buttons.add(save);
            stack.add(buttons, BorderLayout.SOUTH);
            return stack;
        }

        /** Das ganze Fenster ohne Bildschirm, z. B. für Screenshots. */
        JPanel surface() {
            return Dialogs.surface(title, stack(), () -> { });
        }

        /** Zeigt den Dialog modal (rahmenlos wie die übrigen Dialoge). */
        void open() {
            JDialog dialog = Dialogs.dialog(parent, title);
            close = dialog::dispose;
            Dialogs.frame(dialog, title, stack(), dialog::dispose);
            dialog.getRootPane().setDefaultButton(save);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    onClose.run();
                }
            });
            Dialogs.present(dialog, new Dimension(560, 0));
        }
    }

    /** Beschriftung, Feld und graue Hinweiszeile je Zeile; Zeilen lassen sich ausblenden. */
    static final class Rows extends JPanel {

        private record Row(JLabel label, JComponent field, JLabel hint) {
        }

        private final List<Row> rows = new ArrayList<>();

        Rows() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        void add(String text, JComponent field, String hint) {
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
            JLabel hintLabel = Comic.muted(hint == null ? " " : hint);
            GridBagConstraints below = new GridBagConstraints();
            below.gridx = 1;
            below.gridy = y + 1;
            below.anchor = GridBagConstraints.WEST;
            below.fill = GridBagConstraints.HORIZONTAL;
            add(hintLabel, below);
            rows.add(new Row(label, field, hintLabel));
        }

        private Row find(JComponent field) {
            for (Row row : rows) {
                if (row.field() == field || row.field() instanceof JPanel panel && panel.getComponentCount() > 0
                        && panel.getComponent(0) == field) {
                    return row;
                }
            }
            throw new IllegalArgumentException("Keine Zeile für " + field);
        }

        void visible(JComponent field, boolean visible) {
            Row row = find(field);
            row.label().setVisible(visible);
            row.field().setVisible(visible);
            row.hint().setVisible(visible);
        }

        boolean shows(JComponent field) {
            return find(field).field().isVisible();
        }

        void label(JComponent field, String text) {
            find(field).label().setText(text);
        }

        void hint(JComponent field, String text) {
            find(field).hint().setText(text);
        }

        String hint(JComponent field) {
            return find(field).hint().getText();
        }
    }
}
