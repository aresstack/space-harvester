package com.aresstack.spaceharvester.testsupport;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;

/** Keep the security checks active on Linux without requiring elevated Windows privileges. */
public final class TestSymbolicLinks {

    private TestSymbolicLinks() {
    }

    public static void createOrSkipUnsupported(Path link, Path destination) throws IOException {
        try {
            Files.createSymbolicLink(link, destination);
        } catch (FileSystemException failure) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                throw failure;
            }
            Assumptions.assumeTrue(false,
                    "Windows does not permit creating test symbolic links: " + failure.getClass().getSimpleName());
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "The test filesystem does not support symbolic links");
        }
    }
}
