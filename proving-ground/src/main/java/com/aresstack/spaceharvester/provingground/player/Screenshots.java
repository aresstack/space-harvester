package com.aresstack.spaceharvester.provingground.player;

import com.microsoft.playwright.BrowserContext;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * Screenshot comparison as a step: what Playwright Test's {@code expect(page).toHaveScreenshot(name)} does, in Java
 * (Playwright Java has the screenshot, but not the comparison; that lives in Playwright's Node test runner). The step's
 * code takes the screenshot with Playwright itself and hands it in:
 *
 * <pre>
 *   screenshots.expect("startseite", () -&gt; page.screenshot(new Page.ScreenshotOptions()
 *       .setAnimations(ScreenshotAnimations.DISABLED).setCaret(ScreenshotCaret.HIDE)));
 * </pre>
 *
 * <p>As in Playwright: screenshots are taken until two in a row are the same (the page has settled) or one matches the
 * reference; the reference is {@code <case>-snapshots/<name>-<browser>-<platform>.png} next to the test case. Without
 * a reference the screenshot becomes it and the step fails (the next run compares). On a mismatch the actual
 * screenshot and a diff image go to the output directory. Pixels count as different by Playwright's rule (YIQ colour
 * distance above {@link Options#threshold}); the allowed number of different pixels comes from
 * {@link Options#maxDiffPixels} and {@link Options#maxDiffPixelRatio}, as in Playwright. Playwright's anti-aliasing
 * detection is not done here: anti-aliased pixels count as different (stricter).</p>
 */
public final class Screenshots {

    /** Playwright's default {@code threshold} of {@code toHaveScreenshot}. */
    public static final double PLAYWRIGHT_THRESHOLD = 0.2;
    /** Playwright's default timeout of an {@code expect} assertion, used when the run sets none. */
    public static final Duration PLAYWRIGHT_EXPECT_TIMEOUT = Duration.ofSeconds(5);
    /** Largest YIQ distance of two colours (pixelmatch, which Playwright uses). */
    private static final double MAX_YIQ_DELTA = 35215;
    static final String NO_REFERENCES = "Bildschirmfoto-Vergleich braucht den Ordner des Testprojekts";
    private static final int DIFF_RED = 0xFFFF0000;
    private static final int DIFF_FADED_ALPHA = 0x40;

    /** Options of one comparison, named and defaulted like Playwright's {@code toHaveScreenshot} options. */
    public static final class Options {

        private double threshold = PLAYWRIGHT_THRESHOLD;
        private Integer maxDiffPixels;
        private Double maxDiffPixelRatio;
        private Duration timeout;

        /** Colour distance (0 strict … 1 lax) above which a pixel counts as different. */
        public Options setThreshold(double value) {
            threshold = value;
            return this;
        }

        /** How many different pixels are still accepted. */
        public Options setMaxDiffPixels(int value) {
            maxDiffPixels = value;
            return this;
        }

        /** Which share (0 … 1) of different pixels is still accepted. */
        public Options setMaxDiffPixelRatio(double value) {
            maxDiffPixelRatio = value;
            return this;
        }

        /** How long the page may take to settle; default the run's assertion timeout. */
        public Options setTimeout(double millis) {
            timeout = Duration.ofMillis((long) millis);
            return this;
        }

        /** Playwright's rule: the smaller of both limits when both are set, else the one set, else none. */
        long allowed(int pixels) {
            Long byCount = maxDiffPixels == null ? null : maxDiffPixels.longValue();
            Long byRatio = maxDiffPixelRatio == null ? null : (long) (pixels * maxDiffPixelRatio);
            if (byCount != null && byRatio != null) {
                return Math.min(byCount, byRatio);
            }
            return byCount != null ? byCount : byRatio != null ? byRatio : 0;
        }
    }

    private final Path references;
    private final Path output;
    private final Duration timeout;
    private String suffix = "";

    /**
     * @param references where the reference images of this test case are ({@code <case>-snapshots}), or null
     * @param output     where actual and diff images of a mismatch go, or null (then next to the references)
     * @param timeout    how long the page may take to settle; zero = Playwright's default
     */
    public Screenshots(Path references, Path output, Duration timeout) {
        this.references = references;
        this.output = output;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? PLAYWRIGHT_EXPECT_TIMEOUT
                : timeout;
    }

    /** Names the browser and platform of the following steps, for the file name as Playwright does. */
    void bind(BrowserContext context) {
        String browser = "browser";
        try {
            if (context.browser() != null) {
                browser = context.browser().browserType().name();
            }
        } catch (RuntimeException unknown) {
            // persistent context or closed browser: keep the neutral name
        }
        suffix = "-" + browser + "-" + platform();
    }

    /** The reference file of {@code name} for the bound browser. */
    public Path reference(String name) {
        if (references == null) {
            throw new IllegalStateException(NO_REFERENCES);
        }
        return references.resolve(fileName(name) + suffix + ".png");
    }

    public void expect(String name, Supplier<byte[]> screenshot) {
        expect(name, screenshot, new Options());
    }

    /** Takes screenshots until the page has settled or one matches, then compares (see the class comment). */
    public void expect(String name, Supplier<byte[]> screenshot, Options options) {
        Path reference = reference(name);
        BufferedImage expected = Files.exists(reference) ? read(reference) : null;
        Duration wait = options.timeout != null ? options.timeout : timeout;
        long deadline = System.nanoTime() + wait.toNanos();
        byte[] previous = null;
        byte[] actual;
        while (true) {
            actual = screenshot.get();
            if (expected != null && matches(expected, decode(actual), options)) {
                return;
            }
            if (previous != null && Arrays.equals(previous, actual)) {
                break;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Bildschirmfoto „" + name + "“: die Seite kam in " + wait.toMillis()
                        + " ms nicht zur Ruhe");
            }
            previous = actual;
        }
        if (expected == null) {
            write(reference, actual);
            throw new AssertionError("Bildschirmfoto „" + name + "“: Referenzbild fehlte und wurde angelegt ("
                    + reference + "); der nächste Lauf vergleicht");
        }
        BufferedImage image = decode(actual);
        Path target = output != null ? output : reference.getParent();
        String base = fileName(name) + suffix;
        write(target.resolve(base + "-actual.png"), actual);
        if (image.getWidth() != expected.getWidth() || image.getHeight() != expected.getHeight()) {
            throw new AssertionError("Bildschirmfoto „" + name + "“: Größe " + image.getWidth() + "×"
                    + image.getHeight() + " statt " + expected.getWidth() + "×" + expected.getHeight()
                    + " (Bild: " + target.resolve(base + "-actual.png") + ")");
        }
        BufferedImage diff = new BufferedImage(expected.getWidth(), expected.getHeight(), BufferedImage.TYPE_INT_ARGB);
        long different = differing(expected, image, options.threshold, diff);
        Path diffFile = target.resolve(base + "-diff.png");
        try {
            Files.createDirectories(target);
            ImageIO.write(diff, "png", diffFile.toFile());
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
        throw new AssertionError("Bildschirmfoto „" + name + "“: " + different + " Pixel weichen ab, erlaubt "
                + options.allowed(expected.getWidth() * expected.getHeight()) + " (Unterschied: " + diffFile + ")");
    }

    /** Same size and at most the allowed number of different pixels (a size change never passes). */
    static boolean matches(BufferedImage expected, BufferedImage actual, Options options) {
        return actual.getWidth() == expected.getWidth() && actual.getHeight() == expected.getHeight()
                && differing(expected, actual, options.threshold, null)
                <= options.allowed(expected.getWidth() * expected.getHeight());
    }

    /**
     * Number of pixels whose colour distance is above {@code threshold}, as pixelmatch counts them (colours blended
     * over white); images of another size differ everywhere. With {@code diff}, differing pixels are drawn red over
     * a faded copy of {@code expected}.
     */
    static long differing(BufferedImage expected, BufferedImage actual, double threshold, BufferedImage diff) {
        int width = expected.getWidth();
        int height = expected.getHeight();
        if (actual.getWidth() != width || actual.getHeight() != height) {
            return (long) width * height;
        }
        double maxDelta = MAX_YIQ_DELTA * threshold * threshold;
        long count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = expected.getRGB(x, y);
                int b = actual.getRGB(x, y);
                boolean differs = a != b && Math.abs(delta(a, b)) > maxDelta;
                if (differs) {
                    count++;
                }
                if (diff != null) {
                    diff.setRGB(x, y, differs ? DIFF_RED : (DIFF_FADED_ALPHA << 24) | (a & 0xFFFFFF));
                }
            }
        }
        return count;
    }

    /** pixelmatch's colour delta in YIQ space of two ARGB pixels blended over white (signed by brightness). */
    static double delta(int first, int second) {
        double[] p = blended(first);
        double[] q = blended(second);
        double y = yiq(p, 0.29889531, 0.58662247, 0.11448223) - yiq(q, 0.29889531, 0.58662247, 0.11448223);
        double i = yiq(p, 0.59597799, -0.27417610, -0.32180189) - yiq(q, 0.59597799, -0.27417610, -0.32180189);
        double j = yiq(p, 0.21147017, -0.52261711, 0.31114694) - yiq(q, 0.21147017, -0.52261711, 0.31114694);
        double delta = 0.5053 * y * y + 0.299 * i * i + 0.1957 * j * j;
        return y > 0 ? -delta : delta;
    }

    private static double yiq(double[] rgb, double r, double g, double b) {
        return rgb[0] * r + rgb[1] * g + rgb[2] * b;
    }

    private static double[] blended(int argb) {
        double alpha = ((argb >>> 24) & 0xFF) / 255.0;
        return new double[]{
                255 + (((argb >> 16) & 0xFF) - 255) * alpha,
                255 + (((argb >> 8) & 0xFF) - 255) * alpha,
                255 + ((argb & 0xFF) - 255) * alpha};
    }

    private static BufferedImage decode(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                throw new IllegalStateException("Bildschirmfoto ist kein lesbares Bild");
            }
            return image;
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    private static BufferedImage read(Path file) {
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    private static void write(Path file, byte[] png) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, png);
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    /** Playwright's platform names ({@code process.platform}). */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac")) {
            return "darwin";
        }
        return os.contains("linux") ? "linux" : os.replaceAll("[^a-z0-9]", "");
    }

    /** A name usable as file name on every platform. */
    static String fileName(String name) {
        String safe = name == null ? "" : name.strip().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return safe.isEmpty() ? "bildschirmfoto" : safe;
    }
}
