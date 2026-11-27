package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserPageReadiness;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PDF search hits in the session: a document the driver reports (viewer or download) is read from the network and
 * stays the current page, without page reads or consent/challenge guards that would only wait for page scripts the
 * viewer never runs; "back" leaves it the way the tab history really is. It stays current only while the tab still
 * shows it: the next navigation (even a failed one) and a user who navigates the visible window both leave it.
 */
public class PlaywrightBrowserSessionPdfTest {

    private static final String PDF_TEXT = "Wearables warn workers before a fall.";
    /** What a tab shows before its first navigation. */
    private static final String FRESH_TAB = "about:blank";

    /** Tab with a history; a downloaded document never enters it, a viewed one does. */
    private static final class DocumentDriver implements PlaywrightDriver {
        final Map<String, PlaywrightPageState> byUrl = new LinkedHashMap<String, PlaywrightPageState>();
        final List<PlaywrightPageState> history = new ArrayList<PlaywrightPageState>();
        int currentReads;
        int backCalls;
        int guardCalls;

        public PlaywrightPageState open(String url) throws BrowserException {
            PlaywrightPageState state = byUrl.get(url);
            if (state == null) {
                throw new BrowserException("Navigation failed: 404 " + url);
            }
            if (state.isDocument() && !state.documentInTab) {
                return PlaywrightPageState.downloadedDocument(url, currentUrl());
            }
            history.add(state);
            return state;
        }

        /** The user navigates the visible window; the session is not asked. */
        void userNavigatesTo(String url) {
            history.add(byUrl.get(url));
        }

        PlaywrightPageState top() {
            return history.get(history.size() - 1);
        }

        /** The tab's URL from its own state, the way the real driver answers: no page read. */
        @Override
        public String currentUrl() {
            return history.isEmpty() ? FRESH_TAB : top().url;
        }

        public PlaywrightPageState current() throws BrowserException {
            currentReads++;
            if (history.isEmpty()) {
                throw new BrowserException("no page");
            }
            return history.get(history.size() - 1);
        }

        public PlaywrightPageState back() throws BrowserException {
            backCalls++;
            if (history.size() < 2) {
                throw new BrowserException("No previous page in history.");
            }
            history.remove(history.size() - 1);
            return history.get(history.size() - 1);
        }

        @Override
        public String challengeMarker() {
            guardCalls++;
            return "none";
        }

        @Override
        public String consentCandidate() {
            guardCalls++;
            return "none";
        }

        @Override
        public String tryDismissConsent() {
            guardCalls++;
            return "none";
        }

        public void close() {
        }
    }

    private HttpServer server;
    private String base;
    private DocumentDriver driver;
    private PlaywrightBrowserSession session;

