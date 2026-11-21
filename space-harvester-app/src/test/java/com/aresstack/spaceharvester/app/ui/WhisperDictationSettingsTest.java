package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import com.aresstack.spaceharvester.app.transcription.WhisperModel;
import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Angelo 21.11.2026: Die Spracheingabe im Chat hat unter Einstellungen › Modelle ihr eigenes Whisper-Modell, Vorgabe
 * Tiny; das der Transkripte bleibt davon unberührt. Mit {@code SCREENSHOTS_DIR} entstehen Bilder zur Sichtprüfung.
 */
class WhisperDictationSettingsTest {

    @TempDir
    Path home;

    private Path config;
    private AppContext context;

    @BeforeEach
    void start() throws Exception {
        config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                  whisper:
                    model: small
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

    @Test
    void dictationHasItsOwnModelDefaultTinyAndChoosingItLeavesTranscriptsAlone() throws Exception {
        AiSettingsPanel[] panel = new AiSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new AiSettingsPanel(context));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(context.whisper()).isEqualTo(WhisperModel.SMALL);
            assertThat(context.dictationWhisper()).as("ohne Angabe Tiny").isEqualTo(WhisperModel.TINY);
            assertThat(panel[0].whisperChoice.getSelectedIndex()).isEqualTo(WhisperModel.SMALL.ordinal());
            assertThat(panel[0].dictationChoice.getSelectedIndex()).isEqualTo(WhisperModel.TINY.ordinal());
            panel[0].dictationChoice.setSelectedIndex(WhisperModel.BASE.ordinal());
        });
        long deadline = System.currentTimeMillis() + 30_000;
        WhisperModel[] now = new WhisperModel[1];
        do {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> now[0] = context.dictationWhisper());
        } while (now[0] != WhisperModel.BASE && System.currentTimeMillis() < deadline);

        assertThat(now[0]).isEqualTo(WhisperModel.BASE);
        assertThat(context.whisper()).as("Transkripte bleiben").isEqualTo(WhisperModel.SMALL);
        assertThat(WhisperModelLocation.dictation()).isEqualTo(WhisperModel.BASE);
        assertThat(WhisperModelLocation.selected()).isEqualTo(WhisperModel.SMALL);
        assertThat(Files.readString(config)).contains("    model: small\n", "    dictation-model: base\n");
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].dictationInstall.isVisible()).as("Base fehlt: Laden sichtbar").isTrue();
            assertThat(panel[0].dictationInstall.isEnabled()).as("kein Download läuft").isTrue();
            assertThat(panel[0].dictationInstall.getText()).isEqualTo(WhisperModelSetup.INSTALL);
        });

        String directory = System.getenv("SCREENSHOTS_DIR");
        if (directory != null && !directory.isBlank()) {
            Path out = Files.createDirectories(Path.of(directory));
            for (int width : new int[] {1280, 960}) {
                SwingUtilities.invokeAndWait(() -> {
                    JComponent models = panel[0].modelsComponent();
                    models.setSize(width, models.getPreferredSize().height);
                    Screenshots.write(models, out.resolve("einstellungen-modelle-whisper-" + width + ".png"));
                });
            }
        }
    }
}
