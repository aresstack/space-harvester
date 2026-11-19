package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.images.ImagePayload;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.sources.MediaByteRange;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manual probe, not a regression test: the path of „Bilder beschreiben“ for a video with the user's configuration
 * (copy {@code probe-video.yml} with its own data directory and an AI service with a vision model): stream the N-th
 * video bookmark from its source, grab a still frame per slide change, store them as pseudo attachments and describe
 * them. They appear in the attachment list of the page. Runs only with the environment variable {@code SPACEHARVESTER_PROBE_FRAMES=N} (optionally
 * {@code SPACEHARVESTER_PROBE_LIMIT}).
 */
class VideoFramesLiveProbe {

    @Test
    void probe() throws Exception {
        String which = System.getenv("SPACEHARVESTER_PROBE_FRAMES");
        if (which == null) {
            return;
        }
        int limit = Integer.parseInt(System.getenv().getOrDefault("SPACEHARVESTER_PROBE_LIMIT", "8"));
        Path home = AppSettingsLoader.defaultHome();
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("probe-video.yml"), "probe");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        started.await(120, TimeUnit.SECONDS);
        tools.jackson.databind.JsonNode favorite = null;
        int found = 0;
        for (tools.jackson.databind.JsonNode entry : tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(Files.readString(home.resolve("data").resolve("favorites.json"))).path("favorites")) {
            if (entry.path("title").asText().toLowerCase(Locale.ROOT).endsWith(".mp4")
                    && ++found == Integer.parseInt(which)) {
                favorite = entry;
            }
        }
        String source = favorite.path("sourceId").asText();
        String pageId = favorite.path("pageId").asText();
        var workspace = context.runtime().workspace();
        var page = workspace.page(source, pageId, true);
        long size = Long.parseLong(page.header().metadata().attributes().getOrDefault("dav.contentLength", "-1"));
        System.out.println("PROBE page " + page.header().metadata().title() + " revision "
                + page.snapshot().revision() + " size " + size + " vision " + workspace.imagesAvailable()
                + " released " + workspace.imagesReleased(source, pageId));
        MediaByteRange ranges = workspace.openMediaRange(source, pageId);
        long start = System.nanoTime();
        List<Assets.VideoFrame> frames = VideoFrameGrabber.grab(ranges::read, size, "video/mp4", limit,
                (done, total) -> System.out.printf(Locale.ROOT, "PROBE frame %d/%d after %.1f s%n", done, total,
                        (System.nanoTime() - start) / 1e9), () -> false);
        workspace.storeVideoFrames(source, pageId, frames);
        System.out.println("PROBE stored " + frames.size() + " frames, stored=" + workspace.videoFramesStored(source,
                pageId));
        workspace.attachments(source, pageId).attachments().attachments().forEach(attachment -> System.out.println(
                "PROBE attachment " + attachment.title() + " " + attachment.mediaType() + " " + attachment.fileSize()));
        long describing = System.nanoTime();
        PageImages images = workspace.describeImages(source, pageId);
        System.out.printf(Locale.ROOT, "PROBE described %d of %d in %.1f s, state %s, warnings %s%n",
                images.describedCount(), images.images().size(), (System.nanoTime() - describing) / 1e9,
                images.state(), images.warnings());
        for (ImagePayload.Entry image : images.images()) {
            System.out.println("PROBE " + image.title() + " | " + image.status() + " | " + image.description());
        }
        context.shutdown();
    }
}
