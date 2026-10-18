package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.TrustSettings;
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
 * Das Quellenformular ohne Bildschirm: eine Seite mit Quelle, Anmeldung, Client-Zertifikat und Freigabe; Pflichtfelder
 * halten Test und Speichern auf, der Verbindungstest läuft vor dem Speichern, Speichern ergänzt die Datei und startet
 * die neue Quelle neben der laufenden. Proxy und Netzwerk stehen nicht im Formular.
 */
class SourceFormTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    private void start(Path config, SecretVault vault) throws Exception {
        context = vault == null
                ? new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test")
                : new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
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
        start(config, null);

        SourceForm[] form = new SourceForm[1];
        CountDownLatch saved = new CountDownLatch(1);
        AtomicReference<String> savedId = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context);
            form[0] = f;
            f.onSaved(id -> {
                savedId.set(id);
                saved.countDown();
            });
            f.kind.setSelectedIndex(1);
            assertThat(f.validateInput()).as("Ordner und Kennung fehlen").isFalse();
            assertThat(f.error(f.directory)).isEqualTo("Bitte einen Ordner wählen.");
            assertThat(f.error(f.id)).startsWith("Bitte eine Kennung");
            f.directory.setText(handbuch.toString());
            assertThat(f.error(f.directory)).as("Tippen nimmt den Hinweis weg").isNull();
            assertThat(f.id.getText()).as("Kennung aus dem Ordner, schon vergeben").isEqualTo("handbuch-2");
            assertThat(f.title.getText()).isEqualTo("handbuch");
            f.id.setText("handbuch");
            assertThat(f.validateInput()).as("Kennung vergeben").isFalse();
            assertThat(f.error(f.id)).isEqualTo("Diese Kennung ist schon vergeben.");
            f.id.setText("");
            f.directory.setText(notizen.toString());
            assertThat(f.id.getText()).isEqualTo("notizen");
            assertThat(f.validateInput()).as("Dateien brauchen keine Anmeldung").isTrue();
            assertThat(f.auth.isVisible()).isFalse();
        });

        CountDownLatch tested = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> form[0].test(tested::countDown));
        assertThat(tested.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).doesNotContain("notizen");

        SwingUtilities.invokeAndWait(() -> form[0].save());
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(savedId.get()).isEqualTo("notizen");
        assertThat(Files.readString(config)).startsWith("# eigene Notiz bleibt").contains("- id: \"notizen\"")
                .doesNotContain("network:", "ai:");
        assertThat(context.settings().sources()).extracting(AppSettings.SourceEntry::id)
                .containsExactly("handbuch", "notizen");
        assertThat(context.runtime().workspace().sources()).extracting(HarvestSource::id)
                .containsExactly("handbuch", "notizen");
    }

    @Test
    void theCoreRejectsTheSameTargetUnderANewIdBeforeAnythingIsWritten() throws Exception {
        rejectsTheSameTarget("");
    }

    /** Auch eine eingerichtete, aber nicht geöffnete Quelle (wartet auf ihr Token) belegt ihr Ziel (#236). */
    @Test
    void aSourceWaitingForItsTokenStillOccupiesItsTarget() throws Exception {
        rejectsTheSameTarget("""
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                """);
        assertThat(context.runtime().waitingForSecret()).containsExactly("wiki");
    }

    private void rejectsTheSameTarget(String auth) throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Wiki
                      base-url: https://wiki.example.invalid
                """.formatted(home.resolve("data")) + auth);
        start(config, null);
        String before = Files.readString(config);

        SourceForm[] form = new SourceForm[1];
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context);
            form[0] = f;
            f.baseUrl.setText("https://WIKI.example.invalid/");
            f.auth.setSelectedItem("Ohne Anmeldung");
            f.id.setText("zweites");
            assertThat(f.validateInput()).isTrue();
            f.save();
        });
        String text = "";
        for (int i = 0; i < 300 && !text.contains("bereits vorhanden"); i++) {
            Thread.sleep(50);
            String[] shown = new String[1];
            SwingUtilities.invokeAndWait(() -> shown[0] = form[0].feedback.text());
            text = shown[0];
        }
        assertThat(text).contains("Diese Wissensquelle ist bereits vorhanden (gleiches Ziel wie 'wiki')");
        assertThat(Files.readString(config)).isEqualTo(before);
    }

    @Test
    void theReleaseForTheAiServiceBelongsToTheSourceAndWorksWithoutAService() throws Exception {
        Path notizen = Files.createDirectories(home.resolve("notizen"));
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        start(config, null);

        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context);
            f.onSaved(id -> saved.countDown());
            assertThat(f.release.isSelected()).as("Vorgabe aus").isFalse();
            f.kind.setSelectedIndex(1);
            f.directory.setText(notizen.toString());
            f.release.setSelected(true);
            f.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).contains("- id: \"notizen\"", "external-processing-sources:")
                .doesNotContain("base-url");
        assertThat(context.settings().ai().externalProcessingSources()).containsExactly("notizen");
        assertThat(context.settings().ai().configured()).as("ohne Dienst wirkt die Freigabe noch nicht").isFalse();

        SourceDraft.Editable editable = new ConfigFileEditor(context.loader()).editable(config, "notizen");
        CountDownLatch again = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context, editable);
            f.onSaved(id -> again.countDown());
            assertThat(f.release.isSelected()).isTrue();
            f.release.setSelected(false);
            f.save();
        });
        assertThat(again.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.settings().ai().externalProcessingSources()).isEmpty();
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
        start(config, null);

        SourceDraft.Editable editable = new ConfigFileEditor(context.loader()).editable(config, "handbuch");
        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context, editable);
            f.onSaved(id -> saved.countDown());
            assertThat(f.id.isEditable()).isFalse();
            assertThat(f.title.getText()).isEqualTo("Alt");
            f.title.setText("Neu");
            f.directory.setText(neu.toString());
            assertThat(f.id.getText()).as("Beim Bearbeiten bleibt die Kennung").isEqualTo("handbuch");
            f.save();
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
        start(config, null);
        context.secrets().put("wiki", AppRuntime.SessionSecrets.login(SourceSettingsConfig.AuthType.PAT, null,
                "WIKI_TOKEN"), "sitzungs-token".toCharArray());

        SourceDraft.Editable editable = new ConfigFileEditor(context.loader()).editable(config, "wiki");
        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context, editable);
            f.onSaved(id -> saved.countDown());
            assertThat(f.validateInput()).isTrue();
            f.auth.setSelectedItem("Benutzer und Passwort");
            f.username.setText("angelo");
            assertThat(f.sessionSecrets(f.draft()).has("wiki")).as("Verbindungstest ohne altes Token").isFalse();
            assertThat(f.validateInput()).as("Token taugt nicht als Passwort").isFalse();
            assertThat(f.error(f.sessionValue)).isEqualTo("Bitte das Passwort eingeben.");
            f.sessionValue.setText("neues-passwort");
            assertThat(f.validateInput()).isTrue();
            assertThat(f.notes.getText()).contains("KEINE .bak").doesNotContain("bleibt als .bak");
            f.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).doesNotContain("alt-klartext", "neues-passwort").contains("type: basic");
        assertThat(home.resolve("app.yml.bak")).doesNotExist();
        assertThat(context.secrets().has("wiki")).as("das neue Passwort").isTrue();
    }

    @Test
    void confluenceAsksForLoginAndCertificatesOnTheSamePage() throws Exception {
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), home.resolve("app.yml"),
                "test");
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context);
            f.id.setText("Wiki.Neu");
            assertThat(f.validateInput()).as("ID wie im Core").isFalse();
            assertThat(f.error(f.id)).startsWith("Nur Kleinbuchstaben");
            f.id.setText("wiki");
            f.baseUrl.setText("ftp://wiki.example.org");
            assertThat(f.validateInput()).as("Adresse falsch").isFalse();
            assertThat(f.error(f.baseUrl)).isEqualTo("Die Adresse beginnt mit https://.");
            assertThat(f.error(f.id)).isNull();
            f.baseUrl.setText("https://wiki.example.org");
            assertThat(f.id.getText()).as("selbst eingetragen, bleibt").isEqualTo("wiki");
            assertThat(f.title.getText()).isEqualTo("wiki.example.org");
            assertThat(f.auth.getSelectedItem()).isEqualTo("Personal Access Token");
            assertThat(f.variable.getText()).isEqualTo("WIKI_TOKEN");
            assertThat(f.validateInput()).as("Token fehlt").isFalse();
            assertThat(f.error(f.sessionValue)).isEqualTo("Bitte das Token eingeben.");
            f.sessionValue.setText("geheim-123");
            f.certificate.setSelectedIndex(1);
            assertThat(f.chooseCertificate.isVisible()).isTrue();
            f.thumbprint.setText("0123456789abcdef0123456789abcdef01234567");
            assertThat(f.validateInput()).as("Alias fehlt").isFalse();
            assertThat(f.error(f.alias)).isEqualTo("Bitte den Alias wählen oder eintragen.");
            f.alias.setText("confluence-client");
            assertThat(f.validateInput()).isTrue();
            assertThat(f.draft().yaml("")).contains("token: ${WIKI_TOKEN}", "type: windows-my")
                    .doesNotContain("geheim-123");
            assertThat(f.notes.getText()).contains("nur für diese Sitzung").doesNotContain("setx");
        });
    }

    @Test
    void newSourcesFollowTheTrustOfAllSourcesWhichUnderWindowsIncludesTheWindowsStores() throws Exception {
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home, true),
                home.resolve("app.yml"), "test");
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context);
            assertThat(f.trust.getSelectedItem()).isEqualTo(SourceForm.NETWORK_TRUST);
            assertThat(f.draft().trustBundle()).isNull();
            assertThat(f.draft().yaml("")).doesNotContain("trust-bundle");
        });
        assertThat(context.loader().defaultTls()).isEqualTo(TrustSettings.system());
        assertThat(new AppSettingsLoader(new Placeholders(name -> null), home, false).defaultTls())
                .isEqualTo(TrustSettings.jvm());
    }

    @Test
    void anEmptyAddressMeansThePublicConfluenceHelpWithoutLogin() throws Exception {
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), home.resolve("app.yml"),
                "test");
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context);
            assertThat(f.baseUrl.getText()).isEmpty();
            assertThat(f.baseUrl.placeholder()).isEqualTo("https://confluence.atlassian.com");
            assertThat(f.id.getText()).isEqualTo("confluence-atlassian");
            assertThat(f.title.getText()).isEqualTo("Confluence-Hilfe (Atlassian)");
            assertThat(f.auth.getSelectedItem()).isEqualTo("Ohne Anmeldung");
            assertThat(f.validateInput()).isTrue();
            assertThat(f.draft().baseUrl()).isEqualTo("https://confluence.atlassian.com");
            assertThat(f.draft().yaml("")).contains("confluence.atlassian.com", "confluence-atlassian");

            f.baseUrl.setText("https://www.confluence.example.org/wiki");
            assertThat(f.id.getText()).isEqualTo("confluence-example");
            assertThat(f.title.getText()).isEqualTo("confluence.example.org");
            assertThat(f.auth.getSelectedItem()).as("eigene Instanz: mit Token").isEqualTo("Personal Access Token");
            f.auth.setSelectedItem("Ohne Anmeldung");
            f.baseUrl.setText("https://confluence.atlassian.com/");
            f.baseUrl.setText("https://intern.example.org");
            assertThat(f.auth.getSelectedItem()).as("Gewählte Anmeldung bleibt").isEqualTo("Ohne Anmeldung");
        });
    }

    @Test
    void aTypedTokenIsStoredInTheVaultAndUnlocksTheSourceAtTheNextStart() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        start(config, vault);

        CountDownLatch saved = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            SourceForm f = new SourceForm(context);
            f.onSaved(id -> saved.countDown());
            f.baseUrl.setText("https://wiki.example.invalid");
            assertThat(f.auth.getSelectedItem()).isEqualTo("Personal Access Token");
            f.sessionValue.setText("mein-token");
            assertThat(f.validateInput()).isTrue();
            assertThat(f.notes.getText()).contains("verschlüsselt", "die App setzt es beim Start selbst");
            f.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(config)).contains("token: ${WIKI_EXAMPLE_TOKEN}").doesNotContain("mein-token");
        assertThat(vault.has("wiki-example")).isTrue();
        assertThat(Files.readString(home.resolve("secrets").resolve("wiki-example.secret"))).doesNotContain("mein-token");

        // Neuer Start ohne Sitzungswert und ohne Umgebungsvariable: Der Tresor liefert das Token.
        context.shutdown();
        start(config, vault);
        assertThat(context.runtime().waitingForSecret()).isEmpty();
        assertThat(context.secrets().has("wiki-example")).isTrue();
    }
}
