package com.aresstack.spaceharvester.browser.runtime;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserStartFailureLogTest {

    @TempDir
    Path temp;

    @Test
    void theFullMultiLineExceptionWithItsCauseIsAppendedNotJustTheFirstLine() throws Exception {
        File dir = temp.resolve("logs").toFile();
        RuntimeException failure = new RuntimeException(
                "Timeout 30000ms exceeded.\n=========================== logs ===========================\n"
                        + "<launching> chrome.exe --remote-debugging-pipe",
                new IllegalStateException("devtools pipe closed"));

        String path = BrowserStartFailureLog.append(dir, "chrome", false, "BROWSER_LAUNCH", 30412L, failure);

        assertEquals(new File(dir, BrowserStartFailureLog.FILE_NAME).getAbsolutePath(), path);
        String log = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("channel=chrome headless=false stage=BROWSER_LAUNCH elapsedMs=30412"), log);
        assertTrue(log.contains("<launching> chrome.exe"), "the lines firstLine() drops must be kept");
        assertTrue(log.contains("devtools pipe closed"), "the cause chain must be kept");
        assertTrue(log.contains("freePhysicalMb="));
    }

    @Test
    void failuresAccumulateInOneFile() throws Exception {
        File dir = temp.toFile();
        BrowserStartFailureLog.append(dir, "chrome", true, "PLAYWRIGHT_CREATE", 1L, new RuntimeException("first"));
        BrowserStartFailureLog.append(dir, "msedge", true, "PAGE_CREATE", 2L, new RuntimeException("second"));

        String log = new String(Files.readAllBytes(new File(dir, BrowserStartFailureLog.FILE_NAME).toPath()),
                StandardCharsets.UTF_8);
        assertTrue(log.indexOf("first") < log.indexOf("second"));
    }

    @Test
    void anUnwritableDirectoryYieldsNullInsteadOfMaskingTheStartFailure() throws Exception {
        File blocker = Files.createFile(temp.resolve("not-a-directory")).toFile();

        assertNull(BrowserStartFailureLog.append(new File(blocker, "logs"), "chrome", true, "BROWSER_LAUNCH", 1L,
                new RuntimeException("boom")));
    }
}
