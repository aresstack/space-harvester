package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
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
            wizard[0].kind.setSelectedIndex(1);
            assertThat(wizard[0].next()).as("ID fehlt").isFalse();
            wizard[0].id.setText("handbuch");
            assertThat(wizard[0].next()).as("ID vergeben").isFalse();
            wizard[0].id.setText("notizen");
            assertThat(wizard[0].next()).isTrue();
            assertThat(wizard[0].step()).isEqualTo(SourceWizard.Step.ADDRESS);
            assertThat(wizard[0].next()).as("Ordner fehlt").isFalse();
            wizard[0].directory.setText(notizen.toString());
            assertThat(wizard[0].next()).isTrue();
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
            assertThat(w.next()).as("ID ist schon vergeben, aber die eigene").isTrue();
            w.directory.setText(neu.toString());
            assertThat(w.next()).isTrue();
            w.save();
        });
        assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
        String text = Files.readString(config);
        assertThat(text).contains("title: \"Neu\"", "maxFileBytes: \"1000\"").doesNotContain("title: Alt");
        assertThat(context.settings().sources()).singleElement()
                .satisfies(source -> assertThat(source.config().title()).isEqualTo("Neu"));
    }

    @Test
    void confluenceAsksForLoginAndCertificates() throws Exception {
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), home.resolve("app.yml"),
                "test");
        SwingUtilities.invokeAndWait(() -> {
            SourceWizard w = new SourceWizard(context);
            w.id.setText("Wiki.Neu");
            assertThat(w.next()).as("ID wie im Core").isFalse();
            w.id.setText("wiki");
            assertThat(w.next()).isTrue();
            assertThat(w.next()).as("Adresse fehlt").isFalse();
            w.baseUrl.setText("https://wiki.example.org");
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.AUTH);
            assertThat(w.variable.getText()).isEqualTo("WIKI_TOKEN");
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.SECURITY);
            w.certificate.setSelectedIndex(1);
            w.thumbprint.setText("0123456789abcdef0123456789abcdef01234567");
            assertThat(w.next()).as("Alias fehlt").isFalse();
            w.alias.setText("confluence-client");
            assertThat(w.next()).isTrue();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.CHECK);
            w.sessionValue.setText("geheim-123");
            assertThat(w.draft().yaml("")).contains("token: ${WIKI_TOKEN}", "type: windows-my")
                    .doesNotContain("geheim-123");
            w.back();
            assertThat(w.step()).isEqualTo(SourceWizard.Step.SECURITY);
        });
    }
}
