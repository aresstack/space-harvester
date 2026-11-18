package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Verify that an indexed BSCW search result reveals its directory after a process restart. */
class BscwSearchNavigationTest {

    private static final String SOURCE = "bscw-test";
    private static final String ROOT = "/sec/bscw.cgi/146377797";
    private static final String FOLDER = "/sec/bscw.cgi/300";
    private static final String FILE = "/sec/bscw.cgi/301";

    @TempDir
    Path home;

    private HttpServer server;
    private AppContext context;

    @AfterEach
    void close() {
        if (context != null) {
            context.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void selectingIndexedFileExpandsItsActualBscwFolderInsteadOfStoppingAtTheSource() throws Exception {
        startServer();
        Path config = writeConfig();
        initialize(config);

        String rootId = id(ROOT);
        List<HarvestSource.PageNode> rootChildren =
                context.runtime().workspace().children(SOURCE, SOURCE, null, null).pages();
        String folderId = rootChildren.get(0).pageId();
        String fileId = context.runtime().workspace().children(SOURCE, SOURCE, folderId, null)
                .pages().get(0).pageId();

        // Cache root and parent metadata while the adapter still knows the BSCW parent links.
        context.runtime().workspace().page(SOURCE, rootId, false);
        context.runtime().workspace().page(SOURCE, folderId, false);
        context.runtime().workspace().page(SOURCE, fileId, false);
        assertThat(context.runtime().workspace().cachedPage(SOURCE, fileId)).isPresent();

        context.shutdown();
        context = null;
        initialize(config);

        // Open a search hit before revealing it: a page refresh must not destroy its cached parent relation.
        context.runtime().workspace().page(SOURCE, fileId, true);
        PageNavigation.Position position = context.runtime().workspace().position(SOURCE, fileId);
        assertThat(position.ancestors()).extracting(HarvestSource.PageNode::pageId)
                .containsExactly(folderId);
        assertThat(position.page().pageId()).isEqualTo(fileId);

        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            explorer[0].revealPage(position);
        });

        boolean selected = false;
        for (int attempt = 0; attempt < 200; attempt++) {
            final boolean[] matches = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                TreePath path = explorer[0].tree().getSelectionPath();
                if (path == null || !(path.getLastPathComponent() instanceof Explorer.Node node)
                        || !(node.item() instanceof Explorer.PageItem page)) {
                    return;
                }
                if (!fileId.equals(page.pageId())) {
                    return;
                }
                Object[] levels = path.getPath();
                matches[0] = levels.length >= 5 && explorer[0].tree().isExpanded(path.getParentPath());
            });
            if (matches[0]) {
                selected = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(selected).as("The nested BSCW folder is expanded and the file is selected").isTrue();
    }

    private void initialize(Path config) throws Exception {
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch ready = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(ready::countDown));
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
    }

    private Path writeConfig() throws IOException {
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
        return config;
    }

    private String id(String path) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sec/bscw.cgi/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if ("PROPFIND".equals(exchange.getRequestMethod())) {
                    boolean depthOne = "1".equals(exchange.getRequestHeaders().getFirst("Depth"));
                    StringBuilder xml = new StringBuilder(
                            "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
                    if (ROOT.equals(path)) {
                        xml.append(entry(ROOT, "BSCW", true));
                        if (depthOne) {
                            xml.append(entry(FOLDER, "Vorhaben", true));
                        }
                    } else if (FOLDER.equals(path)) {
                        xml.append(entry(FOLDER, "Vorhaben", true));
                        if (depthOne) {
                            xml.append(entry(FILE, "Projektbericht.txt", false));
                        }
                    } else if (FILE.equals(path)) {
                        xml.append(entry(FILE, "Projektbericht.txt", false));
                    }
                    answer(exchange, 207, xml.append("</d:multistatus>").toString()
                            .getBytes(StandardCharsets.UTF_8), "application/xml");
                } else if ("GET".equals(exchange.getRequestMethod()) && FILE.equals(path)) {
                    answer(exchange, 200, "BSCW-Bericht".getBytes(StandardCharsets.UTF_8), "text/plain");
                } else {
                    answer(exchange, 404, new byte[0], "text/plain");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static String entry(String path, String title, boolean folder) {
        return "<d:response><d:href>" + path + "</d:href><d:propstat>"
                + "<d:status>HTTP/1.1 200 OK</d:status><d:prop><d:displayname>" + title
                + "</d:displayname><d:resourcetype>" + (folder ? "<d:collection/>" : "")
                + "</d:resourcetype><d:getetag>v1</d:getetag>"
                + "<d:getlastmodified>Tue, 17 Nov 2026 18:00:00 GMT</d:getlastmodified>"
                + "<d:getcontentlength>128</d:getcontentlength>"
                + "<d:getcontenttype>" + (folder ? "inode/directory" : "text/plain")
                + "</d:getcontenttype></d:prop></d:propstat></d:response>";
    }

    private static void answer(HttpExchange exchange, int status, byte[] content, String mediaType)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
        if (content.length > 0) {
            exchange.getResponseBody().write(content);
        }
    }
}
