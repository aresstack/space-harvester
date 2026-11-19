package com.aresstack.spaceharvester.app.transcription;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Extract the model shipped inside the executable JAR into a verified, versioned local cache.
 * Never retrieve model files over the network at runtime.
 *
 * <p>Another Hugging Face Whisper checkpoint (for example {@code openai/whisper-base}) can be installed
 * manually: copy {@code config.json}, {@code model.safetensors}, {@code tokenizer.json} and
 * {@code vocab.json} (optionally {@code generation_config.json}) into
 * {@code ~/.space-harvester/models/whisper}. The system property {@value #PROPERTY} names any other
 * directory and takes precedence; without either, the embedded Whisper Tiny model is used.
 */
public final class WhisperModelLocation {

    private static final String REVISION = "169d4a4341b33bc18d8881c4b69c2e104e1cc0af";
    private static final String MODEL_RESOURCE = "/models/whisper-tiny/";
    static final String PROPERTY = "spaceharvester.whisper.model";
    static final String INSTALLED_FOLDER = "whisper";
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(WhisperModelLocation.class.getName());
    private static final Map<String, String> MODEL_HASHES = modelHashes();

    private WhisperModelLocation() {
    }

    public static Path resolve() throws IOException {
        String overridden = System.getProperty(PROPERTY);
        if (overridden != null && !overridden.trim().isEmpty()) {
            return Paths.get(overridden.trim()).toAbsolutePath();
        }
        Path models = Paths.get(System.getProperty("user.home", "."), ".space-harvester", "models");
        Path installed = resolveInstalledModel(models);
        if (installed != null) {
            LOG.info("Whisper: manuell installiertes Modell " + installed);
            return installed;
        }
        Path destination = models.resolve("whisper-tiny-" + REVISION);
        if (isDevelopmentClasspath() && !isEmbeddedModelAvailable()) {
            Path prepared = resolveDevelopmentModel(Paths.get("").toAbsolutePath(), MODEL_HASHES);
            if (prepared != null) {
                return prepared;
            }
        }
        return extract(destination, name -> WhisperModelLocation.class.getResourceAsStream(
                MODEL_RESOURCE + name), MODEL_HASHES);
    }

    /** Return the manually installed model under {@code models/whisper}, or {@code null} if it is incomplete. */
    static Path resolveInstalledModel(Path modelsDirectory) {
        Path directory = modelsDirectory.resolve(INSTALLED_FOLDER);
        return WhisperSpeechTranscriber.isModelAvailable(directory)
                ? directory.toAbsolutePath().normalize() : null;
    }

    private static boolean isDevelopmentClasspath() {
        java.net.URL classResource = WhisperModelLocation.class.getResource("WhisperModelLocation.class");
        return classResource != null && "file".equals(classResource.getProtocol());
    }

    private static boolean isEmbeddedModelAvailable() {
        return WhisperModelLocation.class.getResource(MODEL_RESOURCE + "config.json") != null;
    }

    /**
     * Reuse an already verified Gradle-staged model for direct IntelliJ launches.
     * Never download a model at runtime or trust unverified local model files.
     */
    static Path resolveDevelopmentModel(Path workingDirectory, Map<String, String> hashes)
            throws IOException {
        Path[] candidates = {
                workingDirectory.resolve("build/embedded-whisper-model"),
                workingDirectory.resolve("space-harvester-app/build/embedded-whisper-model")
        };
        for (Path directory : candidates) {
            boolean valid = true;
            for (Map.Entry<String, String> entry : hashes.entrySet()) {
                if (!matches(directory.resolve(entry.getKey()), entry.getValue())) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                return directory.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    static synchronized Path extract(Path destination, Function<String, InputStream> resources,
                                     Map<String, String> expectedHashes) throws IOException {
        Files.createDirectories(destination);
        for (Map.Entry<String, String> item : expectedHashes.entrySet()) {
            Path target = destination.resolve(item.getKey());
            if (matches(target, item.getValue())) {
                continue;
            }
            Path temporary = Files.createTempFile(destination, "whisper-", ".part");
            try {
                try (InputStream input = resources.apply(item.getKey())) {
                    if (input == null) {
                        throw new IOException("Whisper model is not embedded: " + item.getKey()
                                + ". Use the complete Space Harvester desktop JAR.");
                    }
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("Whisper model extraction interrupted");
                }
                if (!matches(temporary, item.getValue())) {
                    throw new IOException("Bundled Whisper model failed SHA-256: " + item.getKey());
                }
                moveVerified(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return destination;
    }

    private static void moveVerified(Path from, Path destination) throws IOException {
        try {
            Files.move(from, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean matches(Path file, String expected) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest()) {
                value.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                value.append(Character.forDigit(b & 0x0f, 16));
            }
            return expected.equals(value.toString());
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 is unavailable", failure);
        }
    }

    private static Map<String, String> modelHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("config.json", "ffdccec4f3211f4c63310f2b7098f309fe70f3952cedc5e4d11e43f5b2379b98");
        hashes.put("model.safetensors", "7ebd0e69e78190ffe1438491fa05cc1f5c1aa3a4c4db3bc1723adbb551ea2395");
        hashes.put("tokenizer.json", "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566");
        hashes.put("vocab.json", "8f680bba319e01a653d2e8a5dbc17a9157179e0576e6ce74ce0c06356c6e24f9");
        return Collections.unmodifiableMap(hashes);
    }
}
