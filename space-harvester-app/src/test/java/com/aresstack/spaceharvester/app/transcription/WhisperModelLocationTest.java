package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WhisperModelLocationTest {

    @TempDir
    Path workspace;

    @Test
    void extractsEmbeddedFilesAndReusesVerifiedCache() throws Exception {
        byte[] model = "example-model-data".getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = Collections.singletonMap("model.safetensors", sha256(model));
        AtomicInteger opened = new AtomicInteger();
        Path destination = workspace.resolve("model");

        Path extracted = WhisperModelLocation.extract(destination, name -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(model);
        }, hashes);
        Path reused = WhisperModelLocation.extract(destination, name -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(model);
        }, hashes);

        assertThat(extracted).isEqualTo(destination);
        assertThat(reused).isEqualTo(destination);
        assertThat(Files.readAllBytes(destination.resolve("model.safetensors"))).containsExactly(model);
        assertThat(opened.get()).isEqualTo(1);
    }

    @Test
    void replacesCorruptCachedModelWithVerifiedResource() throws Exception {
        byte[] original = "trusted-model".getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = Collections.singletonMap("model.safetensors", sha256(original));
        Path target = workspace.resolve("model").resolve("model.safetensors");
        Files.createDirectories(target.getParent());
        Files.write(target, "damaged-model".getBytes(StandardCharsets.UTF_8));

        WhisperModelLocation.extract(target.getParent(), name -> new ByteArrayInputStream(original), hashes);

        assertThat(Files.readAllBytes(target)).containsExactly(original);
    }

    @Test
    void refusesMissingOrModifiedEmbeddedModel() throws Exception {
        byte[] data = "wrong-model".getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("model.safetensors", sha256("original-model".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> WhisperModelLocation.extract(workspace.resolve("wrong"),
                name -> new ByteArrayInputStream(data), hashes))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("SHA-256");

        assertThatThrownBy(() -> WhisperModelLocation.extract(workspace.resolve("missing"),
                name -> null, hashes))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("not embedded");

        try (java.util.stream.Stream<Path> entries = Files.list(workspace.resolve("wrong"))) {
            assertThat(entries.count()).isZero();
        }
    }

    @Test
    void resolvesVerifiedStagedModelFromProjectRootForDirectIntelliJStart() throws Exception {
        byte[] model = "verified-local-model".getBytes(StandardCharsets.UTF_8);
        Path staged = workspace.resolve("space-harvester-app/build/embedded-whisper-model");
        Files.createDirectories(staged);
        Files.write(staged.resolve("config.json"), model);

        Path resolved = WhisperModelLocation.resolveDevelopmentModel(workspace,
                Collections.singletonMap("config.json", sha256(model)));

        assertThat(resolved).isEqualTo(staged.toAbsolutePath());
    }

    @Test
    void resolvesVerifiedStagedModelFromModuleWorkingDirectory() throws Exception {
        byte[] model = "verified-local-model".getBytes(StandardCharsets.UTF_8);
        Path staged = workspace.resolve("build/embedded-whisper-model");
        Files.createDirectories(staged);
        Files.write(staged.resolve("config.json"), model);

        Path resolved = WhisperModelLocation.resolveDevelopmentModel(workspace,
                Collections.singletonMap("config.json", sha256(model)));

        assertThat(resolved).isEqualTo(staged.toAbsolutePath());
    }

    @Test
    void refusesCorruptedOrIncompleteDevelopmentModel() throws Exception {
        Path staged = workspace.resolve("space-harvester-app/build/embedded-whisper-model");
        Files.createDirectories(staged);
        Files.write(staged.resolve("config.json"), "corrupted".getBytes(StandardCharsets.UTF_8));
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("config.json", sha256("expected".getBytes(StandardCharsets.UTF_8)));
        hashes.put("model.safetensors", sha256("missing".getBytes(StandardCharsets.UTF_8)));

        assertThat(WhisperModelLocation.resolveDevelopmentModel(workspace, hashes)).isNull();
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }

    @Test
    void prefersACompleteManuallyInstalledModel() throws Exception {
        Path installed = Files.createDirectories(workspace.resolve("models").resolve("whisper"));
        for (String file : new String[]{"config.json", "model.safetensors", "tokenizer.json", "vocab.json"}) {
            Files.write(installed.resolve(file), new byte[]{1});
        }

        assertThat(WhisperModelLocation.resolveInstalledModel(workspace.resolve("models")))
                .isEqualTo(installed.toAbsolutePath().normalize());
    }

    @Test
    void ignoresAnIncompleteManualInstallation() throws Exception {
        Path installed = Files.createDirectories(workspace.resolve("models").resolve("whisper"));
        Files.write(installed.resolve("config.json"), new byte[]{1});
        Files.write(installed.resolve("model.safetensors"), new byte[]{1});

        assertThat(WhisperModelLocation.resolveInstalledModel(workspace.resolve("models"))).isNull();
        assertThat(WhisperModelLocation.resolveInstalledModel(workspace.resolve("nothing"))).isNull();
    }
}
