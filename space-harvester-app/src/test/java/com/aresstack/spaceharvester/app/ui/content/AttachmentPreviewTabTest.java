package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.content.viewer.AttachmentViewerPanel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reiter „Anhänge“ (#183) über die echte Laufzeit der App mit einer Fake-Confluence: „Vorschau“ ist ohne gewählten
 * Anhang gesperrt; mit Auswahl öffnet sie den Anhang-Viewer als Overlay über der Liste, der die Datei erst jetzt über
 * den Core lädt; Schließen bringt die Liste zurück.
 */
class AttachmentPreviewTabTest {

    @TempDir
    Path home;

    private HttpServer server;
    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void previewOpensTheSelectedAttachmentInTheViewerOverlay() throws Exception {
        FakeConfluence wiki = new FakeConfluence();
        wiki.space("DEV", "Entwicklung");
        wiki.page("200", "Betrieb", "DEV", "<p>Ansicht</p>").storage("<p>Text</p>");
        wiki.homepage("DEV", "200");
        wiki.attachment("att901", "200", "notizen.txt", "text/plain", "Zeile 1\nÄnderung 2\n");
        start(serve(wiki));

        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        assertThat(onEdt(() -> panel.previewAsset().isEnabled())).as("ohne Seite").isFalse();
        onEdt(() -> {
            panel.tabs().select("Anhänge");
            panel.load("wiki", "200", false);
            return null;
        });
        await(() -> onEdt(() -> panel.assets().getRowCount() == 1));
        assertThat(onEdt(() -> panel.previewAsset().isEnabled())).as("ohne Auswahl").isFalse();
        assertThat(wiki.requestCount("/download/attachments/")).as("nichts geladen vor dem Klick").isZero();

        onEdt(() -> {
            panel.assets().setRowSelectionInterval(0, 0);
            return null;
        });
        assertThat(onEdt(() -> panel.previewAsset().isEnabled())).isTrue();
        onEdt(() -> {
            panel.previewAsset().doClick();
            return null;
        });
        AttachmentViewerPanel viewer = onEdt(panel::attachmentViewer);
        assertThat(viewer).isNotNull();
        assertThat(onEdt(() -> viewer.attachment().fileName())).isEqualTo("notizen.txt");
        await(() -> onEdt(viewer::isShowingText));
        assertThat(onEdt(viewer::textContent)).isEqualTo("Zeile 1\nÄnderung 2\n");
        assertThat(onEdt(viewer::fileInfoText)).startsWith("TXT");

        onEdt(() -> {
            panel.closeAttachmentOverlay();
            return null;
        });
        assertThat(onEdt(panel::attachmentViewer)).isNull();
    }

    // ------------------------------------------------------------------ Aufbau

    private void start(URI wiki) throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Wiki
                      base-url: %s
                """.formatted(home.resolve("data"), wiki));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime()).isNotNull();
    }

    private URI serve(FakeConfluence confluence) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> answer(confluence, exchange));
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + FakeConfluence.BASE.getPath());
    }

    private static void answer(FakeConfluence confluence, HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        ConfluenceHttpResponse response;
        synchronized (confluence) {
            response = confluence.get(mapped, headers, Integer.MAX_VALUE);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    private static void await(Check check) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (check.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Zustand nicht erreicht");
    }

    private static <T> T onEdt(ThrowingSupplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.get());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
