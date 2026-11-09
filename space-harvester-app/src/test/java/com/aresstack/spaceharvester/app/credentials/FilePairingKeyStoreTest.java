package com.aresstack.spaceharvester.app.credentials;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kopie von EAC {@code FilePairingKeyStoreTest} (a64d004b), auf JUnit 5 umgestellt. Pairing-Schlüssel: Datei im Benutzerverzeichnis, Rechte nur für den Besitzer, Löschen überschreibt. */
public class FilePairingKeyStoreTest {

    @TempDir
    Path root;

    @Test
    public void roundTripThroughFileCreatingParentDirectories() throws Exception {
        Path file = root.resolve("sub/dir/keepassrpc-pairing.key");
        FilePairingKeyStore store = new FilePairingKeyStore(file);
        assertNull(store.load());
        store.save("0123456789abcdef".toCharArray());
        assertTrue(Files.isRegularFile(file));
        assertArrayEquals("0123456789abcdef".toCharArray(), store.load());
        assertArrayEquals("0123456789abcdef".toCharArray(), new FilePairingKeyStore(file).load(),
                "zweite Instanz sieht denselben Schlüssel");
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
    }

    @Test
    public void fileIsReadableOnlyByOwnerWherePosixIsSupported() throws Exception {
        Path file = root.resolve("pairing.key");
        new FilePairingKeyStore(file).save("key".toCharArray());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
        } else {
            assertTrue(Files.isReadable(file));
        }
    }

    @Test
    public void savingReplacesAndClearingRemovesTheFile() throws Exception {
        Path file = root.resolve("pairing.key");
        FilePairingKeyStore store = new FilePairingKeyStore(file);
        store.save("first".toCharArray());
        store.save("second".toCharArray());
        assertArrayEquals("second".toCharArray(), store.load());
        store.clear();
        assertFalse(Files.exists(file));
        assertNull(store.load());
        store.clear();
    }

    @Test
    public void emptyKeyClearsAndWhitespaceIsTrimmed() throws Exception {
        Path file = root.resolve("pairing.key");
        FilePairingKeyStore store = new FilePairingKeyStore(file);
        Files.write(file, "  abc\n".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("abc".toCharArray(), store.load());
        store.save(new char[0]);
        assertFalse(Files.exists(file));
        Files.write(file, "   \n".getBytes(StandardCharsets.UTF_8));
        assertNull(store.load());
    }

    @Test
    public void toStringHidesTheKey() throws Exception {
        Path file = root.resolve("pairing.key");
        FilePairingKeyStore store = new FilePairingKeyStore(file);
        store.save("geheim".toCharArray());
        assertFalse(store.toString().contains("geheim"));
        assertTrue(store.toString().contains("pairing.key"));
    }
}
