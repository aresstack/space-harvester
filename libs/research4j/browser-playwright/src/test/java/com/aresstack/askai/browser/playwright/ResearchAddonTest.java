package com.aresstack.askai.browser.playwright;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The AskAI Research add-on as Camoufox gets it: complete, with this start's bridge config, nothing for pages. */
public class ResearchAddonTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installWritesEveryFileAndTheBridgeConfig() throws Exception {
        Path addon = ResearchAddon.install(temporary.getRoot().toPath(), "{\"protocol\":1,\"port\":4711,\"token\":\"t\"}");
        assertEquals(ResearchAddon.DIRECTORY, addon.getFileName().toString());
        for (String file : ResearchAddon.FILES) {
            assertTrue(file, Files.isRegularFile(addon.resolve(file)));
        }
        assertEquals("{\"protocol\":1,\"port\":4711,\"token\":\"t\"}",
                Files.readString(addon.resolve("bridge.json"), StandardCharsets.UTF_8));
    }

    @Test
    public void aRestartReplacesTheOldBridgeConfig() throws Exception {
        Path parent = temporary.getRoot().toPath();
        ResearchAddon.install(parent, "{\"token\":\"old\"}");
        Path addon = ResearchAddon.install(parent, "{\"token\":\"new\"}");
        assertEquals("{\"token\":\"new\"}", Files.readString(addon.resolve("bridge.json")));
        ResearchAddon.install(parent, null);
        assertFalse("an idle add-on has no bridge", Files.exists(addon.resolve("bridge.json")));
    }

    @Test
    public void theManifestExposesNothingToPages() throws Exception {
        Path addon = ResearchAddon.install(temporary.getRoot().toPath(), null);
        JsonObject manifest = JsonParser.parseString(Files.readString(addon.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(ResearchAddon.ID, manifest.getAsJsonObject("browser_specific_settings")
                .getAsJsonObject("gecko").get("id").getAsString());
        // No registered content script, no web-accessible resource: a visited page has nothing of the add-on to
        // observe. Page reads are injected on demand into the isolated content-script world, which needs the tab
        // URLs (tabs) and the host permission for every page (<all_urls>).
        assertFalse(manifest.has("content_scripts"));
        assertFalse(manifest.has("web_accessible_resources"));
        assertEquals("[\"http://127.0.0.1/*\",\"tabs\",\"<all_urls>\"]", manifest.get("permissions").toString());
        for (String file : ResearchAddon.FILES) {
            String text = Files.readString(addon.resolve(file));
            assertFalse(file + " never registers page scripts", text.contains("contentScripts.register")
                    || text.contains("scripting.executeScript") || text.contains("wrappedJSObject")
                    || text.contains("exportFunction"));
            if (!"background.js".equals(file)) {
                assertFalse(file + " does not inject", text.contains("tabs.executeScript"));
            }
        }
    }

    @Test
    public void pageReadsRunOnceInTheIsolatedWorldOfTheMatchingTab() throws Exception {
        Path addon = ResearchAddon.install(temporary.getRoot().toPath(), null);
        String background = Files.readString(addon.resolve("background.js"));
        // Exactly one injection point: the read loop, with AskAI's script as code and nothing for other frames.
        assertEquals(1, background.split("tabs\\.executeScript\\(", -1).length - 1);
        assertTrue(background.contains("code: '(' + read.script + ')()'"));
        assertFalse(background.contains("allFrames"));
        assertTrue(background.contains("tab.url === url"));
    }

    @Test
    public void theChromeExtensionIsCompleteWithItsBridgeConfig() throws Exception {
        Path extension = ResearchAddon.installChrome(temporary.getRoot().toPath(), "{\"protocol\":1,\"port\":4711,\"token\":\"t\"}");
        for (String file : ResearchAddon.CHROME_FILES.keySet()) {
            assertTrue(file, Files.isRegularFile(extension.resolve(file)));
        }
        assertEquals("{\"protocol\":1,\"port\":4711,\"token\":\"t\"}", Files.readString(extension.resolve("bridge.json")));
    }

    @Test
    public void theChromeManifestKeyFixesTheIdTheBridgeAccepts() throws Exception {
        Path extension = ResearchAddon.installChrome(temporary.getRoot().toPath(), null);
        JsonObject manifest = JsonParser.parseString(Files.readString(extension.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(3, manifest.get("manifest_version").getAsInt());
        // Chrome derives an extension's id from its public key: the first 16 bytes of SHA-256, hex digits as a..p.
        byte[] key = java.util.Base64.getDecoder().decode(manifest.get("key").getAsString());
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key);
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            id.append((char) ('a' + ((digest[i] >> 4) & 0xf))).append((char) ('a' + (digest[i] & 0xf)));
        }
        assertEquals(ResearchAddon.CHROME_ID, id.toString());
        assertEquals("0.2.0", manifest.get("version").getAsString());
    }

    @Test
    public void theChromeManifestExposesNothingToPages() throws Exception {
        Path extension = ResearchAddon.installChrome(temporary.getRoot().toPath(), null);
        JsonObject manifest = JsonParser.parseString(Files.readString(extension.resolve("manifest.json"))).getAsJsonObject();
        assertFalse(manifest.has("content_scripts"));
        assertFalse(manifest.has("web_accessible_resources"));
        assertFalse("no remote code, no eval", manifest.has("content_security_policy"));
        assertEquals("[\"tabs\",\"scripting\",\"sidePanel\",\"alarms\"]", manifest.get("permissions").toString());
        assertEquals("[\"http://127.0.0.1/*\",\"<all_urls>\"]", manifest.get("host_permissions").toString());
        for (String file : ResearchAddon.CHROME_FILES.keySet()) {
            String text = Files.readString(extension.resolve(file));
            assertFalse(file + " never registers page scripts", text.contains("registerContentScripts")
                    || text.contains("userScripts") || text.contains("eval(") || text.contains("new Function"));
            if (!"background.js".equals(file)) {
                assertFalse(file + " does not inject", text.contains("executeScript"));
            }
        }
    }

    @Test
    public void chromeReadsRunOnlyAskAisFilesInTheTopFrameOfTheMatchingTab() throws Exception {
        Path extension = ResearchAddon.installChrome(temporary.getRoot().toPath(), null);
        String background = Files.readString(extension.resolve("background.js"));
        assertEquals(1, background.split("scripting\\.executeScript\\(", -1).length - 1);
        assertTrue(background.contains("files: [read.file]"));
        assertTrue(background.contains("frameIds: [0]"));
        assertTrue(background.contains("world: 'ISOLATED'"));
        assertTrue("only files AskAI wrote into reads/", background.contains("READ_FILE.test(read.file)"));
        assertFalse(background.contains("allFrames"));
        assertFalse("never script text", background.contains("func:") || background.contains("code:"));
        assertTrue(background.contains("tab.url === url"));
    }
}
