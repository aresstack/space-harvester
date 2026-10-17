package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.ProxyDraft;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.app.settings.TrustStores;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.network.InvalidNetworkConfigurationException;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
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
 * Assistenten ({@link SourceSaving}, {@link ConfigFileEditor}, Tresor), geprüft mit denselben Regeln
 * ({@link SourceDraft#problems()}, {@link ProxyDraft#problems()}). Der Assistent bleibt für neue Quellen und
 * „Assistent erneut öffnen“.
 *
 * <p>Übernehmen startet die Quellen neu. Laufen Aufträge, fragt {@link Dialogs#restartAllowed} vorher nach.
 */
final class SettingDialogs {

    static final String TRUST_TITLE = "Serverzertifikate prüfen";
    static final String TRUST_WINDOWS = "Windows und Java";
    static final String TRUST_JAVA = "Nur Java";
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
    static String trustLabel(String bundle) {
        return bundle == null ? TRUST_JAVA : TrustStores.WINDOWS.equals(bundle) ? TRUST_WINDOWS
                : "Konfiguriertes Bundle „" + bundle + "“";
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
     * Wem die App bei Serverzertifikaten glaubt: Windows und Java (Vorgabe unter Windows), nur Java oder ein
     * konfiguriertes Bundle. Die bisherige Einstellung bleibt gewählt.
     */
    static Shell trust(Component parent, AppContext context, SourceDraft.Editable editing, Consumer<String> saved) {
        SourceDraft current = editing.draft();
        List<String> bundles = new ArrayList<>();
        bundles.add(TrustStores.WINDOWS);
        bundles.add(null);
        AppSettings settings = context.settings();
        if (settings != null) {
            settings.trustStores().keySet().stream().sorted()
                    .filter(name -> !TrustStores.WINDOWS.equals(name)).forEach(bundles::add);
        }
        if (!bundles.contains(current.trustBundle())) {
            bundles.add(current.trustBundle());
        }
        Map<JRadioButton, String> choices = new LinkedHashMap<>();
        ButtonGroup group = new ButtonGroup();
        JPanel options = new JPanel();
        options.setOpaque(false);
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        for (String bundle : bundles) {
            JRadioButton button = new JRadioButton(trustLabel(bundle) + switch (bundle == null ? "" : bundle) {
                case "" -> " · nur die Zertifizierungsstellen der Java-Laufzeit";
                case TrustStores.WINDOWS -> " · Firmen-CAs aus Windows wie im Browser, dazu die der Java-Laufzeit";
                default -> " · Truststore aus spring.ssl.bundle";
            });
            button.setOpaque(false);
            button.setFont(Comic.regular(13f));
            button.setSelected(Objects.equals(bundle, current.trustBundle()));
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
            String bundle = choices.entrySet().stream().filter(entry -> entry.getKey().isSelected())
                    .map(Map.Entry::getValue).findFirst().orElse(current.trustBundle());
            SourceDraft source = copy(current, current.authType(), current.username(), current.secretVariable(),
                    current.certificateType(), current.certificatePath(), current.certificatePasswordVariable(),
                    current.certificateAlias(), current.thumbprint(), bundle);
            return new Change(source, new char[0]);
        }, context, editing, saved);
        return shell;
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
        SourceWizard.PROXY_MODES.forEach(m -> mode.addItem(SourceWizard.proxyLabel(m)));
        mode.setSelectedItem(SourceWizard.proxyLabel(current.mode()));
        JTextField pacUrl = Comic.field(32);
        pacUrl.setText(current.pacUrl() == null ? "" : current.pacUrl());
        JTextField host = Comic.field(24);
        host.setText(current.proxyHost() == null ? "" : current.proxyHost());
        JTextField port = Comic.field(6);
        port.setText(current.proxyPort() == null ? "" : current.proxyPort());
        JTextField address = Comic.field(32);
        address.setText(target != null ? target : defaultTarget(context));
        HostListEditor exceptions = new HostListEditor();
        exceptions.hosts(current.nonProxyHosts());
        ComposerButton advanced = Comic.button(Glyph.EXPAND, "Erweitert", "Proxy-Ausnahmen anzeigen");
        boolean[] open = {false};
        ComposerButton resolve = Comic.button("Proxy auflösen",
                "Route zur Adresse mit diesen Einstellungen bestimmen, ohne etwas zu speichern");
        JTextArea log = Comic.code();
        log.setRows(5);
        Supplier<ProxySettings.Mode> chosen = () -> SourceWizard.PROXY_MODES.get(Math.max(0, mode.getSelectedIndex()));
        Supplier<ProxyDraft> draft = () -> new ProxyDraft(chosen.get(), pacUrl.getText(), host.getText(),
                port.getText(), exceptions.hosts(), currentProxy.resolveTimeout(), currentProxy.cacheTtl(),
                currentProxy.failureTtl());

        Rows rows = new Rows();
        rows.add("Proxy", mode, "Standard unter Windows: PAC-Adresse der Interneteinstellungen per PowerShell");
        rows.add("PAC-Adresse", pacUrl, "z. B. http://proxy.example.org/proxy.pac");
        rows.add("Proxy-Host", host, null);
        rows.add("Proxy-Port", port, "1 bis 65535, z. B. 8080");
        JPanel advancedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        advancedRow.setOpaque(false);
        advancedRow.add(advanced);
        rows.add("", advancedRow, null);
        rows.add("Proxy-Ausnahmen", exceptions, "Diese Hosts werden direkt und nicht über den Proxy angesprochen.");
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
            int count = exceptions.count();
            advanced.setIcon(open[0] ? Glyph.COLLAPSE : Glyph.EXPAND);
            advanced.setText("Erweitert" + (count == 0 ? ""
                    : " (" + Texts.count(count, "Proxy-Ausnahme", "Proxy-Ausnahmen") + ")"));
            rows.revalidate();
            rows.repaint();
        };
        mode.addActionListener(e -> visibility.run());
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
            editor.save(context.configFile(), written, !editor.fileHasPlaintextSecret(context.configFile()));
        }, () -> draft.get().settings().equals(currentProxy), context, saved);
        return shell;
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
        return SourceWizard.DEFAULT_URL;
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
                                + "⋯ › „Assistent erneut öffnen“ an der Karte bearbeiten; der Assistent legt es "
                                + "sicher ab.");
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
