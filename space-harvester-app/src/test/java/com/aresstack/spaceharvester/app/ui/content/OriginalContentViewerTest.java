package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.content.viewer.AttachmentViewerPanel;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OriginalContentViewerTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void originalTabShowsSourceFileBytesAndKeepsTextAsSeparateTab() throws Exception {
        PageDetailPanel panel = panel();
        byte[] original = "Originalinhalt mit Formatierung".getBytes(StandardCharsets.UTF_8);

        onEdt(() -> {
            panel.showOriginalContent(metadata("bericht.txt", PageMetadata.FILE_KIND),
                    new NativePayload(original, "text/plain", "file"));
            return null;
        });

        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().isShowingText()));
        AttachmentViewerPanel viewer = onEdt(panel::originalViewer);
        assertThat(onEdt(viewer::textContent)).isEqualTo("Originalinhalt mit Formatierung");
        onEdt(() -> {
            panel.tabs().select("Podcast-Ansicht");
            assertThat(panel.selectedTab()).isEqualTo("Podcast-Ansicht");
            assertThat(panel.footTools().getComponentCount()).isZero();
            panel.tabs().select("Leseansicht");
            assertThat(panel.originalViewer()).isSameAs(viewer);
            // Speichern, Öffnen und „Typ · Größe“ stehen in der Leiste mit der Lupe, nicht mehr in der Vorschau.
            assertThat(panel.footTools().getComponent(0)).isSameAs(viewer.footer());
            assertThat(viewer.footer().getParent()).isSameAs(panel.footTools());
            return null;
        });
    }

    @Test
    void unsupportedFilesRemainDownloadableAndFoldersUseTheNormalReader() throws Exception {
        PageDetailPanel panel = panel();
        onEdt(() -> {
            panel.showOriginalContent(metadata("projekt.bin", PageMetadata.FILE_KIND),
                    new NativePayload(new byte[] {1, 2, 3}, "application/octet-stream", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().statusText().contains(
                        AttachmentViewerPanel.NO_PREVIEW_TEXT)));
        AttachmentViewerPanel viewer = onEdt(panel::originalViewer);
        assertThat(onEdt(viewer::fileInfoText)).contains("BIN", "3 B");
        assertThat(onEdt(viewer::isShowingText)).isFalse();
        onEdt(() -> {
            panel.showOriginalContent(metadata("Projektordner", PageMetadata.FOLDER_KIND),
                    new NativePayload(new byte[0], "text/plain", "bscw-folder"));
            assertThat(panel.originalViewer()).isNull();
            return null;
        });
    }

    @Test
    void rendersPngEvenWhenWebDavReportsAnIncorrectMediaType() throws Exception {
        PageDetailPanel panel = panel();
        byte[] original = png();
        onEdt(() -> {
            panel.showOriginalContent(metadata("Diagramm", PageMetadata.FILE_KIND),
                    new NativePayload(original, "application/octet-stream", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().showsPicture()));
        AttachmentViewerPanel viewer = onEdt(panel::originalViewer);
        assertThat(onEdt(() -> viewer.attachment().kind().name())).isEqualTo("IMAGE");
        assertThat(onEdt(viewer::fileInfoText)).contains("PNG");
    }

    @Test
    void rendersCsvAsSpreadsheetAndKeepsBinaryPayloadOutOfTechnicalText() throws Exception {
        PageDetailPanel panel = panel();
        String csv = "name,value\nNorth,12\nSouth,15\n";
        onEdt(() -> {
            panel.showOriginalContent(metadata("werte.csv", PageMetadata.FILE_KIND),
                    new NativePayload(csv.getBytes(StandardCharsets.UTF_8), "text/csv", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().isShowingSpreadsheet()));
        assertThat(onEdt(() -> panel.originalViewer().attachment().kind().name()))
                .isEqualTo("SPREADSHEET");
        assertThat(onEdt(() -> panel.originalViewer().isShowingText())).isFalse();

        byte[] binary = png();
        NativePayload payload = new NativePayload(binary, "image/png", "file");
        assertThat(Payloads.display(payload)).contains("Binärinhalt", "image/png")
                .doesNotContain("\uFFFD");
        assertThat(Payloads.isText(payload)).isFalse();
    }

    @Test
    void imageViewerExplainsWhenTheSourceReturnedHtmlInsteadOfImageBytes() throws Exception {
        PageDetailPanel panel = panel();
        byte[] loginPage = "<html><body>Sign in</body></html>".getBytes(StandardCharsets.UTF_8);
        onEdt(() -> {
            panel.showOriginalContent(metadata("zeichnung.png", PageMetadata.FILE_KIND),
                    new NativePayload(loginPage, "image/png", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().statusText().contains("HTML statt Bilddaten")));
        assertThat(onEdt(() -> panel.originalViewer().statusText()))
                .contains("Anmeldung oder Proxy-Antwort")
                .doesNotContain("Sign in");
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 2, 0x13579B);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    private PageDetailPanel panel() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: "
                + home.resolve("data").toString().replace('\\', '/') + "\n");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        return onEdt(() -> new PageDetailPanel(context));
    }

    private static PageMetadata metadata(String title, String kind) {
        return new PageMetadata("node-id", "source:node-id", title, "space", "parent-id",
                "1", Instant.now(), null,
                PageMetadata.sorted(Map.of(PageMetadata.RESOURCE_KIND, kind)));
    }

    private static void await(Check condition) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(20);
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

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }
}
