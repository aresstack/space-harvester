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

    private static String sha256(byte[] content) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
}
