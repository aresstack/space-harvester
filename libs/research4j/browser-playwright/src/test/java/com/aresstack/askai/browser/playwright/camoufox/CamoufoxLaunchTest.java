package com.aresstack.askai.browser.playwright.camoufox;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CamoufoxLaunchTest {
    private static final Gson GSON = new Gson();

    @TempDir Path temporary;

    @Test
    void builtInProfileKeepsTheChosenFingerprintAndNoMachinePaths() throws Exception {
        JsonObject profile = CamoufoxLaunch.defaultProfile();
        JsonObject config = profile.getAsJsonObject("config");
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:156.0) Gecko/20100101 Firefox/156.0",
                config.get("navigator.userAgent").getAsString());
        assertEquals("Win32", config.get("navigator.platform").getAsString());
        assertEquals(24, config.get("navigator.hardwareConcurrency").getAsInt());
        assertEquals(1366, config.get("screen.width").getAsInt());
        assertTrue(config.getAsJsonArray("fonts").size() > 100);
        assertTrue(config.get("webGl:renderer").getAsString().contains("GTX 980"));
        assertTrue(config.has("voices"));
        // Media devices stay one consistent machine from the launcher's catalogue, without a monitor model.
        assertEquals(1, config.get("mediaDevices:speakers").getAsInt());
        assertEquals(1, config.getAsJsonArray("mediaDevices:speakerLabels").size());
        assertEquals(1, config.getAsJsonArray("mediaDevices:speakerGroups").size());
        assertEquals("Speakers (Realtek(R) Audio)",
                config.getAsJsonArray("mediaDevices:speakerLabels").get(0).getAsString());
        assertEquals(config.get("mediaDevices:micros").getAsInt(),
                config.getAsJsonArray("mediaDevices:microphoneLabels").size());
        assertEquals(config.get("mediaDevices:webcams").getAsInt(),
                config.getAsJsonArray("mediaDevices:webcamLabels").size());
        assertFalse(config.toString().contains("DELL"));
        assertTrue(config.has("audio:seed"));
        assertFalse(config.has("addons"), "add-on paths are resolved per installation");
        assertEquals("UBO", profile.getAsJsonArray("bundledAddons").get(0).getAsString());
        assertEquals("en-US", profile.getAsJsonObject("firefoxUserPrefs").get("intl.locale.requested").getAsString());
        String text = profile.toString();
        assertFalse(text.contains("C:\\\\Users"), "no developer paths");
        assertFalse(text.contains(".browser-event-hub"));
        // Argos owns the browser network settings; the default profile must not set them.
        profile.getAsJsonObject("firefoxUserPrefs").keySet().forEach(key ->
                assertFalse(key.startsWith("network.proxy.") || key.startsWith("network.trr."), key));
    }

    @Test
    void windowsLaunchSplitsTheFingerprintInto2047CharacterVariables() throws Exception {
        Path browser = bundle("camoufox.exe");
        CamoufoxLaunch launch = CamoufoxLaunch.builtIn(CamoufoxLaunch.defaultProfile(),
                browser.resolve("camoufox.exe"), temporary.resolve("runtime"), "Windows 11");

        StringBuilder joined = new StringBuilder();
        int part = 1;
        for (; launch.environment().containsKey("CAMOU_CONFIG_" + part); part++) {
            String chunk = launch.environment().get("CAMOU_CONFIG_" + part);
            assertTrue(chunk.length() <= 2047);
            joined.append(chunk);
        }
        assertTrue(part > 3, "the fingerprint needs several variables on Windows");
        JsonObject config = JsonParser.parseString(joined.toString()).getAsJsonObject();
        assertEquals(browser.resolve("addons").resolve("UBO").toString(),
                config.getAsJsonArray("addons").get(0).getAsString());
        assertEquals(1293, config.get("window.outerWidth").getAsInt());
        assertEquals(JsonParser.parseString(launch.environment().get("CAMOU_PREFS_1")),
                GSON.toJsonTree(launch.prefs()));
        // autoconfig reads the environment through the ANSI code page on Windows: the prefs stay pure ASCII.
        assertTrue(launch.environment().get("CAMOU_PREFS_1").chars().allMatch(c -> c < 0x80));
        assertFalse(launch.environment().containsKey("FONTCONFIG_FILE"));
        assertTrue(launch.environment().keySet().stream().allMatch(key -> key.startsWith("CAMOU_")));
        assertTrue(launch.args().isEmpty());
    }

    @Test
    void withAddonAppendsTheAddonAndRechunksOnlyTheFingerprint() throws Exception {
        Path browser = bundle("camoufox.exe");
        CamoufoxLaunch launch = CamoufoxLaunch.builtIn(CamoufoxLaunch.defaultProfile(),
                browser.resolve("camoufox.exe"), temporary.resolve("runtime"), "Windows 11");
        Path addon = temporary.resolve("profile").resolve("askai-research-addon");

        CamoufoxLaunch withAddon = launch.withAddon(addon, "Windows 11");

        StringBuilder joined = new StringBuilder();
        for (int part = 1; withAddon.environment().containsKey("CAMOU_CONFIG_" + part); part++) {
            assertTrue(withAddon.environment().get("CAMOU_CONFIG_" + part).length() <= 2047);
            joined.append(withAddon.environment().get("CAMOU_CONFIG_" + part));
        }
        JsonObject config = JsonParser.parseString(joined.toString()).getAsJsonObject();
        assertEquals(2, config.getAsJsonArray("addons").size());
        assertEquals(browser.resolve("addons").resolve("UBO").toString(),
                config.getAsJsonArray("addons").get(0).getAsString(), "the bundled add-ons stay first");
        assertEquals(addon.toAbsolutePath().toString(), config.getAsJsonArray("addons").get(1).getAsString());
        assertEquals(config, withAddon.fingerprint());
        // Apart from the add-on list the fingerprint is unchanged.
        config.remove("addons");
        JsonObject before = launch.fingerprint().deepCopy();
        before.remove("addons");
        assertEquals(before, config);
        assertEquals(launch.environment().get("CAMOU_PREFS_1"), withAddon.environment().get("CAMOU_PREFS_1"));
        assertEquals(launch.prefs(), withAddon.prefs());
        assertEquals(launch.args(), withAddon.args());
        assertEquals(1, launch.fingerprint().getAsJsonArray("addons").size(), "the original launch is untouched");
    }

    @Test
    void linuxLaunchUsesTheBundledWindowsFonts() throws Exception {
        Path browser = bundle("camoufox");
        Files.createDirectories(browser.resolve("fontconfig/windows"));
        Files.writeString(browser.resolve("fontconfig/windows/fonts.conf"),
                "<fontconfig><dir prefix=\"cwd\">fonts</dir></fontconfig>");
        Files.createDirectories(browser.resolve("fonts/W"));
        Files.createDirectories(browser.resolve("fonts/LW"));
        Files.createDirectories(browser.resolve("fonts/L"));
        Files.writeString(browser.resolve("fonts/groups.json"), "{\"readBy\":{\"win\":[\"W\",\"LW\"],\"lin\":[\"L\"]}}");

        CamoufoxLaunch launch = CamoufoxLaunch.builtIn(CamoufoxLaunch.defaultProfile(),
                browser.resolve("camoufox"), temporary.resolve("runtime"), "Linux");

        assertFalse(launch.environment().containsKey("CAMOU_CONFIG_2"), "one variable suffices on Linux");
        Path fonts = Path.of(launch.environment().get("FONTCONFIG_FILE"));
        assertTrue(fonts.startsWith(temporary.resolve("runtime")));
        String conf = Files.readString(fonts);
        assertTrue(conf.contains("<dir>" + browser.resolve("fonts/W") + "</dir>"));
        assertTrue(conf.contains("<dir>" + browser.resolve("fonts/LW") + "</dir>"));
        assertFalse(conf.contains(browser.resolve("fonts/L") + "</dir>"));
    }

    @Test
    void refusesACamoufoxWithoutTheBundledAddOn() throws Exception {
        Path browser = temporary.resolve("own");
        Files.createDirectories(browser);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> CamoufoxLaunch.builtIn(
                CamoufoxLaunch.defaultProfile(), browser.resolve("camoufox.exe"), temporary, "Windows 11"));
        assertTrue(error.getMessage().contains("BROWSER_LAUNCH_CONFIG"));
    }

    @Test
    void explicitLaunchFileOverridesTheDefaultsAndMustExist() throws Exception {
        Path file = temporary.resolve("launch.json");
        Files.writeString(file, "{\"env\":{\"CAMOU_CONFIG_1\":\"{\\\"window.innerWidth\\\":800,\",\"CAMOU_CONFIG_2\":"
                + "\"\\\"window.innerHeight\\\":600}\"},\"firefoxUserPrefs\":{\"a.b\":1},\"args\":[\"-x\"]}");
        CamoufoxLaunch launch = CamoufoxLaunch.fromFile(file);
        assertEquals(800, launch.fingerprint().get("window.innerWidth").getAsInt());
        assertEquals(Map.of("a.b", 1), launch.prefs());
        assertEquals("-x", launch.args().get(0));

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> CamoufoxLaunch.fromFile(temporary.resolve("absent.json")));
        assertTrue(missing.getMessage().contains("does not exist"));
        Files.writeString(file, "{not json");
        assertThrows(IllegalArgumentException.class, () -> CamoufoxLaunch.fromFile(file));
        Files.writeString(file, "{\"env\":{\"CAMOU_CONFIG_1\":\"{}\",\"LD_PRELOAD\":\"x\"}}");
        assertThrows(IllegalArgumentException.class, () -> CamoufoxLaunch.fromFile(file));
    }

    @Test
    void chunksNeverSplitASurrogatePair() {
        String json = "ab\uD83D\uDE00cd";
        Map<String, String> parts = CamoufoxLaunch.chunks("P_", json, 3);
        assertEquals("ab", parts.get("P_1"));
        assertEquals(json, String.join("", parts.values()));
    }

    @Test
    void prefsAreEscapedToAsciiAndParseBackToTheSameText() throws Exception {
        String escaped = CamoufoxLaunch.escapeNonAscii(GSON.toJson(Map.of("k", "Gr\u00fc\u00dfe \uD83D\uDE00")));
        assertEquals("{\"k\":\"Gr\\u00FC\\u00DFe \\uD83D\\uDE00\"}", escaped);
        assertEquals("Gr\u00fc\u00dfe \uD83D\uDE00",
                JsonParser.parseString(escaped).getAsJsonObject().get("k").getAsString());
    }

    @Test
    void launchFilesAreReadStrictlyLikeBefore() throws Exception {
        Path file = temporary.resolve("lenient.json");
        // Gson's lenient defaults would accept these; the Jackson reader this replaced did not.
        Files.writeString(file, "{'env':{'CAMOU_CONFIG_1':'{}'}}");
        assertThrows(IllegalArgumentException.class, () -> CamoufoxLaunch.fromFile(file));
        Files.writeString(file, "{\"env\":{\"CAMOU_CONFIG_1\":\"{}\"}} trailing");
        assertThrows(IllegalArgumentException.class, () -> CamoufoxLaunch.fromFile(file));
        Files.writeString(file, "{\"env\":{\"CAMOU_CONFIG_1\":\"{}\"},\"proxy\":{\"server\":\"http://p:1\"}}");
        CamoufoxLaunch launch = CamoufoxLaunch.fromFile(file);
        assertEquals("http://p:1", launch.proxy().getAsJsonObject().get("server").getAsString());
        assertTrue(launch.fingerprint().entrySet().isEmpty());
    }

    private Path bundle(String executable) throws Exception {
        Path browser = temporary.resolve("browser");
        Files.createDirectories(browser.resolve("addons").resolve("UBO"));
        Files.writeString(browser.resolve(executable), "");
        return browser;
    }
}
