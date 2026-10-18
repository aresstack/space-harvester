package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die kleinen Dialoge am Stift einer Einstellung: Sie ändern genau ihre Einstellung in der Datei, legen Geheimnisse
 * nur im Tresor ab und lassen alles andere stehen.
 */
class SettingDialogsTest {

    @TempDir
    Path home;

    private AppContext context;
    private SecretVault vault;
    private Path config;

    @BeforeEach
    void start() throws Exception {
        config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    # Kommentar bleibt stehen
                    - id: wiki
                      type: confluence
                      title: Firmenwiki
                      base-url: https://wiki.example.invalid
                      auth:
                        type: none
                """.formatted(home.resolve("data")));
        vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        reload();
    }

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void loginKeepsTheTokenInTheVaultAndNeverInTheFile() throws Exception {
        SettingDialogs.Shell[] shell = new SettingDialogs.Shell[1];
        String[] saved = new String[1];
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            shell[0] = SettingDialogs.auth(null, context, editable(), id -> {
                saved[0] = id;
                done.countDown();
            });
            find(shell[0].body(), JComboBox.class).get(0).setSelectedItem("Personal Access Token");
            // Ohne Token wird nichts gespeichert; der Grund steht im Dialog.
            shell[0].save();
            assertThat(shell[0].feedback.text()).contains("Das Token fehlt");
            find(shell[0].body(), JPasswordField.class).get(0).setText("mein-token");
            shell[0].save();
        });
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(saved[0]).isEqualTo("wiki");
        assertThat(Files.readString(config)).contains("type: pat", "token: ${WIKI_TOKEN}", "# Kommentar bleibt")
                .doesNotContain("mein-token");
        assertThat(vault.has("wiki")).isTrue();
        assertThat(context.secrets().has("wiki")).isTrue();
    }

    @Test
    void certificateComesFromTheStoreWithItsFingerprint() throws Exception {
        List<CertificateChooser.Entry> store = List.of(
                new CertificateChooser.Entry("angelo", "CN=Angelo, O=Firma", new Date(System.currentTimeMillis()
                        + 86_400_000L), "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01", true));
        SettingDialogs.Shell[] shell = new SettingDialogs.Shell[1];
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            shell[0] = SettingDialogs.certificate(null, context, editable(), id -> done.countDown(), store);
            JComboBox<?> origin = find(shell[0].body(), JComboBox.class).get(0);
            assertThat(origin.getSelectedItem()).isEqualTo("Kein Client-Zertifikat");
            origin.setSelectedItem("Windows-Zertifikatsspeicher");
            // Ohne Auswahl gibt es nichts zu speichern.
            shell[0].save();
            assertThat(shell[0].feedback.text()).contains("Bitte ein Zertifikat");
            find(shell[0].body(), JTable.class).get(0).setRowSelectionInterval(0, 0);
            assertThat(texts(shell[0].body())).anyMatch(text -> text.contains("Fingerabdruck (SHA-1): AB:CD:EF"));
            shell[0].save();
        });
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).contains("type: windows-my", "alias: \"angelo\"",
                "thumbprint: \"AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01\"");
    }

    @Test
    void serverCertificatesKeepTheCurrentChoiceAndExplainTheDifference() throws Exception {
        SettingDialogs.Shell[] shell = new SettingDialogs.Shell[1];
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            shell[0] = SettingDialogs.trust(null, context, editable(), id -> done.countDown());
            assertThat(shell[0].title()).startsWith("Serverzertifikate prüfen");
            assertThat(texts(shell[0].body())).anyMatch(text -> text.contains("unabhängig vom Client-Zertifikat"));
            List<JRadioButton> options = find(shell[0].body(), JRadioButton.class);
            assertThat(options.get(0).getText()).startsWith("Windows und Java");
            // Die bisherige Einstellung (ohne trust-bundle: nur Java) bleibt gewählt, auch unter Windows.
            assertThat(options.get(1).getText()).startsWith("Nur Java");
            assertThat(options.get(1).isSelected()).isTrue();
            options.get(0).setSelected(true);
            shell[0].save();
        });
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).contains("trust-bundle: \"windows\"");
    }

    @Test
    void proxyAppliesToAllSourcesAndRejectsIncompleteInput() throws Exception {
        SettingDialogs.Shell[] shell = new SettingDialogs.Shell[1];
        CountDownLatch done = new CountDownLatch(1);
        String before = Files.readString(config);
        SwingUtilities.invokeAndWait(() -> {
            shell[0] = SettingDialogs.proxy(null, context, null, done::countDown);
            assertThat(texts(shell[0].body())).anyMatch(text -> text.contains("gelten für alle Quellen"));
            assertThat(find(shell[0].body(), JTextField.class).stream().map(JTextField::getText))
                    .contains("https://wiki.example.invalid");
            JComboBox<?> mode = find(shell[0].body(), JComboBox.class).get(0);
            mode.setSelectedItem(SourceWizard.proxyLabel(
                    com.aresstack.spaceharvester.source.network.ProxySettings.Mode.MANUAL_PROXY));
            shell[0].save();
            assertThat(shell[0].feedback.text()).contains("Proxy-Host");
        });
        assertThat(Files.readString(config)).isEqualTo(before);
        SwingUtilities.invokeAndWait(() -> {
            List<JTextField> fields = find(shell[0].body(), JTextField.class);
            fields.get(1).setText("proxy.example.invalid");
            fields.get(2).setText("8080");
            shell[0].save();
        });
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).contains("mode: manual-proxy", "proxy-host: \"proxy.example.invalid\"",
                "proxy-port: 8080", "id: wiki");
    }

    @Test
    void aiSettingsKeepTheApiKeyInItsOwnVaultAndNeverInTheFile() throws Exception {
        AiSettingsPanel[] panel = new AiSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new AiSettingsPanel(context);
            assertThat(panel[0].released).containsOnlyKeys("wiki");
            assertThat(panel[0].released.get("wiki").isSelected()).as("Vorgabe: keine Quelle freigegeben").isFalse();
            panel[0].baseUrl.setText("kein-link");
            panel[0].save.doClick();
            assertThat(panel[0].feedback.text()).contains("http://");
            panel[0].baseUrl.setText("https://ki.example.invalid/v1");
            panel[0].embeddingModel.setText("text-embedding-3-small");
            panel[0].released.get("wiki").setSelected(true);
            panel[0].apiKey.setText("sk-geheim");
            panel[0].save.doClick();
        });
        long until = System.currentTimeMillis() + 30_000;
        String[] text = {""};
        while (!text[0].contains("übernommen") && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> text[0] = panel[0].feedback.text());
        }
        assertThat(text[0]).contains("KI-Einstellungen übernommen");

        String file = Files.readString(config);
        assertThat(file).contains("base-url: \"https://ki.example.invalid/v1\"", "- \"wiki\"", "# Kommentar bleibt")
                .doesNotContain("sk-geheim", "api-key");
        assertThat(context.settings().ai().configured()).isTrue();
        assertThat(context.settings().ai().externalProcessingSources()).containsExactly("wiki");
        assertThat(vault.has("api-key")).as("nicht im Tresor der Quellen").isFalse();
        assertThat(new String(context.aiVault().load(AiSettingsPanel.KEY_ID, AiSettingsPanel.KEY_LOGIN)))
                .isEqualTo("sk-geheim");
        assertThat(context.aiKeyAvailable()).isTrue();
        SwingUtilities.invokeAndWait(() -> assertThat(panel[0].apiKey.getPassword()).isEmpty());
    }

    private SourceDraft.Editable editable() {
        try {
            return new ConfigFileEditor(context.loader()).editable(config, "wiki");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void reload() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
    }

    private static List<String> texts(Container container) {
        List<String> texts = new ArrayList<>();
        find(container, javax.swing.JLabel.class).forEach(label -> texts.add(label.getText()));
        find(container, javax.swing.JTextArea.class).forEach(area -> texts.add(area.getText()));
        return texts;
    }

    private static <T> List<T> find(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                found.addAll(find(nested, type));
            }
        }
        return found;
    }
}
