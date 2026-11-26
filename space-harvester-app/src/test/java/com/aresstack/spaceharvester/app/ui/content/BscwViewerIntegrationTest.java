package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercise the real WebDAV, page snapshot and Swing reader pipeline. */
final class BscwViewerIntegrationTest {

    private static final String ROOT = "/sec/bscw.cgi/146377797";
    private static final String PNG = "/sec/bscw.cgi/900";
    private static final String CSV = "/sec/bscw.cgi/901";
    private static final String LINK = "/sec/bscw.cgi/902";
    private static final byte[] CSV_BYTES = "name,value\nNord,12\nSued,15\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path home;

    private HttpServer server;
    private HttpServer externalServer;
    private ExecutorService serverThreads;
    private java.util.concurrent.atomic.AtomicInteger externalRequests = new java.util.concurrent.atomic.AtomicInteger();
    private AppContext context;
    private final CountDownLatch imageStarted = new CountDownLatch(1);
    private final CountDownLatch releaseImage = new CountDownLatch(1);
    private byte[] pngBytes;

    @AfterEach
    void shutdown() {
        releaseImage.countDown();
        if (context != null) {
            context.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (externalServer != null) {
            externalServer.stop(0);
        }
        if (serverThreads != null) {
            serverThreads.shutdownNow();
        }
    }

    @Test
    void delayedImageDoesNotBlockEdtOrPreventOpeningCsvAndLaterDisplaysPng() throws Exception {
        start();
        List<HarvestSource.PageNode> children =
                context.runtime().workspace().children("bscw-test", "bscw-test", null, null).pages();
        assertThat(children).extracting(HarvestSource.PageNode::title)
                .containsExactly("Diagramm.png", "Informationen hierzu im Confluence", "Tabelle.csv");
        assertThat(children).extracting(HarvestSource.PageNode::kind)
                .containsOnly(HarvestSource.PageNode.Kind.FILE);
        String imageId = children.get(0).pageId();
        String csvId = children.stream().filter(node -> "Tabelle.csv".equals(node.title()))
                .findFirst().orElseThrow().pageId();

        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        onEdt(() -> {
            panel.tabs().select("Leseansicht");
            panel.load("bscw-test", imageId, false);
            return null;
        });
        assertThat(imageStarted.await(15, TimeUnit.SECONDS)).as("PNG HTTP GET began").isTrue();

        long begin = System.nanoTime();
        onEdt(() -> {
            panel.tabs().select("Podcast-Ansicht");
            panel.tabs().select("Leseansicht");
            panel.load("bscw-test", csvId, false);
            return null;
        });
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin))
                .as("EDT remains responsive during a blocked PNG download").isLessThan(4000);

        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().isShowingSpreadsheet()));

        releaseImage.countDown();
        onEdt(() -> {
            panel.load("bscw-test", imageId, false);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().showsPicture()));
        assertThat(onEdt(() -> panel.originalViewer().attachment().typeLabel())).isEqualTo("PNG");
    }

    @Test
    void redirectedLinkShowsARealLoadingIndicatorAndDoesNotLeaveThePreviousCsvVisible() throws Exception {
        start();
        List<HarvestSource.PageNode> nodes =
                context.runtime().workspace().children("bscw-test", "bscw-test", null, null).pages();
        String csvId = nodes.stream().filter(node -> "Tabelle.csv".equals(node.title()))
                .findFirst().orElseThrow().pageId();
        String linkId = nodes.stream().filter(node -> "Informationen hierzu im Confluence".equals(node.title()))
                .findFirst().orElseThrow().pageId();
        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));

        onEdt(() -> {
            panel.tabs().select("Leseansicht");
            panel.load("bscw-test", csvId, false);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().isShowingSpreadsheet()));
        onEdt(() -> {
            panel.load("bscw-test", linkId, false);
            assertThat(panel.originalViewer()).as("Old CSV reader is gone").isNull();
            assertThat(panel.originalLinkShown()).isFalse();
            assertThat(panel.originalLoadingShown()).as("Loading spinner is visible").isTrue();
            return null;
        });
        await(() -> onEdt(panel::originalLinkShown));
        assertThat(onEdt(panel::originalLoadingShown)).isFalse();
        assertThat(onEdt(panel::originalViewer)).isNull();
        assertThat(externalRequests).hasValue(0);

        com.aresstack.spaceharvester.application.query.DeliveredPage stored =
                context.runtime().workspace().cachedPage("bscw-test", linkId).orElseThrow();
        assertThat(stored.snapshot().metadata().attributes().get(
                com.aresstack.spaceharvester.application.sources.PageMetadata.RESOURCE_KIND))
                .isEqualTo(com.aresstack.spaceharvester.application.sources.PageMetadata.LINK_KIND);
        String parent = stored.snapshot().metadata().parentPageId();
        assertThat(parent).isNotNull();
        assertThat(context.runtime().workspace().cachedChildren("bscw-test", "bscw-test", parent, null, 100).pages())
                .filteredOn(node -> linkId.equals(node.pageId()))
                .singleElement().satisfies(node -> {
                    assertThat(node.kind()).isEqualTo(HarvestSource.PageNode.Kind.FILE);
                    assertThat(node.hasChildren()).isFalse();
                });
    }

    private void start() throws Exception {
        pngBytes = png();
        externalServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        externalServer.createContext("/external", exchange -> {
            externalRequests.incrementAndGet();
            answer(exchange, 200, "external".getBytes(StandardCharsets.UTF_8), "text/plain");
            exchange.close();
        });
        externalServer.start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/sec/bscw.cgi/", this::handle);
        server.start();

        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: bscw-test
                      type: bscw
                      title: BSCW
                      base-url: http://127.0.0.1:%d%s
                      auth:
                        type: basic
                        username: reader
                        password: secret
                """.formatted(home.resolve("data").toString().replace('\\', '/'),
                server.getAddress().getPort(), ROOT));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch ready = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(ready::countDown));
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime()).isNotNull();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("PROPFIND".equals(exchange.getRequestMethod())) {
                String response = directory(path, "1".equals(exchange.getRequestHeaders().getFirst("Depth")));
                answer(exchange, 207, response.getBytes(StandardCharsets.UTF_8), "application/xml");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && PNG.equals(path)) {
                imageStarted.countDown();
                try {
                    if (!releaseImage.await(15, TimeUnit.SECONDS)) {
                        answer(exchange, 503, new byte[0], "text/plain");
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    answer(exchange, 503, new byte[0], "text/plain");
                    return;
                }
                answer(exchange, 200, pngBytes, "image/png");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && CSV.equals(path)) {
                answer(exchange, 200, CSV_BYTES, "text/csv");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && LINK.equals(path)) {
                String location = "http://127.0.0.1:" + externalServer.getAddress().getPort() + "/external";
                exchange.getResponseHeaders().set("Location", location);
                answer(exchange, 303, new byte[0], "text/html");
                return;
            }
            answer(exchange, 404, new byte[0], "text/plain");
        } finally {
            exchange.close();
        }
    }

    private static String directory(String path, boolean children) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
        if (ROOT.equals(path)) {
            xml.append(entry(ROOT, "BSCW", true));
            if (children) {
                xml.append(entry(PNG, "Diagramm.png", false));
                xml.append(entry(CSV, "Tabelle.csv", false));
                xml.append(entry(LINK, "Informationen hierzu im Confluence", false));
            }
        } else if (PNG.equals(path)) {
            xml.append(entry(PNG, "Diagramm.png", false));
        } else if (CSV.equals(path)) {
            xml.append(entry(CSV, "Tabelle.csv", false));
        } else if (LINK.equals(path)) {
            xml.append(entry(LINK, "Informationen hierzu im Confluence", false));
        }
        return xml.append("</d:multistatus>").toString();
    }

    private static String entry(String path, String name, boolean folder) {
        return "<d:response><d:href>" + path + "</d:href><d:propstat>"
                + "<d:status>HTTP/1.1 200 OK</d:status><d:prop>"
                + "<d:displayname>" + name + "</d:displayname><d:resourcetype>"
                + (folder ? "<d:collection/>" : "") + "</d:resourcetype>"
                + "<d:getetag>v1</d:getetag>"
                + "<d:getlastmodified>Tue, 17 Nov 2026 18:00:00 GMT</d:getlastmodified>"
                + "<d:getcontenttype>application/octet-stream</d:getcontenttype>"
                + "</d:prop></d:propstat></d:response>";
    }

    private static void answer(HttpExchange exchange, int status, byte[] content, String mediaType)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
        if (content.length > 0) {
            exchange.getResponseBody().write(content);
        }
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 4, 0x3388BB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return output.toByteArray();
    }

    private static void await(Check check) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            if (check.holds()) {
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("Reader did not reach the expected state");
    }

    private static <T> T onEdt(Work<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.run());
            } catch (Exception error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    private interface Work<T> {
        T run() throws Exception;
    }
}
