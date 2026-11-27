package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The add-on bridge: only the add-on (token, extension origin) reads the HUD and sends commands. */
public class AddonBridgeTest {

    private static final String EXTENSION_ORIGIN = "moz-extension://3f1c2b7e-0000-4000-8000-000000000001";
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final HudCommandInbox inbox = new HudCommandInbox();
    private AddonBridge bridge;

    @Before
    public void start() throws Exception {
        bridge = AddonBridge.start();
        bridge.deliverTo(inbox);
    }

    @After
    public void stop() {
        bridge.close();
    }

    @Test
    public void clientConfigNamesPortTokenAndProtocol() {
        JsonObject config = JsonParser.parseString(bridge.clientConfig()).getAsJsonObject();
        assertEquals(AddonBridge.PROTOCOL, config.get("protocol").getAsInt());
        assertEquals(bridge.port(), config.get("port").getAsInt());
        assertEquals(64, config.get("token").getAsString().length());
        // The add-on connects to 127.0.0.1, also on a JVM that prefers IPv6 (where the loopback address is ::1).
        assertEquals("127.0.0.1", bridge.address().getHostAddress());
    }

    @Test
    public void theAddonReadsThePublishedStateAndBecomesConnected() throws Exception {
        assertFalse(bridge.addonConnected());
        bridge.publish(new ResearchHudState("WAITING_FOR_USER", "Solve the check", true, 42, false, 3, true),
                "www.bing.com");
        HttpResponse<String> response = send(get("/v1/state?after=0").header("Origin", EXTENSION_ORIGIN)
                .header("Authorization", "Bearer " + bridge.token()));
        assertEquals(200, response.statusCode());
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(1L, body.get("version").getAsLong());
        JsonObject state = body.getAsJsonObject("state");
        assertEquals("WAITING_FOR_USER", state.get("phase").getAsString());
        assertEquals("Solve the check", state.get("status").getAsString());
        assertTrue(state.get("waiting").getAsBoolean());
        assertEquals(42, state.get("countdown").getAsInt());
        assertEquals(3, state.get("delay").getAsInt());
        assertTrue(state.get("relevant").getAsBoolean());
        assertFalse(state.get("terminal").getAsBoolean());
        assertEquals("www.bing.com", state.get("host").getAsString());
        assertTrue(bridge.addonConnected());
    }

