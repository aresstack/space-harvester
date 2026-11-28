package com.aresstack.spaceharvester.provingground.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Screenshot comparison like Playwright Test's toHaveScreenshot: reference, settling, pixel rule, limits. */
class ScreenshotsTest {

    @TempDir
    Path folder;

    @Test
    void withoutAReferenceTheScreenshotBecomesItAndTheStepFailsOnce() throws IOException {
        Path references = folder.resolve("anmelden-snapshots");
        Screenshots shots = new Screenshots(references, folder.resolve("out"), Duration.ofSeconds(2));
        byte[] page = png(box(Color.BLUE, 0));
        AssertionError first = assertThrows(AssertionError.class, () -> shots.expect("start", () -> page));
        assertTrue(first.getMessage().contains("Referenzbild fehlte"), first.getMessage());
        Path reference = shots.reference("start");
        assertTrue(Files.exists(reference), reference.toString());
        assertEquals("start.png", reference.getFileName().toString()); // browser and platform come with bind()
        assertDoesNotThrow(() -> shots.expect("start", () -> page));
    }

    @Test
    void aChangedPageFailsWithActualAndDiffImageAndTheCountOfPixels() throws IOException {
        Path out = folder.resolve("out");
        Screenshots shots = new Screenshots(folder.resolve("ref"), out, Duration.ofSeconds(2));
        Files.createDirectories(folder.resolve("ref"));
        Files.write(shots.reference("start"), png(box(Color.BLUE, 0)));
        byte[] moved = png(box(Color.BLUE, 5));
        AssertionError failed = assertThrows(AssertionError.class, () -> shots.expect("start", () -> moved));
        assertTrue(failed.getMessage().contains("Pixel weichen ab, erlaubt 0"), failed.getMessage());
        try (var files = Files.list(out)) {
            List<String> names = files.map(file -> file.getFileName().toString()).sorted().toList();
            assertEquals(2, names.size(), names.toString());
            assertTrue(names.get(0).endsWith("-actual.png") && names.get(1).endsWith("-diff.png"), names.toString());
        }
        // Playwright's limits: enough allowed pixels, or a large enough share, let it pass
        assertDoesNotThrow(() -> shots.expect("start", () -> moved, new Screenshots.Options().setMaxDiffPixels(400)));
        assertDoesNotThrow(() -> shots.expect("start", () -> moved,
                new Screenshots.Options().setMaxDiffPixelRatio(0.5)));
        assertThrows(AssertionError.class, () -> shots.expect("start", () -> moved,
                new Screenshots.Options().setMaxDiffPixels(400).setMaxDiffPixelRatio(0.0001)));
    }

    @Test
    void theComparisonWaitsUntilThePageHasSettled() throws IOException {
        Screenshots shots = new Screenshots(folder.resolve("ref"), folder.resolve("out"), Duration.ofSeconds(2));
        byte[] loading = png(box(Color.GRAY, 0));
        byte[] done = png(box(Color.BLUE, 0));
        ArrayDeque<byte[]> frames = new ArrayDeque<>(List.of(loading, done, done));
        Supplier<byte[]> screen = () -> frames.size() > 1 ? frames.poll() : frames.peek();
        assertThrows(AssertionError.class, () -> shots.expect("start", screen)); // the reference is written
        assertEquals(done.length, Files.readAllBytes(shots.reference("start")).length);
    }

    @Test
    void slightColourShiftsBelowPlaywrightsThresholdAreTheSame() {
        BufferedImage a = box(new Color(100, 100, 200), 0);
        BufferedImage b = box(new Color(102, 101, 199), 0);
        assertEquals(0, Screenshots.differing(a, b, Screenshots.PLAYWRIGHT_THRESHOLD, null));
        assertTrue(Screenshots.differing(a, b, 0, null) > 0);
    }

    @Test
    void namesFollowPlaywrightsPlatformAndAreSafeFileNames() {
        assertTrue(List.of("linux", "win32", "darwin").contains(Screenshots.platform())
                || !Screenshots.platform().isEmpty());
        assertEquals("Konto-Übersicht", Screenshots.fileName("Konto Übersicht"));
        assertEquals("a-b", Screenshots.fileName("a/b"));
    }

    private static BufferedImage box(Color color, int shift) {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 40, 30);
        g.setColor(color);
        g.fillRect(5 + shift, 5, 20, 15);
        g.dispose();
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
