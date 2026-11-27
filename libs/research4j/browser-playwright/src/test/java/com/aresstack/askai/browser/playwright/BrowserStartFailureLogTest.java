package com.aresstack.askai.browser.playwright;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BrowserStartFailureLogTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void theFullMultiLineExceptionWithItsCauseIsAppendedNotJustTheFirstLine() throws Exception {
        File dir = new File(temp.getRoot(), "logs");
        RuntimeException failure = new RuntimeException(
                "Timeout 30000ms exceeded.\n=========================== logs ===========================\n"
                        + "<launching> chrome.exe --remote-debugging-pipe",
                new IllegalStateException("devtools pipe closed"));

        String path = BrowserStartFailureLog.append(dir, "chrome", false, "BROWSER_LAUNCH", 30412L, failure);

        assertEquals(new File(dir, BrowserStartFailureLog.FILE_NAME).getAbsolutePath(), path);
        String log = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        assertTrue(log, log.contains("channel=chrome headless=false stage=BROWSER_LAUNCH elapsedMs=30412"));
        assertTrue("the lines firstLine() drops must be kept", log.contains("<launching> chrome.exe"));
        assertTrue("the cause chain must be kept", log.contains("devtools pipe closed"));
        assertTrue(log.contains("freePhysicalMb="));
    }

    @Test
    public void failuresAccumulateInOneFile() throws Exception {
        File dir = temp.getRoot();
        BrowserStartFailureLog.append(dir, "chrome", true, "PLAYWRIGHT_CREATE", 1L, new RuntimeException("first"));
        BrowserStartFailureLog.append(dir, "msedge", true, "PAGE_CREATE", 2L, new RuntimeException("second"));

        String log = new String(Files.readAllBytes(new File(dir, BrowserStartFailureLog.FILE_NAME).toPath()),
                StandardCharsets.UTF_8);
        assertTrue(log.indexOf("first") < log.indexOf("second"));
    }

    @Test
    public void anUnwritableDirectoryYieldsNullInsteadOfMaskingTheStartFailure() throws Exception {
        File blocker = temp.newFile("not-a-directory");

        assertNull(BrowserStartFailureLog.append(new File(blocker, "logs"), "chrome", true, "BROWSER_LAUNCH", 1L,
                new RuntimeException("boom")));
    }
}
