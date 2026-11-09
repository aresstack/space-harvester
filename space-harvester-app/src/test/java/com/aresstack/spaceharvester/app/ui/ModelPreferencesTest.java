package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Vision und Rerank: Vorzugsmodell nur, wenn angeboten; nie über eine Eingabe; 401/403 verständlich. */
class ModelPreferencesTest {

    @TempDir
    Path home;

    private AppContext context;

    @BeforeEach
    void start() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources: []
                """.formatted(home.resolve("data")));
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(120, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    private static AiModel model(String id) {
        return new AiModel(id, id, Set.of());
    }

    @Test
    void presentMatchesCaseInsensitiveWithOrWithoutVendorAndCatalog() {
        String vision = PreferredModels.bundled().model("vision");
        String rerank = PreferredModels.bundled().model("rerank");
        assertThat(vision).isEqualTo("google/gemma-4-31B-it");
        assertThat(rerank).isEqualTo("nvidia/llama-nemotron-rerank-1b-v2");
        assertThat(ModelPreselection.present(List.of("a", "GOOGLE/gemma-4-31b-IT"), vision))
                .isEqualTo("GOOGLE/gemma-4-31b-IT");
        assertThat(ModelPreselection.present(List.of("kipitz:google/gemma-4-31B-it"), vision))
                .isEqualTo("kipitz:google/gemma-4-31B-it");
        assertThat(ModelPreselection.present(List.of("llama-nemotron-rerank-1b-v2"), rerank))
                .as("ohne Anbieterteil").isEqualTo("llama-nemotron-rerank-1b-v2");
        assertThat(ModelPreselection.present(List.of("x/gemma-4-31B-it", "google/gemma-4-31B-it"), vision))
                .as("genaue Kennung zuerst").isEqualTo("google/gemma-4-31B-it");
        assertThat(ModelPreselection.present(List.of("google/gemma-3-27b-it", "bge-reranker"), vision)).isEmpty();
    }

    @Test
    void visionAndRerankArePreselectedOnlyWhenOfferedAndNeverOverwritten() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AiSettingsPanel panel = new AiSettingsPanel(context);
            panel.showModels(List.of(model("openai/gpt-oss-120b"), model("other/embed")));
            assertThat(panel.visionModel.getText()).as("nicht angeboten: leer").isEmpty();
            assertThat(panel.rerankModel.getText()).isEmpty();

            panel.showModels(List.of(model("openai/gpt-oss-120b"), model("google/gemma-4-31B-it"),
                    model("nvidia/llama-nemotron-rerank-1b-v2")));
            assertThat(panel.visionModel.getText()).isEqualTo("google/gemma-4-31B-it");
            assertThat(panel.rerankModel.getText()).isEqualTo("nvidia/llama-nemotron-rerank-1b-v2");

            AiSettingsPanel chosen = new AiSettingsPanel(context);
            chosen.visionModel.setText("mein-vision");
            chosen.rerankModel.setText("bge-reranker");
            chosen.showModels(List.of(model("google/gemma-4-31B-it"), model("nvidia/llama-nemotron-rerank-1b-v2")));
            assertThat(chosen.visionModel.getText()).isEqualTo("mein-vision");
            assertThat(chosen.rerankModel.getText()).isEqualTo("bge-reranker");
        });
    }

    @Test
    void unauthorizedSaysWhatToDoAndIsNeverRetried() {
        AiServiceException rejected = new AiServiceException(AiServiceException.Reason.UNAUTHORIZED, "GET /models");
        assertThat(AiSettingsPanel.reason(rejected, true)).isEqualTo("Nicht authentifiziert (401/403): API-Key prüfen");
        assertThat(AiSettingsPanel.reason(rejected, false)).isEqualTo("Nicht angemeldet – API-Key fehlt");
        AiServiceException old = new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE,
                "GET /models: HTTP 403");
        assertThat(AiSettingsPanel.reason(old, true)).as("Status aus der Meldung des Core")
                .isEqualTo("Nicht authentifiziert (401/403): API-Key prüfen");
        assertThat(AiSettingsPanel.reason(new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE,
                "GET /models: HTTP 302"), true)).isEqualTo("unerwartete Antwort des Dienstes (HTTP 302)");
        assertThat(AiSettingsPanel.retryable(rejected)).isFalse();
        assertThat(AiSettingsPanel.retryable(old)).isFalse();
        assertThat(AiSettingsPanel.retryable(new AiServiceException(AiServiceException.Reason.UNREACHABLE, "x")))
                .isTrue();
    }

    @Test
    void theWizardRetriesAnUnreachableServiceOnlyWhileModelsIsOpen() throws Exception {
        ModelRetryTest.FakeScheduler scheduler = new ModelRetryTest.FakeScheduler();
        AiSettingsPanel[] panel = new AiSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new AiSettingsPanel(context, scheduler);
            panel[0].access.setSelectedItem(AiSettingsPanel.MANUAL);
            // Ein Port ohne Dienst: nicht erreichbar.
            panel[0].baseUrl.setText("http://127.0.0.1:9/v1");
            panel[0].prepareModels();
            assertThat(panel[0].modelRetry.active()).isTrue();
        });
        long until = System.currentTimeMillis() + 120_000;
        boolean[] waiting = {false};
        while (!waiting[0] && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> waiting[0] = scheduler.pending != null);
        }
        assertThat(waiting[0]).as("nächster Versuch eingeplant").isTrue();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].modelsFeedback.text()).contains("Versuch 1 – nächster in 5 s …",
                    "Dienst nicht erreichbar");
            panel[0].stopModels();
            assertThat(panel[0].modelRetry.active()).isFalse();
            assertThat(scheduler.pending).as("Schritt verlassen").isNull();
            assertThat(scheduler.cancelled).isEqualTo(1);
        });
    }

    @Test
    void anUnauthorizedFailureIsShownAndStopsTheRetry() throws Exception {
        ModelRetryTest.FakeScheduler scheduler = new ModelRetryTest.FakeScheduler();
        SwingUtilities.invokeAndWait(() -> {
            AiSettingsPanel panel = new AiSettingsPanel(context, scheduler);
            boolean[] retryable = {true};
            panel.modelsFailed(new AiServiceException(AiServiceException.Reason.UNAUTHORIZED, "GET /models: HTTP 401"),
                    true, new ModelRetry.Outcome() {
                        @Override
                        public void succeeded() {
                        }

                        @Override
                        public void failed(boolean again) {
                            retryable[0] = again;
                        }
                    });
            assertThat(retryable[0]).isFalse();
            assertThat(panel.modelsFeedback.text()).contains("Nicht authentifiziert (401/403): API-Key prüfen")
                    .doesNotContain("unerwartete Antwort");
        });
    }
}
