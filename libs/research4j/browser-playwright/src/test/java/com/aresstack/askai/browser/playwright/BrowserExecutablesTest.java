package com.aresstack.askai.browser.playwright;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BrowserExecutablesTest {

    @After
    public void autodetectAgain() {
        BrowserExecutables.use("firefox", "");
    }

    @Test
    public void aChosenExecutableWinsOverAutodetect() throws Exception {
        File exe = Files.createTempFile("firefox", ".exe").toFile();
        exe.deleteOnExit();
        BrowserExecutables.use("firefox", exe.getPath());
        assertEquals(exe.getAbsolutePath(), BrowserExecutables.effective("firefox"));
    }

    @Test
    public void aChosenButMissingExecutableIsNotReplacedByAnotherBrowser() {
        BrowserExecutables.use("firefox", new File("does-not-exist-firefox.exe").getAbsolutePath());
        assertNull(BrowserExecutables.effective("firefox"));
    }

    @Test
    public void blankMeansAutodetect() {
        BrowserExecutables.use("firefox", "  ");
        assertEquals("", BrowserExecutables.configured("firefox"));
    }
}
