package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.images.PageImages;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manual probe, not a regression test: „Bilder beschreiben“ with a copy of the user's configuration and data
 * ({@code probe-describe.yml}), for every video bookmark: what the Bilder tab reads, then a describe run with every
 * finished image printed. Runs only with the environment variable {@code SPACEHARVESTER_PROBE_DESCRIBE}.
 */
class DescribeImagesProbe {

    @Test
    void probe() throws Exception {
        if (System.getenv("SPACEHARVESTER_PROBE_DESCRIBE") == null) {
            return;
        }
        Path home = AppSettingsLoader.defaultHome();
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("probe-describe.yml"), "probe");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        started.await(120, TimeUnit.SECONDS);
        var workspace = context.runtime().workspace();
        System.out.println("PROBE vision " + workspace.imagesAvailable());
        for (tools.jackson.databind.JsonNode entry : tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(Files.readString(home.resolve("data").resolve("favorites.json"))).path("favorites")) {
            if (!entry.path("title").asText().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                continue;
            }
            String source = entry.path("sourceId").asText();
            String pageId = entry.path("pageId").asText();
            System.out.println("PROBE ===== " + entry.path("title").asText());
            try {
                var page = workspace.page(source, pageId, false);
                System.out.println("PROBE released " + workspace.imagesReleased(source, pageId) + ", marks "
                        + workspace.videoMarks(source, pageId).size() + ", missing frames "
                        + workspace.missingVideoFrames(source, pageId).size());
                PageImages read = workspace.images(page);
                System.out.println("PROBE read: " + read.state() + " reason " + read.reason() + ", images "
                        + read.images().size());
                long start = System.nanoTime();
                PageImages described = workspace.describeImages(source, pageId, false, image -> System.out.printf(
                        Locale.ROOT, "PROBE   %s %s %s after %.1f s%n", image.title(), image.status(),
                        image.reason(), (System.nanoTime() - start) / 1e9));
                System.out.println("PROBE describe: " + described.state() + " reason " + described.reason()
                        + ", images " + described.images().size() + ", described " + described.describedCount()
                        + ", warnings " + described.warnings());
            } catch (RuntimeException failure) {
                System.out.println("PROBE failed: " + failure);
                failure.printStackTrace(System.out);
            }
        }
        context.shutdown();
    }
}
