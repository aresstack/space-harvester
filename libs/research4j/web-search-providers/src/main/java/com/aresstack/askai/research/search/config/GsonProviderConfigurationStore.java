package com.aresstack.askai.research.search.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class GsonProviderConfigurationStore
        implements ProviderConfigurationStore {

    private final Gson gson;

    public GsonProviderConfigurationStore() {
        this(new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .disableHtmlEscaping()
                .create());
    }

    public GsonProviderConfigurationStore(Gson gson) {
        if (gson == null) {
            throw new IllegalArgumentException(
                    "gson must not be null");
        }
        this.gson = gson;
    }

    @Override
    public <T> T load(
            Path file,
            Class<T> configurationType) {

        if (file == null) {
            throw new IllegalArgumentException(
                    "file must not be null");
        }
        if (configurationType == null) {
            throw new IllegalArgumentException(
                    "configurationType must not be null");
        }

        try (Reader reader = Files.newBufferedReader(
                file,
                StandardCharsets.UTF_8)) {

            T configuration =
                    gson.fromJson(reader, configurationType);

            if (configuration == null) {
                throw new IllegalStateException(
                        "Configuration file is empty: " + file);
            }

            return configuration;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load provider configuration "
                            + file,
                    exception);
        } catch (JsonIOException exception) {
            throw new IllegalStateException(
                    "Could not read provider configuration "
                            + file,
                    exception);
        } catch (JsonSyntaxException exception) {
            throw new IllegalStateException(
                    "Provider configuration contains invalid JSON "
                            + file,
                    exception);
        }
    }

    @Override
    public void save(
            Path file,
            Object configuration) {

        if (file == null) {
            throw new IllegalArgumentException(
                    "file must not be null");
        }
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "configuration must not be null");
        }

        Path parent = file.toAbsolutePath()
                .normalize()
                .getParent();

        if (parent == null) {
            throw new IllegalArgumentException(
                    "file must have a parent directory");
        }

        try {
            Files.createDirectories(parent);

            Path temporaryFile = Files.createTempFile(
                    parent,
                    file.getFileName().toString(),
                    ".tmp");

            try {
                writeConfiguration(
                        temporaryFile,
                        configuration);
                moveReplacing(temporaryFile, file);
            } finally {
                Files.deleteIfExists(temporaryFile);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not save provider configuration "
                            + file,
                    exception);
        }
    }

    private void writeConfiguration(
            Path temporaryFile,
            Object configuration)
            throws IOException {

        try (Writer writer = Files.newBufferedWriter(
                temporaryFile,
                StandardCharsets.UTF_8)) {

            gson.toJson(configuration, writer);
        }
    }

    private void moveReplacing(
            Path source,
            Path target)
            throws IOException {

        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
