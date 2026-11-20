package com.aresstack.spaceharvester.app.hf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class HuggingFaceClientTest {

    private static final String REPO = "openai/whisper-base";
    private static final String FILE = "/openai/whisper-base/resolve/abc/model.safetensors";

    @TempDir
    Path directory;

    @Test
    void readsSizeAndChecksumOfLfsFilesFromTheTree() throws Exception {
        List<HuggingFaceFile> files = HuggingFaceClient.parseTree(REPO, "["
                + "{\"type\":\"directory\",\"oid\":\"1\",\"size\":0,\"path\":\"onnx\"},"
                + "{\"type\":\"file\",\"oid\":\"aa11\",\"size\":1983,\"path\":\"config.json\"},"
                + "{\"type\":\"file\",\"oid\":\"bb22\",\"size\":135,\"lfs\":{\"oid\":\"" + "c".repeat(64)
                + "\",\"size\":290403936,\"pointerSize\":135},\"path\":\"model.safetensors\"}]");

        assertThat(files).containsExactly(
                new HuggingFaceFile(REPO, "config.json", 1983, "", "aa11"),
                new HuggingFaceFile(REPO, "model.safetensors", 290403936L, "c".repeat(64), ""));
    }

    @Test
    void resolvesMainToItsCommit() throws Exception {
        FakeHuggingFace hub = new FakeHuggingFace().put("/api/models/openai/whisper-base/revision/main",
                "{\"id\":\"openai/whisper-base\",\"sha\":\"abc\"}");

        assertThat(new HuggingFaceClient(hub).resolveRevision(REPO, "main")).isEqualTo("abc");
    }

    @Test
    void resumesAfterADroppedConnectionAndVerifiesTheChecksum() throws Exception {
        byte[] weights = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
        FakeHuggingFace hub = new FakeHuggingFace().put(FILE, weights);
        hub.breaks = 1;
        Path target = directory.resolve("model.safetensors");

        new HuggingFaceClient(hub).download(new HuggingFaceFile(REPO, "model.safetensors", weights.length,
                FakeHuggingFace.sha256(weights), ""), "abc", target, (done, total) -> { }, () -> false);

        assertThat(Files.readAllBytes(target)).containsExactly(weights);
        assertThat(hub.requests).containsExactly(FILE + "@0", FILE + "@10");
        assertThat(target.resolveSibling("model.safetensors.part")).doesNotExist();
    }

    @Test
    void refusesContentWithAWrongChecksum() throws Exception {
        byte[] weights = "manipuliert".getBytes(StandardCharsets.UTF_8);
        FakeHuggingFace hub = new FakeHuggingFace().put(FILE, weights);
        Path target = directory.resolve("model.safetensors");

        assertThatThrownBy(() -> new HuggingFaceClient(hub).download(new HuggingFaceFile(REPO, "model.safetensors",
                weights.length, "0".repeat(64), ""), "abc", target, (done, total) -> { }, () -> false))
                .isInstanceOf(IOException.class).hasMessageContaining("SHA-256");
        assertThat(target).doesNotExist();
        assertThat(target.resolveSibling("model.safetensors.part")).doesNotExist();
    }

    @Test
    void verifiesSmallFilesAgainstTheirGitChecksum() throws Exception {
        byte[] config = "{\"d_model\":512}".getBytes(StandardCharsets.UTF_8);
        FakeHuggingFace hub = new FakeHuggingFace().put("/openai/whisper-base/resolve/abc/config.json", config);
        HuggingFaceClient client = new HuggingFaceClient(hub);

        client.download(new HuggingFaceFile(REPO, "config.json", config.length, "", FakeHuggingFace.gitOid(config)),
                "abc", directory.resolve("config.json"), (done, total) -> { }, () -> false);
        assertThat(directory.resolve("config.json")).hasBinaryContent(config);

        assertThatThrownBy(() -> client.download(new HuggingFaceFile(REPO, "config.json", config.length, "",
                "0".repeat(40)), "abc", directory.resolve("other.json"), (done, total) -> { }, () -> false))
                .isInstanceOf(IOException.class).hasMessageContaining("Git-Prüfsumme");
    }

    @Test
    void aCancelledDownloadKeepsWhatIsLoadedForTheNextAttempt() throws Exception {
        byte[] weights = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
        FakeHuggingFace hub = new FakeHuggingFace().put(FILE, weights);
        hub.breaks = 1;
        Path target = directory.resolve("model.safetensors");
        HuggingFaceFile file = new HuggingFaceFile(REPO, "model.safetensors", weights.length,
                FakeHuggingFace.sha256(weights), "");
        boolean[] cancelled = {false};

        assertThatThrownBy(() -> new HuggingFaceClient(hub).download(file, "abc", target, (done, total) -> {
            cancelled[0] = done >= 5;
        }, () -> cancelled[0])).isInstanceOf(CancellationException.class);
        assertThat(target).doesNotExist();
        assertThat(target.resolveSibling("model.safetensors.part")).exists();

        new HuggingFaceClient(hub).download(file, "abc", target, (done, total) -> { }, () -> false);
        assertThat(Files.readAllBytes(target)).containsExactly(weights);
    }

    @Test
    void namesTheStatusWhenTheProxyRefuses() {
        assertThatThrownBy(() -> new HuggingFaceClient(new FakeHuggingFace()).listFiles(REPO, "abc"))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP 404").hasMessageContaining("huggingface.co");
    }
}
