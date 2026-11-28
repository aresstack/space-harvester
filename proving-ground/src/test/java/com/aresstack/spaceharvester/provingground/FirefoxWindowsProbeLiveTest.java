package com.aresstack.spaceharvester.provingground;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Vorübergehende Messung (wird wieder entfernt): wie viele Firefox-Fenster ein sichtbarer Start zeigt. */
@Tag("live")
class FirefoxWindowsProbeLiveTest {

    @TempDir
    Path data;

    @Test
    void probe() throws Exception {
        BrowserChannel channel = BrowserChannel.of(System.getenv().getOrDefault("PROVING_GROUND_TEST_CHANNEL",
                BrowserChannel.FIREFOX.id()));
        assumeTrue(channel.firefoxFamily(), "nur Firefox");
        BrowserRuntime runtime = new BrowserRuntime(BrowserRuntimeConfig.of(channel,
                System.getenv().getOrDefault("PROVING_GROUND_TEST_EXECUTABLE", ""), data.resolve("b"),
                Duration.ofMinutes(2)));
        try (RunningBrowser browser = runtime.ephemeral(false, null, false)) {
            log("ephemeral after start: " + windows());
            BrowserContext context = browser.browser().newContext();
            log("ephemeral contexts=" + browser.browser().contexts().size() + " pages=" + context.pages().size());
            Page page = context.newPage();
            page.navigate("data:text/html,<title>probe</title>");
            log("ephemeral after newPage: " + windows());
        }
        try (RunningBrowser browser = runtime.persistentIn(data.resolve("p"), false, null)) {
            BrowserContext context = browser.context();
            log("persistent after start: pages=" + context.pages().size() + " browser=" + context.browser()
                    + " windows=" + windows());
            java.util.List<Page> start = java.util.List.copyOf(context.pages());
            Page page = context.newPage();
            page.navigate("data:text/html,<title>probe</title>");
            log("persistent after newPage: pages=" + context.pages().size() + " windows=" + windows());
            if (context.browser() != null) {
                try {
                    BrowserContext other = context.browser().newContext();
                    other.newPage().navigate("data:text/html,<title>other</title>");
                    log("persistent other context ok: windows=" + windows());
                } catch (RuntimeException failed) {
                    log("persistent other context failed: " + failed.getMessage());
                }
            }
            for (Page each : start) {
                each.close();
            }
            log("persistent after closing start pages: pages=" + context.pages().size() + " windows=" + windows());
            page.navigate("data:text/html,<title>still</title>");
            log("persistent still alive: " + page.title());
        }
    }

    private static void log(String line) {
        System.out.println("PROBE " + line);
    }

    private static String windows() {
        try {
            Process process = new ProcessBuilder("sh", "-c",
                    "xwininfo -root -tree 2>&1 | grep -i -E 'firefox|mozilla|navigator' | grep -v '1x1' | head -20")
                    .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return "\n" + out;
        } catch (Exception failed) {
            return "xwininfo: " + failed;
        }
    }
}
