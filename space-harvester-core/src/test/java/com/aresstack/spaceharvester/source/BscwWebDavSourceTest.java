package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.PartitionListingSource;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class BscwWebDavSourceTest {

    private static final byte[] ZIP_CONTENT = new byte[] {0x50, 0x4b, 0x03, 0x04, 0, 0};
    private static final byte[] TEXT_CONTENT = "Example BSCW document".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private AtomicInteger downloads;
    private String root;

    @TempDir
    Path data;

    @BeforeEach
    void startWebDavServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downloads = new AtomicInteger();
        server.createContext("/sec/bscw.cgi/", this::handle);
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort() + "/sec/bscw.cgi/146377797";
    }

    @AfterEach
    void stopWebDavServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listsNestedFilesWithoutDownloadingAndReturnsOriginalBytes() throws Exception {
        KnowledgeSourcePort port = open("reader", "secret");
        PartitionListingSource listing = (PartitionListingSource) port;
        PartitionResources first = listing.resources("bscw-test", null, 1);
        assertThat(first.resources()).hasSize(1);
        assertThat(first.complete()).isFalse();
        PartitionResources second = listing.resources("bscw-test", first.nextCursor(), 1);
        assertThat(second.resources()).hasSize(1);
        assertThat(second.complete()).isTrue();
        assertThat(downloads).hasValue(0);

        KnowledgeResource document = first.resources().get(0);
        String id = ((StoredContentSource) port).localId(document.id());
        KnowledgeResource metadata = ((ResourceMetadataSource) port).resourceMetadata(id);
        assertThat(metadata.id()).isEqualTo(document.id());
        StoredContent original = ((StoredContentSource) port).storedContent(id);
        assertThat(original.nativeFormat()).isEqualTo("file");
        assertThat(original.payload()).isEqualTo(ZIP_CONTENT);
        assertThat(downloads).hasValue(1);

        KnowledgeDocument text = port.load(second.resources().get(0).id());
        assertThat(text.text()).contains("Example BSCW document");
    }

    @Test
    void exposesOriginalFileToTheExistingCoreTextProjector() throws Exception {
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "BSCW test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", root));
        String url = root.replace("/146377797", "/666");
        String localId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                url.getBytes(StandardCharsets.UTF_8));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).source(config).build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "bscw-test", localId,
                    false).snapshot();
            assertThat(snapshot.payload().bytes()).isEqualTo(TEXT_CONTENT);
            assertThat(harvester.pageText(snapshot)).map(PageText::text)
                    .hasValueSatisfying(text -> assertThat(text).contains("Example BSCW document"));
        }
    }

    @Test
    void refusesInvalidCredentialsAndNonBscwUrls() throws Exception {
        KnowledgeSourcePort wrong = open("reader", "wrong");
        assertThatThrownBy(() -> ((PartitionListingSource) wrong).resources("bscw-test", null, 2))
                .isInstanceOf(KnowledgeSourceException.class)
                .satisfies(error -> assertThat(((KnowledgeSourceException) error).kind())
                        .isEqualTo(KnowledgeSourceException.Kind.ACCESS_DENIED));
        BscwSourceAdapter adapter = new BscwSourceAdapter();
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "Test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, "reader", "secret", null),
                Map.of("rootUrl", "https://example.com/sec/bscw.cgi/home"));
        assertThat(adapter.check(config)).anyMatch(problem -> problem.contains("options.rootUrl"));
    }

    private KnowledgeSourcePort open(String username, String password) {
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        secrets.put("bscw-test", username, password);
        SourceAdapter.Context context = new SourceAdapter.Context(secrets,
                ProxyRoutes.from(ProxySettings.disabled()), "bscw-test-agent",
                TrustBundles.NONE, null, ProxyCredentials.none());
        SourceSettingsConfig config = new SourceSettingsConfig("bscw-test", "bscw", "Test", null,
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.BASIC, username, password, null),
                Map.of("rootUrl", root, "maxDepth", "2"));
        return new BscwSourceAdapter().open(config, context).port();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"BSCW\"");
                respond(exchange, 401, new byte[0], "text/plain");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if ("PROPFIND".equals(exchange.getRequestMethod())) {
                String depth = exchange.getRequestHeaders().getFirst("Depth");
                String body = directoryResponse(path, "1".equals(depth));
                respond(exchange, 207, body.getBytes(StandardCharsets.UTF_8), "application/xml");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                downloads.incrementAndGet();
                if ("/sec/bscw.cgi/555".equals(path)) {
                    respond(exchange, 200, ZIP_CONTENT, "application/zip");
                    return;
                }
                if ("/sec/bscw.cgi/666".equals(path)) {
                    respond(exchange, 200, TEXT_CONTENT, "text/plain");
                    return;
                }
            }
            respond(exchange, 404, new byte[0], "text/plain");
        } finally {
            exchange.close();
        }
    }

    private static boolean authorized(HttpExchange exchange) {
        String credentials = java.util.Base64.getEncoder().encodeToString(
                "reader:secret".getBytes(StandardCharsets.UTF_8));
        return ("Basic " + credentials).equals(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static String directoryResponse(String path, boolean children) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
        if ("/sec/bscw.cgi/146377797".equals(path)) {
            xml.append(entry(path, "Root", true, null));
            if (children) {
                xml.append(entry("/sec/bscw.cgi/444", "Nested", true, null));
                xml.append(entry("/sec/bscw.cgi/666", "readme.txt", false, "text/plain"));
            }
        } else if ("/sec/bscw.cgi/444".equals(path)) {
            xml.append(entry(path, "Nested", true, null));
            if (children) {
                xml.append(entry("/sec/bscw.cgi/555", "archive.zip", false, "application/zip"));
            }
        } else if ("/sec/bscw.cgi/555".equals(path)) {
            xml.append(entry(path, "archive.zip", false, "application/zip"));
        } else if ("/sec/bscw.cgi/666".equals(path)) {
            xml.append(entry(path, "readme.txt", false, "text/plain"));
        }
        return xml.append("</d:multistatus>").toString();
    }

    private static String entry(String path, String name, boolean folder, String mime) {
        return "<d:response><d:href>" + path + "</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status>"
                + "<d:prop><d:displayname>" + name + "</d:displayname><d:resourcetype>"
                + (folder ? "<d:collection/>" : "") + "</d:resourcetype>"
                + "<d:getetag>\"version-one\"</d:getetag>"
                + "<d:getlastmodified>Tue, 17 Nov 2026 18:00:00 GMT</d:getlastmodified>"
                + "<d:getcontentlength>24</d:getcontentlength>"
                + (mime == null ? "" : "<d:getcontenttype>" + mime + "</d:getcontenttype>")
                + "</d:prop></d:propstat></d:response>";
    }

    private static void respond(HttpExchange exchange, int status, byte[] content, String contentType)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
        if (content.length > 0) {
            exchange.getResponseBody().write(content);
        }
    }
}
