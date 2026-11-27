package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.PlaywrightException;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** How the driver tells a PDF navigation from a page: the response's Content-Type and Playwright's download error. */
public class PlaywrightPdfDetectionTest {

    @Test
    public void aPdfResponseIsRecognisedByItsContentType() {
        assertTrue(Playwright4jDriver.isPdf("application/pdf"));
        assertTrue(Playwright4jDriver.isPdf("Application/PDF; charset=binary"));
        assertTrue(Playwright4jDriver.isPdf(" application/x-pdf "));
        assertFalse(Playwright4jDriver.isPdf("text/html; charset=utf-8"));
        assertFalse(Playwright4jDriver.isPdf("application/pdfx"));
        assertFalse(Playwright4jDriver.isPdf(""));
        assertFalse(Playwright4jDriver.isPdf(null));
    }

    @Test
    public void onlyPlaywrightsDownloadErrorCountsAsADownload() {
        assertTrue(Playwright4jDriver.startedDownload(new PlaywrightException("Download is starting")));
        assertTrue(Playwright4jDriver.startedDownload(
                new PlaywrightException("Error {\n  message='Download is starting\n  name='Error\n}")));
        assertFalse("Chromium's abort is shared by other aborted navigations",
                Playwright4jDriver.startedDownload(new PlaywrightException("net::ERR_ABORTED at https://x.test/a.pdf")));
        assertFalse(Playwright4jDriver.startedDownload(new PlaywrightException("Timeout 20000ms exceeded.")));
    }
}
