package com.aresstack.spaceharvester.browser.runtime;

import com.aresstack.spaceharvester.browser.runtime.camoufox.CamoufoxDistribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Camoufox: "installed?" and "install" are separate, a start never downloads, a foreign build is never overwritten. */
class CamoufoxProvisioningTest {

    @TempDir
    Path temp;

    @Test
    void aFolderWithAForeignCamoufoxIsNeverDownloadedOver() throws Exception {
        Path empty = Files.createDirectories(temp.resolve("empty"));
        assertEquals(CamoufoxProvisioning.Found.NONE, CamoufoxProvisioning.inspect(empty));
        Path foreign = Files.createDirectories(temp.resolve("my-camoufox"));
        Path executable = foreign.resolve(CamoufoxDistribution.executableName(CamoufoxDistribution.currentTarget()));
        Files.write(executable, new byte[]{1, 2, 3});
        assertEquals(CamoufoxProvisioning.Found.FOREIGN, CamoufoxProvisioning.inspect(foreign));
        assertThrows(IllegalStateException.class, () -> CamoufoxProvisioning.install(foreign, line -> { }));
        assertTrue(Files.isRegularFile(executable), "the foreign Camoufox stays untouched");
        try (Stream<Path> entries = Files.list(foreign)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void aStartWithoutAnInstalledCamoufoxFailsInsteadOfDownloading() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        BrowserRuntime runtime = new BrowserRuntime(BrowserRuntimeConfig.of(BrowserChannel.CAMOUFOX, "", data,
                Duration.ZERO));
        assertFalse(runtime.camoufoxInstalled());
        BrowserStartException failed = assertThrows(BrowserStartException.class,
                () -> runtime.ephemeral(true, null));
        assertEquals("CAMOUFOX_CHECK", failed.stage());
        assertTrue(failed.getMessage().contains("never downloads"), failed.getMessage());
        assertFalse(Files.exists(data.resolve(BrowserRuntimeConfig.DEFAULT_CAMOUFOX_FOLDER)),
                "nothing was downloaded into the Camoufox folder");
    }
}
