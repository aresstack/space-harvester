package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.EngineHostRefusedException;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The PDF reader against a local HTTP server: it reads a PDF, follows redirects hop by hop under the URL policy,
 * keeps to the download limit and the time budget of the whole read, stops when the browser is torn down and
 * refuses what is no readable PDF with an honest reason.
 */
public class PdfPageReaderTest {

    private static final BrowserLimits LIMITS = new BrowserLimits(64 * 1024, 40_000, 100, 5_000);
    /** The tab URL of a download; the reader never looks at it. */
    private static final String NO_TAB = "about:blank";
    /** A trickling server sends one byte per pause: each socket read returns long before its timeout. */
    private static final long TRICKLE_PAUSE_MILLIS = 50;
    private static final int TRICKLE_BYTES = 400;
    /** What a stalling server sends before it falls silent: the PDF signature. */
    private static final int STALL_HEAD_BYTES = "%PDF-".length();
    /** How long a cut or timed-out read may take at most here; far below the trickle's 20 seconds. */
    private static final long PROMPT_END_MILLIS = 3_000;

    private HttpServer server;
    private java.util.concurrent.ExecutorService handlers;
    private String base;
    private byte[] reportPdf;
    private final List<String> requested = Collections.synchronizedList(new ArrayList<String>());
    private final java.util.concurrent.CountDownLatch trickleStarted = new java.util.concurrent.CountDownLatch(1);

    @Before
    public void startServer() throws Exception {
        PdfPageReader.useConnector(null);
        reportPdf = TestPdfs.withText("Site Safety Report", "Wearables warn workers before a fall.");
        final byte[] untitled = TestPdfs.withText(null, "Gas sensors measure carbon monoxide.");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(path);
            switch (path) {
                case "/report.pdf":
                    send(exchange, 200, "application/pdf", reportPdf);
                    break;
                case "/Leitfaden%20Baustelle.pdf":
                case "/Leitfaden Baustelle.pdf":
                    send(exchange, 200, "application/octet-stream", untitled);
                    break;
                case "/moved":
                    redirect(exchange, 302, "/report.pdf");
                    break;
                case "/moved-twice":
                    redirect(exchange, 301, base + "/moved");
                    break;
                case "/to-ftp":
                    redirect(exchange, 302, "ftp://127.0.0.1/report.pdf");
                    break;
                case "/to-engine":
                    redirect(exchange, 302, "https://www.google.com/search?q=wearables");
                    break;
                case "/loop":
                    redirect(exchange, 302, "/loop");
                    break;
                case "/page.html":
                    send(exchange, 200, "text/html; charset=utf-8",
                            "<!doctype html><title>Not a PDF</title>".getBytes(StandardCharsets.UTF_8));
                    break;
                case "/large.pdf":
                    byte[] large = new byte[(int) LIMITS.getMaxDownloadBytes() + 1];
                    System.arraycopy(reportPdf, 0, large, 0, reportPdf.length);
                    send(exchange, 200, "application/pdf", large);
                    break;
                case "/locked-out.pdf":
                    send(exchange, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                    break;
                case "/blank.pdf":
                    send(exchange, 200, "application/pdf", TestPdfs.blank());
                    break;
                case "/trickle.pdf":
                    trickle(exchange);
                    break;
                case "/stall.pdf":
                    stall(exchange);
                    break;
                default:
                    send(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
            }
        });
        handlers = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        handlers.shutdownNow();
        server.stop(0);
        PdfPageReader.useConnector(null);
    }

