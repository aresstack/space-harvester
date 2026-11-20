package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class WhisperModelLocationTest {

    @TempDir
    Path workspace;

    @Test
    void aModelThatWasNeverLoadedIsMissing() {
        assertThat(WhisperModelLocation.find(workspace.resolve("models"), WhisperModel.TINY)).isEmpty();
        assertThat(new WhisperModelMissingException(WhisperModel.SMALL)).hasMessageContaining("Small")
                .hasMessageContaining("Einstellungen › Modelle");
    }

    @Test
    void findsTheLoadedModelInItsOwnFolder() throws Exception {
        Path small = complete(workspace.resolve("models").resolve("whisper-small"));

        assertThat(WhisperModelLocation.find(workspace.resolve("models"), WhisperModel.SMALL))
                .contains(small.toAbsolutePath().normalize());
        assertThat(WhisperModelLocation.find(workspace.resolve("models"), WhisperModel.TINY)).isEmpty();
    }

    @Test
    void keepsUsingTheTinyModelAnEarlierVersionExtracted() throws Exception {
        Path legacy = complete(workspace.resolve("models").resolve(WhisperModelLocation.LEGACY_TINY_FOLDER));

        assertThat(WhisperModelLocation.find(workspace.resolve("models"), WhisperModel.TINY))
                .contains(legacy.toAbsolutePath().normalize());
        assertThat(WhisperModelLocation.find(workspace.resolve("models"), WhisperModel.BASE)).isEmpty();
    }

    @Test
    void prefersACompleteManuallyInstalledModel() throws Exception {
        Path installed = complete(workspace.resolve("models").resolve("whisper"));
        complete(workspace.resolve("models").resolve("whisper-tiny"));

        assertThat(WhisperModelLocation.find(workspace.resolve("models"), WhisperModel.TINY))
                .contains(installed.toAbsolutePath().normalize());
    }

    @Test
    void ignoresAnIncompleteManualInstallation() throws Exception {
        Path installed = Files.createDirectories(workspace.resolve("models").resolve("whisper"));
        Files.write(installed.resolve("config.json"), new byte[]{1});
        Files.write(installed.resolve("model.safetensors"), new byte[]{1});

        assertThat(WhisperModelLocation.find(workspace.resolve("models"), WhisperModel.TINY)).isEmpty();
    }

    @Test
    void readsTheModelFromItsConfigurationValue() {
        assertThat(WhisperModel.of("large-v3-turbo")).isEqualTo(WhisperModel.LARGE_V3_TURBO);
        assertThat(WhisperModel.of(" Tiny ")).isEqualTo(WhisperModel.TINY);
        assertThat(WhisperModel.of("riesig")).isNull();
        assertThat(WhisperModel.DEFAULT).isEqualTo(WhisperModel.TINY);
        assertThat(WhisperModel.MEDIUM.described()).isEqualTo("Medium (ca. 3,1 GB)");
    }

    static Path complete(Path directory) throws Exception {
        Files.createDirectories(directory);
        for (String file : WhisperModelInstaller.REQUIRED) {
            Files.write(directory.resolve(file), new byte[]{1});
        }
        return directory;
    }
}
