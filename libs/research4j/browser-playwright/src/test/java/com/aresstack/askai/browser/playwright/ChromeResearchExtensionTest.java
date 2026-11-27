package com.aresstack.askai.browser.playwright;

import com.google.gson.JsonParser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Request;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Chrome/Edge chat browser with the research extension: launch options, toolbar pin, bridge exception in routing. */
public class ChromeResearchExtensionTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void theBrowserStartsWithTheProtocolLoaderAndExtensionsOn() {
        BrowserType.LaunchPersistentContextOptions options = ChromeResearchExtension.options("msedge", true);
        assertEquals("msedge", options.channel);
        assertEquals(List.of("--enable-unsafe-extension-debugging"), options.args);
        assertEquals(List.of("--disable-extensions"), options.ignoreDefaultArgs);
        assertEquals(Boolean.FALSE, options.acceptDownloads);
        assertFalse("--load-extension is ignored by branded Chrome since 137", options.args.toString()
                .contains("--load-extension"));
    }

    @Test
    public void aFreshProfilePinsTheToolbarButton() throws Exception {
        Path profile = temporary.getRoot().toPath().resolve("profile");
        ChromeResearchExtension.pinToolbarButton(profile);
        String preferences = Files.readString(profile.resolve("Default").resolve("Preferences"), StandardCharsets.UTF_8);
        assertEquals("[\"" + ResearchAddon.CHROME_ID + "\"]", JsonParser.parseString(preferences).getAsJsonObject()
                .getAsJsonObject("extensions").get("pinned_extensions").toString());
        // A profile that already ran keeps its own preferences.
        Files.writeString(profile.resolve("Default").resolve("Preferences"), "{\"mine\":true}");
        ChromeResearchExtension.pinToolbarButton(profile);
        assertEquals("{\"mine\":true}", Files.readString(profile.resolve("Default").resolve("Preferences")));
    }

    @Test
    public void onlyTheExtensionsBridgeRequestsWithTheTokenPassTheRequestFilter() {
        String prefix = "http://127.0.0.1:4711/v1/";
        String bearer = "Bearer " + "f".repeat(64);
        assertTrue(Playwright4jDriver.fromExtensionWorker(request(prefix + "state?after=0", bearer), prefix, bearer));
        assertFalse("no token", Playwright4jDriver.fromExtensionWorker(request(prefix + "state", null), prefix, bearer));
        assertFalse("wrong token", Playwright4jDriver.fromExtensionWorker(
                request(prefix + "state", "Bearer " + "0".repeat(64)), prefix, bearer));
        assertFalse("another loopback port", Playwright4jDriver.fromExtensionWorker(
                request("http://127.0.0.1:4712/v1/state", bearer), prefix, bearer));
        assertFalse("another path", Playwright4jDriver.fromExtensionWorker(
                request("http://127.0.0.1:4711/admin", bearer), prefix, bearer));
    }

    private static Request request(String url, String authorization) {
        return (Request) Proxy.newProxyInstance(Request.class.getClassLoader(), new Class<?>[]{Request.class},
                (proxy, method, args) -> {
                    if ("url".equals(method.getName())) {
                        return url;
                    }
                    if ("headers".equals(method.getName())) {
                        return authorization == null ? Map.of() : Map.of("authorization", authorization);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