    /** A PDF body sent one byte at a time with a pause after each, for 20 seconds, chunked. */
    private void trickle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/pdf");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            for (int i = 0; i < TRICKLE_BYTES; i++) {
                out.write(reportPdf[i % reportPdf.length]);
                out.flush();
                trickleStarted.countDown();
                Thread.sleep(TRICKLE_PAUSE_MILLIS);
            }
            out.close();
        } catch (IOException | InterruptedException readerGone) {
            exchange.close();
        }
    }

    /** The start of a PDF, then silence until the test ends. */
    private void stall(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/pdf");
        exchange.sendResponseHeaders(200, reportPdf.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(reportPdf, 0, STALL_HEAD_BYTES);
            out.flush();
            Thread.sleep(TRICKLE_PAUSE_MILLIS * TRICKLE_BYTES);
            out.close();
        } catch (IOException | InterruptedException readerGone) {
            exchange.close();
        }
    }

    /** Runs the read on a thread of its own, the way the browser owner thread runs it. */
    private java.util.concurrent.Future<PlaywrightPageState> readInBackground(final PdfPageReader reader,
                                                                              final String path) {
        java.util.concurrent.ExecutorService owner = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return owner.submit(() -> reader.read(PlaywrightPageState.downloadedDocument(base + path, NO_TAB)));
        } finally {
            owner.shutdown();
        }
    }

    private static BrowserException failureOf(java.util.concurrent.Future<PlaywrightPageState> read)
            throws Exception {
        try {
            read.get(PROMPT_END_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException ended) {
            if (ended.getCause() instanceof BrowserException) {
                return (BrowserException) ended.getCause();
            }
            throw ended;
        } catch (java.util.concurrent.TimeoutException stillRunning) {
            read.cancel(true);
            fail("the read did not end within " + PROMPT_END_MILLIS + " ms");
        }
        fail("a trickling download must not succeed");
        return null;
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private PdfPageReader reader(UrlSafetyPolicy policy) {
        return new PdfPageReader(LIMITS, policy);
    }

    private PdfPageReader reader() {
        return reader(UrlSafetyPolicy.allowingPrivateNetworks());
    }

    @Test
    public void readsTheDocumentTheBrowserShowsInItsViewer() throws Exception {
        PlaywrightPageState read = reader().read(
                PlaywrightPageState.viewedDocument(base + "/report.pdf", "application/pdf"));

        assertEquals(base + "/report.pdf", read.url);
        assertEquals("Site Safety Report", read.title);
        assertEquals("Wearables warn workers before a fall.", read.text);
        assertTrue(read.isDocument());
        assertTrue("the viewer is in the tab history", read.documentInTab);
        assertTrue(read.anchors.isEmpty());
    }

    @Test
    public void aDocumentWithoutMetadataTitleIsNamedAfterItsFile() throws Exception {
        PlaywrightPageState read = reader().read(
                PlaywrightPageState.downloadedDocument(base + "/Leitfaden%20Baustelle.pdf", NO_TAB));

        assertEquals("Leitfaden Baustelle.pdf", read.title);
        assertEquals("Gas sensors measure carbon monoxide.", read.text);
        assertEquals("the signature decides, not the declared octet-stream", PdfPageReader.PDF_CONTENT_TYPE,
                read.documentType);
        assertTrue("a download never replaced the tab's page", !read.documentInTab);
    }

    @Test
    public void followsRedirectsOneHopAtATime() throws Exception {
        PlaywrightPageState read = reader().read(PlaywrightPageState.downloadedDocument(base + "/moved-twice", NO_TAB));

        assertEquals(base + "/report.pdf", read.url);
        assertEquals("Wearables warn workers before a fall.", read.text);
        assertEquals(java.util.Arrays.asList("/moved-twice", "/moved", "/report.pdf"), requested);
    }

    @Test
    public void aRedirectToAForbiddenSchemeIsRefusedBeforeItIsFetched() {
        try {
            reader().read(PlaywrightPageState.downloadedDocument(base + "/to-ftp", NO_TAB));
            fail("an ftp redirect must be refused");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Blocked URL scheme: ftp"));
        }
    }

    @Test
    public void aWorkerNeverFollowsARedirectToASearchEngine() {
        UrlSafetyPolicy worker = UrlSafetyPolicy.allowingPrivateNetworks()
                .refusingSearchEngines(Collections.<String>emptySet());
        try {
            reader(worker).read(PlaywrightPageState.downloadedDocument(base + "/to-engine", NO_TAB));
            fail("a worker must not reach a search engine through a redirect");
        } catch (EngineHostRefusedException expected) {
            assertEquals(Collections.singletonList("/to-engine"), requested);
        } catch (BrowserException other) {
            fail("expected the engine refusal, got: " + other.getMessage());
        }
    }

    @Test
    public void endlessRedirectsStopAtTheRedirectLimit() {
        try {
            reader().read(PlaywrightPageState.downloadedDocument(base + "/loop", NO_TAB));
            fail("a redirect loop must end");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("redirects"));
        }
    }

    @Test
    public void aFileAboveTheDownloadLimitIsRefused() {
        try {
            reader().read(PlaywrightPageState.viewedDocument(base + "/large.pdf", "application/pdf"));
            fail("the download limit must hold");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("download limit"));
        }
    }

    @Test
    public void aDownloadThatIsNoPdfSaysWhatItIs() {
        try {
            reader().read(PlaywrightPageState.downloadedDocument(base + "/page.html", NO_TAB));
            fail("HTML is no PDF");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("is no PDF"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("text/html"));
        }
    }

    @Test
    public void aRefusingServerIsReportedWithItsStatus() {
        try {
            reader().read(PlaywrightPageState.viewedDocument(base + "/locked-out.pdf", "application/pdf"));
            fail("HTTP 403 must fail");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 403"));
        }
    }

    @Test
    public void aScanWithoutTextIsReadAsEmptyNeverInvented() throws Exception {
        PlaywrightPageState read = reader().read(
                PlaywrightPageState.viewedDocument(base + "/blank.pdf", "application/pdf"));

        assertEquals("", read.text);
        assertEquals("blank.pdf", read.title);
    }

    @Test
    public void theEmergencyTeardownCutsADownloadThatKeepsTrickling() throws Exception {
        PdfPageReader reader = reader();
        java.util.concurrent.Future<PlaywrightPageState> read = readInBackground(reader, "/trickle.pdf");
        assertTrue("the download started", trickleStarted.await(PROMPT_END_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS));

        reader.terminate();

        BrowserException cut = failureOf(read);
        assertTrue(cut.getMessage(), cut.getMessage().contains("shut down"));
    }

    @Test
    public void aServerThatKeepsTricklingIsCutAtTheTimeout() throws Exception {
        int timeoutMillis = 600;
        PdfPageReader reader = new PdfPageReader(new BrowserLimits(64 * 1024, 40_000, 100, timeoutMillis),
                UrlSafetyPolicy.allowingPrivateNetworks());

        BrowserException timedOut = failureOf(readInBackground(reader, "/trickle.pdf"));

        assertTrue(timedOut.getMessage(), timedOut.getMessage().contains("within the browser's timeout ("
                + timeoutMillis + " ms)"));
    }

    @Test
    public void aServerThatFallsSilentIsCutAtTheTimeout() throws Exception {
        int timeoutMillis = 600;
        PdfPageReader reader = new PdfPageReader(new BrowserLimits(64 * 1024, 40_000, 100, timeoutMillis),
                UrlSafetyPolicy.allowingPrivateNetworks());

        BrowserException timedOut = failureOf(readInBackground(reader, "/stall.pdf"));

        assertTrue(timedOut.getMessage(), timedOut.getMessage().contains("within the browser's timeout ("
                + timeoutMillis + " ms)"));
    }

    @Test
    public void theHostConnectorOpensEveryHop() throws Exception {
        final List<String> opened = new ArrayList<String>();
        PdfPageReader.useConnector(url -> {
            opened.add(url);
            return (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        });

        reader().read(PlaywrightPageState.downloadedDocument(base + "/moved", NO_TAB));

        assertEquals(java.util.Arrays.asList(base + "/moved", base + "/report.pdf"), opened);
    }
}
