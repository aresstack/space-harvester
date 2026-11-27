package com.aresstack.askai.browser.playwright;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * Persistent post-mortem for a failed browser start. The browser runtime's stderr only survives as a small ring in
 * the host and a one-line chat message, which cuts Playwright's multi-line exception (browser log, call
 * log) to its first line. Start failures are rare and hard to reproduce, so every one is APPENDED in full
 * to {@code <APPDATA>/.askai/logs/browser-start-failures.log} — the same app directory the host uses
 * (AskAiPaths), resolved here because the browser runtime cannot depend on the host module.
 *
 * <p>Best effort: a log that cannot be written never masks the start failure itself.</p>
 */
final class BrowserStartFailureLog {

    static final String FILE_NAME = "browser-start-failures.log";

    private BrowserStartFailureLog() {
    }

    /** @return the absolute path of the log the report was appended to, or null when writing failed. */
    static String append(String channel, boolean headless, String stage, long elapsedMillis, Throwable failure) {
        return append(defaultDirectory(), channel, headless, stage, elapsedMillis, failure);
    }

    static String append(File directory, String channel, boolean headless, String stage, long elapsedMillis,
                         Throwable failure) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return null;
            }
            File file = new File(directory, FILE_NAME);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8));
            try {
                out.println("=== " + OffsetDateTime.now() + " browser start failed");
                out.println("channel=" + channel + " headless=" + headless + " stage=" + stage
                        + " elapsedMs=" + elapsedMillis);
                out.println("java=" + System.getProperty("java.version")
                        + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " freePhysicalMb=" + freePhysicalMegabytes());
                failure.printStackTrace(out);
                out.println();
            } finally {
                out.close();
            }
            return file.getAbsolutePath();
        } catch (Exception unwritable) {
            System.err.println("[browser-mcp] could not write the browser start failure log: " + unwritable);
            return null;
        }
    }

    /** Free physical memory at failure time — a starved machine is a known cause of failed launches. */
    private static String freePhysicalMegabytes() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                long free = ((com.sun.management.OperatingSystemMXBean) os).getFreeMemorySize();
                return String.valueOf(free / (1024 * 1024));
            }
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
        return "unknown";
    }

    static File defaultDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        return new File(new File(appData, ".askai"), "logs");
    }
}
