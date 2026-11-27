package com.aresstack.spaceharvester.browser.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * Persistent post-mortem for a failed browser start (moved from Ask AI). The exception message travels as one line;
 * Playwright's multi-line exception (browser log, call log) would be cut to it. Start failures are rare and hard to
 * reproduce, so every one is APPENDED in full to {@code <data>/logs/browser-start-failures.log}.
 *
 * <p>Best effort: a log that cannot be written never masks the start failure itself.</p>
 */
final class BrowserStartFailureLog {

    static final String FILE_NAME = "browser-start-failures.log";

    private BrowserStartFailureLog() {
    }

    /** @return the absolute path of the log the report was appended to, or null when writing failed. */
    static String append(File directory, String channel, boolean headless, String stage, long elapsedMillis,
                         Throwable failure) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return null;
            }
            File file = new File(directory, FILE_NAME);
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                out.println("=== " + OffsetDateTime.now() + " browser start failed");
                out.println("channel=" + channel + " headless=" + headless + " stage=" + stage
                        + " elapsedMs=" + elapsedMillis);
                out.println("java=" + System.getProperty("java.version")
                        + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " freePhysicalMb=" + freePhysicalMegabytes());
                failure.printStackTrace(out);
                out.println();
            }
            return file.getAbsolutePath();
        } catch (Exception unwritable) {
            System.getLogger(BrowserStartFailureLog.class.getName()).log(System.Logger.Level.WARNING,
                    "could not write the browser start failure log: " + unwritable);
            return null;
        }
    }

    /** Free physical memory at failure time: a starved machine is a known cause of failed launches. */
    private static String freePhysicalMegabytes() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean bean) {
                return String.valueOf(bean.getFreeMemorySize() / (1024 * 1024));
            }
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
        return "unknown";
    }
}