    @Before
    public void setUp() throws Exception {
        PdfPageReader.useConnector(null);
        final byte[] pdf = TestPdfs.withText("Site Safety Report", PDF_TEXT);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            boolean found = exchange.getRequestURI().getPath().endsWith("report.pdf");
            byte[] body = found ? pdf : "missing".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", found ? "application/pdf" : "text/plain");
            exchange.sendResponseHeaders(found ? 200 : 404, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        driver = new DocumentDriver();
        driver.byUrl.put(base + "/start", new PlaywrightPageState(base + "/start", "Start", "results page",
                java.util.Collections.singletonList(new PlaywrightPageState.Anchor("Report", base + "/report.pdf"))));
        driver.byUrl.put(base + "/report.pdf",
                PlaywrightPageState.viewedDocument(base + "/report.pdf", "application/pdf"));
        driver.byUrl.put(base + "/download/report.pdf",
                PlaywrightPageState.downloadedDocument(base + "/download/report.pdf", ""));
        driver.byUrl.put(base + "/download/gone.pdf",
                PlaywrightPageState.downloadedDocument(base + "/download/gone.pdf", ""));
        driver.byUrl.put(base + "/gone.pdf", PlaywrightPageState.viewedDocument(base + "/gone.pdf", "application/pdf"));
        session = new PlaywrightBrowserSession(driver, UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults(), null, null);
    }

    @After
    public void tearDown() {
        server.stop(0);
        PdfPageReader.useConnector(null);
    }

    @Test
    public void aPdfInTheViewerIsReadAndStaysTheCurrentPage() throws Exception {
        BrowserPageSnapshot opened = session.open(base + "/report.pdf");

        assertEquals(base + "/report.pdf", opened.getUrl());
        assertEquals("Site Safety Report", opened.getTitle());
        assertEquals(PDF_TEXT, opened.getText());
        assertTrue(session.links().isEmpty());

        BrowserPageSnapshot again = session.currentPage();
        assertEquals(PDF_TEXT, again.getText());
        assertEquals("the viewer is never read as a page", 0, driver.currentReads);
    }

    @Test
    public void aDocumentProbeAsksNoPageGuards() throws Exception {
        BrowserPageReadiness readiness = session.probe(base + "/download/report.pdf");

        assertEquals(PDF_TEXT.length(), readiness.textLength);
        assertFalse(readiness.challengePresent);
        assertFalse(readiness.consentPresent);
        assertEquals("none", session.dismissConsent());
        assertEquals(PDF_TEXT.length(), session.probeCurrent().textLength);
        assertEquals("no consent or challenge script waits on a document", 0, driver.guardCalls);
    }

    @Test
    public void backFromADownloadIsThePageTheTabStillShows() throws Exception {
        session.open(base + "/start");
        session.open(base + "/download/report.pdf");

        BrowserPageSnapshot back = session.back();

        assertEquals(base + "/start", back.getUrl());
        assertEquals("the download never entered the tab history", 0, driver.backCalls);
        assertEquals("link ids belong to the page again", 1, session.links().size());
    }

    @Test
    public void backFromTheViewerUsesTheTabHistory() throws Exception {
        session.open(base + "/start");
        session.follow("link-1");

        BrowserPageSnapshot back = session.back();

        assertEquals(base + "/start", back.getUrl());
        assertEquals(1, driver.backCalls);
    }

    @Test
    public void aPageAfterADocumentIsReadFromTheTabAgain() throws Exception {
        session.open(base + "/report.pdf");
        session.open(base + "/start");

        assertEquals("results page", session.currentPage().getText());
        assertEquals(1, driver.currentReads);
    }

    @Test
    public void aDownloadStaysTheCurrentPageWhileTheTabKeepsItsPage() throws Exception {
        session.open(base + "/start");
        session.open(base + "/download/report.pdf");

        assertEquals(PDF_TEXT, session.currentPage().getText());
        assertEquals("the kept page is not read instead of the download", 0, driver.currentReads);
    }

    @Test
    public void aDocumentTheUserNavigatedAwayFromIsNoLongerTheCurrentPage() throws Exception {
        session.open(base + "/report.pdf");
        driver.userNavigatesTo(base + "/start");

        BrowserPageSnapshot now = session.currentPage();

        assertEquals(base + "/start", now.getUrl());
        assertEquals("results page", now.getText());
        assertEquals("the page's links are back", 1, session.links().size());
    }

    @Test
    public void aDownloadIsLeftOnceTheUserNavigatesTheTab() throws Exception {
        session.open(base + "/start");
        session.open(base + "/download/report.pdf");
        driver.userNavigatesTo(base + "/report.pdf");

        BrowserPageReadiness now = session.probeCurrent();

        assertEquals(base + "/report.pdf", now.url);
        assertEquals("the viewer the user opened is read, not the old download", PDF_TEXT.length(), now.textLength);
    }

    @Test
    public void consentIsAskedAgainOnceTheUserLeftTheDocument() throws Exception {
        session.open(base + "/report.pdf");
        assertEquals("none", session.dismissConsent());
        assertEquals(0, driver.guardCalls);

        driver.userNavigatesTo(base + "/start");
        session.dismissConsent();

        assertEquals("a page may carry a consent banner again", 1, driver.guardCalls);
    }

    @Test
    public void aFailedDocumentNavigationLeavesNoStaleDocument() throws Exception {
        session.open(base + "/start");
        session.open(base + "/download/report.pdf");
        try {
            session.open(base + "/download/gone.pdf");
            fail("a missing PDF must not become a page");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 404"));
        }

        BrowserPageSnapshot now = session.currentPage();

        assertEquals("the tab's page, never the earlier download", "results page", now.getText());
    }

    @Test
    public void afterTheEmergencyTeardownNoDocumentIsRead() throws Exception {
        session.open(base + "/start");
        session.terminate();

        try {
            session.open(base + "/download/report.pdf");
            fail("a torn-down session must not start a download");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("shut down"));
        }
    }

    @Test
    public void anUnreadableDocumentFailsHonestly() {
        try {
            session.open(base + "/gone.pdf");
            fail("a missing PDF must not become a page");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 404"));
        }
    }
}
