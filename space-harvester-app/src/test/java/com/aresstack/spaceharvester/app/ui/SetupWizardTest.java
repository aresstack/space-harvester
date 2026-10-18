package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Einrichtungsassistent: einmal beim Start, danach über die Einstellungen; Schritte wie im EAC. */
class SetupWizardTest {

    @TempDir
    Path home;

    private AppContext context;
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
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void theShipWalksThroughTheStepsAndTheSummaryShowsTheSource() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        SwingUtilities.invokeAndWait(() -> wizard[0] = new SetupWizard(context));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(wizard[0].step()).isZero();
            assertThat(wizard[0].back.isEnabled()).isFalse();
            assertThat(wizard[0].finish.isVisible()).isFalse();
            for (int i = 1; i < SetupWizard.STEPS.length; i++) {
                wizard[0].next.doClick();
                assertThat(wizard[0].step()).isEqualTo(i);
            }
            assertThat(wizard[0].finish.isVisible()).isTrue();
            assertThat(wizard[0].next.isVisible()).isFalse();
            assertThat(wizard[0].trail.current()).isEqualTo(SetupWizard.STEPS.length - 1);
        });
        assertThat(SetupWizard.LINES).hasSameSizeAs(SetupWizard.STEPS);
        assertThat(texts(wizard[0].summary)).contains("1 Quelle");
        assertThat(texts(wizard[0].sources)).contains("Firmenwiki", "wiki");
    }

    @Test
    void closingTheWizardRemembersItInTheFileAndKeepsTheRest() throws Exception {
        assertThat(SetupWizard.due(context)).isTrue();
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> context.setupCompleted(done::countDown, error -> {
            failure[0] = error;
            done.countDown();
        }));
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failure[0]).isNull();
        assertThat(SetupWizard.due(context)).isFalse();
        String text = Files.readString(config, StandardCharsets.UTF_8);
        assertThat(text).contains("  setup-completed: true", "# Kommentar bleibt stehen", "title: Firmenwiki");
        assertThat(context.loader().read(config, text).setupCompleted()).isTrue();
    }

    private static java.util.List<String> texts(java.awt.Container container) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JLabel label) {
                texts.add(label.getText());
            } else if (child instanceof javax.swing.text.JTextComponent area) {
                texts.add(area.getText());
            } else if (child instanceof java.awt.Container nested) {
                texts.addAll(texts(nested));
            }
        }
        return texts;
    }
}
