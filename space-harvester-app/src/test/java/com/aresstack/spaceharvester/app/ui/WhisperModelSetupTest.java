package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.hf.FakeHuggingFace;
import com.aresstack.spaceharvester.app.hf.HuggingFaceClient;
import com.aresstack.spaceharvester.app.transcription.WhisperModel;
import com.aresstack.spaceharvester.app.transcription.WhisperModelInstaller;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperModelSetupTest {

    @TempDir
    Path models;

    private final List<String> asked = new ArrayList<>();
    private final List<String> progress = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();
    private boolean answer;
    private WhisperModel selected = WhisperModel.TINY;
    private int started;

    private final WhisperModelSetup.Runner direct = new WhisperModelSetup.Runner() {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            try {
                onSuccess.accept(work.call());
            } catch (Exception e) {
                onFailure.accept(e);
            }
        }
    };

    private WhisperModelSetup setup(FakeHuggingFace hub) {
        WhisperModelInstaller installer = new WhisperModelInstaller(models, new HuggingFaceClient(hub));
        return new WhisperModelSetup(() -> Optional.of(installer), () -> selected, direct, (parent, title, text) -> {
            asked.add(text);
            return answer;
        });
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
        // Drain progress notifications posted by the synchronous test runner.
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void aModelThatIsThereStartsTheActionWithoutAsking() throws Exception {
        Path tiny = Files.createDirectories(models.resolve("whisper-tiny"));
        for (String name : List.of("config.json", "model.safetensors", "tokenizer.json", "vocab.json")) {
            Files.write(tiny.resolve(name), new byte[] {1});
        }
        WhisperModelSetup setup = setup(new FakeHuggingFace());

        onEdt(() -> setup.ensure(null, progress::add, stopped::add, () -> started++));

        assertThat(started).isEqualTo(1);
        assertThat(asked).isEmpty();
        assertThat(setup.status().state()).isEqualTo(WhisperModelSetup.State.INSTALLED);
    }

    @Test
    void aMissingModelIsOnlyLoadedAfterYes() throws Exception {
        FakeHuggingFace hub = new FakeHuggingFace();
        WhisperModelSetup setup = setup(hub);
        answer = false;

        onEdt(() -> setup.ensure(null, progress::add, stopped::add, () -> started++));

        assertThat(asked).singleElement().asString().contains("Tiny (ca. 150 MB)", "Wirklich", "Hugging Face");
        assertThat(hub.requests).isEmpty();
        assertThat(started).isZero();
        assertThat(stopped).containsExactly("Ohne Whisper-Modell nicht gestartet.");
    }

    @Test
    void afterYesTheChosenModelIsLoadedAndTheActionContinues() throws Exception {
        selected = WhisperModel.BASE;
        byte[] weights = {1, 2, 3};
        byte[] json = {'{', '}'};
        StringBuilder tree = new StringBuilder("[{\"type\":\"file\",\"lfs\":{\"oid\":\"")
                .append(FakeHuggingFace.sha256(weights)).append("\",\"size\":3},\"path\":\"model.safetensors\"}");
        FakeHuggingFace hub = new FakeHuggingFace()
                .put("/api/models/openai/whisper-base/revision/main", "{\"sha\":\"c0ffee\"}")
                .put("/openai/whisper-base/resolve/c0ffee/model.safetensors", weights);
        for (String name : List.of("config.json", "tokenizer.json", "vocab.json")) {
            tree.append(",{\"type\":\"file\",\"oid\":\"").append(FakeHuggingFace.gitOid(json))
                    .append("\",\"size\":2,\"path\":\"").append(name).append("\"}");
            hub.put("/openai/whisper-base/resolve/c0ffee/" + name, json);
        }
        hub.put("/api/models/openai/whisper-base/tree/c0ffee", tree.append("]").toString());
        WhisperModelSetup setup = setup(hub);
        answer = true;

        onEdt(() -> setup.ensure(null, progress::add, stopped::add, () -> started++));

        assertThat(asked).singleElement().asString().contains("Base");
        assertThat(started).isEqualTo(1);
        assertThat(stopped).isEmpty();
        assertThat(setup.status().state()).isEqualTo(WhisperModelSetup.State.INSTALLED);
        assertThat(models.resolve("whisper-base").resolve("model.safetensors")).exists();
    }

    @Test
    void theModelChosenInTheTranscriptViewIsLoadedWithoutChangingTheSetting() throws Exception {
        // Angelo 20.11.2026: je Whisper-Modell ein Original; fehlt das gewählte, kommt die bekannte Ja/Nein-Abfrage.
        byte[] weights = {1, 2, 3};
        byte[] json = {'{', '}'};
        StringBuilder tree = new StringBuilder("[{\"type\":\"file\",\"lfs\":{\"oid\":\"")
                .append(FakeHuggingFace.sha256(weights)).append("\",\"size\":3},\"path\":\"model.safetensors\"}");
        FakeHuggingFace hub = new FakeHuggingFace()
                .put("/api/models/openai/whisper-small/revision/main", "{\"sha\":\"c0ffee\"}")
                .put("/openai/whisper-small/resolve/c0ffee/model.safetensors", weights);
        for (String name : List.of("config.json", "tokenizer.json", "vocab.json")) {
            tree.append(",{\"type\":\"file\",\"oid\":\"").append(FakeHuggingFace.gitOid(json))
                    .append("\",\"size\":2,\"path\":\"").append(name).append("\"}");
            hub.put("/openai/whisper-small/resolve/c0ffee/" + name, json);
        }
        hub.put("/api/models/openai/whisper-small/tree/c0ffee", tree.append("]").toString());
        WhisperModelSetup setup = setup(hub);
        answer = true;

        onEdt(() -> setup.ensure(WhisperModel.SMALL, null, progress::add, stopped::add, () -> started++));

        assertThat(asked).singleElement().asString().contains("Small");
        assertThat(started).isEqualTo(1);
        assertThat(stopped).isEmpty();
        assertThat(models.resolve("whisper-small").resolve("model.safetensors")).exists();
        assertThat(setup.status().model()).as("die Einstellung bleibt").isEqualTo(WhisperModel.TINY);

        onEdt(() -> setup.ensure(WhisperModel.SMALL, null, progress::add, stopped::add, () -> started++));
        assertThat(asked).as("jetzt da: keine Frage mehr").hasSize(1);
        assertThat(started).isEqualTo(2);
    }

    @Test
    void aFailedDownloadIsReportedAndDoesNotStartTheAction() throws Exception {
        WhisperModelSetup setup = setup(new FakeHuggingFace());
        answer = true;

        onEdt(() -> setup.ensure(null, progress::add, stopped::add, () -> started++));

        assertThat(started).isZero();
        assertThat(setup.status().state()).isEqualTo(WhisperModelSetup.State.NOT_INSTALLED);
        assertThat(setup.status().failed()).isTrue();
        assertThat(stopped).singleElement().asString().contains("Whisper-Modell nicht geladen", "HTTP 404");
    }

    @Test
    void anotherChoiceShowsItsOwnState() throws Exception {
        Path tiny = Files.createDirectories(models.resolve("whisper-tiny"));
        for (String name : List.of("config.json", "model.safetensors", "tokenizer.json", "vocab.json")) {
            Files.write(tiny.resolve(name), new byte[] {1});
        }
        WhisperModelSetup setup = setup(new FakeHuggingFace());
        assertThat(setup.status().state()).isEqualTo(WhisperModelSetup.State.INSTALLED);

        selected = WhisperModel.SMALL;
        onEdt(setup::refresh);

        assertThat(setup.status().model()).isEqualTo(WhisperModel.SMALL);
        assertThat(setup.status().state()).isEqualTo(WhisperModelSetup.State.NOT_INSTALLED);
    }
}
