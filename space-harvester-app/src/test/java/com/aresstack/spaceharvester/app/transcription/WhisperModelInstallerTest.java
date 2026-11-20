package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.spaceharvester.app.hf.FakeHuggingFace;
import com.aresstack.spaceharvester.app.hf.HuggingFaceClient;
import com.aresstack.spaceharvester.app.hf.HuggingFaceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WhisperModelInstallerTest {

    private static final WhisperModelInstaller.Progress SILENT = new WhisperModelInstaller.Progress() {
        @Override
        public void step(String text) {
        }

        @Override
        public void bytes(long done, long total) {
        }
    };

    @TempDir
    Path models;

    @Test
    void loadsABiggerModelFromTheRevisionHuggingFaceNamesAndActivatesItAsAWhole() throws Exception {
        FakeHuggingFace hub = base(true);
        WhisperModelInstaller installer = new WhisperModelInstaller(models, new HuggingFaceClient(hub));
        List<Long> reported = new ArrayList<>();
        assertThat(installer.installed(WhisperModel.BASE)).isFalse();

        installer.install(WhisperModel.BASE, new WhisperModelInstaller.Progress() {
            @Override
            public void step(String text) {
            }

            @Override
            public void bytes(long done, long total) {
                reported.add(total);
            }
        }, () -> false);

        Path directory = models.resolve("whisper-base");
        assertThat(installer.installed(WhisperModel.BASE)).isTrue();
        assertThat(installer.directory(WhisperModel.BASE)).isEqualTo(directory);
        assertThat(directory.resolve("model.safetensors")).hasContent("gewichte");
        assertThat(directory.resolve("generation_config.json")).exists();
        assertThat(models.resolve("whisper-base.part")).doesNotExist();
        // Alles aus dem aufgelösten Commit, nichts aus „main“.
        assertThat(hub.requests).filteredOn(request -> request.contains("/resolve/"))
                .allMatch(request -> request.contains("/resolve/c0ffee/"));
        // Die Gesamtlänge aller Dateien steht von Anfang an fest.
        assertThat(reported).isNotEmpty().containsOnly((long) ("gewichte".length() + 4 * 2));
        assertThat(installer.installed(WhisperModel.TINY)).isFalse();
    }

    @Test
    void afterAFailureTheNextAttemptOnlyLoadsWhatIsMissing() throws Exception {
        FakeHuggingFace hub = base(false);
        WhisperModelInstaller installer = new WhisperModelInstaller(models, new HuggingFaceClient(hub));

        assertThatThrownBy(() -> installer.install(WhisperModel.BASE, SILENT, () -> false))
                .isInstanceOf(IOException.class);
        assertThat(installer.installed(WhisperModel.BASE)).isFalse();
        assertThat(models.resolve("whisper-base.part").resolve("model.safetensors")).exists();

        hub.put("/openai/whisper-base/resolve/c0ffee/vocab.json", "{}");
        hub.requests.clear();
        installer.install(WhisperModel.BASE, SILENT, () -> false);

        assertThat(installer.installed(WhisperModel.BASE)).isTrue();
        assertThat(hub.requests).noneMatch(request -> request.contains("model.safetensors"));
    }

    @Test
    void filesStagedFromAnOlderRevisionAreLoadedAgain() throws Exception {
        FakeHuggingFace hub = base(true);
        Path staging = Files.createDirectories(models.resolve("whisper-base.part"));
        Files.writeString(staging.resolve(WhisperModelInstaller.REVISION_MARKER), "alt");
        Files.writeString(staging.resolve("config.json"), "alter Stand");
        WhisperModelInstaller installer = new WhisperModelInstaller(models, new HuggingFaceClient(hub));

        installer.install(WhisperModel.BASE, SILENT, () -> false);

        assertThat(models.resolve("whisper-base").resolve("config.json")).hasContent("{}");
        assertThat(models.resolve("whisper-base").resolve(WhisperModelInstaller.REVISION_MARKER)).hasContent("c0ffee");
    }

    @Test
    void anExplicitlyGivenModelFolderCountsAsInstalledForEveryModel() {
        WhisperModelInstaller installer = new WhisperModelInstaller(models, new HuggingFaceClient(new FakeHuggingFace()));
        System.setProperty(WhisperModelLocation.PROPERTY, models.resolve("eigenes").toString());
        try {
            assertThat(installer.installed(WhisperModel.LARGE_V3)).isTrue();
        } finally {
            System.clearProperty(WhisperModelLocation.PROPERTY);
        }
        assertThat(installer.installed(WhisperModel.LARGE_V3)).isFalse();
    }

    @Test
    void neverLoadsWeightsWithoutAChecksum() {
        List<HuggingFaceFile> listed = new ArrayList<>();
        for (String name : WhisperModelInstaller.REQUIRED) {
            listed.add(new HuggingFaceFile("openai/whisper-base", name, 2, "", "aa"));
        }

        assertThatThrownBy(() -> WhisperModelInstaller.wanted(WhisperModel.BASE, listed))
                .isInstanceOf(IOException.class).hasMessageContaining("keine Prüfsumme");
        assertThatThrownBy(() -> WhisperModelInstaller.wanted(WhisperModel.BASE, listed.subList(0, 1)))
                .isInstanceOf(IOException.class).hasMessageContaining("enthält");
    }

    @Test
    void theDefaultModelComesFromItsPinnedRevisionAndRejectsOtherContent() {
        FakeHuggingFace hub = new FakeHuggingFace();
        String revision = WhisperModel.TINY.revision();
        for (String name : WhisperModelInstaller.REQUIRED) {
            hub.put("/openai/whisper-tiny/resolve/" + revision + "/" + name, "nicht das Original");
        }
        WhisperModelInstaller installer = new WhisperModelInstaller(models, new HuggingFaceClient(hub));

        assertThatThrownBy(() -> installer.install(WhisperModel.TINY, SILENT, () -> false))
                .isInstanceOf(IOException.class).hasMessageContaining("SHA-256");
        assertThat(installer.installed(WhisperModel.TINY)).isFalse();
        // Festgelegt: keine Abfrage, welche Revision oder welche Prüfsummen gerade gelten.
        assertThat(hub.requests).noneMatch(request -> request.contains("/api/"));
    }

    /** Ein Repository „openai/whisper-base“ im Commit {@code c0ffee}; ohne {@code vocab.json}, wenn unvollständig. */
    private static FakeHuggingFace base(boolean complete) throws Exception {
        byte[] weights = "gewichte".getBytes(StandardCharsets.UTF_8);
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        StringBuilder tree = new StringBuilder("[{\"type\":\"file\",\"oid\":\"x\",\"size\":134,\"lfs\":{\"oid\":\"")
                .append(FakeHuggingFace.sha256(weights)).append("\",\"size\":").append(weights.length)
                .append("},\"path\":\"model.safetensors\"}");
        FakeHuggingFace hub = new FakeHuggingFace()
                .put("/api/models/openai/whisper-base/revision/main", "{\"sha\":\"c0ffee\"}")
                .put("/openai/whisper-base/resolve/c0ffee/model.safetensors", weights);
        for (String name : List.of("config.json", "tokenizer.json", "vocab.json", "generation_config.json")) {
            tree.append(",{\"type\":\"file\",\"oid\":\"").append(FakeHuggingFace.gitOid(json))
                    .append("\",\"size\":2,\"path\":\"").append(name).append("\"}");
            if (complete || !name.equals("vocab.json")) {
                hub.put("/openai/whisper-base/resolve/c0ffee/" + name, json);
            }
        }
        return hub.put("/api/models/openai/whisper-base/tree/c0ffee", tree.append("]").toString());
    }
}
