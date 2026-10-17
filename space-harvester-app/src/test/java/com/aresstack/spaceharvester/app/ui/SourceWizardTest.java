package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Quellen-Assistent ohne Bildschirm: Pflichtfelder halten ihn auf, der Verbindungstest läuft vor dem Speichern,
 * Speichern ergänzt die Datei und startet die neue Quelle neben der laufenden.
 */
class SourceWizardTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void addsALocalFolderAsSourceAndStartsIt() throws Exception {
        Path handbuch = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(handbuch.resolve("betrieb.md"), "# Betrieb\n");
        Path notizen = Files.createDirectories(home.resolve("notizen"));
        Files.writeString(notizen.resolve("idee.md"), "# Idee\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                # eigene Notiz bleibt
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), handbuch));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SourceWizard[] wizard = new SourceWizard[1];
        CountDownLatch saved = new CountDownLatch(1);
        AtomicReference<String> savedId = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SourceWizard(context);
            wizard[0].onSaved(id -> {
                savedId.set(id);
                saved.countDown();
            });
            SourceWizard w = wizard[0];
            w.kind.setSelectedIndex(1);
            assertThat(w.next()).as("Ordner und Kennung fehlen").isFalse();
            assertThat(w.error(w.directory)).isEqualTo("Bitte einen Ordner wählen.");
            assertThat(w.error(w.id)).startsWith("Bitte eine Kennung");
            w.directory.setText(handbuch.toString());
            assertThat(w.error(w.directory)).as("Tippen nimmt den Hinweis weg").isNull();
            assertThat(w.id.getText()).as("Kennung aus dem Ordner, schon vergeben").isEqualTo("handbuch-2");
            assertThat(w.title.getText()).isEqualTo("handbuch");
            w.id.setText("handbuch");
            assertThat(w.next()).as("Kennung vergeben").isFalse();
            assertThat(w.error(w.id)).isEqualTo("Diese Kennung ist schon vergeben.");
            w.id.setText("");
            w.directory.setText(notizen.toString());
            assertThat(w.id.getText()).isEqualTo("notizen");
            assertThat(w.next()).isTrue();
            assertThat(wizard[0].step()).as("Dateien brauchen keine Anmeldung").isEqualTo(SourceWizard.Step.CHECK);
        });

        CountDownLatch tested = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> wizard[0].test(tested::countDown));
        assertThat(tested.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).doesNotContain("notizen");

        SwingUtilities.invokeAndWait(() -> wizard[0].save());
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(savedId.get()).isEqualTo("notizen");
        assertThat(Files.readString(config)).startsWith("# eigene Notiz bleibt").contains("- id: \"notizen\"");
        assertThat(context.settings().sources()).extracting(AppSettings.SourceEntry::id)
                .containsExactly("handbuch", "notizen");
        assertThat(context.runtime().workspace().sources()).extracting(HarvestSource::id)
                .containsExactly("handbuch", "notizen");
    }

    @Test
    void editsAnExistingSourceInPlace() throws Exception {
        Path handbuch = Files.createDirectories(home.resolve("handbuch"));
        Path neu = Files.createDirectories(home.resolve("neu"));
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Alt
                      options:
                        directory: %s
                        maxFileBytes: 1000
                """.formatted(home.resolve("data"), handbuch));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SourceDraft.Editable editable = new ConfigFileEditor(context.loader()).editable(config, "handbuch");
        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context, editable);
            w.onSaved(id -> saved.countDown());
            assertThat(w.id.isEditable()).isFalse();
            assertThat(w.title.getText()).isEqualTo("Alt");
            w.title.setText("Neu");
            w.directory.setText(neu.toString());
            assertThat(w.id.getText()).as("Beim Bearbeiten bleibt die Kennung").isEqualTo("handbuch");
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.CHECK);
            w.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        String text = Files.readString(config);
        assertThat(text).contains("title: \"Neu\"", "maxFileBytes: \"1000\"").doesNotContain("title: Alt");
        assertThat(context.settings().sources()).singleElement()
                .satisfies(source -> assertThat(source.config().title()).isEqualTo("Neu"));
    }

    @Test
    void switchingTheLoginDropsTheSessionValueAndAPlaintextTokenSkipsTheBackup() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      base-url: https://wiki.example.invalid
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                        password: alt-klartext
                """.formatted(home.resolve("data")));
        Files.writeString(home.resolve("app.yml.bak"), "alt");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        context.secrets().put("wiki", "pat", "sitzungs-token".toCharArray());

        SourceDraft.Editable editable = new ConfigFileEditor(context.loader()).editable(config, "wiki");
        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context, editable);
            w.onSaved(id -> saved.countDown());
            assertThat(w.next()).isTrue();
            w.auth.setSelectedItem("Benutzer und Passwort");
            w.username.setText("angelo");
            assertThat(w.sessionSecrets(w.draft()).has("wiki")).as("Verbindungstest ohne altes Token").isFalse();
            assertThat(w.next()).as("Token taugt nicht als Passwort").isFalse();
            assertThat(w.error(w.sessionValue)).isEqualTo("Bitte das Passwort eingeben.");
            w.sessionValue.setText("neues-passwort");
            assertThat(w.next()).isTrue();
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.CHECK);
            assertThat(w.checkNotes.getText()).contains("KEINE .bak").doesNotContain("bleibt als .bak");
            w.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).doesNotContain("alt-klartext", "neues-passwort").contains("type: basic");
        assertThat(home.resolve("app.yml.bak")).doesNotExist();
        assertThat(context.secrets().has("wiki")).as("das neue Passwort").isTrue();
    }

    @Test
    void switchingFromPasswordToTokenDropsTheSessionValueAndAnotherSourcesSecretSkipsTheBackup() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      base-url: https://wiki.example.invalid
                      auth:
                        type: basic
                        username: angelo
                        password: ${WIKI_PASSWORD}
                    - id: andere
                      type: confluence
                      base-url: https://andere.example.invalid
                      auth:
                        type: pat
                        token: anderes-klartext
                """.formatted(home.resolve("data")));
        Files.writeString(home.resolve("app.yml.bak"), "alt");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        context.secrets().put("wiki", "basic:angelo", "sitzungs-passwort".toCharArray());

        SourceDraft.Editable editable = new ConfigFileEditor(context.loader()).editable(config, "wiki");
        assertThat(editable.plaintextSecrets()).isEmpty();
        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context, editable);
            w.onSaved(id -> saved.countDown());
            assertThat(w.next()).isTrue();
            w.auth.setSelectedItem("Personal Access Token");
            assertThat(w.next()).as("Passwort taugt nicht als Token").isFalse();
            assertThat(w.error(w.sessionValue)).isEqualTo("Bitte das Token eingeben.");
            w.sessionValue.setText("neues-token");
            assertThat(w.next()).isTrue();
            assertThat(w.next()).isTrue();
            assertThat(w.checkNotes.getText()).as("Geheimnis der anderen Quelle").contains("KEINE .bak")
                    .doesNotContain("bleibt als .bak");
            w.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).contains("type: pat", "anderes-klartext").doesNotContain("neues-token");
        assertThat(home.resolve("app.yml.bak")).doesNotExist();
    }

    @Test
    void confluenceAsksForLoginAndCertificates() throws Exception {
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), home.resolve("app.yml"),
                "test");
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context);
            w.id.setText("Wiki.Neu");
            assertThat(w.next()).as("ID wie im Core").isFalse();
            assertThat(w.error(w.id)).startsWith("Nur Kleinbuchstaben");
            w.id.setText("wiki");
            w.baseUrl.setText("ftp://wiki.example.org");
            assertThat(w.next()).as("Adresse falsch").isFalse();
            assertThat(w.error(w.baseUrl)).isEqualTo("Die Adresse beginnt mit https://.");
            assertThat(w.error(w.id)).isNull();
            w.baseUrl.setText("https://wiki.example.org");
            assertThat(w.id.getText()).as("selbst eingetragen, bleibt").isEqualTo("wiki");
            assertThat(w.title.getText()).isEqualTo("wiki.example.org");
            assertThat(w.auth.getSelectedItem()).isEqualTo("Personal Access Token");
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.AUTH);
            assertThat(w.variable.getText()).isEqualTo("WIKI_TOKEN");
            assertThat(w.next()).as("Token fehlt").isFalse();
            assertThat(w.error(w.sessionValue)).isEqualTo("Bitte das Token eingeben.");
            w.sessionValue.setText("geheim-123");
            // Das Zertifikat gehört zur Anmeldung, auf derselben Seite.
            w.certificate.setSelectedIndex(1);
            assertThat(w.chooseCertificate.isVisible()).isTrue();
            w.thumbprint.setText("0123456789abcdef0123456789abcdef01234567");
            assertThat(w.next()).as("Alias fehlt").isFalse();
            assertThat(w.error(w.alias)).isEqualTo("Bitte den Alias wählen oder eintragen.");
            w.alias.setText("confluence-client");
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.NETWORK);
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.CHECK);
            assertThat(w.draft().yaml("")).contains("token: ${WIKI_TOKEN}", "type: windows-my")
                    .doesNotContain("geheim-123");
            assertThat(w.checkNotes.getText()).contains("nur für diese Sitzung").doesNotContain("setx");
            w.back();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.NETWORK);
        });
    }

    @Test
    void anEmptyAddressMeansThePublicConfluenceHelpWithoutLogin() throws Exception {
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), home.resolve("app.yml"),
                "test");
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context);
            assertThat(w.step()).isEqualTo(SourceWizard.Step.SOURCE);
            assertThat(w.baseUrl.getText()).isEmpty();
            assertThat(w.baseUrl.placeholder()).isEqualTo("https://confluence.atlassian.com");
            assertThat(w.id.getText()).isEqualTo("confluence-atlassian");
            assertThat(w.title.getText()).isEqualTo("Confluence-Hilfe (Atlassian)");
            assertThat(w.auth.getSelectedItem()).isEqualTo("Ohne Anmeldung");
            assertThat(w.next()).isTrue();
            assertThat(w.next()).isTrue();
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.CHECK);
            assertThat(w.draft().baseUrl()).isEqualTo("https://confluence.atlassian.com");
            assertThat(w.draft().yaml("")).contains("confluence.atlassian.com", "confluence-atlassian");

            w.back();
            w.back();
            w.back();
            w.baseUrl.setText("https://www.confluence.example.org/wiki");
            assertThat(w.id.getText()).isEqualTo("confluence-example");
            assertThat(w.title.getText()).isEqualTo("confluence.example.org");
            assertThat(w.auth.getSelectedItem()).as("eigene Instanz: mit Token").isEqualTo("Personal Access Token");
            w.auth.setSelectedItem("Ohne Anmeldung");
            w.baseUrl.setText("https://confluence.atlassian.com/");
            w.baseUrl.setText("https://intern.example.org");
            assertThat(w.auth.getSelectedItem()).as("Gewählte Anmeldung bleibt").isEqualTo("Ohne Anmeldung");
        });
    }

    @Test
    void theProxyIsEditableTestedWithTheChoiceAndSavedForAllSources() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                # Kommentar bleibt
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources: []
                  network:
                    proxy:
                      mode: disabled
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SourceWizard[] wizard = new SourceWizard[1];
        CountDownLatch resolved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context);
            wizard[0] = w;
            assertThat(w.next()).isTrue();
            assertThat(w.next()).as("öffentliche Hilfe: ohne Anmeldung").isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.NETWORK);
            assertThat(w.proxyMode.getSelectedItem()).isEqualTo(SourceWizard.proxyLabel(ProxySettings.Mode.DISABLED));
            assertThat(w.proxyMode.getItemCount()).as("alle Modi des Cores").isEqualTo(7);
            w.resolveProxy(resolved::countDown);
        });
        assertThat(resolved.await(30, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> assertThat(wizard[0].proxyLog.getText())
                .contains("confluence.atlassian.com", "Mode: disabled", "Result:"));

        CountDownLatch windowsOnly = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = wizard[0];
            w.proxyMode.setSelectedItem(SourceWizard.proxyLabel(ProxySettings.Mode.PAC_URL_POWERSHELL));
            w.resolveProxy(windowsOnly::countDown);
        });
        assertThat(windowsOnly.await(30, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> assertThat(wizard[0].proxyLog.getText()).as("Linux im Test")
                .contains("nur unter Windows"));

        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = wizard[0];
            w.onSaved(id -> saved.countDown());
            w.proxyMode.setSelectedItem(SourceWizard.proxyLabel(ProxySettings.Mode.MANUAL_PROXY));
            assertThat(w.next()).as("Host und Port fehlen").isFalse();
            assertThat(w.error(w.proxyHost)).isEqualTo("Der Proxy-Host fehlt.");
            assertThat(w.error(w.proxyPort)).startsWith("Der Proxy-Port");
            w.proxyHost.setText("proxy.firma.de");
            w.proxyPort.setText("3128");
            w.nonProxyHosts.setText("localhost, *.intern");
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.CHECK);
            assertThat(w.checkNotes.getText()).contains("ALLE Quellen", "manual-proxy");
            w.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).startsWith("# Kommentar bleibt")
                .contains("mode: manual-proxy", "proxy-host: \"proxy.firma.de\"", "- id: \"confluence-atlassian\"");
        assertThat(context.settings().proxy().proxyHost()).isEqualTo("proxy.firma.de");
        assertThat(context.settings().proxy().nonProxyHosts()).containsExactly("localhost", "*.intern");
    }

    @Test
    void aTypedTokenIsStoredInTheVaultAndUnlocksTheSourceAtTheNextStart() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context);
            w.onSaved(id -> saved.countDown());
            w.baseUrl.setText("https://wiki.example.invalid");
            assertThat(w.next()).isTrue();
            assertThat(w.auth.getSelectedItem()).isEqualTo("Personal Access Token");
            w.sessionValue.setText("mein-token");
            assertThat(w.next()).isTrue();
            assertThat(w.next()).isTrue();
            assertThat(w.checkNotes.getText()).contains("verschlüsselt", "die App setzt es beim Start selbst");
            w.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).contains("token: ${WIKI_EXAMPLE_TOKEN}").doesNotContain("mein-token");
        assertThat(vault.has("wiki-example")).isTrue();
        assertThat(Files.readString(home.resolve("secrets").resolve("wiki-example.secret"))).doesNotContain("mein-token");

        // Neuer Start ohne Sitzungswert und ohne Umgebungsvariable: Der Tresor liefert das Token.
        context.shutdown();
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        CountDownLatch again = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(again::countDown));
        assertThat(again.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime().waitingForSecret()).isEmpty();
        assertThat(context.secrets().has("wiki-example")).isTrue();

        // Von Hand auf Benutzer und Passwort umgestellt: Das gespeicherte Token gilt nicht als Passwort.
        String yaml = Files.readString(config);
        Files.writeString(config, yaml.replaceFirst("type: pat\\s+token: \\$\\{WIKI_EXAMPLE_TOKEN}",
                "type: basic\n        username: angelo\n        password: \\${WIKI_EXAMPLE_PASSWORD}"));
        assertThat(Files.readString(config)).contains("type: basic");
        CountDownLatch basic = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(basic::countDown));
        assertThat(basic.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime().waitingForSecret()).containsExactly("wiki-example");
        context.shutdown();
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        CountDownLatch fresh = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(fresh::countDown));
        assertThat(fresh.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime().waitingForSecret()).containsExactly("wiki-example");
    }
}
