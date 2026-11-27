package com.aresstack.askai.research.search.security;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

public final class FileSecretKeyProvider
        implements SecretKeyProvider {

    private static final int AES_KEY_SIZE_BITS = 128;

    private final Path keyFile;

    public FileSecretKeyProvider(Path keyFile) {
        if (keyFile == null) {
            throw new IllegalArgumentException(
                    "keyFile must not be null");
        }
        this.keyFile = keyFile.toAbsolutePath().normalize();
    }

    @Override
    public synchronized SecretKey getOrCreateKey() {
        try {
            if (Files.exists(keyFile)) {
                return readKey();
            }
            return createKey();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not access provider secret key file "
                            + keyFile,
                    exception);
        }
    }

    private SecretKey readKey() throws IOException {
        String encoded = new String(
                Files.readAllBytes(keyFile),
                StandardCharsets.US_ASCII).trim();

        byte[] keyBytes = Base64.getDecoder().decode(encoded);
        try {
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            SecretArrays.clear(keyBytes);
        }
    }

    private SecretKey createKey() throws IOException {
        SecretKey secretKey = generateKey();
        byte[] encoded = Base64.getEncoder()
                .encode(secretKey.getEncoded());

        try {
            writeKeyAtomically(encoded);
            restrictPermissions();
            return secretKey;
        } finally {
            SecretArrays.clear(encoded);
        }
    }

    private SecretKey generateKey() {
        try {
            KeyGenerator generator =
                    KeyGenerator.getInstance("AES");
            generator.init(AES_KEY_SIZE_BITS);
            return generator.generateKey();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Could not generate AES key",
                    exception);
        }
    }

    private void writeKeyAtomically(byte[] encoded)
            throws IOException {

        Path parent = keyFile.getParent();
        if (parent == null) {
            throw new IOException(
                    "Secret key file has no parent directory");
        }

        Files.createDirectories(parent);

        Path temporaryFile = Files.createTempFile(
                parent,
                keyFile.getFileName().toString(),
                ".tmp");

        try {
            Files.write(temporaryFile, encoded);
            moveReplacing(temporaryFile, keyFile);
        } finally {
            Files.deleteIfExists(temporaryFile);
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

    private void restrictPermissions() {
        try {
            Set<PosixFilePermission> permissions =
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyFile, permissions);
            return;
        } catch (UnsupportedOperationException ignored) {
            // Continue with the portable owner-only fallback.
        } catch (IOException ignored) {
            // Continue with the portable owner-only fallback.
        }

        keyFile.toFile().setReadable(false, false);
        keyFile.toFile().setWritable(false, false);
        keyFile.toFile().setExecutable(false, false);
        keyFile.toFile().setReadable(true, true);
        keyFile.toFile().setWritable(true, true);
    }
}