    @Test
    public void aHeldPollReturnsAsSoonAsANewStateIsPublished() throws Exception {
        bridge.publish(new ResearchHudState("READABLE", "first", false, -1, false));
        CompletableFuture<HttpResponse<String>> held = http.sendAsync(
                get("/v1/state?after=1").header("Authorization", "Bearer " + bridge.token()).build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(300);
        assertFalse("nothing new yet", held.isDone());
        bridge.publish(new ResearchHudState("DELAY", "second", false, -1, false));
        HttpResponse<String> response = held.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"second\""));
    }

    @Test
    public void commandsFromTheAddonReachTheInbox() throws Exception {
        assertEquals(204, send(post("/v1/command", "SKIP")).statusCode());
        assertEquals(204, send(post("/v1/command", "SET_DELAY:12")).statusCode());
        assertEquals(204, send(post("/v1/command", "SET_RELEVANCE:on")).statusCode());
        assertEquals("SKIP\nSET_DELAY:12\nSET_RELEVANCE:on", inbox.drain());
    }

    @Test
    public void unknownCommandsAreRefused() throws Exception {
        assertEquals(400, send(post("/v1/command", "SET_DELAY:12\nSKIP")).statusCode());
        assertEquals(400, send(post("/v1/command", "DELETE_EVERYTHING")).statusCode());
        assertEquals("", inbox.drain());
    }

    @Test
    public void aPageCannotUseTheBridge() throws Exception {
        bridge.publish(new ResearchHudState("READABLE", "secret status", false, -1, false));
        // A page's fetch carries the page origin, even when it somehow knew the token.
        assertEquals(403, send(get("/v1/state?after=0").header("Origin", "https://www.bing.com")
                .header("Authorization", "Bearer " + bridge.token())).statusCode());
        // Without the token nothing is answered.
        assertEquals(403, send(get("/v1/state?after=0").header("Origin", EXTENSION_ORIGIN)).statusCode());
        assertEquals(403, send(get("/v1/state?after=0").header("Origin", EXTENSION_ORIGIN)
                .header("Authorization", "Bearer " + "0".repeat(64))).statusCode());
        assertEquals(403, send(HttpRequest.newBuilder(uri("/v1/command")).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("SKIP"))).statusCode());
        assertEquals("", inbox.drain());
        assertFalse("refused requests are no contact", bridge.addonConnected());
    }

    @Test
    public void helloRecordsTheAddonVersion() throws Exception {
        assertEquals(204, send(HttpRequest.newBuilder(uri("/v1/hello")).timeout(Duration.ofSeconds(5))
                .header("Origin", EXTENSION_ORIGIN).header("Authorization", "Bearer " + bridge.token())
                .POST(HttpRequest.BodyPublishers.ofString("{\"protocol\":1,\"addonVersion\":\"0.1.0\"}")))
                .statusCode());
        assertEquals("0.1.0", bridge.addonVersion());
        assertTrue(bridge.addonConnected());
    }

    @Test
    public void closingEndsTheConnection() throws Exception {
        send(post("/v1/command", "PAUSE"));
        assertTrue(bridge.addonConnected());
        bridge.close();
        assertFalse(bridge.addonConnected());
    }

    @Test
    public void aPageReadTravelsToTheAddonAndItsValueBack() throws Exception {
        AddonBridge.PageRead read = bridge.submitRead("page-1", "https://www.bing.com/search?q=kaninchen", "() => 42");
        HttpResponse<String> fetched = send(get("/v1/read").header("Origin", EXTENSION_ORIGIN)
                .header("Authorization", "Bearer " + bridge.token()));
        assertEquals(200, fetched.statusCode());
        JsonObject task = JsonParser.parseString(fetched.body()).getAsJsonObject();
        assertEquals("page-1", task.get("tab").getAsString());
        assertEquals("https://www.bing.com/search?q=kaninchen", task.get("url").getAsString());
        assertEquals("() => 42", task.get("script").getAsString());
        assertFalse(read.isDone());
        assertEquals(204, send(post("/v1/read-result", "{\"id\":\"" + task.get("id").getAsString()
                + "\",\"ok\":true,\"value\":{\"containers\":[{\"id\":\"container-0001\",\"visible\":true,"
                + "\"textLength\":12}],\"truncated\":false}}")).statusCode());
        assertTrue(read.isDone());
        // The same value shapes page.evaluate returned: maps, lists, numbers, booleans, strings.
        Map<?, ?> value = (Map<?, ?>) read.value();
        assertEquals(Boolean.FALSE, value.get("truncated"));
        Map<?, ?> container = (Map<?, ?>) ((List<?>) value.get("containers")).get(0);
        assertEquals("container-0001", container.get("id"));
        assertEquals(Boolean.TRUE, container.get("visible"));
        assertEquals(12, ((Number) container.get("textLength")).intValue());
        assertEquals(0, bridge.openReadCount());
    }

    @Test
    public void aHeldReadPollReturnsAsSoonAsAReadIsSubmitted() throws Exception {
        CompletableFuture<HttpResponse<String>> held = http.sendAsync(get("/v1/read")
                .header("Authorization", "Bearer " + bridge.token()).build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(300);
        assertFalse("nothing queued yet", held.isDone());
        bridge.submitRead("page-1", "https://example.org/", "() => document.title");
        HttpResponse<String> response = held.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("document.title"));
    }

    @Test
    public void anAddonErrorFailsTheRead() throws Exception {
        AddonBridge.PageRead read = bridge.submitRead("page-1", "https://example.org/", "() => 1");
        String id = JsonParser.parseString(send(get("/v1/read").header("Authorization", "Bearer " + bridge.token()))
                .body()).getAsJsonObject().get("id").getAsString();
        send(post("/v1/read-result", "{\"id\":\"" + id + "\",\"ok\":false,\"error\":\"NO_TAB: no tab shows x\"}"));
        try {
            read.value();
            fail("an error result must fail the read");
        } catch (AddonBridge.PageReadFailed expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("NO_TAB"));
        }
    }

    @Test
    public void aCancelledReadIsNeverHandedOutAndALateResultIsDropped() throws Exception {
        AddonBridge.PageRead cancelled = bridge.submitRead("page-1", "https://example.org/a", "() => 'a'");
        cancelled.cancel();
        AddonBridge.PageRead next = bridge.submitRead("page-1", "https://example.org/b", "() => 'b'");
        JsonObject task = JsonParser.parseString(send(get("/v1/read")
                .header("Authorization", "Bearer " + bridge.token())).body()).getAsJsonObject();
        assertEquals("https://example.org/b", task.get("url").getAsString());
        assertEquals(204, send(post("/v1/read-result", "{\"id\":\"" + cancelled.id + "\",\"ok\":true,\"value\":1}"))
                .statusCode());
        assertFalse(next.isDone());
        assertEquals(1, bridge.openReadCount());
    }

    @Test
    public void aPageCannotFetchOrAnswerReads() throws Exception {
        AddonBridge.PageRead read = bridge.submitRead("page-1", "https://example.org/", "() => 1");
        assertEquals(403, send(get("/v1/read").header("Origin", "https://www.bing.com")
                .header("Authorization", "Bearer " + bridge.token())).statusCode());
        assertEquals(403, send(HttpRequest.newBuilder(uri("/v1/read-result")).timeout(Duration.ofSeconds(5))
                .header("Origin", "https://www.bing.com").header("Authorization", "Bearer " + bridge.token())
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + read.id + "\",\"ok\":true,\"value\":2}")))
                .statusCode());
        assertFalse(read.isDone());
        assertEquals(400, send(post("/v1/read-result", "not json")).statusCode());
    }

    @Test
    public void closingFailsOpenReads() {
        AddonBridge.PageRead read = bridge.submitRead("page-1", "https://example.org/", "() => 1");
        bridge.close();
        assertTrue(read.isDone());
        try {
            read.value();
            fail("a closed bridge cannot answer");
        } catch (AddonBridge.PageReadFailed expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }

    @Test
    public void theChromeExtensionIsAcceptedOnlyWithItsOwnId() throws Exception {
        bridge.publish(new ResearchHudState("READABLE", "chrome", false, -1, false));
        assertEquals(200, send(get("/v1/state?after=0").header("Origin", "chrome-extension://" + ResearchAddon.CHROME_ID)
                .header("Authorization", "Bearer " + bridge.token())).statusCode());
        // Another extension in the same browser has another id: refused even with the token.
        assertEquals(403, send(get("/v1/state?after=0").header("Origin", "chrome-extension://" + "a".repeat(32))
                .header("Authorization", "Bearer " + bridge.token())).statusCode());
        assertTrue(AddonBridge.extensionOrigin("moz-extension://3f1c2b7e-0000-4000-8000-000000000001"));
        assertFalse(AddonBridge.extensionOrigin("chrome-extension://" + ResearchAddon.CHROME_ID + ".evil"));
        assertFalse(AddonBridge.extensionOrigin("https://chrome-extension.example"));
    }

    @Test
    public void forChromeTheReadNamesAScriptFileInsteadOfScriptText() throws Exception {
        java.nio.file.Path extension = java.nio.file.Files.createTempDirectory("askai-addon-test");
        try {
            bridge.readScriptsAsFilesIn(extension);
            AddonBridge.PageRead read = bridge.submitRead("page-1", "https://example.org/", "() => document.title");
            JsonObject task = JsonParser.parseString(send(get("/v1/read").header("Origin", EXTENSION_ORIGIN)
                    .header("Authorization", "Bearer " + bridge.token())).body()).getAsJsonObject();
            assertFalse("MV3 executes files only, no script text travels", task.has("script"));
            String file = task.get("file").getAsString();
            assertTrue(file, file.matches("reads/[0-9a-f]{64}\\.js"));
            assertEquals("(() => document.title)();\n",
                    java.nio.file.Files.readString(extension.resolve(file)));
            // The same script again: the same file, written once.
            assertEquals(file, AddonBridge.writeScriptFile(extension, "() => document.title"));
            assertTrue(!file.equals(AddonBridge.writeScriptFile(extension, "() => 2")));
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(extension.resolve("reads"))) {
                assertEquals("no temporary file is left behind", 2L, files.count());
            }
            read.cancel();
        } finally {
            Playwright4jDriver.deleteTree(extension);
        }
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET();
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Origin", EXTENSION_ORIGIN).header("Authorization", "Bearer " + bridge.token())
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + bridge.port() + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
