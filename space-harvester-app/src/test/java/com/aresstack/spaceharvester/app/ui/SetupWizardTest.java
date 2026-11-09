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
        assertThat(SetupWizard.STEPS).containsExactly("Wissensquellen", "Netzwerk", "KI-Dienst", "Modelle",
                "Abschluss");
        assertThat(texts(wizard[0].summary)).contains("Firmenwiki (wiki)", "keine Quelle", ParserModelSetup.LABEL);
        assertThat(texts(wizard[0].summary).stream().filter(t -> t.startsWith("Installiert")
                || t.startsWith("Nicht installiert") || t.startsWith("Nicht verfügbar")))
                .as("Stand des Parsermodells auf der Abschlussseite").isNotEmpty();
        assertThat(texts(wizard[0].sources)).contains("Firmenwiki (wiki)");
        // Ansehen ist kein Einrichten: Die Quelle ist nicht erreichbar, kein KI-Dienst, also kein Häkchen.
        SwingUtilities.invokeAndWait(() -> assertThat(wizard[0].done()).containsOnly(false));
    }

    @Test
    void theNetworkTestTriesTheSourceAndSaysClearlyThatItFailed() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        CountDownLatch tested = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            wizard[0].show(SetupWizard.NETWORK);
            wizard[0].testConnections(tested::countDown);
        });
        assertThat(tested.await(60, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(texts(wizard[0].tests)).anyMatch(text -> text.startsWith("Verbindung fehlgeschlagen"));
            assertThat(wizard[0].feedback.text()).contains("Verbindung fehlgeschlagen");
            assertThat(wizard[0].done()[SetupWizard.NETWORK]).isFalse();
        });
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

    @Test
    void theSecurityTabNamesWhereSecretsLiveButNeverTheirValues() throws Exception {
        SettingsView[] view = new SettingsView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SettingsView(context));
        assertThat(texts(view[0].security)).contains("Tresor", "API-Key des KI-Dienstes",
                "kein KI-Dienst eingerichtet", "Zugang Firmenwiki", "keins nötig");
    }

    @Test
    void theWizardHasNoSaveButtonAndTheSummaryShowsWhatStepsThreeAndFourEntered() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            assertThat(buttons(wizard[0].component())).as("kein „Speichern“ im Assistenten")
                    .noneMatch(text -> text.contains("Speichern"));
            wizard[0].show(SetupWizard.AI);
            wizard[0].ai.access.setSelectedItem(AiSettingsPanel.MANUAL);
            // Ein Port ohne Dienst: „Modelle“ lädt vergeblich, das Übernehmen hängt nicht davon ab.
            wizard[0].ai.baseUrl.setText("http://127.0.0.1:9/v1");
            wizard[0].ai.apiKey.setText("sk-wizard-geheim");
            wizard[0].next.doClick();
            assertThat(wizard[0].step()).isEqualTo(SetupWizard.MODELS);
            wizard[0].ai.visionModel.setText("vision-x");
            wizard[0].ai.rerankModel.setText("rerank-y");
            wizard[0].next.doClick();
            assertThat(wizard[0].step()).isEqualTo(SetupWizard.SUMMARY);
        });
        long until = System.currentTimeMillis() + 120_000;
        java.util.List<String> summary = new java.util.ArrayList<>();
        while (System.currentTimeMillis() < until) {
            SwingUtilities.invokeAndWait(() -> {
                summary.clear();
                summary.addAll(texts(wizard[0].summary));
            });
            if (summary.contains("rerank-y") && summary.contains("im Tresor")) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(summary).as("ohne „Speichern“ übernommen").contains("vision-x", "rerank-y",
                "im Tresor").doesNotContain("fehlt");
        assertThat(String.join("\n", summary)).doesNotContain("sk-wizard-geheim");
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).contains("vision-x", "rerank-y",
                "http://127.0.0.1:9/v1").doesNotContain("sk-wizard-geheim");
        SwingUtilities.invokeAndWait(() -> assertThat(wizard[0].ai.modelRetry.active())
                .as("„Modelle“ verlassen: keine Wiederholung").isFalse());
    }

    @Test
    void closingLikeLaterSavesFirstAndIncompleteEntriesKeepTheWizardOpen() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        int[] closed = {0};
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            wizard[0].close = () -> closed[0]++;
            wizard[0].show(SetupWizard.AI);
            wizard[0].ai.access.setSelectedItem(AiSettingsPanel.MANUAL);
            wizard[0].ai.baseUrl.setText("http://127.0.0.1:9/v1");
            wizard[0].ai.dimensions.setText("keine Zahl");
            // Escape und ✕ rufen dasselbe wie „Später“.
            wizard[0].requestClose();
            assertThat(closed[0]).as("unvollständig: bleibt offen").isZero();
            assertThat(wizard[0].feedback.text()).contains("nicht übernommen", "Dimension");
            wizard[0].ai.dimensions.setText("");
            wizard[0].ai.visionModel.setText("vision-beim-schliessen");
            wizard[0].requestClose();
        });
        long until = System.currentTimeMillis() + 120_000;
        int[] seen = {0};
        while (seen[0] == 0 && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> seen[0] = closed[0]);
        }
        assertThat(seen[0]).as("erst übernommen, dann geschlossen").isEqualTo(1);
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).contains("vision-beim-schliessen");
    }

    @Test
    void advancedHoldsDimensionAndChunkingAndInputsShareOneEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AiSettingsPanel panel = new AiSettingsPanel(context);
            javax.swing.JComponent models = panel.modelsComponent();
            models.setSize(700, 600);
            layout(models);
            assertThat(panel.dimensions.isVisible()).as("Dimension unter „Erweitert“").isFalse();
            assertThat(panel.chunkTokens.isVisible()).isFalse();
            panel.advanced.doClick();
            layout(models);
            assertThat(panel.dimensions.isVisible()).isTrue();
            // Modellfelder tragen ▾ im Feld (InputFrame): verglichen wird die äußere Kante des Felds.
            java.awt.Component chatFrame = panel.chatModel.getParent();
            int left = javax.swing.SwingUtilities.convertPoint(chatFrame, 0, 0, models).x;
            for (java.awt.Component field : java.util.List.of(panel.embeddingModel.getParent(),
                    panel.visionModel.getParent(), panel.rerankModel.getParent(), panel.dimensions,
                    panel.chunkTokens)) {
                assertThat(javax.swing.SwingUtilities.convertPoint(field, 0, 0, models).x).isEqualTo(left);
            }
            int right = javax.swing.SwingUtilities.convertPoint(chatFrame, chatFrame.getWidth(), 0, models).x;
            assertThat(javax.swing.SwingUtilities.convertPoint(panel.dimensions, panel.dimensions.getWidth(), 0,
                    models).x).as("gleiche rechte Kante wie Feld mit ▾").isEqualTo(right);
            assertThat(javax.swing.SwingUtilities.convertPoint(panel.advanced, 0, 0, models).x)
                    .as("„Erweitert“ linksbündig").isLessThan(left);
        });
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layout(nested);
            }
        }
    }

    private static java.util.List<String> buttons(java.awt.Container container) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.AbstractButton button && button.isVisible()) {
                texts.add(button.getText());
            }
            if (child instanceof java.awt.Container nested && child.isVisible()) {
                texts.addAll(buttons(nested));
            }
        }
        return texts;
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
