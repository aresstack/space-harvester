package com.aresstack.spaceharvester.app.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyDirectoryTest {

    @TempDir
    Path root;

    @Test
    void createsTheDirectoryForTheOwnerOnly() throws Exception {
        Path dir = root.resolve("conf").resolve("keepass");

        assertThat(OwnerOnlyDirectory.secure(dir)).isTrue();

        assertThat(Files.isDirectory(dir)).isTrue();
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void reportsFailureInsteadOfThrowing() throws Exception {
        Path blocker = Files.writeString(root.resolve("datei"), "x");

        assertThat(OwnerOnlyDirectory.secure(blocker.resolve("keepass"))).isFalse();
    }
}
