package com.aresstack.spaceharvester.browser.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserExecutablesTest {

    @TempDir
    Path temp;

    @Test
    void aChosenExecutableWinsOverAutodetect() throws Exception {
        File exe = Files.createFile(temp.resolve("firefox.exe")).toFile();
        assertEquals(exe.getAbsolutePath(), BrowserExecutables.effective("firefox", exe.getPath()));
    }

    @Test
    void aChosenButMissingExecutableIsNotReplacedByAnotherBrowser() {
        assertNull(BrowserExecutables.effective("firefox", temp.resolve("does-not-exist-firefox.exe").toString()));
    }

    @Test
    void blankMeansAutodetect() {
        assertEquals(BrowserExecutables.detected("firefox").isEmpty() ? null : BrowserExecutables.detected("firefox"),
                BrowserExecutables.effective("firefox", "  "));
    }

    @Test
    void onlyStandardInstallLocationsAreSearched() {
        assertFalse(BrowserExecutables.candidatePaths("firefox").isEmpty());
        assertTrue(BrowserExecutables.candidatePaths("firefox").stream().allMatch(path -> path.contains("irefox")));
        assertTrue(BrowserExecutables.candidatePaths("msedge").stream().allMatch(path -> path.contains("dge")));
    }
}
